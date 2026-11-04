package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.response.ReviewResponseDTO;
import com.platform.movieservice.service.ReviewService;
import com.platform.movieservice.service.UserAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserReviewController {

    private final ReviewService reviewService;
    private final UserAccessService userAccessService;

    /**
     * Get reviews written by the currently authenticated user.
     * Endpoints:
     * - GET /api/v1/movies/reviews/me
     * - GET /api/v1/movies/users/me/reviews
     */
    @GetMapping({"/api/v1/movies/reviews/me", "/api/v1/movies/users/me/reviews"})
    public ResponseEntity<Page<ReviewResponseDTO>> getMyReviews(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String sortBy) {
        UUID userId = UserContext.getRequiredUserId();
        String effectiveSort = (sort != null && !sort.isBlank()) ? sort : sortBy;
        return ResponseEntity.ok(reviewService.getUserReviews(userId, userId, page, size, effectiveSort));
    }

    public ResponseEntity<Page<ReviewResponseDTO>> getMyReviews(int page, int size) {
        return getMyReviews(page, size, null, null);
    }

    /**
     * Get reviews written by a specific user by their UUID with privacy control.
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
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String sortBy) {
        UUID requesterId = UserContext.getUserId();
        if (userAccessService != null) {
            userAccessService.validateReviewAccess(userId, requesterId);
        }
        String effectiveSort = (sort != null && !sort.isBlank()) ? sort : sortBy;
        return ResponseEntity.ok(reviewService.getUserReviews(userId, requesterId, page, size, effectiveSort));
    }

    public ResponseEntity<Page<ReviewResponseDTO>> getUserReviews(UUID userId, int page, int size) {
        return getUserReviews(userId, page, size, null, null);
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
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String sortBy) {
        UUID requesterId = UserContext.getUserId();
        UUID targetUserId = (userId != null) ? userId : UserContext.getRequiredUserId();
        if (userAccessService != null) {
            userAccessService.validateReviewAccess(targetUserId, requesterId);
        }
        String effectiveSort = (sort != null && !sort.isBlank()) ? sort : sortBy;
        return ResponseEntity.ok(reviewService.getUserReviews(targetUserId, requesterId, page, size, effectiveSort));
    }

    public ResponseEntity<Page<ReviewResponseDTO>> getReviewsByQuery(UUID userId, int page, int size) {
        return getReviewsByQuery(userId, page, size, null, null);
    }
}
