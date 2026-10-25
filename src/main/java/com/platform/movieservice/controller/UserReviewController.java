package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.response.ReviewResponseDTO;
import com.platform.movieservice.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserReviewController {

    private final ReviewService reviewService;

    /**
     * Get reviews written by the currently authenticated user.
     * Endpoints:
     * - GET /api/v1/movies/reviews/me
     * - GET /api/v1/movies/users/me/reviews
     */
    @GetMapping({"/api/v1/movies/reviews/me", "/api/v1/movies/users/me/reviews"})
    public ResponseEntity<Page<ReviewResponseDTO>> getMyReviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UUID userId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(reviewService.getUserReviews(userId, page, size));
    }

    /**
     * Get reviews written by a specific user by their UUID.
     * Endpoints:
     * - GET /api/v1/movies/reviews/users/{userId}
     * - GET /api/v1/movies/users/{userId}/reviews
     * - GET /api/v1/movies/reviews/user/{userId}
     */
    @GetMapping({
            "/api/v1/movies/reviews/users/{userId}",
            "/api/v1/movies/users/{userId}/reviews",
            "/api/v1/movies/reviews/user/{userId}"
    })
    public ResponseEntity<Page<ReviewResponseDTO>> getUserReviews(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(reviewService.getUserReviews(userId, page, size));
    }

    /**
     * Query reviews by optional userId query param, or fallback to current authenticated user.
     * Endpoint:
     * - GET /api/v1/movies/reviews?userId={userId}&page=0&size=10
     */
    @GetMapping("/api/v1/movies/reviews")
    public ResponseEntity<Page<ReviewResponseDTO>> getReviewsByQuery(
            @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UUID targetUserId = (userId != null) ? userId : UserContext.getRequiredUserId();
        return ResponseEntity.ok(reviewService.getUserReviews(targetUserId, page, size));
    }
}
