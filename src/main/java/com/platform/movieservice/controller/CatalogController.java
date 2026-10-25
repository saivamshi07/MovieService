package com.platform.movieservice.controller;

import com.platform.movieservice.dto.response.ActorDetailDTO;
import com.platform.movieservice.dto.response.CrewDetailDTO;
import com.platform.movieservice.dto.response.MovieDetailDTO;
import com.platform.movieservice.dto.response.MultiSearchResponseDTO;
import com.platform.movieservice.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/{id}")
    public ResponseEntity<MovieDetailDTO> getMovieDetail(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getMovieDetail(id));
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
            @RequestParam(defaultValue = "1") int page) {
        return ResponseEntity.ok(catalogService.search(query, page));
    }
}
