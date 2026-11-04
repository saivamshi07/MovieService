package com.platform.movieservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.dto.response.*;
import com.platform.movieservice.dto.tmdb.*;
import com.platform.movieservice.entity.Movie;
import com.platform.movieservice.entity.MovieCast;
import com.platform.movieservice.entity.MovieCrew;
import com.platform.movieservice.entity.Person;
import com.platform.movieservice.entity.enums.CinemaIndustry;
import com.platform.movieservice.entity.enums.MovieGenre;
import com.platform.movieservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final TmdbService tmdbService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheLeaderExecutor cacheLeaderExecutor;
    private final AsyncCatalogPersistenceService asyncPersistenceService;
    private final MovieRepository movieRepository;
    private final PersonRepository personRepository;
    private final MovieCastRepository movieCastRepository;
    private final MovieCrewRepository movieCrewRepository;
    private final ReviewRepository reviewRepository;
    private final ObjectMapper objectMapper;

    @Value("${cache.ttl.movie-details:86400}")
    private long movieDetailsTtl;

    @Value("${cache.ttl.actor-details:86400}")
    private long actorDetailsTtl;

    @Value("${cache.ttl.crew-details:86400}")
    private long crewDetailsTtl;

    @Value("${cache.ttl.search-query:86400}")
    private long searchTtl;

    /**
     * 3-Tier Multi-Level Read-Through with Singleflight Leader Mutex for Movie Details.
     */
    public MovieDetailDTO getMovieDetail(Long movieId) {
        String cacheKey = "movie:details:" + movieId;

        MovieDetailDTO dto = cacheLeaderExecutor.executeWithLeader(
                cacheKey,
                Duration.ofSeconds(5),
                // 1. Read from Redis
                () -> {
                    try {
                        Object cached = redisTemplate.opsForValue().get(cacheKey);
                        if (cached instanceof MovieDetailDTO) {
                            return (MovieDetailDTO) cached;
                        }
                    } catch (Exception ignored) {}
                    return null;
                },
                // 2. Fallback to DB or TMDB
                () -> {
                    // Try DB first
                    Optional<Movie> movieOpt = movieRepository.findById(movieId);
                    if (movieOpt.isPresent()) {
                        return mapEntityToDetailDTO(movieOpt.get());
                    }

                    // DB Miss -> Call TMDB
                    TmdbMovieDetail tmdbDetail = tmdbService.fetchMovieDetail(movieId);
                    MovieDetailDTO tmdbDto = mapTmdbToDetailDTO(tmdbDetail);

                    // Trigger async permanent DB save
                    asyncPersistenceService.persistMovieAndCredits(tmdbDetail);

                    return tmdbDto;
                },
                // 3. Write to Redis with 1-day TTL
                freshDto -> {
                    try {
                        redisTemplate.opsForValue().set(cacheKey, freshDto, Duration.ofSeconds(movieDetailsTtl));
                    } catch (Exception ignored) {}
                }
        );

        // Hydrate platform ratings
        if (dto != null) {
            Double avg = reviewRepository.calculateAverageRating(movieId);
            long count = reviewRepository.countByMovieId(movieId);
            dto.setPlatformRating(avg != null ? Math.round(avg * 10.0) / 10.0 : 0.0);
            dto.setPlatformRatingCount(count);
        }

        return dto;
    }

    /**
     * Batch retrieval of movie details following the 3-tier cascade:
     * 1. Multi-get from Redis cache.
     * 2. DB fallback (findAllById) for any missed IDs; results cached to Redis.
     * 3. TMDB fallback (fetchMovieDetail) for any still-missed IDs; async persisted to DB and cached to Redis.
     * 4. Platform ratings hydrated for all retrieved movies.
     * 5. Returns results preserving the input ID ordering.
     */
    public List<MovieDetailDTO> getMoviesBatch(List<Long> movieIds) {
        if (movieIds == null || movieIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> distinctIds = movieIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (distinctIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, MovieDetailDTO> resultMap = new HashMap<>();

        // 1. Tier 1: Redis Cache Multi-Get
        List<Long> missedFromRedis = new ArrayList<>();
        List<String> cacheKeys = distinctIds.stream()
                .map(id -> "movie:details:" + id)
                .toList();

        try {
            List<Object> cachedObjects = redisTemplate.opsForValue().multiGet(cacheKeys);
            if (cachedObjects != null) {
                for (int i = 0; i < distinctIds.size(); i++) {
                    Long id = distinctIds.get(i);
                    Object cached = i < cachedObjects.size() ? cachedObjects.get(i) : null;
                    if (cached instanceof MovieDetailDTO dto) {
                        resultMap.put(id, dto);
                    } else if (cached != null) {
                        try {
                            MovieDetailDTO dto = objectMapper.convertValue(cached, MovieDetailDTO.class);
                            resultMap.put(id, dto);
                        } catch (Exception ignored) {
                            missedFromRedis.add(id);
                        }
                    } else {
                        missedFromRedis.add(id);
                    }
                }
            } else {
                missedFromRedis.addAll(distinctIds);
            }
        } catch (Exception e) {
            log.warn("Redis multiGet failed for movie batch, falling back to DB/TMDB: {}", e.getMessage());
            missedFromRedis.addAll(distinctIds);
        }

        // 2. Tier 2: DB Batch Lookup for Redis Misses
        List<Long> missedFromDb = new ArrayList<>();
        if (!missedFromRedis.isEmpty()) {
            try {
                List<Movie> dbMovies = movieRepository.findAllById(missedFromRedis);
                Map<Long, Movie> dbMovieMap = dbMovies.stream()
                        .collect(Collectors.toMap(Movie::getId, m -> m, (a, b) -> a));

                for (Long id : missedFromRedis) {
                    Movie movie = dbMovieMap.get(id);
                    if (movie != null) {
                        MovieDetailDTO dto = mapEntityToDetailDTO(movie);
                        resultMap.put(id, dto);
                        // Warm Redis cache
                        try {
                            redisTemplate.opsForValue().set("movie:details:" + id, dto, Duration.ofSeconds(movieDetailsTtl));
                        } catch (Exception ignored) {}
                    } else {
                        missedFromDb.add(id);
                    }
                }
            } catch (Exception e) {
                log.warn("DB findAllById failed for movie batch: {}", e.getMessage());
                missedFromDb.addAll(missedFromRedis);
            }
        }

        // 3. Tier 3: TMDB Fallback for DB Misses
        for (Long id : missedFromDb) {
            try {
                TmdbMovieDetail tmdbDetail = tmdbService.fetchMovieDetail(id);
                if (tmdbDetail != null) {
                    MovieDetailDTO tmdbDto = mapTmdbToDetailDTO(tmdbDetail);
                    resultMap.put(id, tmdbDto);

                    // Async permanent DB save
                    asyncPersistenceService.persistMovieAndCredits(tmdbDetail);

                    // Warm Redis cache
                    try {
                        redisTemplate.opsForValue().set("movie:details:" + id, tmdbDto, Duration.ofSeconds(movieDetailsTtl));
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log.warn("TMDB fallback failed for movieId {}: {}", id, e.getMessage());
            }
        }

        // 4. Hydrate Platform Ratings
        for (Map.Entry<Long, MovieDetailDTO> entry : resultMap.entrySet()) {
            MovieDetailDTO dto = entry.getValue();
            if (dto != null) {
                Long mId = entry.getKey();
                Double avg = reviewRepository.calculateAverageRating(mId);
                long count = reviewRepository.countByMovieId(mId);
                dto.setPlatformRating(avg != null ? Math.round(avg * 10.0) / 10.0 : 0.0);
                dto.setPlatformRatingCount(count);
            }
        }

        // 5. Return results preserving input ordering (distinct)
        List<MovieDetailDTO> orderedResults = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long id : movieIds) {
            if (id != null && resultMap.containsKey(id) && seen.add(id)) {
                orderedResults.add(resultMap.get(id));
            }
        }

        return orderedResults;
    }

    /**
     * Batch retrieval of movie cards.
     */
    public List<MovieCardDTO> getMovieCardsBatch(List<Long> movieIds) {
        List<MovieDetailDTO> details = getMoviesBatch(movieIds);
        return details.stream()
                .map(this::mapDetailToCardDTO)
                .toList();
    }

    public MovieCardDTO mapDetailToCardDTO(MovieDetailDTO detail) {
        if (detail == null) {
            return null;
        }
        return MovieCardDTO.builder()
                .id(detail.getId())
                .title(detail.getTitle())
                .overview(detail.getOverview())
                .posterUrl(detail.getPosterUrl())
                .backdropUrl(detail.getBackdropUrl())
                .releaseDate(detail.getReleaseDate())
                .releaseYear(detail.getReleaseYear())
                .originalLanguage(detail.getOriginalLanguage())
                .industry(detail.getIndustry())
                .genres(detail.getGenres())
                .tmdbVoteAverage(detail.getTmdbVoteAverage())
                .platformRating(detail.getPlatformRating())
                .platformRatingCount(detail.getPlatformRatingCount())
                .build();
    }

    /**
     * 3-Tier Multi-Level Read-Through with Singleflight Leader Mutex for Actor Details.
     */
    public ActorDetailDTO getActorDetail(Long personId) {
        String cacheKey = "person:actor:" + personId;

        return cacheLeaderExecutor.executeWithLeader(
                cacheKey,
                Duration.ofSeconds(5),
                // 1. Read from Redis
                () -> {
                    try {
                        Object cached = redisTemplate.opsForValue().get(cacheKey);
                        if (cached instanceof ActorDetailDTO) {
                            return (ActorDetailDTO) cached;
                        }
                    } catch (Exception ignored) {}
                    return null;
                },
                // 2. Fallback to DB or TMDB
                () -> {
                    TmdbPersonDetail personDetail = tmdbService.fetchPersonDetail(personId);
                    ActorDetailDTO actorDto = mapTmdbToActorDTO(personDetail);

                    // Trigger async DB save
                    asyncPersistenceService.persistPerson(personDetail);

                    return actorDto;
                },
                // 3. Write to Redis with 1-day TTL
                freshDto -> {
                    try {
                        redisTemplate.opsForValue().set(cacheKey, freshDto, Duration.ofSeconds(actorDetailsTtl));
                    } catch (Exception ignored) {}
                }
        );
    }

    /**
     * 3-Tier Multi-Level Read-Through with Singleflight Leader Mutex for Crew Details.
     */
    public CrewDetailDTO getCrewDetail(Long personId) {
        String cacheKey = "person:crew:" + personId;

        return cacheLeaderExecutor.executeWithLeader(
                cacheKey,
                Duration.ofSeconds(5),
                // 1. Read from Redis
                () -> {
                    try {
                        Object cached = redisTemplate.opsForValue().get(cacheKey);
                        if (cached instanceof CrewDetailDTO) {
                            return (CrewDetailDTO) cached;
                        }
                    } catch (Exception ignored) {}
                    return null;
                },
                // 2. Fallback to DB or TMDB
                () -> {
                    TmdbPersonDetail personDetail = tmdbService.fetchPersonDetail(personId);
                    CrewDetailDTO crewDto = mapTmdbToCrewDTO(personDetail);

                    // Trigger async DB save
                    asyncPersistenceService.persistPerson(personDetail);

                    return crewDto;
                },
                // 3. Write to Redis with 1-day TTL
                freshDto -> {
                    try {
                        redisTemplate.opsForValue().set(cacheKey, freshDto, Duration.ofSeconds(crewDetailsTtl));
                    } catch (Exception ignored) {}
                }
        );
    }

    /**
     * Fast Search across Movies, Actors, and Crew with type filtering support.
     * type: null, empty, or "ALL" -> multi search
     * type: "MOVIE" -> movie search only
     * type: "PERSON" -> person search only
     */
    public MultiSearchResponseDTO search(String rawQuery, int page) {
        return search(rawQuery, "ALL", page);
    }

    @SuppressWarnings("unchecked")
    public MultiSearchResponseDTO search(String rawQuery, String type, int page) {
        String query = rawQuery != null ? rawQuery.trim().toLowerCase() : "";
        String searchType = (type != null && !type.isBlank()) ? type.trim().toUpperCase() : "ALL";

        if (query.length() < 2) {
            return MultiSearchResponseDTO.builder()
                    .query(query)
                    .totalMatches(0)
                    .movies(List.of())
                    .actors(List.of())
                    .crew(List.of())
                    .build();
        }

        if (!List.of("ALL", "MOVIE", "PERSON").contains(searchType)) {
            searchType = "ALL";
        }
        // Explicit type-based Redis cache key: search:all:q:..., search:movie:q:..., search:person:q:...
        String cacheKey = "search:" + searchType.toLowerCase() + ":q:" + query + ":p:" + page;

        // Tier 1: Redis Search Cache (< 2ms)
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof MultiSearchResponseDTO) {
                return (MultiSearchResponseDTO) cached;
            }
        } catch (Exception ignored) {}

        List<MovieCardDTO> movieMatches = new ArrayList<>();
        List<MultiSearchResponseDTO.ActorSearchResultDTO> actorMatches = new ArrayList<>();
        List<MultiSearchResponseDTO.CrewSearchResultDTO> crewMatches = new ArrayList<>();

        switch (searchType) {
            case "MOVIE" -> {
                Map<String, Object> rawResponse = tmdbService.searchMovieRaw(query, page);
                List<Map<String, Object>> results = (List<Map<String, Object>>) rawResponse.getOrDefault("results", List.of());
                for (Map<String, Object> item : results) {
                    movieMatches.add(mapRawMovieToCard(item));
                }
            }
            case "PERSON" -> {
                Map<String, Object> rawResponse = tmdbService.searchPersonRaw(query, page);
                List<Map<String, Object>> results = (List<Map<String, Object>>) rawResponse.getOrDefault("results", List.of());
                for (Map<String, Object> item : results) {
                    populatePersonMatch(item, actorMatches, crewMatches);
                }
            }
            default -> { // "ALL"
                Map<String, Object> rawResponse = tmdbService.searchMultiRaw(query, page);
                List<Map<String, Object>> results = (List<Map<String, Object>>) rawResponse.getOrDefault("results", List.of());
                for (Map<String, Object> item : results) {
                    String mediaType = (String) item.get("media_type");
                    if ("movie".equalsIgnoreCase(mediaType)) {
                        movieMatches.add(mapRawMovieToCard(item));
                    } else if ("person".equalsIgnoreCase(mediaType)) {
                        populatePersonMatch(item, actorMatches, crewMatches);
                    }
                }
            }
        }

        MultiSearchResponseDTO searchResponse = MultiSearchResponseDTO.builder()
                .query(query)
                .totalMatches(movieMatches.size() + actorMatches.size() + crewMatches.size())
                .movies(movieMatches)
                .actors(actorMatches)
                .crew(crewMatches)
                .build();

        // Warm Redis search cache (1 Day TTL)
        try {
            redisTemplate.opsForValue().set(cacheKey, searchResponse, Duration.ofSeconds(searchTtl));
        } catch (Exception ignored) {}

        return searchResponse;
    }

    @SuppressWarnings("unchecked")
    private void populatePersonMatch(Map<String, Object> item,
                                     List<MultiSearchResponseDTO.ActorSearchResultDTO> actorMatches,
                                     List<MultiSearchResponseDTO.CrewSearchResultDTO> crewMatches) {
        String knownForDept = (String) item.get("known_for_department");
        Long personId = item.get("id") != null ? Long.valueOf(item.get("id").toString()) : null;
        String name = (String) item.get("name");
        String profilePath = (String) item.get("profile_path");
        List<Map<String, Object>> knownForRaw = (List<Map<String, Object>>) item.getOrDefault("known_for", List.of());
        List<String> knownForTitles = knownForRaw.stream()
                .map(k -> (String) k.getOrDefault("title", k.get("name")))
                .filter(Objects::nonNull)
                .limit(3)
                .toList();

        if ("Acting".equalsIgnoreCase(knownForDept)) {
            actorMatches.add(MultiSearchResponseDTO.ActorSearchResultDTO.builder()
                    .id(personId)
                    .name(name)
                    .profileUrl(tmdbService.buildProfileUrl(profilePath, "w185"))
                    .knownFor(knownForTitles)
                    .build());
        } else {
            crewMatches.add(MultiSearchResponseDTO.CrewSearchResultDTO.builder()
                    .id(personId)
                    .name(name)
                    .department(knownForDept)
                    .profileUrl(tmdbService.buildProfileUrl(profilePath, "w185"))
                    .knownFor(knownForTitles)
                    .build());
        }
    }

    private MovieCardDTO mapRawMovieToCard(Map<String, Object> map) {
        Long id = map.get("id") != null ? Long.valueOf(map.get("id").toString()) : null;
        String title = (String) map.get("title");
        String releaseDate = (String) map.get("release_date");
        Integer year = null;
        if (releaseDate != null && releaseDate.length() >= 4) {
            try { year = Integer.parseInt(releaseDate.substring(0, 4)); } catch (Exception ignored) {}
        }
        String origLang = (String) map.get("original_language");
        CinemaIndustry industry = CinemaIndustry.fromString(origLang);

        Double voteAvg = null;
        if (map.get("vote_average") != null) {
            voteAvg = Double.valueOf(map.get("vote_average").toString());
        }

        return MovieCardDTO.builder()
                .id(id)
                .title(title)
                .overview((String) map.get("overview"))
                .posterUrl(tmdbService.buildPosterUrl((String) map.get("poster_path"), "w500"))
                .backdropUrl(tmdbService.buildBackdropUrl((String) map.get("backdrop_path"), "w1280"))
                .releaseDate(releaseDate)
                .releaseYear(year)
                .originalLanguage(origLang)
                .industry(industry != CinemaIndustry.ALL ? industry.name() : "GLOBAL")
                .genres(List.of())
                .tmdbVoteAverage(voteAvg)
                .platformRating(0.0)
                .platformRatingCount(0L)
                .build();
    }

    private MovieDetailDTO mapEntityToDetailDTO(Movie movie) {
        List<MovieCast> cast = movieCastRepository.findByMovieIdOrderByCastOrderAsc(movie.getId());
        List<MovieCrew> crew = movieCrewRepository.findByMovieId(movie.getId());

        List<MovieDetailDTO.PersonSummaryDTO> directors = crew.stream()
                .filter(c -> "Director".equalsIgnoreCase(c.getJobDesignation()))
                .map(c -> MovieDetailDTO.PersonSummaryDTO.builder()
                        .id(c.getPersonId())
                        .name(c.getPersonName())
                        .job(c.getJobDesignation())
                        .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                        .build())
                .toList();

        List<MovieDetailDTO.PersonSummaryDTO> writers = crew.stream()
                .filter(c -> "Writer".equalsIgnoreCase(c.getJobDesignation()) || "Screenplay".equalsIgnoreCase(c.getJobDesignation()))
                .map(c -> MovieDetailDTO.PersonSummaryDTO.builder()
                        .id(c.getPersonId())
                        .name(c.getPersonName())
                        .job(c.getJobDesignation())
                        .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                        .build())
                .toList();

        List<MovieDetailDTO.CrewMemberDTO> crewMembers = crew.stream()
                .map(c -> MovieDetailDTO.CrewMemberDTO.builder()
                        .id(c.getPersonId())
                        .name(c.getPersonName())
                        .department(c.getDepartment())
                        .job(c.getJobDesignation())
                        .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                        .build())
                .toList();

        List<MovieDetailDTO.CastMemberDTO> topCast = cast.stream()
                .limit(10)
                .map(c -> MovieDetailDTO.CastMemberDTO.builder()
                        .id(c.getPersonId())
                        .name(c.getPersonName())
                        .character(c.getCharacterName())
                        .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                        .order(c.getCastOrder())
                        .build())
                .toList();

        List<String> genres = List.of();
        if (movie.getGenresJson() != null) {
            try {
                List<TmdbGenre> genreList = objectMapper.readValue(movie.getGenresJson(), new TypeReference<List<TmdbGenre>>() {});
                genres = genreList.stream().map(TmdbGenre::getName).toList();
            } catch (Exception ignored) {}
        }

        CinemaIndustry industry = CinemaIndustry.fromString(movie.getOriginalLanguage());

        return MovieDetailDTO.builder()
                .id(movie.getId())
                .title(movie.getTitle())
                .originalTitle(movie.getOriginalTitle())
                .overview(movie.getOverview())
                .tagline(movie.getTagline())
                .posterUrl(tmdbService.buildPosterUrl(movie.getPosterPath(), "w500"))
                .backdropUrl(tmdbService.buildBackdropUrl(movie.getBackdropPath(), "original"))
                .releaseDate(movie.getReleaseDate() != null ? movie.getReleaseDate().toString() : null)
                .releaseYear(movie.getReleaseDate() != null ? movie.getReleaseDate().getYear() : null)
                .runtime(movie.getRuntime())
                .originalLanguage(movie.getOriginalLanguage())
                .industry(industry != CinemaIndustry.ALL ? industry.name() : "GLOBAL")
                .genres(genres)
                .tmdbVoteAverage(movie.getTmdbVoteAverage() != null ? movie.getTmdbVoteAverage().doubleValue() : null)
                .tmdbVoteCount(movie.getTmdbVoteCount())
                .platformRating(movie.getPlatformRatingAverage() != null ? movie.getPlatformRatingAverage().doubleValue() : 0.0)
                .platformRatingCount(movie.getPlatformRatingCount())
                .status(movie.getStatus())
                .directors(directors)
                .writers(writers)
                .topCast(topCast)
                .crew(crewMembers)
                .build();
    }

    private MovieDetailDTO mapTmdbToDetailDTO(TmdbMovieDetail detail) {
        Integer year = null;
        if (detail.getReleaseDate() != null && detail.getReleaseDate().length() >= 4) {
            try { year = Integer.parseInt(detail.getReleaseDate().substring(0, 4)); } catch (Exception ignored) {}
        }

        List<String> genres = detail.getGenres() != null ?
                detail.getGenres().stream().map(TmdbGenre::getName).toList() : List.of();

        List<MovieDetailDTO.PersonSummaryDTO> directors = List.of();
        List<MovieDetailDTO.PersonSummaryDTO> writers = List.of();
        List<MovieDetailDTO.CastMemberDTO> topCast = List.of();
        List<MovieDetailDTO.CrewMemberDTO> crewMembers = List.of();

        if (detail.getCredits() != null) {
            if (detail.getCredits().getCrew() != null) {
                directors = detail.getCredits().getCrew().stream()
                        .filter(c -> "Director".equalsIgnoreCase(c.getJob()))
                        .map(c -> MovieDetailDTO.PersonSummaryDTO.builder()
                                .id(c.getId())
                                .name(c.getName())
                                .job(c.getJob())
                                .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                                .build())
                        .toList();

                writers = detail.getCredits().getCrew().stream()
                        .filter(c -> "Writer".equalsIgnoreCase(c.getJob()) || "Screenplay".equalsIgnoreCase(c.getJob()))
                        .map(c -> MovieDetailDTO.PersonSummaryDTO.builder()
                                .id(c.getId())
                                .name(c.getName())
                                .job(c.getJob())
                                .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                                .build())
                        .toList();

                crewMembers = detail.getCredits().getCrew().stream()
                        .map(c -> MovieDetailDTO.CrewMemberDTO.builder()
                                .id(c.getId())
                                .name(c.getName())
                                .department(c.getDepartment())
                                .job(c.getJob())
                                .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                                .build())
                        .toList();
            }

            if (detail.getCredits().getCast() != null) {
                topCast = detail.getCredits().getCast().stream()
                        .limit(10)
                        .map(c -> MovieDetailDTO.CastMemberDTO.builder()
                                .id(c.getId())
                                .name(c.getName())
                                .character(c.getCharacter())
                                .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                                .order(c.getOrder())
                                .build())
                        .toList();
            }
        }

        CinemaIndustry industry = CinemaIndustry.fromString(detail.getOriginalLanguage());

        return MovieDetailDTO.builder()
                .id(detail.getId())
                .title(detail.getTitle())
                .originalTitle(detail.getOriginalTitle())
                .overview(detail.getOverview())
                .tagline(detail.getTagline())
                .posterUrl(tmdbService.buildPosterUrl(detail.getPosterPath(), "w500"))
                .backdropUrl(tmdbService.buildBackdropUrl(detail.getBackdropPath(), "original"))
                .releaseDate(detail.getReleaseDate())
                .releaseYear(year)
                .runtime(detail.getRuntime())
                .originalLanguage(detail.getOriginalLanguage())
                .industry(industry != CinemaIndustry.ALL ? industry.name() : "GLOBAL")
                .genres(genres)
                .tmdbVoteAverage(detail.getVoteAverage())
                .tmdbVoteCount(detail.getVoteCount())
                .platformRating(0.0)
                .platformRatingCount(0L)
                .status(detail.getStatus())
                .directors(directors)
                .writers(writers)
                .topCast(topCast)
                .crew(crewMembers)
                .build();
    }

    private ActorDetailDTO mapTmdbToActorDTO(TmdbPersonDetail person) {
        Integer age = calculateAge(person.getBirthday(), person.getDeathday());

        List<MovieCardDTO> filmsActed = List.of();
        if (person.getMovieCredits() != null && person.getMovieCredits().getCast() != null) {
            filmsActed = person.getMovieCredits().getCast().stream()
                    .filter(m -> m.getId() != null && m.getTitle() != null)
                    .sorted((a, b) -> Objects.compare(b.getReleaseDate(), a.getReleaseDate(), Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(50)
                    .map(m -> {
                        Integer year = null;
                        if (m.getReleaseDate() != null && m.getReleaseDate().length() >= 4) {
                            try { year = Integer.parseInt(m.getReleaseDate().substring(0, 4)); } catch (Exception ignored) {}
                        }
                        return MovieCardDTO.builder()
                                .id(m.getId())
                                .title(m.getTitle())
                                .overview(m.getOverview())
                                .posterUrl(tmdbService.buildPosterUrl(m.getPosterPath(), "w342"))
                                .releaseDate(m.getReleaseDate())
                                .releaseYear(year)
                                .tmdbVoteAverage(m.getVoteAverage())
                                .build();
                    })
                    .toList();
        }

        return ActorDetailDTO.builder()
                .id(person.getId())
                .name(person.getName())
                .biography(person.getBiography())
                .birthday(person.getBirthday())
                .deathday(person.getDeathday())
                .age(age)
                .placeOfBirth(person.getPlaceOfBirth())
                .profileUrl(tmdbService.buildProfileUrl(person.getProfilePath(), "h632"))
                .filmsActed(filmsActed)
                .build();
    }

    private CrewDetailDTO mapTmdbToCrewDTO(TmdbPersonDetail person) {
        List<CrewDetailDTO.CrewWorkedFilmDTO> filmsWorked = List.of();
        Set<String> designations = new LinkedHashSet<>();

        if (person.getMovieCredits() != null && person.getMovieCredits().getCrew() != null) {
            filmsWorked = person.getMovieCredits().getCrew().stream()
                    .filter(c -> c.getId() != null && c.getTitle() != null)
                    .sorted((a, b) -> Objects.compare(b.getReleaseDate(), a.getReleaseDate(), Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(50)
                    .map(c -> {
                        if (c.getJob() != null) {
                            designations.add(c.getJob());
                        }
                        Integer year = null;
                        if (c.getReleaseDate() != null && c.getReleaseDate().length() >= 4) {
                            try { year = Integer.parseInt(c.getReleaseDate().substring(0, 4)); } catch (Exception ignored) {}
                        }
                        return CrewDetailDTO.CrewWorkedFilmDTO.builder()
                                .id(c.getId())
                                .title(c.getTitle())
                                .department(c.getDepartment())
                                .job(c.getJob())
                                .releaseDate(c.getReleaseDate())
                                .releaseYear(year)
                                .posterUrl(tmdbService.buildPosterUrl(c.getPosterPath(), "w342"))
                                .tmdbVoteAverage(c.getVoteAverage())
                                .build();
                    })
                    .toList();
        }

        return CrewDetailDTO.builder()
                .id(person.getId())
                .name(person.getName())
                .biography(person.getBiography())
                .birthday(person.getBirthday())
                .deathday(person.getDeathday())
                .knownForDepartment(person.getKnownForDepartment())
                .profileUrl(tmdbService.buildProfileUrl(person.getProfilePath(), "h632"))
                .primaryDesignations(new ArrayList<>(designations))
                .filmsWorked(filmsWorked)
                .build();
    }

    private Integer calculateAge(String birthdayStr, String deathdayStr) {
        if (birthdayStr == null || birthdayStr.isBlank()) {
            return null;
        }
        try {
            LocalDate birth = LocalDate.parse(birthdayStr);
            LocalDate end = (deathdayStr != null && !deathdayStr.isBlank()) ?
                    LocalDate.parse(deathdayStr) : LocalDate.now();
            return Period.between(birth, end).getYears();
        } catch (Exception e) {
            return null;
        }
    }
}
