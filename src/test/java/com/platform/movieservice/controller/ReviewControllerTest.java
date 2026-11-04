package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.request.CreateReviewRequest;
import com.platform.movieservice.dto.response.ReviewResponseDTO;
import com.platform.movieservice.exception.ForbiddenException;
import com.platform.movieservice.service.ReviewService;
import com.platform.movieservice.service.UserAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewControllerTest {

    @Mock
    private ReviewService reviewService;

    @Mock
    private UserAccessService userAccessService;

    @InjectMocks
    private ReviewController reviewController;

    private Long movieId;
    private UUID userId;
    private ReviewResponseDTO sampleReview;

    @BeforeEach
    void setUp() {
        movieId = 550L;
        userId = UUID.randomUUID();
        sampleReview = ReviewResponseDTO.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .movieId(movieId)
                .rating(BigDecimal.valueOf(5.0))
                .reviewText("Incredible movie")
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testGetMovieReviews_DefaultPagination() {
        Page<ReviewResponseDTO> page = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getMovieReviews(movieId, null, 0, 10, null)).thenReturn(page);

        ResponseEntity<Page<ReviewResponseDTO>> response = reviewController.getMovieReviews(movieId, 0, 10);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getTotalElements());
        verify(reviewService).getMovieReviews(movieId, null, 0, 10, null);
    }

    @Test
    void testGetMovieReviews_WithSortTop() {
        Page<ReviewResponseDTO> page = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getMovieReviews(movieId, null, 0, 10, "top")).thenReturn(page);

        ResponseEntity<Page<ReviewResponseDTO>> response = reviewController.getMovieReviews(movieId, 0, 10, "top", null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(reviewService).getMovieReviews(movieId, null, 0, 10, "top");
    }

    @Test
    void testGetMovieReviews_WithSortLow() {
        Page<ReviewResponseDTO> page = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getMovieReviews(movieId, null, 1, 20, "low")).thenReturn(page);

        ResponseEntity<Page<ReviewResponseDTO>> response = reviewController.getMovieReviews(movieId, 1, 20, "low", null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(reviewService).getMovieReviews(movieId, null, 1, 20, "low");
    }

    @Test
    void testGetMovieReviews_WithSortByParam() {
        Page<ReviewResponseDTO> page = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getMovieReviews(movieId, null, 0, 10, "latest")).thenReturn(page);

        ResponseEntity<Page<ReviewResponseDTO>> response = reviewController.getMovieReviews(movieId, 0, 10, null, "latest");

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(reviewService).getMovieReviews(movieId, null, 0, 10, "latest");
    }

    @Test
    void testGetMyReview_Authenticated_Default() {
        UserContext.setUserId(userId);
        Page<ReviewResponseDTO> page = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getUserReview(userId, userId, movieId, 0, 10, null)).thenReturn(page);

        ResponseEntity<Page<ReviewResponseDTO>> response = reviewController.getMyReview(movieId);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getTotalElements());
        assertEquals(sampleReview, response.getBody().getContent().get(0));
    }

    @Test
    void testGetMyReview_WithSortTop() {
        UserContext.setUserId(userId);
        Page<ReviewResponseDTO> page = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getUserReview(userId, userId, movieId, 0, 10, "top")).thenReturn(page);

        ResponseEntity<Page<ReviewResponseDTO>> response = reviewController.getMyReview(movieId, 0, 10, "top", null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        verify(reviewService).getUserReview(userId, userId, movieId, 0, 10, "top");
    }

    @Test
    void testGetMyReview_WithSortLow() {
        UserContext.setUserId(userId);
        Page<ReviewResponseDTO> page = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getUserReview(userId, userId, movieId, 0, 10, "low")).thenReturn(page);

        ResponseEntity<Page<ReviewResponseDTO>> response = reviewController.getMyReview(movieId, 0, 10, "low", null);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        verify(reviewService).getUserReview(userId, userId, movieId, 0, 10, "low");
    }

    @Test
    void testGetUserReview_PrivateUser_Forbidden() {
        UUID targetUserId = UUID.randomUUID();
        doThrow(new ForbiddenException("This user's profile is private. Follow this user to view their reviews."))
                .when(userAccessService).validateReviewAccess(targetUserId, null);

        assertThrows(ForbiddenException.class, () ->
                reviewController.getUserReview(movieId, targetUserId, 0, 10, null, null));
        verify(reviewService, never()).getUserReview(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void testGetUserReview_Accessible_Success() {
        UUID targetUserId = UUID.randomUUID();
        doNothing().when(userAccessService).validateReviewAccess(targetUserId, null);
        Page<ReviewResponseDTO> page = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getUserReview(targetUserId, null, movieId, 0, 10, null)).thenReturn(page);

        ResponseEntity<Page<ReviewResponseDTO>> response = reviewController.getUserReview(movieId, targetUserId, 0, 10, null, null);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(userAccessService).validateReviewAccess(targetUserId, null);
        verify(reviewService).getUserReview(targetUserId, null, movieId, 0, 10, null);
    }

    @Test
    void testSubmitReview() {
        UserContext.setUserId(userId);
        CreateReviewRequest request = CreateReviewRequest.builder()
                .rating(BigDecimal.valueOf(4.0))
                .reviewText("Great")
                .build();
        when(reviewService.submitOrUpdateReview(userId, movieId, request)).thenReturn(sampleReview);

        ResponseEntity<ReviewResponseDTO> response = reviewController.submitReview(movieId, request);

        assertEquals(201, response.getStatusCode().value());
        assertEquals(sampleReview, response.getBody());
    }
}
