package com.platform.movieservice.controller;

import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.SectionResponseDTO;
import com.platform.movieservice.service.HomeFeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeFeedControllerTest {

    @Mock
    private HomeFeedService homeFeedService;

    @InjectMocks
    private HomeFeedController homeFeedController;

    private SectionResponseDTO sampleSection;

    @BeforeEach
    void setUp() {
        MovieCardDTO card = MovieCardDTO.builder()
                .id(100L)
                .title("Interstellar")
                .posterUrl("https://image.tmdb.org/t/p/w500/interstellar.jpg")
                .industry("HOLLYWOOD")
                .genres(List.of("Science Fiction", "Drama"))
                .build();

        sampleSection = SectionResponseDTO.builder()
                .section("TEST_SECTION")
                .title("Test Section Title")
                .page(1)
                .totalPages(1)
                .totalResults(1)
                .movies(List.of(card))
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/movies/home/trending-today calls HomeFeedService correctly")
    void testGetTrendingToday() {
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, 1, "TOLLYWOOD", null))
                .thenReturn(sampleSection);

        ResponseEntity<SectionResponseDTO> response = homeFeedController.getTrendingToday(1, "TOLLYWOOD", null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Test Section Title", response.getBody().getTitle());
        verify(homeFeedService, times(1)).getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, 1, "TOLLYWOOD", null);
    }

    @Test
    @DisplayName("GET /api/v1/movies/home/trending-week calls HomeFeedService correctly")
    void testGetTrendingWeek() {
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_WEEK, 2, null, null))
                .thenReturn(sampleSection);

        ResponseEntity<SectionResponseDTO> response = homeFeedController.getTrendingWeek(2, null, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(homeFeedService, times(1)).getSectionFeed(HomeFeedService.SECTION_TRENDING_WEEK, 2, null, null);
    }

    @Test
    @DisplayName("GET /api/v1/movies/home/popular calls HomeFeedService correctly")
    void testGetPopular() {
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_POPULAR, 1, null, "ACTION", 550L))
                .thenReturn(sampleSection);

        ResponseEntity<SectionResponseDTO> response = homeFeedController.getPopular(1, null, "ACTION", 550L, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(homeFeedService, times(1)).getSectionFeed(HomeFeedService.SECTION_POPULAR, 1, null, "ACTION", 550L);
    }

    @Test
    @DisplayName("GET /api/v1/movies/home/popular/top calls HomeFeedService correctly")
    void testGetTopPopular() {
        MovieCardDTO card = MovieCardDTO.builder().id(100L).title("Interstellar").build();
        when(homeFeedService.getTopPopularMovies("HOLLYWOOD", "ACTION", 550L))
                .thenReturn(List.of(card));

        ResponseEntity<List<MovieCardDTO>> response = homeFeedController.getTopPopular("HOLLYWOOD", "ACTION", 550L, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(100L, response.getBody().get(0).getId());
        verify(homeFeedService, times(1)).getTopPopularMovies("HOLLYWOOD", "ACTION", 550L);
    }

    @Test
    @DisplayName("GET /api/v1/movies/home/top-rated calls HomeFeedService correctly")
    void testGetTopRated() {
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1, null, null))
                .thenReturn(sampleSection);

        ResponseEntity<SectionResponseDTO> response = homeFeedController.getTopRated(1, null, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(homeFeedService, times(1)).getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1, null, null);
    }

    @Test
    @DisplayName("GET /api/v1/movies/home/now-playing calls HomeFeedService correctly")
    void testGetNowPlaying() {
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_NOW_PLAYING, 1, null, null))
                .thenReturn(sampleSection);

        ResponseEntity<SectionResponseDTO> response = homeFeedController.getNowPlaying(1, null, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(homeFeedService, times(1)).getSectionFeed(HomeFeedService.SECTION_NOW_PLAYING, 1, null, null);
    }

    @Test
    @DisplayName("GET /api/v1/movies/home aggregates all 5 sections into a unified map")
    void testGetUnifiedHomeFeed() {
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_TODAY, 1)).thenReturn(sampleSection);
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_TRENDING_WEEK, 1)).thenReturn(sampleSection);
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_POPULAR, 1)).thenReturn(sampleSection);
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_TOP_RATED, 1)).thenReturn(sampleSection);
        when(homeFeedService.getSectionFeed(HomeFeedService.SECTION_NOW_PLAYING, 1)).thenReturn(sampleSection);

        ResponseEntity<Map<String, Object>> response = homeFeedController.getUnifiedHomeFeed();

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("trendingToday"));
        assertTrue(body.containsKey("trendingWeek"));
        assertTrue(body.containsKey("popular"));
        assertTrue(body.containsKey("topRated"));
        assertTrue(body.containsKey("nowPlaying"));

        verify(homeFeedService, times(5)).getSectionFeed(anyString(), eq(1));
    }
}
