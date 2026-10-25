package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.response.ReviewResponseDTO;
import com.platform.movieservice.service.ReviewService;
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
class UserReviewControllerTest {

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private UserReviewController userReviewController;

    private UUID userId;
    private ReviewResponseDTO sampleReview;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sampleReview = ReviewResponseDTO.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .movieId(550L)
                .movieTitle("Fight Club")
                .moviePosterUrl("https://image.tmdb.org/t/p/w342/poster.jpg")
                .rating(BigDecimal.valueOf(4.5))
                .reviewText("Cult classic!")
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testGetUserReviews_ByUserId() {
        Page<ReviewResponseDTO> pageResponse = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getUserReviews(userId, 0, 10)).thenReturn(pageResponse);

        ResponseEntity<Page<ReviewResponseDTO>> response = userReviewController.getUserReviews(userId, 0, 10);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getTotalElements());
        assertEquals("Fight Club", response.getBody().getContent().get(0).getMovieTitle());
        verify(reviewService, times(1)).getUserReviews(userId, 0, 10);
    }

    @Test
    void testGetMyReviews_AuthenticatedUser() {
        UserContext.setUserId(userId);
        Page<ReviewResponseDTO> pageResponse = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getUserReviews(userId, 0, 10)).thenReturn(pageResponse);

        ResponseEntity<Page<ReviewResponseDTO>> response = userReviewController.getMyReviews(0, 10);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getTotalElements());
        verify(reviewService, times(1)).getUserReviews(userId, 0, 10);
    }

    @Test
    void testGetReviewsByQuery_WithUserId() {
        Page<ReviewResponseDTO> pageResponse = new PageImpl<>(List.of(sampleReview));
        when(reviewService.getUserReviews(userId, 0, 10)).thenReturn(pageResponse);

        ResponseEntity<Page<ReviewResponseDTO>> response = userReviewController.getReviewsByQuery(userId, 0, 10);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().getTotalElements());
        verify(reviewService, times(1)).getUserReviews(userId, 0, 10);
    }
}
