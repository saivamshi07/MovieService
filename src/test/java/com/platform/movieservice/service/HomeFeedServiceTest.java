package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.SectionResponseDTO;
import com.platform.movieservice.dto.response.UserPreferenceResponse;
import com.platform.movieservice.dto.tmdb.TmdbMovieResult;
import com.platform.movieservice.dto.tmdb.TmdbPageResponse;
import com.platform.movieservice.entity.enums.MovieGenre;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
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

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private HomeFeedService homeFeedService;

    private UUID userId;
    private TmdbMovieResult sampleTmdbMovie;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        lenient().when(tmdbService.resolveGenreId(anyString())).thenAnswer(inv -> {
            String g = inv.getArgument(0);
            if (g == null) return null;
            MovieGenre mg = MovieGenre.fromString(g);
            if (mg != null) return mg.getTmdbId();
            try {
                return Integer.parseInt(g);
            } catch (NumberFormatException e) {
                return null;
            }
        });
        lenient().when(tmdbService.resolveGenreName(anyInt())).thenAnswer(inv -> {
            Integer id = inv.getArgument(0);
            MovieGenre mg = MovieGenre.fromTmdbId(id);
            return mg != null ? mg.getDisplayName() : String.valueOf(id);
        });

        sampleTmdbMovie = new TmdbMovieResult();
        sampleTmdbMovie.setId(550L);
        sampleTmdbMovie.setTitle("Fight Club");
        sampleTmdbMovie.setOverview("An insomniac office worker...");
        sampleTmdbMovie.setPosterPath("/poster.jpg");
        sampleTmdbMovie.setBackdropPath("/backdrop.jpg");
        sampleTmdbMovie.setReleaseDate("1999-10-15");
        sampleTmdbMovie.setOriginalLanguage("en");
        sampleTmdbMovie.setVoteAverage(8.4);
        sampleTmdbMovie.setVoteCount(28000L);
        sampleTmdbMovie.setPopularity(85.5);
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
    @DisplayName("Guest User - Cache Miss falls back to TMDB discover and warms Redis")
    void testGetSectionFeed_Guest_CacheMiss_FetchesTmdb() {
        when(valueOperations.get("feed:default:TRENDING_TODAY:p:1")).thenReturn(null);

        TmdbPageResponse<TmdbMovieResult> tmdbPage = new TmdbPageResponse<>();
        tmdbPage.setPage(1);
        tmdbPage.setTotalPages(10);
        tmdbPage.setTotalResults(200);
        tmdbPage.setResults(List.of(sampleTmdbMovie));

        when(tmdbService.discoverMovies(isNull(), isNull(), eq("vote_count.desc"), anyString(), anyString(), isNull(), eq(1)))
                .thenReturn(tmdbPage);
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
    @DisplayName("Trending Today - Sorted strictly by vote_count desc, then popularity desc")
    void testTrendingToday_SortedByVoteCountDescThenPopularity() {
        TmdbMovieResult movieA = new TmdbMovieResult();
        movieA.setId(1L);
        movieA.setTitle("Movie A");
        movieA.setVoteCount(500L);
        movieA.setPopularity(30.0);

        TmdbMovieResult movieB = new TmdbMovieResult();
        movieB.setId(2L);
        movieB.setTitle("Movie B");
        movieB.setVoteCount(1000L);
        movieB.setPopularity(10.0);

        TmdbMovieResult movieC = new TmdbMovieResult();
        movieC.setId(3L);
        movieC.setTitle("Movie C");
        movieC.setVoteCount(1000L);
        movieC.setPopularity(50.0); // same voteCount as B, but higher popularity -> should be first!

        TmdbPageResponse<TmdbMovieResult> page = new TmdbPageResponse<>();
        // Give them in scrambled order
        page.setResults(new java.util.ArrayList<>(List.of(movieA, movieB, movieC)));

        when(tmdbService.discoverMovies(eq("te"), isNull(), eq("vote_count.desc"), anyString(), anyString(), isNull(), eq(1)))
                .thenReturn(page);

        SectionResponseDTO response = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, 1, "TOLLYWOOD", null);

        assertNotNull(response);
        assertEquals(3, response.getMovies().size());
        // Rank 1: Movie C (1000 votes, 50.0 pop)
        assertEquals(3L, response.getMovies().get(0).getId());
        // Rank 2: Movie B (1000 votes, 10.0 pop)
        assertEquals(2L, response.getMovies().get(1).getId());
        // Rank 3: Movie A (500 votes, 30.0 pop)
        assertEquals(1L, response.getMovies().get(2).getId());
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
                .originalLanguage("te")
                .build();

        MovieCardDTO hollywoodMovie = MovieCardDTO.builder()
                .id(1002L)
                .title("Inception")
                .industry("HOLLYWOOD")
                .originalLanguage("en")
                .build();

        when(valueOperations.get("feed:pool:POPULAR:ind:TOLLYWOOD:genre:ACTION")).thenReturn(List.of(tollywoodMovie));
        when(valueOperations.get("feed:pool:POPULAR:ind:HOLLYWOOD:genre:ACTION")).thenReturn(List.of(hollywoodMovie));

        SectionResponseDTO result = homeFeedService.getSectionFeed(HomeFeedService.SECTION_POPULAR, 1);

        assertNotNull(result);
        assertEquals(HomeFeedService.SECTION_POPULAR, result.getSection());
        assertEquals(2, result.getTotalResults());
        assertEquals(2, result.getMovies().size());

        List<Long> movieIds = result.getMovies().stream().map(MovieCardDTO::getId).toList();
        assertTrue(movieIds.contains(1001L));
        assertTrue(movieIds.contains(1002L));
    }

    @Test
    @DisplayName("Authenticated User - Telugu Only Preference Excludes English Movies")
    void testGetSectionFeed_TeluguOnly_ExcludesEnglishMovies() {
        UserContext.setUserId(userId);

        UserPreferenceResponse preferences = UserPreferenceResponse.builder()
                .userId(userId)
                .industries(Set.of("TOLLYWOOD"))
                .genres(Set.of("ACTION"))
                .build();

        when(userPreferenceService.getPreferences(userId)).thenReturn(preferences);

        MovieCardDTO tollywoodMovie = MovieCardDTO.builder()
                .id(1001L)
                .title("RRR")
                .industry("TOLLYWOOD")
                .originalLanguage("te")
                .build();

        MovieCardDTO englishMovie = MovieCardDTO.builder()
                .id(1002L)
                .title("Fight Club")
                .industry("HOLLYWOOD")
                .originalLanguage("en")
                .build();

        // Simulate pool containing both or separate
        when(valueOperations.get("feed:pool:POPULAR:ind:TOLLYWOOD:genre:ACTION")).thenReturn(List.of(tollywoodMovie, englishMovie));

        SectionResponseDTO result = homeFeedService.getSectionFeed(HomeFeedService.SECTION_POPULAR, 1);

        assertNotNull(result);
        // English movie must be filtered out because user's preferred industry is only TOLLYWOOD
        assertEquals(1, result.getMovies().size());
        assertEquals("RRR", result.getMovies().get(0).getTitle());
        assertEquals("TOLLYWOOD", result.getMovies().get(0).getIndustry());
    }

    @Test
    @DisplayName("Authenticated User - Preferences exist but Redis pool empty fetches on-demand for preferred industry")
    void testGetSectionFeed_AuthenticatedUser_EmptyPools_FetchesOnDemand() {
        UserContext.setUserId(userId);

        UserPreferenceResponse preferences = UserPreferenceResponse.builder()
                .userId(userId)
                .industries(Set.of("BOLLYWOOD"))
                .genres(Set.of("DRAMA"))
                .build();

        when(userPreferenceService.getPreferences(userId)).thenReturn(preferences);
        when(valueOperations.get("feed:pool:TOP_RATED:ind:BOLLYWOOD:genre:DRAMA")).thenReturn(null);

        TmdbPageResponse<TmdbMovieResult> tmdbPage = new TmdbPageResponse<>();
        tmdbPage.setPage(1);
        tmdbPage.setTotalPages(1);
        tmdbPage.setTotalResults(1);
        TmdbMovieResult bollywoodMovie = new TmdbMovieResult();
        bollywoodMovie.setId(9999L);
        bollywoodMovie.setTitle("3 Idiots");
        bollywoodMovie.setOriginalLanguage("hi");
        bollywoodMovie.setReleaseDate("2009-12-25");
        bollywoodMovie.setVoteAverage(8.5);
        bollywoodMovie.setVoteCount(5000L);
        bollywoodMovie.setGenreIds(List.of(18));
        tmdbPage.setResults(List.of(bollywoodMovie));

        when(tmdbService.discoverMovies(eq("hi"), eq("18"), anyString(), any(), any(), any(), eq(1)))
                .thenReturn(tmdbPage);

        SectionResponseDTO result = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1);

        assertNotNull(result);
        assertEquals(HomeFeedService.SECTION_TOP_RATED, result.getSection());
        assertEquals(1, result.getMovies().size());
        assertEquals("BOLLYWOOD", result.getMovies().get(0).getIndustry());
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
    @DisplayName("Deduplication - Removes duplicate movies within the same section")
    void testSectionFeed_RemovesDuplicatesWithinSection() {
        TmdbMovieResult movie1 = new TmdbMovieResult();
        movie1.setId(100L);
        movie1.setTitle("Movie 100");
        movie1.setVoteCount(500L);
        movie1.setPopularity(20.0);

        TmdbMovieResult movie1Dup = new TmdbMovieResult();
        movie1Dup.setId(100L);
        movie1Dup.setTitle("Movie 100 Duplicate");
        movie1Dup.setVoteCount(500L);
        movie1Dup.setPopularity(20.0);

        TmdbMovieResult movie2 = new TmdbMovieResult();
        movie2.setId(200L);
        movie2.setTitle("Movie 200");
        movie2.setVoteCount(300L);
        movie2.setPopularity(15.0);

        TmdbPageResponse<TmdbMovieResult> page = new TmdbPageResponse<>();
        page.setResults(new java.util.ArrayList<>(List.of(movie1, movie1Dup, movie2)));

        when(tmdbService.discoverMovies(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(page);

        SectionResponseDTO response = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, 1, "TOLLYWOOD", null);

        assertNotNull(response);
        // Only 2 distinct movies must remain (movie 100 duplicate removed)
        assertEquals(2, response.getMovies().size());
        assertEquals(100L, response.getMovies().get(0).getId());
        assertEquals(200L, response.getMovies().get(1).getId());
    }

    @Test
    @DisplayName("Cross-section Duplication Allowed - Movie can exist in different sections")
    void testMoviesCanExistInDifferentSections() {
        TmdbMovieResult commonMovie = new TmdbMovieResult();
        commonMovie.setId(550L);
        commonMovie.setTitle("Fight Club");
        commonMovie.setVoteCount(28000L);
        commonMovie.setPopularity(85.5);

        TmdbPageResponse<TmdbMovieResult> page = new TmdbPageResponse<>();
        page.setResults(List.of(commonMovie));

        when(tmdbService.discoverMovies(any(), any(), any(), any(), any(), any(), eq(1)))
                .thenReturn(page);
        lenient().when(tmdbService.fetchPopularMovies(eq(1))).thenReturn(page);

        SectionResponseDTO trending = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, 1);
        SectionResponseDTO popular = homeFeedService.getSectionFeed(HomeFeedService.SECTION_POPULAR, 1);

        assertNotNull(trending);
        assertNotNull(popular);
        assertEquals(1, trending.getMovies().size());
        assertEquals(1, popular.getMovies().size());
        assertEquals(550L, trending.getMovies().get(0).getId());
        assertEquals(550L, popular.getMovies().get(0).getId());
    }

    @Test
    @DisplayName("Startup / Warm-up Refreshes All Feeds and Pre-warms Trending")
    void testRefreshAllFeeds() {
        TmdbPageResponse<TmdbMovieResult> pageResponse = new TmdbPageResponse<>();
        pageResponse.setResults(List.of(sampleTmdbMovie));

        lenient().when(tmdbService.fetchTrendingMovies(anyString(), anyInt())).thenReturn(pageResponse);
        lenient().when(tmdbService.fetchPopularMovies(anyInt())).thenReturn(pageResponse);
        lenient().when(tmdbService.fetchTopRatedMovies(anyInt())).thenReturn(pageResponse);
        lenient().when(tmdbService.fetchNowPlayingMovies(anyInt())).thenReturn(pageResponse);
        lenient().when(tmdbService.discoverMovies(any(), any(), any(), anyInt())).thenReturn(pageResponse);
        lenient().when(tmdbService.discoverMovies(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(pageResponse);

        homeFeedService.refreshAllFeeds();

        // Check that pre-warming was triggered for top trending items
        verify(proactivePrewarmService, atLeastOnce()).prewarmTopMovies(anyList());
        // Check that redis set was invoked
        verify(valueOperations, atLeastOnce()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("Popular Feed - Hits TMDB with vote_count.desc, returns sorted by vote count desc")
    void testPopularFeed_HitsVoteCountDescThenReturnsSortedByVoteCountDesc() {
        TmdbMovieResult movieA = new TmdbMovieResult();
        movieA.setId(1L);
        movieA.setTitle("Movie A");
        movieA.setVoteCount(500L);
        movieA.setPopularity(30.0);

        TmdbMovieResult movieB = new TmdbMovieResult();
        movieB.setId(2L);
        movieB.setTitle("Movie B");
        movieB.setVoteCount(2000L);
        movieB.setPopularity(10.0);

        TmdbMovieResult movieC = new TmdbMovieResult();
        movieC.setId(3L);
        movieC.setTitle("Movie C");
        movieC.setVoteCount(2000L);
        movieC.setPopularity(50.0); // same vote count as B, but higher popularity -> rank 1

        TmdbPageResponse<TmdbMovieResult> page = new TmdbPageResponse<>();
        page.setResults(new java.util.ArrayList<>(List.of(movieA, movieB, movieC)));

        when(tmdbService.discoverMovies(isNull(), isNull(), eq("vote_count.desc"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(page);

        SectionResponseDTO response = homeFeedService.getSectionFeed(HomeFeedService.SECTION_POPULAR, 1);

        assertNotNull(response);
        // Verify it called TMDB discover with vote_count.desc
        verify(tmdbService, times(1)).discoverMovies(isNull(), isNull(), eq("vote_count.desc"), isNull(), isNull(), isNull(), eq(1));
        assertEquals(3, response.getMovies().size());
        // Rank 1: Movie C (2000 votes, 50.0 pop)
        assertEquals(3L, response.getMovies().get(0).getId());
        // Rank 2: Movie B (2000 votes, 10.0 pop)
        assertEquals(2L, response.getMovies().get(1).getId());
        // Rank 3: Movie A (500 votes, 30.0 pop)
        assertEquals(1L, response.getMovies().get(2).getId());
    }

    @Test
    @DisplayName("Now In Theatres - Sorted by vote count desc then popularity, last 3 weeks to today")
    void testNowPlayingFeed_SortedByVoteCountDescThenPopularity() {
        TmdbMovieResult movieA = new TmdbMovieResult();
        movieA.setId(10L);
        movieA.setTitle("Movie A");
        movieA.setVoteCount(100L);
        movieA.setPopularity(20.0);

        TmdbMovieResult movieB = new TmdbMovieResult();
        movieB.setId(20L);
        movieB.setTitle("Movie B");
        movieB.setVoteCount(500L);
        movieB.setPopularity(10.0);

        TmdbMovieResult movieC = new TmdbMovieResult();
        movieC.setId(30L);
        movieC.setTitle("Movie C");
        movieC.setVoteCount(500L);
        movieC.setPopularity(40.0);

        TmdbPageResponse<TmdbMovieResult> page = new TmdbPageResponse<>();
        page.setResults(new java.util.ArrayList<>(List.of(movieA, movieB, movieC)));

        when(tmdbService.discoverMovies(isNull(), isNull(), eq("vote_count.desc"), anyString(), anyString(), isNull(), eq(1)))
                .thenReturn(page);

        SectionResponseDTO response = homeFeedService.getSectionFeed(HomeFeedService.SECTION_NOW_PLAYING, 1);

        assertNotNull(response);
        assertEquals(3, response.getMovies().size());
        assertEquals(30L, response.getMovies().get(0).getId());
        assertEquals(20L, response.getMovies().get(1).getId());
        assertEquals(10L, response.getMovies().get(2).getId());
    }

    @Test
    @DisplayName("Top Rated Classics - Dynamic threshold from page 1, filters and fetches next page if < 20")
    void testTopRatedClassics_DynamicThresholdAndFetchesNextPage() {
        // Page 1
        TmdbMovieResult m1 = new TmdbMovieResult();
        m1.setId(101L);
        m1.setTitle("Movie 1");
        m1.setVoteCount(1000L);
        m1.setVoteAverage(8.5);
        m1.setPopularity(50.0);

        TmdbMovieResult m2 = new TmdbMovieResult();
        m2.setId(102L);
        m2.setTitle("Low Rating Movie");
        m2.setVoteCount(800L);
        m2.setVoteAverage(6.0); // fails: highest is 8.5, threshold is 8.5 - 2.0 = 6.5
        m2.setPopularity(40.0);

        TmdbMovieResult m3 = new TmdbMovieResult();
        m3.setId(103L);
        m3.setTitle("Last Result Movie");
        m3.setVoteCount(400L); // last movie of page 1: voteCount threshold = 400 / 2 = 200
        m3.setVoteAverage(7.0); // passes: >= 6.5
        m3.setPopularity(30.0);

        TmdbPageResponse<TmdbMovieResult> page1 = new TmdbPageResponse<>();
        page1.setPage(1);
        page1.setTotalPages(2);
        page1.setResults(new java.util.ArrayList<>(List.of(m1, m2, m3)));

        // Page 2
        TmdbMovieResult m4 = new TmdbMovieResult();
        m4.setId(104L);
        m4.setTitle("Page 2 Movie");
        m4.setVoteCount(300L); // passes: >= 200
        m4.setVoteAverage(7.5); // passes: >= 6.5
        m4.setPopularity(20.0);

        TmdbMovieResult m5 = new TmdbMovieResult();
        m5.setId(105L);
        m5.setTitle("Low Vote Count Movie");
        m5.setVoteCount(150L); // fails: < 200
        m5.setVoteAverage(8.0);
        m5.setPopularity(10.0);

        TmdbPageResponse<TmdbMovieResult> page2 = new TmdbPageResponse<>();
        page2.setPage(2);
        page2.setTotalPages(2);
        page2.setResults(new java.util.ArrayList<>(List.of(m4, m5)));

        when(tmdbService.discoverMovies(isNull(), isNull(), eq("vote_count.desc"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(page1);
        when(tmdbService.discoverMovies(isNull(), isNull(), eq("vote_count.desc"), isNull(), isNull(), isNull(), eq(2)))
                .thenReturn(page2);

        SectionResponseDTO response = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1);

        assertNotNull(response);
        // Should contain m1 (1000 votes, 8.5 in bucket 4), m4 (300 votes, 7.5 in bucket 0), m3 (400 votes, 7.0 in bucket 0)
        assertEquals(3, response.getMovies().size());
        assertEquals(101L, response.getMovies().get(0).getId()); // Bucket 4
        assertEquals(104L, response.getMovies().get(1).getId()); // Bucket 0 (voteAverage 7.5 > 7.0)
        assertEquals(103L, response.getMovies().get(2).getId()); // Bucket 0 (voteAverage 7.0)
        verify(tmdbService, times(1)).discoverMovies(isNull(), isNull(), eq("vote_count.desc"), isNull(), isNull(), isNull(), eq(2));
    }

    @Test
    @DisplayName("Top Rated Classics - Composite list sorts by 5-bucket vote count tiers in round-robin")
    void testCompositeFeed_TopRated_BucketedVoteCountRoundRobin() {
        UserContext.setUserId(userId);
        UserPreferenceResponse preferences = UserPreferenceResponse.builder()
                .userId(userId)
                .industries(Set.of("TOLLYWOOD"))
                .genres(Set.of("ACTION"))
                .build();
        when(userPreferenceService.getPreferences(userId)).thenReturn(preferences);

        MovieCardDTO m1 = MovieCardDTO.builder().id(1L).title("M1").industry("TOLLYWOOD").tmdbVoteAverage(7.5).tmdbVoteCount(5000L).build();
        MovieCardDTO m2 = MovieCardDTO.builder().id(2L).title("M2").industry("TOLLYWOOD").tmdbVoteAverage(8.8).tmdbVoteCount(1000L).build();
        MovieCardDTO m3 = MovieCardDTO.builder().id(3L).title("M3").industry("TOLLYWOOD").tmdbVoteAverage(8.1).tmdbVoteCount(3000L).build();

        when(valueOperations.get("feed:pool:TOP_RATED:ind:TOLLYWOOD:genre:ACTION")).thenReturn(List.of(m1, m2, m3));

        SectionResponseDTO response = homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1);

        assertNotNull(response);
        assertEquals(3, response.getMovies().size());
        // m1 (5000) is in Bucket 4 (highest vote count tier) -> round robin 1st
        assertEquals(1L, response.getMovies().get(0).getId());
        // m3 (3000) is in Bucket 2 (middle tier) -> round robin 2nd
        assertEquals(3L, response.getMovies().get(1).getId());
        // m2 (1000) is in Bucket 0 (lowest tier) -> round robin 3rd
        assertEquals(2L, response.getMovies().get(2).getId());
    }

    @Test
    @DisplayName("Top Rated Classics - 5-bucket division and 3-item round-robin taking from highest to lowest bucket")
    void testRankTopRatedClassicsByVoteCountBuckets_FiveBucketsRoundRobin() {
        // Range 100 to 1000:
        // Bucket 4 [820 to 1000]: 4 movies (vote averages: 9.1, 8.8, 8.5, 8.0)
        MovieCardDTO h1 = MovieCardDTO.builder().id(101L).tmdbVoteCount(950L).tmdbVoteAverage(8.8).build();
        MovieCardDTO h2 = MovieCardDTO.builder().id(102L).tmdbVoteCount(920L).tmdbVoteAverage(9.1).build();
        MovieCardDTO h3 = MovieCardDTO.builder().id(103L).tmdbVoteCount(990L).tmdbVoteAverage(8.0).build();
        MovieCardDTO h4 = MovieCardDTO.builder().id(104L).tmdbVoteCount(910L).tmdbVoteAverage(8.5).build();

        // Bucket 3 [640 to 820): 2 movies (vote averages: 9.0, 8.9)
        MovieCardDTO m1 = MovieCardDTO.builder().id(201L).tmdbVoteCount(750L).tmdbVoteAverage(8.9).build();
        MovieCardDTO m2 = MovieCardDTO.builder().id(202L).tmdbVoteCount(800L).tmdbVoteAverage(9.0).build();

        // Bucket 0 [100 to 280): 2 movies (vote averages: 9.5, 7.2)
        MovieCardDTO l1 = MovieCardDTO.builder().id(301L).tmdbVoteCount(150L).tmdbVoteAverage(9.5).build();
        MovieCardDTO l2 = MovieCardDTO.builder().id(302L).tmdbVoteCount(200L).tmdbVoteAverage(7.2).build();

        List<MovieCardDTO> all = new java.util.ArrayList<>(List.of(h1, h2, h3, h4, m1, m2, l1, l2));
        List<MovieCardDTO> ranked = homeFeedService.rankTopRatedClassicsByVoteCountBuckets(all);

        assertEquals(8, ranked.size());

        // Round 1:
        // Top bucket 4: first 3 sorted by vote_average desc -> h2 (9.1), h1 (8.8), h4 (8.5)
        assertEquals(102L, ranked.get(0).getId());
        assertEquals(101L, ranked.get(1).getId());
        assertEquals(104L, ranked.get(2).getId());

        // Bucket 3: up to 3 sorted by vote_average desc -> m2 (9.0), m1 (8.9)
        assertEquals(202L, ranked.get(3).getId());
        assertEquals(201L, ranked.get(4).getId());

        // Bucket 2: empty
        // Bucket 1: empty
        // Bucket 0: up to 3 sorted by vote_average desc -> l1 (9.5), l2 (7.2)
        assertEquals(301L, ranked.get(5).getId());
        assertEquals(302L, ranked.get(6).getId());

        // Round 2:
        // Top bucket 4: remaining movie -> h3 (8.0)
        assertEquals(103L, ranked.get(7).getId());
    }

    @Test
    @DisplayName("Top Popular Movies - Cache Hit in Redis with exclusion")
    void testGetTopPopularMovies_CacheHit_WithExclusion() {
        MovieCardDTO m1 = MovieCardDTO.builder().id(101L).title("RRR").industry("TOLLYWOOD").build();
        MovieCardDTO m2 = MovieCardDTO.builder().id(102L).title("Pushpa").industry("TOLLYWOOD").build();
        MovieCardDTO m3 = MovieCardDTO.builder().id(103L).title("Baahubali").industry("TOLLYWOOD").build();

        when(valueOperations.get("feed:pool:POPULAR:ind:TOLLYWOOD:genre:ACTION"))
                .thenReturn(List.of(m1, m2, m3));

        List<MovieCardDTO> result = homeFeedService.getTopPopularMovies("TOLLYWOOD", "ACTION", 102L);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(101L, result.get(0).getId());
        assertEquals(103L, result.get(1).getId());
        // Verify TMDB discover was never called because it was a cache hit
        verify(tmdbService, never()).discoverMovies(any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Top Popular Movies - Cache Miss fetches TMDB discover and warms Redis asynchronously")
    void testGetTopPopularMovies_CacheMiss_FetchesTmdb() {
        when(valueOperations.get("feed:pool:POPULAR:ind:HOLLYWOOD:genre:ACTION")).thenReturn(null);

        TmdbPageResponse<TmdbMovieResult> tmdbPage = new TmdbPageResponse<>();
        tmdbPage.setPage(1);
        tmdbPage.setTotalPages(1);
        tmdbPage.setTotalResults(1);
        tmdbPage.setResults(List.of(sampleTmdbMovie));

        when(tmdbService.discoverMovies(eq("en"), eq("28"), eq("vote_count.desc"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(tmdbPage);

        List<MovieCardDTO> result = homeFeedService.getTopPopularMovies("HOLLYWOOD", "ACTION", null);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(550L, result.get(0).getId());

        verify(valueOperations, timeout(2000).times(1)).set(eq("feed:pool:POPULAR:ind:HOLLYWOOD:genre:ACTION"), anyList(), any(Duration.class));
    }

    @Test
    @DisplayName("Top Popular Movies - Max 20 results returned")
    void testGetTopPopularMovies_Max20_ReturnsTop20() {
        List<MovieCardDTO> pool = new ArrayList<>();
        for (long i = 1; i <= 25; i++) {
            pool.add(MovieCardDTO.builder().id(i).title("Movie " + i).industry("TOLLYWOOD").build());
        }

        when(valueOperations.get("feed:pool:POPULAR:ind:TOLLYWOOD:genre:ACTION"))
                .thenReturn(pool);

        List<MovieCardDTO> result = homeFeedService.getTopPopularMovies("TOLLYWOOD", "ACTION", null);

        assertNotNull(result);
        assertEquals(20, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(20L, result.get(19).getId());
    }

    @Test
    @DisplayName("Section Feed - Exclude movie id filters out target movie")
    void testGetSectionFeed_WithExcludeMovieId() {
        MovieCardDTO m1 = MovieCardDTO.builder().id(101L).title("RRR").industry("TOLLYWOOD").build();
        MovieCardDTO m2 = MovieCardDTO.builder().id(102L).title("Pushpa").industry("TOLLYWOOD").build();

        when(valueOperations.get("feed:pool:POPULAR:ind:TOLLYWOOD:genre:ACTION"))
                .thenReturn(List.of(m1, m2));

        SectionResponseDTO response = homeFeedService.getSectionFeed(
                HomeFeedService.SECTION_POPULAR, 1, "TOLLYWOOD", "ACTION", 101L
        );

        assertNotNull(response);
        assertEquals(1, response.getMovies().size());
        assertEquals(102L, response.getMovies().get(0).getId());
    }
}
