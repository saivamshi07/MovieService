package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.service.RecommendationClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/movies/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationClientService recommendationClientService;

    @GetMapping
    public ResponseEntity<List<MovieCardDTO>> getPersonalizedRecommendations() {
        UUID userId = UserContext.getUserId();
        return ResponseEntity.ok(recommendationClientService.getPersonalizedRecommendations(userId));
    }
}
