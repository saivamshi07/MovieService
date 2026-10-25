package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.config.TmdbConfig;
import com.platform.movieservice.dto.tmdb.TmdbMovieDetail;
import com.platform.movieservice.dto.tmdb.TmdbMovieResult;
import com.platform.movieservice.dto.tmdb.TmdbPageResponse;
import com.platform.movieservice.dto.tmdb.TmdbPersonDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live external test making REAL outbound HTTP calls directly to TMDB's production servers.
 * Verifies live network connectivity, SSL negotiation, bearer token authentication,
 * and deserialization of TMDB live responses.
 */
class TmdbExternalLiveTest {

    private TmdbService tmdbService;

    @BeforeEach
    void setUp() {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIyM2FmZDExMWYxMjRlNTE0OWE5MzZiY2FkOGZmOTYyYSIsIm5iZiI6MTc2NDc5NDMxNi4wNiwic3ViIjoiNjkzMDlmY2NjMDQ4Y2UzNTRmNjMxZDkzIiwic2NvcGVzIjpbImFwaV9yZWFkIl0sInZlcnNpb24iOjF9.PIuhus3qmnqTipyB8CBj0f53ziNF2OvfC604U43kRow";

        TmdbConfig config = new TmdbConfig();
        ReflectionTestUtils.setField(config, "baseUrl", "https://api.themoviedb.org/3");
        ReflectionTestUtils.setField(config, "bearerToken", token);
        ReflectionTestUtils.setField(config, "connectTimeoutMs", 5000);
        ReflectionTestUtils.setField(config, "readTimeoutMs", 8000);

        RestClient client = config.tmdbRestClient();
        tmdbService = new TmdbService(client, config, new ObjectMapper());
        ReflectionTestUtils.setField(tmdbService, "imageBaseUrl", "https://image.tmdb.org/t/p");
        ReflectionTestUtils.setField(tmdbService, "defaultLanguage", "en-US");
    }

    @Test
    @DisplayName("Live TMDB Call: Trending Movies (Day)")
    void testLiveTrendingMoviesDay() {
        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchTrendingMovies("day", 1);

        assertNotNull(response);
        assertNotNull(response.getResults());
        assertFalse(response.getResults().isEmpty(), "Expected live trending movies from TMDB");

        TmdbMovieResult first = response.getResults().get(0);
        System.out.println("Live TMDB Trending Today #1: " + first.getTitle() + " (ID: " + first.getId() + ", Rating: " + first.getVoteAverage() + ")");
        assertNotNull(first.getTitle());
        assertNotNull(first.getId());
    }

    @Test
    @DisplayName("Live TMDB Call: Popular Movies")
    void testLivePopularMovies() {
        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchPopularMovies(1);

        assertNotNull(response);
        assertFalse(response.getResults().isEmpty());
        System.out.println("Live TMDB Popular #1: " + response.getResults().get(0).getTitle());
    }

    @Test
    @DisplayName("Live TMDB Call: Top Rated Movies")
    void testLiveTopRatedMovies() {
        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchTopRatedMovies(1);

        assertNotNull(response);
        assertFalse(response.getResults().isEmpty());
        System.out.println("Live TMDB Top Rated #1: " + response.getResults().get(0).getTitle());
    }

    @Test
    @DisplayName("Live TMDB Call: Now Playing Movies")
    void testLiveNowPlayingMovies() {
        TmdbPageResponse<TmdbMovieResult> response = tmdbService.fetchNowPlayingMovies(1);

        assertNotNull(response);
        assertFalse(response.getResults().isEmpty());
        System.out.println("Live TMDB Now Playing #1: " + response.getResults().get(0).getTitle());
    }

    @Test
    @DisplayName("Live TMDB Call: Discover Tollywood Action Movies")
    void testLiveDiscoverTollywood() {
        TmdbPageResponse<TmdbMovieResult> response = tmdbService.discoverMovies("te", "28", "popularity.desc", 1);

        assertNotNull(response);
        assertFalse(response.getResults().isEmpty());
        System.out.println("Live TMDB Tollywood Action #1: " + response.getResults().get(0).getTitle() + " [lang=" + response.getResults().get(0).getOriginalLanguage() + "]");
        assertEquals("te", response.getResults().get(0).getOriginalLanguage());
    }

    @Test
    @DisplayName("Live TMDB Call: Movie Detail (Fight Club - 550)")
    void testLiveMovieDetail() {
        TmdbMovieDetail detail = tmdbService.fetchMovieDetail(550L);

        assertNotNull(detail);
        assertEquals("Fight Club", detail.getTitle());
        System.out.println("Live TMDB Detail: " + detail.getTitle() + " - Tagline: " + detail.getTagline() + " - Runtime: " + detail.getRuntime() + " mins");
        assertNotNull(detail.getCredits());
        assertFalse(detail.getCredits().getCast().isEmpty());
        System.out.println("Live TMDB Fight Club Lead Actor: " + detail.getCredits().getCast().get(0).getName());
    }

    @Test
    @DisplayName("Live TMDB Call: Person Detail (Brad Pitt - 287)")
    void testLivePersonDetail() {
        TmdbPersonDetail person = tmdbService.fetchPersonDetail(287L);

        assertNotNull(person);
        assertEquals("Brad Pitt", person.getName());
        System.out.println("Live TMDB Person: " + person.getName() + " - Born: " + person.getBirthday() + " in " + person.getPlaceOfBirth());
    }
}
