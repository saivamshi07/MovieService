package com.platform.movieservice.controller;

import com.platform.movieservice.dto.request.BatchMovieRequest;
import com.platform.movieservice.dto.response.ActorDetailDTO;
import com.platform.movieservice.dto.response.CrewDetailDTO;
import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.MovieDetailDTO;
import com.platform.movieservice.dto.response.MultiSearchResponseDTO;
import com.platform.movieservice.service.CatalogService;
import com.platform.movieservice.service.HomeFeedService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;
    private final HomeFeedService homeFeedService;

    @GetMapping("/popular")
    public ResponseEntity<List<MovieCardDTO>> getTopPopularMovies(
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) Long excludeMovieId,
            @RequestParam(required = false) Long excludeId) {
        Long exclude = excludeMovieId != null ? excludeMovieId : excludeId;
        return ResponseEntity.ok(homeFeedService.getTopPopularMovies(industry, genre, exclude));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MovieDetailDTO> getMovieDetail(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getMovieDetail(id));
    }

    @PostMapping("/batch")
    public ResponseEntity<List<MovieDetailDTO>> getMoviesBatchPost(@Valid @RequestBody BatchMovieRequest request) {
        return ResponseEntity.ok(catalogService.getMoviesBatch(request.getMovieIds()));
    }

    @GetMapping("/batch")
    public ResponseEntity<List<MovieDetailDTO>> getMoviesBatchGet(@RequestParam("ids") List<Long> ids) {
        return ResponseEntity.ok(catalogService.getMoviesBatch(ids));
    }

    @PostMapping("/batch/cards")
    public ResponseEntity<List<MovieCardDTO>> getMovieCardsBatchPost(@Valid @RequestBody BatchMovieRequest request) {
        return ResponseEntity.ok(catalogService.getMovieCardsBatch(request.getMovieIds()));
    }

    @GetMapping("/batch/cards")
    public ResponseEntity<List<MovieCardDTO>> getMovieCardsBatchGet(@RequestParam("ids") List<Long> ids) {
        return ResponseEntity.ok(catalogService.getMovieCardsBatch(ids));
    }

    @GetMapping("/actors/{personId}")
    public ResponseEntity<ActorDetailDTO> getActorDetail(@PathVariable Long personId) {
        return ResponseEntity.ok(catalogService.getActorDetail(personId));
    }

    @GetMapping("/crew/{personId}")
    public ResponseEntity<CrewDetailDTO> getCrewDetail(@PathVariable Long personId) {
        return ResponseEntity.ok(catalogService.getCrewDetail(personId));
    }

    @GetMapping("/search")
    public ResponseEntity<MultiSearchResponseDTO> search(
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "ALL") String type,
            @RequestParam(defaultValue = "1") int page) {
        return ResponseEntity.ok(catalogService.search(query, type, page));
    }
}
