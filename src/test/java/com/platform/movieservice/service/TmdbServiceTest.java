package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.config.TmdbConfig;
import com.platform.movieservice.dto.tmdb.TmdbGenre;
import com.platform.movieservice.dto.tmdb.TmdbMovieResult;
import com.platform.movieservice.dto.tmdb.TmdbPageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TmdbServiceTest {

    private MockRestServiceServer mockServer;
    private TmdbService tmdbService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org/3");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        TmdbConfig tmdbConfig = new TmdbConfig();
        tmdbService = new TmdbService(restClient, tmdbConfig, objectMapper);

        ReflectionTestUtils.setField(tmdbService, "imageBaseUrl", "https://image.tmdb.org/t/p");
        ReflectionTestUtils.setField(tmdbService, "defaultLanguage", "en-US");
        tmdbService.initGenreCache();
    }

    @Test
    @DisplayName("Fetch Trending Movies (Day) - Success")
    void testFetchTrendingMovies_Day_Success() {
        String json = """
            {
                "page": 1,
                "results": [
                    {
                        "id": 550,
                        "title": "Fight Club",
                        "overview": "An insomniac office worker...",
                        "poster_path": "/poster550.jpg",
                        "backdrop_path": "/backdrop550.jpg",
                        "release_date": "1999-10-15",
                        "original_language": "en",
                        "vote_average": 8.433,
                        "genre_ids": [18, 53]
                    }
                ],
                "total_pages": 10,
                "total_results": 200
            }
            """;

        mockServer.expect(requestTo("https://api.themoviedb.org/3/trending/movie/day?page=1&language=en-US"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchTrendingMovies("day", 1);

        assertNotNull(response);
        assertEquals(1, response.getPage());
        assertEquals(1, response.getResults().size());
        assertEquals(550L, response.getResults().get(0).getId());
        assertEquals("Fight Club", response.getResults().get(0).getTitle());
        mockServer.verify();
    }

    @Test
    @DisplayName("Fetch Trending Movies (Week) - Success")
    void testFetchTrendingMovies_Week_Success() {
        String json = """
            {
                "page": 1,
                "results": [
                    {
                        "id": 603,
                        "title": "The Matrix",
                        "release_date": "1999-03-30",
                        "original_language": "en",
                        "vote_average": 8.2
                    }
                ],
                "total_pages": 5,
                "total_results": 100
            }
            """;

        mockServer.expect(requestTo("https://api.themoviedb.org/3/trending/movie/week?page=1&language=en-US"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchTrendingMovies("week", 1);

        assertNotNull(response);
        assertEquals(603L, response.getResults().get(0).getId());
        assertEquals("The Matrix", response.getResults().get(0).getTitle());
        mockServer.verify();
    }

    @Test
    @DisplayName("Fetch Popular Movies - Success")
    void testFetchPopularMovies_Success() {
        String json = """
            {
                "page": 1,
                "results": [
                    {"id": 100, "title": "Popular Movie", "original_language": "en"}
                ],
                "total_pages": 1,
                "total_results": 1
            }
            """;

        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/popular?page=1&language=en-US"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchPopularMovies(1);

        assertNotNull(response);
        assertEquals(1, response.getResults().size());
        assertEquals(100L, response.getResults().get(0).getId());
        mockServer.verify();
    }

    @Test
    @DisplayName("Fetch Top Rated Movies - Success")
    void testFetchTopRatedMovies_Success() {
        String json = """
            {
                "page": 1,
                "results": [
                    {"id": 238, "title": "The Godfather", "vote_average": 8.7}
                ],
                "total_pages": 1,
                "total_results": 1
            }
            """;

        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/top_rated?page=1&language=en-US"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchTopRatedMovies(1);

        assertNotNull(response);
        assertEquals(238L, response.getResults().get(0).getId());
        assertEquals("The Godfather", response.getResults().get(0).getTitle());
        mockServer.verify();
    }

    @Test
    @DisplayName("Fetch Now Playing Movies - Success")
    void testFetchNowPlayingMovies_Success() {
        String json = """
            {
                "page": 1,
                "results": [
                    {"id": 500, "title": "In Theatres Now"}
                ],
                "total_pages": 1,
                "total_results": 1
            }
            """;

        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/now_playing?page=1&language=en-US"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchNowPlayingMovies(1);

        assertNotNull(response);
        assertEquals(500L, response.getResults().get(0).getId());
        mockServer.verify();
    }

    @Test
    @DisplayName("Discover Movies with Language and Genre - Success")
    void testDiscoverMovies_Success() {
        String json = """
            {
                "page": 1,
                "results": [
                    {"id": 999, "title": "Tollywood Action Movie", "original_language": "te"}
                ],
                "total_pages": 2,
                "total_results": 30
            }
            """;

        mockServer.expect(requestTo("https://api.themoviedb.org/3/discover/movie?page=1&language=en-US&sort_by=popularity.desc&with_original_language=te&with_genres=28"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TmdbPageResponse<TmdbMovieResult> response = tmdbService.discoverMovies("te", "28", "popularity.desc", 1);

        assertNotNull(response);
        assertEquals(999L, response.getResults().get(0).getId());
        assertEquals("Tollywood Action Movie", response.getResults().get(0).getTitle());
        mockServer.verify();
    }

    @Test
    @DisplayName("Discover Movies with Genre Name translates to Numeric Genre ID")
    void testDiscoverMovies_WithGenreName_TranslatesToGenreId() {
        String json = """
            {
                "page": 1,
                "results": [
                    {"id": 888, "title": "Animated Film", "original_language": "en"}
                ],
                "total_pages": 1,
                "total_results": 10
            }
            """;

        mockServer.expect(requestTo("https://api.themoviedb.org/3/discover/movie?page=1&language=en-US&sort_by=popularity.desc&with_genres=16"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TmdbPageResponse<TmdbMovieResult> response = tmdbService.discoverMovies(null, "Animation", "popularity.desc", 1);

        assertNotNull(response);
        assertEquals(888L, response.getResults().get(0).getId());
        mockServer.verify();
    }

    @Test
    @DisplayName("Resolve Genre ID - Dynamically resolves standard and non-standard genre strings")
    void testResolveGenreId() {
        assertEquals(28, tmdbService.resolveGenreId("Action"));
        assertEquals(28, tmdbService.resolveGenreId("ACTION"));
        assertEquals(16, tmdbService.resolveGenreId("Animation"));
        assertEquals(16, tmdbService.resolveGenreId("animation"));
        assertEquals(14, tmdbService.resolveGenreId("Fantasy"));
        assertEquals(9648, tmdbService.resolveGenreId("Mystery"));
        assertEquals(878, tmdbService.resolveGenreId("Science Fiction"));
        assertEquals(878, tmdbService.resolveGenreId("sci-fi"));
        assertEquals(878, tmdbService.resolveGenreId("SCIFI"));
        assertEquals(28, tmdbService.resolveGenreId("28"));
        assertEquals(16, tmdbService.resolveGenreId("16"));
        assertNull(tmdbService.resolveGenreId(""));
        assertNull(tmdbService.resolveGenreId(null));
    }

    @Test
    @DisplayName("Resolve Genre Name - Resolves TMDB genre ID to name")
    void testResolveGenreName() {
        assertEquals("Action", tmdbService.resolveGenreName(28));
        assertEquals("Animation", tmdbService.resolveGenreName(16));
        assertEquals("Mystery", tmdbService.resolveGenreName(9648));
        assertNull(tmdbService.resolveGenreName(null));
    }

    @Test
    @DisplayName("Fetch Movie Genres from TMDB - Success")
    void testFetchMovieGenres_Success() {
        String json = """
            {
                "genres": [
                    {"id": 28, "name": "Action"},
                    {"id": 16, "name": "Animation"}
                ]
            }
            """;

        mockServer.expect(requestTo("https://api.themoviedb.org/3/genre/movie/list?language=en-US"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<TmdbGenre> genres = tmdbService.fetchMovieGenres();

        assertNotNull(genres);
        assertEquals(2, genres.size());
        assertEquals(28, genres.get(0).getId());
        assertEquals("Action", genres.get(0).getName());
        assertEquals(16, genres.get(1).getId());
        assertEquals("Animation", genres.get(1).getName());
        mockServer.verify();
    }

    @Test
    @DisplayName("TMDB API Failure Handling - Returns High-Fidelity Catalog Fallback Gracefully")
    void testFetchTrendingMovies_ErrorFallback() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/trending/movie/day?page=1&language=en-US"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchTrendingMovies("day", 1);

        assertNotNull(response);
        assertNotNull(response.getResults());
        assertFalse(response.getResults().isEmpty());
        assertEquals("Fight Club", response.getResults().get(0).getTitle());
        mockServer.verify();
    }

    @Test
    @DisplayName("Image URL Builders format correct CDN links")
    void testImageUrlBuilders() {
        String poster = tmdbService.buildPosterUrl("/poster.jpg", "w500");
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", poster);

        String backdrop = tmdbService.buildBackdropUrl("/backdrop.jpg", "original");
        assertEquals("https://image.tmdb.org/t/p/original/backdrop.jpg", backdrop);

        assertNull(tmdbService.buildPosterUrl(null, "w500"));
        assertNull(tmdbService.buildPosterUrl("", "w500"));
    }
}
