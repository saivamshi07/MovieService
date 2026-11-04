package com.platform.movieservice.service;

import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.MovieDetailDTO;
import com.platform.movieservice.dto.response.SectionResponseDTO;
import com.platform.movieservice.dto.response.UserPreferenceResponse;
import com.platform.movieservice.dto.tmdb.TmdbMovieResult;
import com.platform.movieservice.dto.tmdb.TmdbPageResponse;
import com.platform.movieservice.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationClientServiceTest {

    @Mock
    private CatalogService catalogService;

    @Mock
    private UserPreferenceService userPreferenceService;

    @Mock
    private HomeFeedService homeFeedService;

    @Mock
    private TmdbService tmdbService;

    @Mock
    private ReviewRepository reviewRepository;

    @InjectMocks
    private RecommendationClientService recommendationClientService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        ReflectionTestUtils.setField(recommendationClientService, "recommendationServiceBaseUrl", "http://localhost:8083");
        ReflectionTestUtils.setField(recommendationClientService, "connectTimeoutMs", 100);
        ReflectionTestUtils.setField(recommendationClientService, "readTimeoutMs", 100);
    }

    @Test
    @DisplayName("Personalized Recommendations: Fetches top 50 by vote_count.desc, filters rated, sorts by vote_average, returns 20")
    void testGetPersonalizedRecommendations_FiltersRatedAndSortsByVoteAverage() {
        UserPreferenceResponse preferences = UserPreferenceResponse.builder()
                .userId(userId)
                .industries(Set.of("TOLLYWOOD"))
                .genres(Set.of("ACTION"))
                .build();

        when(userPreferenceService.getPreferences(userId)).thenReturn(preferences);

        // Movie 5 has already been rated by user
        when(reviewRepository.findMovieIdsByUserId(userId)).thenReturn(List.of(5L));

        // TMDB discover returns 20 movies with varying voteAverage and voteCount
        List<TmdbMovieResult> resultsPage1 = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            TmdbMovieResult r = new TmdbMovieResult();
            r.setId(i);
            r.setTitle("Movie " + i);
            r.setVoteCount(20000L - (i * 100)); // descending vote count
            r.setVoteAverage(6.0 + (i * 0.15)); // movie 20 has highest voteAverage (9.0)
            resultsPage1.add(r);
        }
        TmdbPageResponse<TmdbMovieResult> pageResponse1 = new TmdbPageResponse<>();
        pageResponse1.setResults(resultsPage1);

        List<TmdbMovieResult> resultsPage2 = new ArrayList<>();
        for (long i = 21; i <= 40; i++) {
            TmdbMovieResult r = new TmdbMovieResult();
            r.setId(i);
            r.setTitle("Movie " + i);
            r.setVoteCount(15000L - (i * 100));
            r.setVoteAverage(5.0 + (i * 0.1));
            resultsPage2.add(r);
        }
        TmdbPageResponse<TmdbMovieResult> pageResponse2 = new TmdbPageResponse<>();
        pageResponse2.setResults(resultsPage2);

        List<TmdbMovieResult> resultsPage3 = new ArrayList<>();
        for (long i = 41; i <= 60; i++) {
            TmdbMovieResult r = new TmdbMovieResult();
            r.setId(i);
            r.setTitle("Movie " + i);
            r.setVoteCount(10000L - (i * 100));
            r.setVoteAverage(4.0 + (i * 0.05));
            resultsPage3.add(r);
        }
        TmdbPageResponse<TmdbMovieResult> pageResponse3 = new TmdbPageResponse<>();
        pageResponse3.setResults(resultsPage3);

        when(tmdbService.discoverMovies(eq("te"), anyString(), eq("vote_count.desc"), eq(1)))
                .thenReturn(pageResponse1);
        when(tmdbService.discoverMovies(eq("te"), anyString(), eq("vote_count.desc"), eq(2)))
                .thenReturn(pageResponse2);
        when(tmdbService.discoverMovies(eq("te"), anyString(), eq("vote_count.desc"), eq(3)))
                .thenReturn(pageResponse3);

        // Catalog hydration
        when(catalogService.getMovieDetail(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return MovieDetailDTO.builder()
                    .id(id)
                    .title("Hydrated Movie " + id)
                    .posterUrl("https://image.tmdb.org/t/p/w500/" + id + ".jpg")
                    .tmdbVoteAverage(id == 20L ? 9.0 : 7.0)
                    .build();
        });

        List<MovieCardDTO> recommendations = recommendationClientService.getPersonalizedRecommendations(userId);

        assertNotNull(recommendations);
        // Reduced TARGET_RECOMMENDATION_COUNT to 20
        assertEquals(20, recommendations.size(), "Recommendation shelf MUST contain exactly 20 movies");

        // Verify rated movie (id 5) was filtered out
        boolean containsRated = recommendations.stream().anyMatch(m -> m.getId().equals(5L));
        assertFalse(containsRated, "Rated movie (ID 5) must be filtered out");

        // Verify tmdb discover was called with vote_count.desc
        verify(tmdbService, atLeastOnce()).discoverMovies(eq("te"), anyString(), eq("vote_count.desc"), anyInt());

        // Verify that the top recommendation is Movie 20 (which had highest vote average: 9.0)
        assertEquals(20L, recommendations.get(0).getId(), "First movie should have highest vote average");
    }
}
