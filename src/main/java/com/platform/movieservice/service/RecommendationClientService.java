package com.platform.movieservice.service;

import com.platform.movieservice.dto.response.*;
import com.platform.movieservice.dto.tmdb.TmdbMovieResult;
import com.platform.movieservice.dto.tmdb.TmdbPageResponse;
import com.platform.movieservice.entity.enums.CinemaIndustry;
import com.platform.movieservice.entity.enums.MovieGenre;
import com.platform.movieservice.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationClientService {

    private final CatalogService catalogService;
    private final UserPreferenceService userPreferenceService;
    private final HomeFeedService homeFeedService;
    private final TmdbService tmdbService;
    private final ReviewRepository reviewRepository;

    @Value("${recommendation.service.base-url:http://localhost:8083}")
    private String recommendationServiceBaseUrl;

    @Value("${recommendation.service.connect-timeout-ms:1500}")
    private int connectTimeoutMs;

    @Value("${recommendation.service.read-timeout-ms:2000}")
    private int readTimeoutMs;

    private static final int TOP_CANDIDATE_POOL_SIZE = 50;
    private static final int TARGET_RECOMMENDATION_COUNT = 20;

    /**
     * Recommends up to TARGET_RECOMMENDATION_COUNT (20) movies:
     * 1. Fetches top 50 movies from TMDB for user preferred genres & industries sorted by vote_count.desc.
     * 2. Filters out any movies already rated by the user.
     * 3. Sorts the remaining movies by voting_average descending.
     * 4. Limits the result to 20 items and hydrates them via CatalogService.
     */
    public List<MovieCardDTO> getPersonalizedRecommendations(UUID userId) {
        // 1. Fetch user's rated movie IDs to filter out
        Set<Long> ratedMovieIds = (userId != null) ?
                new HashSet<>(reviewRepository.findMovieIdsByUserId(userId)) : Collections.emptySet();

        // 2. Fetch top 50 movies from TMDB with vote_count.desc for user's preferred genres & industries
        Map<Long, String> matchReasons = new HashMap<>();
        List<TmdbMovieResult> topFiftyCandidates = fetchTopFiftyByPreferred(userId, matchReasons);

        // 3. Filter out already rated movies
        List<TmdbMovieResult> unratedMovies = topFiftyCandidates.stream()
                .filter(m -> m.getId() != null && !ratedMovieIds.contains(m.getId()))
                .toList();

        // 4. Sort remaining movies by voting_average descending
        List<TmdbMovieResult> sortedByVoteAverage = unratedMovies.stream()
                .sorted(Comparator.comparing(
                        (TmdbMovieResult m) -> m.getVoteAverage() != null ? m.getVoteAverage() : 0.0,
                        Comparator.reverseOrder()
                ).thenComparing(
                        (TmdbMovieResult m) -> m.getVoteCount() != null ? m.getVoteCount() : 0L,
                        Comparator.reverseOrder()
                ))
                .limit(TARGET_RECOMMENDATION_COUNT)
                .toList();

        // 5. Hydrate movie cards
        List<MovieCardDTO> finalCards = new ArrayList<>();
        for (TmdbMovieResult movie : sortedByVoteAverage) {
            Long movieId = movie.getId();
            try {
                MovieDetailDTO detail = catalogService.getMovieDetail(movieId);
                if (detail != null) {
                    finalCards.add(MovieCardDTO.builder()
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
                            .tmdbVoteCount(detail.getTmdbVoteCount())
                            .platformRating(detail.getPlatformRating())
                            .platformRatingCount(detail.getPlatformRatingCount())
                            .matchReason(matchReasons.getOrDefault(movieId, "TOP_RATED_IN_FAVORITE_GENRE"))
                            .build());
                } else {
                    finalCards.add(mapTmdbResultToCard(movie, matchReasons.getOrDefault(movieId, "TOP_RATED_IN_FAVORITE_GENRE")));
                }
            } catch (Exception e) {
                log.warn("Failed to hydrate recommendation card {}: {}. Using fallback card.", movieId, e.getMessage());
                finalCards.add(mapTmdbResultToCard(movie, matchReasons.getOrDefault(movieId, "TOP_RATED_IN_FAVORITE_GENRE")));
            }
        }

        return finalCards;
    }

    private List<TmdbMovieResult> fetchTopFiftyByPreferred(UUID userId, Map<Long, String> matchReasons) {
        UserPreferenceResponse preferences = userId != null ? userPreferenceService.getPreferences(userId) : null;

        List<String> industries = (preferences != null && preferences.getIndustries() != null && !preferences.getIndustries().isEmpty()) ?
                new ArrayList<>(preferences.getIndustries()) : List.of("HOLLYWOOD", "TOLLYWOOD", "BOLLYWOOD");

        List<String> genres = (preferences != null && preferences.getGenres() != null && !preferences.getGenres().isEmpty()) ?
                new ArrayList<>(preferences.getGenres()) : List.of("ACTION", "THRILLER", "DRAMA");

        Map<Long, TmdbMovieResult> candidatesMap = new LinkedHashMap<>();

        // Loop over preferred industries and genres querying TMDB with vote_count.desc
        for (String indName : industries) {
            if (candidatesMap.size() >= TOP_CANDIDATE_POOL_SIZE) break;

            CinemaIndustry industry = CinemaIndustry.fromString(indName);
            String langCode = industry != CinemaIndustry.ALL ? industry.getOriginalLanguageCode() : null;

            for (String genreName : genres) {
                if (candidatesMap.size() >= TOP_CANDIDATE_POOL_SIZE) break;

                Integer resolvedId = tmdbService.resolveGenreId(genreName);
                String genreId = resolvedId != null ? String.valueOf(resolvedId) : null;

                for (int page = 1; page <= 3; page++) {
                    if (candidatesMap.size() >= TOP_CANDIDATE_POOL_SIZE) break;

                    TmdbPageResponse<TmdbMovieResult> pageResp = tmdbService.discoverMovies(langCode, genreId, "vote_count.desc", page);
                    if (pageResp != null && pageResp.getResults() != null) {
                        for (TmdbMovieResult movie : pageResp.getResults()) {
                            if (movie != null && movie.getId() != null && !candidatesMap.containsKey(movie.getId())) {
                                candidatesMap.put(movie.getId(), movie);
                                matchReasons.putIfAbsent(movie.getId(), "TOP_VOTED_IN_" + indName);
                                if (candidatesMap.size() >= TOP_CANDIDATE_POOL_SIZE) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        // Safety fallback if still fewer than 50 candidates: discover by vote_count.desc without language/genre filter
        if (candidatesMap.size() < TOP_CANDIDATE_POOL_SIZE) {
            for (int page = 1; page <= 3 && candidatesMap.size() < TOP_CANDIDATE_POOL_SIZE; page++) {
                TmdbPageResponse<TmdbMovieResult> pageResp = tmdbService.discoverMovies(null, null, "vote_count.desc", page);
                if (pageResp != null && pageResp.getResults() != null) {
                    for (TmdbMovieResult movie : pageResp.getResults()) {
                        if (movie != null && movie.getId() != null && !candidatesMap.containsKey(movie.getId())) {
                            candidatesMap.put(movie.getId(), movie);
                            matchReasons.putIfAbsent(movie.getId(), "GLOBAL_TOP_VOTED");
                            if (candidatesMap.size() >= TOP_CANDIDATE_POOL_SIZE) {
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Additional fallback: HomeFeedService TOP_RATED section
        if (candidatesMap.size() < TOP_CANDIDATE_POOL_SIZE) {
            SectionResponseDTO topRatedSection = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1);
            if (topRatedSection != null && topRatedSection.getMovies() != null) {
                for (MovieCardDTO movie : topRatedSection.getMovies()) {
                    if (movie != null && movie.getId() != null && !candidatesMap.containsKey(movie.getId())) {
                        TmdbMovieResult res = new TmdbMovieResult();
                        res.setId(movie.getId());
                        res.setTitle(movie.getTitle());
                        res.setOverview(movie.getOverview());
                        res.setPosterPath(movie.getPosterUrl());
                        res.setBackdropPath(movie.getBackdropUrl());
                        res.setReleaseDate(movie.getReleaseDate());
                        res.setOriginalLanguage(movie.getOriginalLanguage());
                        res.setVoteAverage(movie.getTmdbVoteAverage());
                        res.setVoteCount(movie.getTmdbVoteCount() != null ? movie.getTmdbVoteCount() : 1000L);
                        candidatesMap.put(movie.getId(), res);
                        matchReasons.putIfAbsent(movie.getId(), "GLOBAL_TOP_RATED");
                        if (candidatesMap.size() >= TOP_CANDIDATE_POOL_SIZE) {
                            break;
                        }
                    }
                }
            }
        }

        List<TmdbMovieResult> list = new ArrayList<>(candidatesMap.values());
        list.sort(Comparator.comparing(
                (TmdbMovieResult m) -> m.getVoteCount() != null ? m.getVoteCount() : 0L,
                Comparator.reverseOrder()
        ));
        if (list.size() > TOP_CANDIDATE_POOL_SIZE) {
            return new ArrayList<>(list.subList(0, TOP_CANDIDATE_POOL_SIZE));
        }
        return list;
    }

    private MovieCardDTO mapTmdbResultToCard(TmdbMovieResult movie, String matchReason) {
        Integer year = null;
        if (movie.getReleaseDate() != null && movie.getReleaseDate().length() >= 4) {
            try { 
                year = Integer.parseInt(movie.getReleaseDate().substring(0, 4)); 
            } catch (Exception ignored) {}
        }
        CinemaIndustry industry = CinemaIndustry.fromString(movie.getOriginalLanguage());
        return MovieCardDTO.builder()
                .id(movie.getId())
                .title(movie.getTitle())
                .overview(movie.getOverview())
                .posterUrl(tmdbService.buildPosterUrl(movie.getPosterPath(), "w500"))
                .backdropUrl(tmdbService.buildBackdropUrl(movie.getBackdropPath(), "w1280"))
                .releaseDate(movie.getReleaseDate())
                .releaseYear(year)
                .originalLanguage(movie.getOriginalLanguage())
                .industry(industry != CinemaIndustry.ALL ? industry.name() : "GLOBAL")
                .genres(List.of())
                .tmdbVoteAverage(movie.getVoteAverage())
                .tmdbVoteCount(movie.getVoteCount())
                .platformRating(0.0)
                .platformRatingCount(0L)
                .matchReason(matchReason)
                .build();
    }

    @SuppressWarnings("unused")
    private List<RecommendationServiceResponse.PredictionItem> fetchPredictionsFromRecService(UUID userId) {
        if (userId == null) {
            return List.of();
        }

        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
            requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

            RestClient client = RestClient.builder()
                    .baseUrl(recommendationServiceBaseUrl)
                    .requestFactory(requestFactory)
                    .build();

            RecommendationServiceResponse response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/recommendations")
                            .queryParam("userId", userId)
                            .queryParam("category", "MOVIE")
                            .queryParam("limit", TARGET_RECOMMENDATION_COUNT)
                            .build())
                    .retrieve()
                    .body(RecommendationServiceResponse.class);

            if (response != null && response.getPredictions() != null) {
                return response.getPredictions();
            }
        } catch (Exception e) {
            log.warn("Recommendation service call failed or timed out ({}).", e.getMessage());
        }

        return List.of();
    }
}
