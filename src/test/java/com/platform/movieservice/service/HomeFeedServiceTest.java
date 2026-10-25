package com.platform.movieservice.service;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.SectionResponseDTO;
import com.platform.movieservice.dto.response.UserPreferenceResponse;
import com.platform.movieservice.dto.tmdb.TmdbMovieResult;
import com.platform.movieservice.dto.tmdb.TmdbPageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeFeedServiceTest {

    @Mock
    private TmdbService tmdbService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private UserPreferenceService userPreferenceService;

    @Mock
    private ProactivePrewarmService proactivePrewarmService;

    @InjectMocks
    private HomeFeedService homeFeedService;

    private UUID userId;
    private TmdbMovieResult sampleTmdbMovie;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        sampleTmdbMovie = new TmdbMovieResult();
        sampleTmdbMovie.setId(550L);
        sampleTmdbMovie.setTitle("Fight Club");
        sampleTmdbMovie.setOverview("An insomniac office worker...");
        sampleTmdbMovie.setPosterPath("/poster.jpg");
        sampleTmdbMovie.setBackdropPath("/backdrop.jpg");
        sampleTmdbMovie.setReleaseDate("1999-10-15");
        sampleTmdbMovie.setOriginalLanguage("en");
        sampleTmdbMovie.setVoteAverage(8.4);
        sampleTmdbMovie.setGenreIds(List.of(18, 53)); // Drama, Thriller
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("Guest User - Feed Cache Hit in Redis")
    void testGetSectionFeed_Guest_CacheHit() {
        SectionResponseDTO cachedResponse = SectionResponseDTO.builder()
                .section(HomeFeedService.SECTION_TRENDING_TODAY)
                .title("Trending Today")
                .page(1)
                .totalPages(5)
                .totalResults(100)
                .movies(List.of())
                .build();

        when(valueOperations.get("feed:default:TRENDING_TODAY:p:1")).thenReturn(cachedResponse);

        SectionResponseDTO result = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, 1);

        assertNotNull(result);
        assertEquals("Trending Today", result.getTitle());
        verify(tmdbService, never()).fetchTrendingMovies(anyString(), anyInt());
    }

    @Test
    @DisplayName("Guest User - Cache Miss falls back to TMDB and warms Redis")
    void testGetSectionFeed_Guest_CacheMiss_FetchesTmdb() {
        when(valueOperations.get("feed:default:TRENDING_TODAY:p:1")).thenReturn(null);

        TmdbPageResponse<TmdbMovieResult> tmdbPage = new TmdbPageResponse<>();
        tmdbPage.setPage(1);
        tmdbPage.setTotalPages(10);
        tmdbPage.setTotalResults(200);
        tmdbPage.setResults(List.of(sampleTmdbMovie));

        when(tmdbService.fetchTrendingMovies("day", 1)).thenReturn(tmdbPage);
        when(tmdbService.buildPosterUrl("/poster.jpg", "w500")).thenReturn("https://image.tmdb.org/t/p/w500/poster.jpg");
        when(tmdbService.buildBackdropUrl("/backdrop.jpg", "w1280")).thenReturn("https://image.tmdb.org/t/p/w1280/backdrop.jpg");

        SectionResponseDTO result = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, 1);

        assertNotNull(result);
        assertEquals(HomeFeedService.SECTION_TRENDING_TODAY, result.getSection());
        assertEquals(1, result.getMovies().size());
        MovieCardDTO card = result.getMovies().get(0);
        assertEquals(550L, card.getId());
        assertEquals("Fight Club", card.getTitle());
        assertEquals(1999, card.getReleaseYear());
        assertEquals("HOLLYWOOD", card.getIndustry());

        // Verify write to Redis cache
        verify(valueOperations, times(1)).set(eq("feed:default:TRENDING_TODAY:p:1"), any(SectionResponseDTO.class), any(Duration.class));
    }

    @Test
    @DisplayName("Authenticated User - Composites preferred Industry and Genre pools")
    void testGetSectionFeed_AuthenticatedUser_WithPreferences() {
        UserContext.setUserId(userId);

        UserPreferenceResponse preferences = UserPreferenceResponse.builder()
                .userId(userId)
                .industries(Set.of("TOLLYWOOD", "HOLLYWOOD"))
                .genres(Set.of("ACTION"))
                .build();

        when(userPreferenceService.getPreferences(userId)).thenReturn(preferences);

        MovieCardDTO tollywoodMovie = MovieCardDTO.builder()
                .id(1001L)
                .title("RRR")
                .industry("TOLLYWOOD")
                .build();

        MovieCardDTO hollywoodMovie = MovieCardDTO.builder()
                .id(1002L)
                .title("Inception")
                .industry("HOLLYWOOD")
                .build();

        MovieCardDTO actionMovie = MovieCardDTO.builder()
                .id(1003L)
                .title("John Wick")
                .industry("HOLLYWOOD")
                .build();

        when(valueOperations.get("feed:pool:POPULAR:ind:TOLLYWOOD")).thenReturn(List.of(tollywoodMovie));
        when(valueOperations.get("feed:pool:POPULAR:ind:HOLLYWOOD")).thenReturn(List.of(hollywoodMovie));
        when(valueOperations.get("feed:pool:POPULAR:genre:ACTION")).thenReturn(List.of(actionMovie));

        SectionResponseDTO result = homeFeedService.getSectionFeed(HomeFeedService.SECTION_POPULAR, 1);

        assertNotNull(result);
        assertEquals(HomeFeedService.SECTION_POPULAR, result.getSection());
        assertEquals(3, result.getTotalResults());
        assertEquals(3, result.getMovies().size());

        List<Long> movieIds = result.getMovies().stream().map(MovieCardDTO::getId).toList();
        assertTrue(movieIds.contains(1001L));
        assertTrue(movieIds.contains(1002L));
        assertTrue(movieIds.contains(1003L));
    }

    @Test
    @DisplayName("Authenticated User - Preferences exist but Redis pools empty falls back to default")
    void testGetSectionFeed_AuthenticatedUser_EmptyPools_FallsBackToDefault() {
        UserContext.setUserId(userId);

        UserPreferenceResponse preferences = UserPreferenceResponse.builder()
                .userId(userId)
                .industries(Set.of("BOLLYWOOD"))
                .genres(Set.of())
                .build();

        when(userPreferenceService.getPreferences(userId)).thenReturn(preferences);
        when(valueOperations.get("feed:pool:TOP_RATED:ind:BOLLYWOOD")).thenReturn(null);

        // Falls back to default feed
        SectionResponseDTO defaultResponse = SectionResponseDTO.builder()
                .section(HomeFeedService.SECTION_TOP_RATED)
                .title("Top Rated Classics")
                .page(1)
                .movies(List.of())
                .build();
        when(valueOperations.get("feed:default:TOP_RATED:p:1")).thenReturn(defaultResponse);

        SectionResponseDTO result = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1);

        assertNotNull(result);
        assertEquals("Top Rated Classics", result.getTitle());
    }

    @Test
    @DisplayName("Map to Cards - Transforms TMDB result correctly")
    void testMapToCards() {
        when(tmdbService.buildPosterUrl("/poster.jpg", "w500")).thenReturn("https://image.tmdb.org/t/p/w500/poster.jpg");
        when(tmdbService.buildBackdropUrl("/backdrop.jpg", "w1280")).thenReturn("https://image.tmdb.org/t/p/w1280/backdrop.jpg");

        List<MovieCardDTO> cards = homeFeedService.mapToCards(List.of(sampleTmdbMovie));

        assertNotNull(cards);
        assertEquals(1, cards.size());
        MovieCardDTO card = cards.get(0);
        assertEquals(550L, card.getId());
        assertEquals("Fight Club", card.getTitle());
        assertEquals(1999, card.getReleaseYear());
        assertEquals("HOLLYWOOD", card.getIndustry());
        assertTrue(card.getGenres().contains("Drama"));
        assertTrue(card.getGenres().contains("Thriller"));
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", card.getPosterUrl());
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", card.getBackdropUrl());
    }

    @Test
    @DisplayName("Startup / Warm-up Refreshes All Feeds and Pre-warms Trending")
    void testRefreshAllFeeds() {
        TmdbPageResponse<TmdbMovieResult> pageResponse = new TmdbPageResponse<>();
        pageResponse.setResults(List.of(sampleTmdbMovie));

        when(tmdbService.fetchTrendingMovies(anyString(), anyInt())).thenReturn(pageResponse);
        when(tmdbService.fetchPopularMovies(anyInt())).thenReturn(pageResponse);
        when(tmdbService.fetchTopRatedMovies(anyInt())).thenReturn(pageResponse);
        when(tmdbService.fetchNowPlayingMovies(anyInt())).thenReturn(pageResponse);
        when(tmdbService.discoverMovies(any(), any(), any(), anyInt())).thenReturn(pageResponse);

        homeFeedService.refreshAllFeeds();

        // Check that pre-warming was triggered for top trending items
        verify(proactivePrewarmService, atLeastOnce()).prewarmTopMovies(anyList());
        // Check that redis set was invoked
        verify(valueOperations, atLeastOnce()).set(anyString(), any(), any(Duration.class));
    }
}
