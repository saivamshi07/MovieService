package com.platform.movieservice.service;

import com.platform.movieservice.dto.response.*;
import com.platform.movieservice.dto.tmdb.TmdbMovieResult;
import com.platform.movieservice.dto.tmdb.TmdbPageResponse;
import com.platform.movieservice.entity.enums.CinemaIndustry;
import com.platform.movieservice.entity.enums.MovieGenre;
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

    @Value("${recommendation.service.base-url:http://localhost:8083}")
    private String recommendationServiceBaseUrl;

    @Value("${recommendation.service.connect-timeout-ms:1500}")
    private int connectTimeoutMs;

    @Value("${recommendation.service.read-timeout-ms:2000}")
    private int readTimeoutMs;

    private static final int TARGET_RECOMMENDATION_COUNT = 50;

    /**
     * Recommends exactly 50 movies at any given stage:
     * 1. Calls recommendation-service for ML predictions.
     * 2. If fewer than 50 returned, backfills the deficit using user's preferred industries & genres
     *    ordered by rating from highest to lowest.
     * 3. Hydrates all cards via the 3-Tier Cache (Redis -> DB -> TMDB).
     */
    public List<MovieCardDTO> getPersonalizedRecommendations(UUID userId) {
        Set<Long> chosenMovieIds = new LinkedHashSet<>();
        Map<Long, String> matchScores = new HashMap<>();
        Map<Long, String> matchReasons = new HashMap<>();

        // Step 1: Call recommendation-service
        List<RecommendationServiceResponse.PredictionItem> predictions = fetchPredictionsFromRecService(userId);
        if (predictions != null) {
            for (RecommendationServiceResponse.PredictionItem item : predictions) {
                try {
                    Long movieId = Long.valueOf(item.getItemId());
                    if (chosenMovieIds.add(movieId)) {
                        if (item.getScore() != null) {
                            int matchPct = (int) Math.round(item.getScore() * 100);
                            matchScores.put(movieId, matchPct + "% Match");
                        }
                        matchReasons.put(movieId, "RECOMMENDED_FOR_YOU");
                    }
                    if (chosenMovieIds.size() >= TARGET_RECOMMENDATION_COUNT) {
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Step 2: Waterfall Backfill if deficit exists (target 50 movies)
        if (chosenMovieIds.size() < TARGET_RECOMMENDATION_COUNT) {
            int deficit = TARGET_RECOMMENDATION_COUNT - chosenMovieIds.size();
            log.debug("Recommendation service returned {} items. Backfilling deficit of {} items.", chosenMovieIds.size(), deficit);
            backfillDeficit(userId, chosenMovieIds, matchReasons, deficit);
        }

        // Step 3: Hydrate all 50 movie cards
        List<MovieCardDTO> finalCards = new ArrayList<>();
        for (Long movieId : chosenMovieIds) {
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
                            .platformRating(detail.getPlatformRating())
                            .platformRatingCount(detail.getPlatformRatingCount())
                            .matchScore(matchScores.get(movieId))
                            .matchReason(matchReasons.getOrDefault(movieId, "TOP_RATED_IN_FAVORITE_GENRE"))
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to hydrate recommendation card {}: {}", movieId, e.getMessage());
            }
        }

        return finalCards;
    }

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
            log.warn("Recommendation service call failed or timed out ({}). Activating top-rated waterfall fallback.", e.getMessage());
        }

        return List.of();
    }

    /**
     * Backfills deficit with movies from user's preferred industry & genre ordered by rating highest to lowest!
     */
    private void backfillDeficit(UUID userId, Set<Long> chosenMovieIds, Map<Long, String> matchReasons, int neededCount) {
        UserPreferenceResponse preferences = userId != null ? userPreferenceService.getPreferences(userId) : null;

        List<String> industries = (preferences != null && !preferences.getIndustries().isEmpty()) ?
                new ArrayList<>(preferences.getIndustries()) : List.of("HOLLYWOOD", "TOLLYWOOD", "BOLLYWOOD");

        List<String> genres = (preferences != null && !preferences.getGenres().isEmpty()) ?
                new ArrayList<>(preferences.getGenres()) : List.of("ACTION", "THRILLER", "DRAMA");

        // Fetch top rated per preferred industry and genre
        for (String indName : industries) {
            if (chosenMovieIds.size() >= TARGET_RECOMMENDATION_COUNT) break;

            CinemaIndustry industry = CinemaIndustry.fromString(indName);
            String langCode = industry.getOriginalLanguageCode();

            for (String genreName : genres) {
                if (chosenMovieIds.size() >= TARGET_RECOMMENDATION_COUNT) break;

                MovieGenre genre = MovieGenre.fromString(genreName);
                String genreId = genre != null ? String.valueOf(genre.getTmdbId()) : null;

                // Discover sorted by vote_average.desc (highest to lowest rating)
                TmdbPageResponse<TmdbMovieResult> topRatedPage = tmdbService.discoverMovies(langCode, genreId, "vote_average.desc", 1);
                if (topRatedPage != null && topRatedPage.getResults() != null) {
                    for (TmdbMovieResult movie : topRatedPage.getResults()) {
                        if (movie.getId() != null && chosenMovieIds.add(movie.getId())) {
                            matchReasons.put(movie.getId(), "TOP_RATED_IN_" + indName);
                            if (chosenMovieIds.size() >= TARGET_RECOMMENDATION_COUNT) {
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Final safety net if still under 50: pull from global top-rated feed
        if (chosenMovieIds.size() < TARGET_RECOMMENDATION_COUNT) {
            SectionResponseDTO topRatedSection = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1);
            if (topRatedSection != null && topRatedSection.getMovies() != null) {
                for (MovieCardDTO movie : topRatedSection.getMovies()) {
                    if (chosenMovieIds.add(movie.getId())) {
                        matchReasons.put(movie.getId(), "GLOBAL_TOP_RATED");
                        if (chosenMovieIds.size() >= TARGET_RECOMMENDATION_COUNT) {
                            break;
                        }
                    }
                }
            }
        }
    }
}
