package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.request.CreateReviewRequest;
import com.platform.movieservice.dto.response.ReviewResponseDTO;
import com.platform.movieservice.service.ReviewService;
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
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(reviewService.getMovieReviews(movieId, page, size));
    }

    @GetMapping("/me")
    public ResponseEntity<ReviewResponseDTO> getMyReview(@PathVariable Long movieId) {
        UUID userId = UserContext.getRequiredUserId();
        ReviewResponseDTO review = reviewService.getUserReview(userId, movieId);
        if (review == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(review);
    }
}
