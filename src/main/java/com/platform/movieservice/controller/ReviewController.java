package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.request.CreateReviewRequest;
import com.platform.movieservice.dto.response.ReviewResponseDTO;
import com.platform.movieservice.service.ReviewService;
import com.platform.movieservice.service.UserAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/movies/{movieId}/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final UserAccessService userAccessService;

    @PostMapping
    public ResponseEntity<ReviewResponseDTO> submitReview(
            @PathVariable Long movieId,
            @Valid @RequestBody CreateReviewRequest request) {
        UUID userId = UserContext.getRequiredUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewService.submitOrUpdateReview(userId, movieId, request));
    }

    @GetMapping
    public ResponseEntity<Page<ReviewResponseDTO>> getMovieReviews(
            @PathVariable Long movieId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String sortBy) {
        UUID viewerId = UserContext.getUserId();
        String effectiveSort = (sort != null && !sort.isBlank()) ? sort : sortBy;
        return ResponseEntity.ok(reviewService.getMovieReviews(movieId, viewerId, page, size, effectiveSort));
    }

    public ResponseEntity<Page<ReviewResponseDTO>> getMovieReviews(Long movieId, int page, int size) {
        return getMovieReviews(movieId, page, size, null, null);
    }

    @GetMapping("/me")
    public ResponseEntity<Page<ReviewResponseDTO>> getMyReview(
            @PathVariable Long movieId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String sortBy) {
        UUID userId = UserContext.getRequiredUserId();
        String effectiveSort = (sort != null && !sort.isBlank()) ? sort : sortBy;
        return ResponseEntity.ok(reviewService.getUserReview(userId, userId, movieId, page, size, effectiveSort));
    }

    public ResponseEntity<Page<ReviewResponseDTO>> getMyReview(Long movieId, int page, int size) {
        return getMyReview(movieId, page, size, null, null);
    }

    public ResponseEntity<Page<ReviewResponseDTO>> getMyReview(Long movieId) {
        return getMyReview(movieId, 0, 10, null, null);
    }

    @GetMapping({"/users/{userId}", "/user/{userId}"})
    public ResponseEntity<Page<ReviewResponseDTO>> getUserReview(
            @PathVariable Long movieId,
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
        return ResponseEntity.ok(reviewService.getUserReview(userId, requesterId, movieId, page, size, effectiveSort));
    }
}
