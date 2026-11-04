package com.platform.movieservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.config.TmdbConfig;
import com.platform.movieservice.dto.tmdb.*;
import com.platform.movieservice.entity.enums.MovieGenre;
import com.platform.movieservice.exception.ResourceNotFoundException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbService {

    private final RestClient tmdbRestClient;
    private final TmdbConfig tmdbConfig;
    private final ObjectMapper objectMapper;

    @Value("${tmdb.image.base-url:https://image.tmdb.org/t/p}")
    private String imageBaseUrl;

    @Value("${tmdb.default-language:en-US}")
    private String defaultLanguage;

    private final Map<Integer, String> genreIdToNameCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> genreNameToIdCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initGenreCache() {
        for (TmdbGenre g : MockTmdbDataProvider.getFallbackGenres()) {
            if (g.getId() != null && StringUtils.hasText(g.getName())) {
                cacheGenre(g.getId(), g.getName());
            }
        }
    }

    private void cacheGenre(Integer id, String name) {
        genreIdToNameCache.put(id, name);
        String normalized = normalizeGenreKey(name);
        genreNameToIdCache.put(normalized, id);
        genreNameToIdCache.put(name.trim().toLowerCase(), id);
        genreNameToIdCache.put(name.trim().toUpperCase().replace(" ", "_"), id);
    }

    private String normalizeGenreKey(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public List<TmdbGenre> fetchMovieGenres() {
        TmdbGenreListResponse response = executeWithRetry(() -> tmdbRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/genre/movie/list")
                        .queryParam("language", defaultLanguage)
                        .build())
                .retrieve()
                .body(TmdbGenreListResponse.class),
                "/genre/movie/list");

        if (response != null && response.getGenres() != null && !response.getGenres().isEmpty()) {
            for (TmdbGenre g : response.getGenres()) {
                if (g.getId() != null && StringUtils.hasText(g.getName())) {
                    cacheGenre(g.getId(), g.getName());
                }
            }
            return response.getGenres();
        }
        return MockTmdbDataProvider.getFallbackGenres();
    }

    /**
     * Dynamically resolves any genre parameter (name, alias, enum, or numeric ID)
     * to its official TMDB genre ID.
     */
    public Integer resolveGenreId(String genreParam) {
        if (!StringUtils.hasText(genreParam)) {
            return null;
        }
        String trimmed = genreParam.trim();

        // 1. If already numeric, parse directly
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {}

        // 2. Lookup in local cache by normalized key, lowercase, or enum format
        String normalized = normalizeGenreKey(trimmed);
        Integer cachedId = genreNameToIdCache.get(normalized);
        if (cachedId != null) {
            return cachedId;
        }
        cachedId = genreNameToIdCache.get(trimmed.toLowerCase());
        if (cachedId != null) {
            return cachedId;
        }

        // 3. Check MovieGenre enum
        MovieGenre enumGenre = MovieGenre.fromString(trimmed);
        if (enumGenre != null) {
            return enumGenre.getTmdbId();
        }

        // 4. Dynamic fetch from TMDB /genre/movie/list on miss
        try {
            List<TmdbGenre> fetched = fetchMovieGenres();
            if (fetched != null) {
                for (TmdbGenre g : fetched) {
                    if (g.getId() != null && StringUtils.hasText(g.getName())) {
                        cacheGenre(g.getId(), g.getName());
                    }
                }
            }
            cachedId = genreNameToIdCache.get(normalized);
            if (cachedId != null) return cachedId;
            cachedId = genreNameToIdCache.get(trimmed.toLowerCase());
            if (cachedId != null) return cachedId;
        } catch (Exception e) {
            log.warn("Failed dynamic genre fetch: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Resolves a TMDB genre ID to its display name.
     */
    public String resolveGenreName(Integer genreId) {
        if (genreId == null) {
            return null;
        }
        String name = genreIdToNameCache.get(genreId);
        if (name != null) {
            return name;
        }

        MovieGenre mg = MovieGenre.fromTmdbId(genreId);
        if (mg != null) {
            return mg.getDisplayName();
        }

        try {
            List<TmdbGenre> fetched = fetchMovieGenres();
            if (fetched != null) {
                for (TmdbGenre g : fetched) {
                    if (g.getId() != null && StringUtils.hasText(g.getName())) {
                        cacheGenre(g.getId(), g.getName());
                    }
                }
            }
            name = genreIdToNameCache.get(genreId);
            if (name != null) return name;
        } catch (Exception e) {
            log.warn("Failed dynamic genre name fetch for ID {}: {}", genreId, e.getMessage());
        }

        return String.valueOf(genreId);
    }

    public String resolveGenreIdsParam(String genreIds) {
        if (!StringUtils.hasText(genreIds)) {
            return null;
        }
        String[] tokens = genreIds.split("[,|]");
        List<String> resolved = new ArrayList<>();
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            Integer id = resolveGenreId(trimmed);
            if (id != null) {
                resolved.add(String.valueOf(id));
            } else {
                resolved.add(trimmed);
            }
        }
        return resolved.isEmpty() ? null : String.join(",", resolved);
    }

    public TmdbPageResponse<TmdbMovieResult> fetchTrendingMovies(String timeWindow, int page) {
        String window = (timeWindow != null && timeWindow.equalsIgnoreCase("week")) ? "week" : "day";
        TmdbPageResponse<TmdbMovieResult> response = executeWithRetry(() -> tmdbRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/trending/movie/{timeWindow}")
                        .queryParam("page", page)
                        .queryParam("language", defaultLanguage)
                        .build(window))
                .retrieve()
                .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {}),
                "/trending/movie/" + window);

        if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
            return response;
        }
        return MockTmdbDataProvider.getFallbackFeed("TRENDING_" + window.toUpperCase(), null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> fetchPopularMovies(int page) {
        TmdbPageResponse<TmdbMovieResult> response = executeWithRetry(() -> tmdbRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/movie/popular")
                        .queryParam("page", page)
                        .queryParam("language", defaultLanguage)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {}),
                "/movie/popular");

        if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
            return response;
        }
        return MockTmdbDataProvider.getFallbackFeed("POPULAR", null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> fetchTopRatedMovies(int page) {
        TmdbPageResponse<TmdbMovieResult> response = executeWithRetry(() -> tmdbRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/movie/top_rated")
                        .queryParam("page", page)
                        .queryParam("language", defaultLanguage)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {}),
                "/movie/top_rated");

        if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
            return response;
        }
        return MockTmdbDataProvider.getFallbackFeed("TOP_RATED", null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> fetchNowPlayingMovies(int page) {
        TmdbPageResponse<TmdbMovieResult> response = executeWithRetry(() -> tmdbRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/movie/now_playing")
                        .queryParam("page", page)
                        .queryParam("language", defaultLanguage)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {}),
                "/movie/now_playing");

        if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
            return response;
        }
        return MockTmdbDataProvider.getFallbackFeed("NOW_PLAYING", null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> discoverMovies(String originalLanguage, String genreIds, String sortBy, int page) {
        return discoverMovies(originalLanguage, genreIds, sortBy, null, null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> discoverMovies(String originalLanguage, String genreIds, String sortBy,
                                                            String releaseDateGte, String releaseDateLte,
                                                            Integer voteCountGte, int page) {
        TmdbPageResponse<TmdbMovieResult> response = executeWithRetry(() -> tmdbRestClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/discover/movie")
                            .queryParam("page", page)
                            .queryParam("language", defaultLanguage);

                    if (StringUtils.hasText(sortBy)) {
                        builder.queryParam("sort_by", sortBy);
                        if (voteCountGte != null) {
                            builder.queryParam("vote_count.gte", voteCountGte);
                        } else if (sortBy.contains("vote_average")) {
                            builder.queryParam("vote_count.gte", 150);
                        }
                    } else {
                        builder.queryParam("sort_by", "popularity.desc");
                        if (voteCountGte != null) {
                            builder.queryParam("vote_count.gte", voteCountGte);
                        }
                    }

                    if (StringUtils.hasText(originalLanguage)) {
                        builder.queryParam("with_original_language", originalLanguage.trim().toLowerCase());
                    }

                    if (StringUtils.hasText(genreIds)) {
                        String resolvedGenres = resolveGenreIdsParam(genreIds);
                        if (StringUtils.hasText(resolvedGenres)) {
                            builder.queryParam("with_genres", resolvedGenres);
                        }
                    }

                    if (StringUtils.hasText(releaseDateGte)) {
                        builder.queryParam("primary_release_date.gte", releaseDateGte.trim());
                    }

                    if (StringUtils.hasText(releaseDateLte)) {
                        builder.queryParam("primary_release_date.lte", releaseDateLte.trim());
                    }

                    return builder.build();
                })
                .retrieve()
                .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {}),
                "/discover/movie");

        if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
            return response;
        }
        return null;
    }

    public TmdbMovieDetail fetchMovieDetail(Long movieId) {
        try {
            return tmdbRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/movie/{movieId}")
                            .queryParam("append_to_response", "credits")
                            .queryParam("language", defaultLanguage)
                            .build(movieId))
                    .retrieve()
                    .body(TmdbMovieDetail.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("Movie not found with TMDB ID: " + movieId);
        } catch (Exception e) {
            log.warn("External TMDB call for movie ID {} failed: {}. Using catalog fallback.", movieId, e.getMessage());
            throw new ResourceNotFoundException("Movie not found with TMDB ID: " + movieId);
        }
    }

    public TmdbPersonDetail fetchPersonDetail(Long personId) {
        try {
            return tmdbRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/person/{personId}")
                            .queryParam("append_to_response", "movie_credits")
                            .queryParam("language", defaultLanguage)
                            .build(personId))
                    .retrieve()
                    .body(TmdbPersonDetail.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("Person not found with TMDB ID: " + personId);
        } catch (Exception e) {
            log.warn("External TMDB call for person ID {} failed: {}. Using catalog fallback.", personId, e.getMessage());
            throw new ResourceNotFoundException("Person not found with TMDB ID: " + personId);
        }
    }

    public Map<String, Object> searchMultiRaw(String query, int page) {
        return executeSearch("/search/multi", query, page);
    }

    public Map<String, Object> searchMovieRaw(String query, int page) {
        return executeSearch("/search/movie", query, page);
    }

    public Map<String, Object> searchPersonRaw(String query, int page) {
        return executeSearch("/search/person", query, page);
    }

    private Map<String, Object> executeSearch(String path, String query, int page) {
        try {
            String rawJson = tmdbRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("query", query)
                            .queryParam("page", page)
                            .queryParam("language", defaultLanguage)
                            .build())
                    .retrieve()
                    .body(String.class);

            if (rawJson != null) {
                return objectMapper.readValue(rawJson, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("Search on '{}' failed for query '{}': {}", path, query, e.getMessage());
        }
        return Map.of();
    }

    private <T> T executeWithRetry(Supplier<T> action, String endpointDesc) {
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (ResourceAccessException e) {
                if (attempt == maxAttempts) {
                    log.warn("External TMDB call to {} failed after {} attempts with I/O error: {}. Using catalog fallback.",
                            endpointDesc, attempt, e.getMessage());
                    break;
                }
                log.warn("Transient I/O error on TMDB call to {} (attempt {}/{}): {}. Retrying in 500ms...",
                        endpointDesc, attempt, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                log.warn("External TMDB call to {} failed: {}. Using catalog fallback.", endpointDesc, e.getMessage());
                break;
            }
        }
        return null;
    }

    public String buildPosterUrl(String posterPath, String size) {
        if (!StringUtils.hasText(posterPath)) {
            return null;
        }
        String targetSize = StringUtils.hasText(size) ? size : "w500";
        return imageBaseUrl + "/" + targetSize + posterPath;
    }

    public String buildBackdropUrl(String backdropPath, String size) {
        if (!StringUtils.hasText(backdropPath)) {
            return null;
        }
        String targetSize = StringUtils.hasText(size) ? size : "original";
        return imageBaseUrl + "/" + targetSize + backdropPath;
    }

    public String buildProfileUrl(String profilePath, String size) {
        if (!StringUtils.hasText(profilePath)) {
            return null;
        }
        String targetSize = StringUtils.hasText(size) ? size : "w185";
        return imageBaseUrl + "/" + targetSize + profilePath;
    }
}
