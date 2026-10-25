package com.platform.movieservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.config.TmdbConfig;
import com.platform.movieservice.dto.tmdb.*;
import com.platform.movieservice.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

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

    public TmdbPageResponse<TmdbMovieResult> fetchTrendingMovies(String timeWindow, int page) {
        String window = (timeWindow != null && timeWindow.equalsIgnoreCase("week")) ? "week" : "day";
        try {
            var response = tmdbRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/trending/movie/{timeWindow}")
                            .queryParam("page", page)
                            .queryParam("language", defaultLanguage)
                            .build(window))
                    .retrieve()
                    .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {});
            if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
                return response;
            }
        } catch (Exception e) {
            log.warn("External TMDB call to /trending/movie/{} failed: {}. Using catalog fallback.", window, e.getMessage());
        }
        return MockTmdbDataProvider.getFallbackFeed("TRENDING_" + window.toUpperCase(), null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> fetchPopularMovies(int page) {
        try {
            var response = tmdbRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/movie/popular")
                            .queryParam("page", page)
                            .queryParam("language", defaultLanguage)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {});
            if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
                return response;
            }
        } catch (Exception e) {
            log.warn("External TMDB call to /movie/popular failed: {}. Using catalog fallback.", e.getMessage());
        }
        return MockTmdbDataProvider.getFallbackFeed("POPULAR", null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> fetchTopRatedMovies(int page) {
        try {
            var response = tmdbRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/movie/top_rated")
                            .queryParam("page", page)
                            .queryParam("language", defaultLanguage)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {});
            if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
                return response;
            }
        } catch (Exception e) {
            log.warn("External TMDB call to /movie/top_rated failed: {}. Using catalog fallback.", e.getMessage());
        }
        return MockTmdbDataProvider.getFallbackFeed("TOP_RATED", null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> fetchNowPlayingMovies(int page) {
        try {
            var response = tmdbRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/movie/now_playing")
                            .queryParam("page", page)
                            .queryParam("language", defaultLanguage)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {});
            if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
                return response;
            }
        } catch (Exception e) {
            log.warn("External TMDB call to /movie/now_playing failed: {}. Using catalog fallback.", e.getMessage());
        }
        return MockTmdbDataProvider.getFallbackFeed("NOW_PLAYING", null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> discoverMovies(String originalLanguage, String genreIds, String sortBy, int page) {
        return discoverMovies(originalLanguage, genreIds, sortBy, null, null, null, page);
    }

    public TmdbPageResponse<TmdbMovieResult> discoverMovies(String originalLanguage, String genreIds, String sortBy,
                                                            String releaseDateGte, String releaseDateLte,
                                                            Integer voteCountGte, int page) {
        try {
            var response = tmdbRestClient.get()
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
                            builder.queryParam("with_genres", genreIds.trim());
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
                    .body(new ParameterizedTypeReference<TmdbPageResponse<TmdbMovieResult>>() {});
            if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
                return response;
            }
        } catch (Exception e) {
            log.warn("External TMDB call to /discover/movie failed: {}. Using catalog fallback.", e.getMessage());
        }
        return MockTmdbDataProvider.getFallbackFeed("DISCOVER", originalLanguage, genreIds, page);
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
            return MockTmdbDataProvider.getFallbackDetail(movieId);
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
            return MockTmdbDataProvider.getFallbackPerson(personId);
        }
    }

    public Map<String, Object> searchMultiRaw(String query, int page) {
        try {
            String rawJson = tmdbRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search/multi")
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
            log.warn("Multi-search failed for query '{}': {}", query, e.getMessage());
        }
        return Map.of();
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