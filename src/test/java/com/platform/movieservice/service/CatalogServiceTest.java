package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.dto.response.*;
import com.platform.movieservice.dto.tmdb.*;
import com.platform.movieservice.entity.Movie;
import com.platform.movieservice.entity.MovieCast;
import com.platform.movieservice.entity.MovieCrew;
import com.platform.movieservice.repository.*;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private TmdbService tmdbService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private CacheLeaderExecutor cacheLeaderExecutor;

    @Mock
    private AsyncCatalogPersistenceService asyncPersistenceService;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private MovieCastRepository movieCastRepository;

    @Mock
    private MovieCrewRepository movieCrewRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CatalogService catalogService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(catalogService, "movieDetailsTtl", 86400L);
        ReflectionTestUtils.setField(catalogService, "actorDetailsTtl", 86400L);
        ReflectionTestUtils.setField(catalogService, "crewDetailsTtl", 86400L);
        ReflectionTestUtils.setField(catalogService, "searchTtl", 86400L);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Configure cacheLeaderExecutor to pass through to redisFetcher and externalLoader
        lenient().when(cacheLeaderExecutor.executeWithLeader(anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> redisFetcher = invocation.getArgument(2);
                    Object cached = redisFetcher.get();
                    if (cached != null) return cached;

                    Supplier<?> externalLoader = invocation.getArgument(3);
                    Object fresh = externalLoader.get();

                    Consumer<Object> cacheWriter = invocation.getArgument(4);
                    if (fresh != null && cacheWriter != null) {
                        cacheWriter.accept(fresh);
                    }
                    return fresh;
                });
    }

    @Test
    @DisplayName("Tier 1: Movie Details Redis Cache Hit")
    void testGetMovieDetail_Tier1_RedisHit() {
        Long movieId = 550L;
        MovieDetailDTO cached = MovieDetailDTO.builder()
                .id(movieId)
                .title("Fight Club")
                .runtime(139)
                .build();

        when(valueOperations.get("movie:details:" + movieId)).thenReturn(cached);
        when(reviewRepository.calculateAverageRating(movieId)).thenReturn(4.8);
        when(reviewRepository.countByMovieId(movieId)).thenReturn(100L);

        MovieDetailDTO result = catalogService.getMovieDetail(movieId);

        assertNotNull(result);
        assertEquals(movieId, result.getId());
        assertEquals("Fight Club", result.getTitle());
        assertEquals(4.8, result.getPlatformRating());
        assertEquals(100L, result.getPlatformRatingCount());

        verify(movieRepository, never()).findById(anyLong());
        verify(tmdbService, never()).fetchMovieDetail(anyLong());
    }

    @Test
    @DisplayName("Tier 2: Movie Details DB Hit (PostgreSQL)")
    void testGetMovieDetail_Tier2_DbHit() {
        Long movieId = 550L;
        when(valueOperations.get("movie:details:" + movieId)).thenReturn(null);

        Movie movie = Movie.builder()
                .id(movieId)
                .title("Fight Club")
                .originalTitle("Fight Club")
                .overview("An insomniac office worker...")
                .tagline("Mischief. Mayhem. Soap.")
                .posterPath("/poster.jpg")
                .backdropPath("/backdrop.jpg")
                .releaseDate(LocalDate.of(1999, 10, 15))
                .runtime(139)
                .originalLanguage("en")
                .tmdbVoteAverage(BigDecimal.valueOf(8.4))
                .tmdbVoteCount(28000L)
                .platformRatingAverage(BigDecimal.valueOf(4.9))
                .platformRatingCount(50L)
                .status("Released")
                .build();

        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

        MovieCast castMember = MovieCast.builder()
                .movieId(movieId)
                .personId(101L)
                .personName("Edward Norton")
                .characterName("The Narrator")
                .profilePath("/norton.jpg")
                .castOrder(0)
                .build();
        when(movieCastRepository.findByMovieIdOrderByCastOrderAsc(movieId)).thenReturn(List.of(castMember));

        MovieCrew director = MovieCrew.builder()
                .movieId(movieId)
                .personId(201L)
                .personName("David Fincher")
                .department("Directing")
                .jobDesignation("Director")
                .profilePath("/fincher.jpg")
                .build();
        when(movieCrewRepository.findByMovieId(movieId)).thenReturn(List.of(director));

        when(tmdbService.buildPosterUrl("/poster.jpg", "w500")).thenReturn("https://image.tmdb.org/t/p/w500/poster.jpg");
        when(tmdbService.buildBackdropUrl("/backdrop.jpg", "original")).thenReturn("https://image.tmdb.org/t/p/original/backdrop.jpg");
        when(tmdbService.buildProfileUrl("/norton.jpg", "w185")).thenReturn("https://image.tmdb.org/t/p/w185/norton.jpg");
        when(tmdbService.buildProfileUrl("/fincher.jpg", "w185")).thenReturn("https://image.tmdb.org/t/p/w185/fincher.jpg");

        when(reviewRepository.calculateAverageRating(movieId)).thenReturn(4.9);
        when(reviewRepository.countByMovieId(movieId)).thenReturn(50L);

        MovieDetailDTO result = catalogService.getMovieDetail(movieId);

        assertNotNull(result);
        assertEquals("Fight Club", result.getTitle());
        assertEquals(1999, result.getReleaseYear());
        assertEquals("HOLLYWOOD", result.getIndustry());
        assertEquals(1, result.getTopCast().size());
        assertEquals("Edward Norton", result.getTopCast().get(0).getName());
        assertEquals(1, result.getDirectors().size());
        assertEquals("David Fincher", result.getDirectors().get(0).getName());

        // Verified TMDB was not called since DB satisfied the request
        verify(tmdbService, never()).fetchMovieDetail(anyLong());
    }

    @Test
    @DisplayName("Tier 3: Movie Details TMDB Fallback + Async DB Ingestion")
    void testGetMovieDetail_Tier3_TmdbFallback() {
        Long movieId = 157336L;
        when(valueOperations.get("movie:details:" + movieId)).thenReturn(null);
        when(movieRepository.findById(movieId)).thenReturn(Optional.empty());

        TmdbMovieDetail tmdbDetail = new TmdbMovieDetail();
        tmdbDetail.setId(movieId);
        tmdbDetail.setTitle("Interstellar");
        tmdbDetail.setReleaseDate("2014-11-05");
        tmdbDetail.setRuntime(169);
        tmdbDetail.setOriginalLanguage("en");
        tmdbDetail.setVoteAverage(8.4);
        tmdbDetail.setVoteCount(32000L);

        when(tmdbService.fetchMovieDetail(movieId)).thenReturn(tmdbDetail);

        MovieDetailDTO result = catalogService.getMovieDetail(movieId);

        assertNotNull(result);
        assertEquals("Interstellar", result.getTitle());
        assertEquals(2014, result.getReleaseYear());
        assertEquals(169, result.getRuntime());

        // Verifies write-behind persistence was triggered
        verify(asyncPersistenceService, times(1)).persistMovieAndCredits(tmdbDetail);
    }

    @Test
    @DisplayName("Actor Details: Resolves from TMDB and computes age dynamically")
    void testGetActorDetail() {
        Long personId = 287L;
        when(valueOperations.get("person:actor:" + personId)).thenReturn(null);

        TmdbPersonDetail person = new TmdbPersonDetail();
        person.setId(personId);
        person.setName("Brad Pitt");
        person.setBirthday("1963-12-18");
        person.setPlaceOfBirth("Shawnee, Oklahoma, USA");
        person.setProfilePath("/brad.jpg");

        TmdbMovieResult movieCredit = new TmdbMovieResult();
        movieCredit.setId(550L);
        movieCredit.setTitle("Fight Club");
        movieCredit.setReleaseDate("1999-10-15");

        TmdbPersonMovieCredits credits = new TmdbPersonMovieCredits();
        credits.setCast(List.of(movieCredit));
        person.setMovieCredits(credits);

        when(tmdbService.fetchPersonDetail(personId)).thenReturn(person);
        when(tmdbService.buildProfileUrl("/brad.jpg", "h632")).thenReturn("https://image.tmdb.org/t/p/h632/brad.jpg");

        ActorDetailDTO result = catalogService.getActorDetail(personId);

        assertNotNull(result);
        assertEquals("Brad Pitt", result.getName());
        assertNotNull(result.getAge());
        assertTrue(result.getAge() >= 60, "Age should be correctly computed from 1963");
        assertEquals(1, result.getFilmsActed().size());
        assertEquals("Fight Club", result.getFilmsActed().get(0).getTitle());

        verify(asyncPersistenceService, times(1)).persistPerson(person);
    }

    @Test
    @DisplayName("Crew Details: Extracts designations and films worked")
    void testGetCrewDetail() {
        Long personId = 525L;
        when(valueOperations.get("person:crew:" + personId)).thenReturn(null);

        TmdbPersonDetail person = new TmdbPersonDetail();
        person.setId(personId);
        person.setName("Christopher Nolan");
        person.setKnownForDepartment("Directing");

        TmdbPersonMovieCredits.TmdbCrewWorkedMovie workedMovie = new TmdbPersonMovieCredits.TmdbCrewWorkedMovie();
        workedMovie.setId(157336L);
        workedMovie.setTitle("Interstellar");
        workedMovie.setDepartment("Directing");
        workedMovie.setJob("Director");
        workedMovie.setReleaseDate("2014-11-05");

        TmdbPersonMovieCredits credits = new TmdbPersonMovieCredits();
        credits.setCrew(List.of(workedMovie));
        person.setMovieCredits(credits);

        when(tmdbService.fetchPersonDetail(personId)).thenReturn(person);

        CrewDetailDTO result = catalogService.getCrewDetail(personId);

        assertNotNull(result);
        assertEquals("Christopher Nolan", result.getName());
        assertEquals("Directing", result.getKnownForDepartment());
        assertTrue(result.getPrimaryDesignations().contains("Director"));
        assertEquals(1, result.getFilmsWorked().size());
        assertEquals("Interstellar", result.getFilmsWorked().get(0).getTitle());

        verify(asyncPersistenceService, times(1)).persistPerson(person);
    }

    @Test
    @DisplayName("Multi-Search: Categorizes Movies, Actors, and Crew")
    void testSearch_CategorizesResults() {
        String query = "nolan";
        when(valueOperations.get("search:q:nolan:p:1")).thenReturn(null);

        Map<String, Object> rawMovie = Map.of(
                "id", 157336,
                "media_type", "movie",
                "title", "Interstellar",
                "release_date", "2014-11-05",
                "original_language", "en"
        );

        Map<String, Object> rawPerson = Map.of(
                "id", 525,
                "media_type", "person",
                "name", "Christopher Nolan",
                "known_for_department", "Directing",
                "known_for", List.of(Map.of("title", "Oppenheimer"))
        );

        Map<String, Object> rawTmdbResponse = Map.of("results", List.of(rawMovie, rawPerson));
        when(tmdbService.searchMultiRaw(query, 1)).thenReturn(rawTmdbResponse);

        MultiSearchResponseDTO result = catalogService.search(query, 1);

        assertNotNull(result);
        assertEquals(query, result.getQuery());
        assertEquals(2, result.getTotalMatches());
        assertEquals(1, result.getMovies().size());
        assertEquals("Interstellar", result.getMovies().get(0).getTitle());
        assertEquals(1, result.getCrew().size());
        assertEquals("Christopher Nolan", result.getCrew().get(0).getName());

        // Verifies write to Redis query cache
        verify(valueOperations, times(1)).set(eq("search:q:nolan:p:1"), any(MultiSearchResponseDTO.class), any(Duration.class));
    }

    @Test
    @DisplayName("Search: Query less than 2 characters returns empty immediately")
    void testSearch_ShortQuery() {
        MultiSearchResponseDTO result = catalogService.search("a", 1);

        assertNotNull(result);
        assertEquals(0, result.getTotalMatches());
        assertTrue(result.getMovies().isEmpty());
        verify(tmdbService, never()).searchMultiRaw(anyString(), anyInt());
    }
}
