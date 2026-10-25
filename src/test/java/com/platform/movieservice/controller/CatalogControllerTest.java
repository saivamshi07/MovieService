package com.platform.movieservice.controller;

import com.platform.movieservice.dto.response.ActorDetailDTO;
import com.platform.movieservice.dto.response.CrewDetailDTO;
import com.platform.movieservice.dto.response.MovieDetailDTO;
import com.platform.movieservice.dto.response.MultiSearchResponseDTO;
import com.platform.movieservice.service.CatalogService;
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

    @InjectMocks
    private CatalogController catalogController;

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
    @DisplayName("GET /api/v1/movies/search returns MultiSearchResponseDTO")
    void testSearch() {
        String query = "nolan";
        MultiSearchResponseDTO mockSearch = MultiSearchResponseDTO.builder()
                .query(query)
                .totalMatches(1)
                .movies(List.of())
                .actors(List.of())
                .crew(List.of())
                .build();

        when(catalogService.search(query, 1)).thenReturn(mockSearch);

        ResponseEntity<MultiSearchResponseDTO> response = catalogController.search(query, 1);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(query, response.getBody().getQuery());
        verify(catalogService, times(1)).search(query, 1);
    }
}
