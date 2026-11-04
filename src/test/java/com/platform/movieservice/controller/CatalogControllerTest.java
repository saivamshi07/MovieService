package com.platform.movieservice.controller;

import com.platform.movieservice.dto.request.BatchMovieRequest;
import com.platform.movieservice.dto.response.ActorDetailDTO;
import com.platform.movieservice.dto.response.CrewDetailDTO;
import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.MovieDetailDTO;
import com.platform.movieservice.dto.response.MultiSearchResponseDTO;
import com.platform.movieservice.service.CatalogService;
import com.platform.movieservice.service.HomeFeedService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {

    @Mock
    private CatalogService catalogService;

    @Mock
    private HomeFeedService homeFeedService;

    @InjectMocks
    private CatalogController catalogController;

    @Test
    @DisplayName("GET /api/v1/movies/popular returns top popular MovieCardDTO list")
    void testGetTopPopularMovies() {
        MovieCardDTO card = MovieCardDTO.builder().id(101L).title("RRR").industry("TOLLYWOOD").build();
        when(homeFeedService.getTopPopularMovies("TOLLYWOOD", "ACTION", 550L)).thenReturn(List.of(card));

        ResponseEntity<List<MovieCardDTO>> response = catalogController.getTopPopularMovies("TOLLYWOOD", "ACTION", 550L, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals(101L, response.getBody().get(0).getId());
        verify(homeFeedService, times(1)).getTopPopularMovies("TOLLYWOOD", "ACTION", 550L);
    }

    @Test
    @DisplayName("GET /api/v1/movies/{id} returns MovieDetailDTO")
    void testGetMovieDetail() {
        Long movieId = 550L;
        MovieDetailDTO mockMovie = MovieDetailDTO.builder()
                .id(movieId)
                .title("Fight Club")
                .runtime(139)
                .build();

        when(catalogService.getMovieDetail(movieId)).thenReturn(mockMovie);

        ResponseEntity<MovieDetailDTO> response = catalogController.getMovieDetail(movieId);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Fight Club", response.getBody().getTitle());
        verify(catalogService, times(1)).getMovieDetail(movieId);
    }

    @Test
    @DisplayName("POST /api/v1/movies/batch returns list of MovieDetailDTO")
    void testGetMoviesBatchPost() {
        List<Long> ids = List.of(550L, 157336L);
        BatchMovieRequest request = new BatchMovieRequest(ids);
        List<MovieDetailDTO> mockDetails = List.of(
                MovieDetailDTO.builder().id(550L).title("Fight Club").build(),
                MovieDetailDTO.builder().id(157336L).title("Interstellar").build()
        );

        when(catalogService.getMoviesBatch(ids)).thenReturn(mockDetails);

        ResponseEntity<List<MovieDetailDTO>> response = catalogController.getMoviesBatchPost(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().size());
        verify(catalogService, times(1)).getMoviesBatch(ids);
    }

    @Test
    @DisplayName("GET /api/v1/movies/batch?ids=... returns list of MovieDetailDTO")
    void testGetMoviesBatchGet() {
        List<Long> ids = List.of(550L);
        List<MovieDetailDTO> mockDetails = List.of(
                MovieDetailDTO.builder().id(550L).title("Fight Club").build()
        );

        when(catalogService.getMoviesBatch(ids)).thenReturn(mockDetails);

        ResponseEntity<List<MovieDetailDTO>> response = catalogController.getMoviesBatchGet(ids);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        verify(catalogService, times(1)).getMoviesBatch(ids);
    }

    @Test
    @DisplayName("POST /api/v1/movies/batch/cards returns list of MovieCardDTO")
    void testGetMovieCardsBatchPost() {
        List<Long> ids = List.of(550L);
        BatchMovieRequest request = new BatchMovieRequest(ids);
        List<MovieCardDTO> mockCards = List.of(
                MovieCardDTO.builder().id(550L).title("Fight Club").build()
        );

        when(catalogService.getMovieCardsBatch(ids)).thenReturn(mockCards);

        ResponseEntity<List<MovieCardDTO>> response = catalogController.getMovieCardsBatchPost(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        verify(catalogService, times(1)).getMovieCardsBatch(ids);
    }

    @Test
    @DisplayName("GET /api/v1/movies/batch/cards?ids=... returns list of MovieCardDTO")
    void testGetMovieCardsBatchGet() {
        List<Long> ids = List.of(550L);
        List<MovieCardDTO> mockCards = List.of(
                MovieCardDTO.builder().id(550L).title("Fight Club").build()
        );

        when(catalogService.getMovieCardsBatch(ids)).thenReturn(mockCards);

        ResponseEntity<List<MovieCardDTO>> response = catalogController.getMovieCardsBatchGet(ids);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        verify(catalogService, times(1)).getMovieCardsBatch(ids);
    }

    @Test
    @DisplayName("GET /api/v1/movies/actors/{personId} returns ActorDetailDTO")
    void testGetActorDetail() {
        Long personId = 287L;
        ActorDetailDTO mockActor = ActorDetailDTO.builder()
                .id(personId)
                .name("Brad Pitt")
                .age(60)
                .build();

        when(catalogService.getActorDetail(personId)).thenReturn(mockActor);

        ResponseEntity<ActorDetailDTO> response = catalogController.getActorDetail(personId);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Brad Pitt", response.getBody().getName());
        verify(catalogService, times(1)).getActorDetail(personId);
    }

    @Test
    @DisplayName("GET /api/v1/movies/crew/{personId} returns CrewDetailDTO")
    void testGetCrewDetail() {
        Long personId = 525L;
        CrewDetailDTO mockCrew = CrewDetailDTO.builder()
                .id(personId)
                .name("Christopher Nolan")
                .knownForDepartment("Directing")
                .build();

        when(catalogService.getCrewDetail(personId)).thenReturn(mockCrew);

        ResponseEntity<CrewDetailDTO> response = catalogController.getCrewDetail(personId);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Christopher Nolan", response.getBody().getName());
        verify(catalogService, times(1)).getCrewDetail(personId);
    }

    @Test
    @DisplayName("GET /api/v1/movies/search returns MultiSearchResponseDTO with default ALL")
    void testSearch_DefaultAll() {
        String query = "nolan";
        MultiSearchResponseDTO mockSearch = MultiSearchResponseDTO.builder()
                .query(query)
                .totalMatches(1)
                .movies(List.of())
                .actors(List.of())
                .crew(List.of())
                .build();

        when(catalogService.search(query, "ALL", 1)).thenReturn(mockSearch);

        ResponseEntity<MultiSearchResponseDTO> response = catalogController.search(query, "ALL", 1);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(query, response.getBody().getQuery());
        verify(catalogService, times(1)).search(query, "ALL", 1);
    }

    @Test
    @DisplayName("GET /api/v1/movies/search with type=MOVIE")
    void testSearch_MovieType() {
        String query = "fight";
        MultiSearchResponseDTO mockSearch = MultiSearchResponseDTO.builder()
                .query(query)
                .totalMatches(1)
                .movies(List.of())
                .actors(List.of())
                .crew(List.of())
                .build();

        when(catalogService.search(query, "MOVIE", 1)).thenReturn(mockSearch);

        ResponseEntity<MultiSearchResponseDTO> response = catalogController.search(query, "MOVIE", 1);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(catalogService, times(1)).search(query, "MOVIE", 1);
    }
}
