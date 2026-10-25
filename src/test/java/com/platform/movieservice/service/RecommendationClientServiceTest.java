package com.platform.movieservice.service;

import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.MovieDetailDTO;
import com.platform.movieservice.dto.response.SectionResponseDTO;
import com.platform.movieservice.dto.response.UserPreferenceResponse;
import com.platform.movieservice.dto.tmdb.TmdbMovieResult;
import com.platform.movieservice.dto.tmdb.TmdbPageResponse;
import org.junit.jupiter.api.BeforeEach;
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
    void testGetPersonalizedRecommendations_Guarantees50Movies() {
        // Cold start (recommendation-service offline or 0 predictions)
        UserPreferenceResponse preferences = UserPreferenceResponse.builder()
                .userId(userId)
                .industries(Set.of("TOLLYWOOD"))
                .genres(Set.of("ACTION"))
                .build();

        when(userPreferenceService.getPreferences(userId)).thenReturn(preferences);

        // TMDB top rated discover returns 20 movies
        List<TmdbMovieResult> results = new ArrayList<>();
        for (long i = 1; i <= 20; i++) {
            TmdbMovieResult r = new TmdbMovieResult();
            r.setId(i);
            r.setTitle("Movie " + i);
            results.add(r);
        }
        TmdbPageResponse<TmdbMovieResult> pageResponse = new TmdbPageResponse<>();
        pageResponse.setResults(results);

        when(tmdbService.discoverMovies(eq("te"), anyString(), eq("vote_average.desc"), eq(1)))
                .thenReturn(pageResponse);

        // Global top rated backfills the remaining 30 movies
        List<MovieCardDTO> globalTopRated = new ArrayList<>();
        for (long i = 21; i <= 60; i++) {
            globalTopRated.add(MovieCardDTO.builder().id(i).title("Global Movie " + i).build());
        }
        SectionResponseDTO topRatedSection = SectionResponseDTO.builder()
                .movies(globalTopRated)
                .build();
        when(homeFeedService.getSectionFeed(eq(HomeFeedService.SECTION_TOP_RATED), eq(1)))
                .thenReturn(topRatedSection);

        // Catalog hydration
        when(catalogService.getMovieDetail(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return MovieDetailDTO.builder()
                    .id(id)
                    .title("Hydrated Movie " + id)
                    .posterUrl("https://image.tmdb.org/t/p/w500/" + id + ".jpg")
                    .build();
        });

        List<MovieCardDTO> recommendations = recommendationClientService.getPersonalizedRecommendations(userId);

        assertNotNull(recommendations);
        assertEquals(50, recommendations.size(), "Recommendation shelf MUST contain exactly 50 movies!");
    }
}
