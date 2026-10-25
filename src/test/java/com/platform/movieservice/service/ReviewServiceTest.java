package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.movieservice.dto.request.CreateReviewRequest;
import com.platform.movieservice.dto.response.ReviewResponseDTO;
import com.platform.movieservice.entity.Movie;
import com.platform.movieservice.entity.OutboxEvent;
import com.platform.movieservice.entity.Review;
import com.platform.movieservice.repository.MovieRepository;
import com.platform.movieservice.repository.OutboxEventRepository;
import com.platform.movieservice.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private TmdbService tmdbService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks
    private ReviewService reviewService;

    private UUID userId;
    private Long movieId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        movieId = 550L;
    }

    @Test
    void testSubmitReview_CreatesOutboxEvent() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .rating(BigDecimal.valueOf(4.5))
                .reviewText("Masterpiece cinema")
                .build();

        when(reviewRepository.findByUserIdAndMovieId(userId, movieId)).thenReturn(Optional.empty());
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review r = invocation.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(reviewRepository.calculateAverageRating(movieId)).thenReturn(4.5);
        when(reviewRepository.countByMovieId(movieId)).thenReturn(1L);
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(Movie.builder().id(movieId).build()));

        ReviewResponseDTO response = reviewService.submitOrUpdateReview(userId, movieId, request);

        assertNotNull(response);
        assertEquals(BigDecimal.valueOf(4.5), response.getRating());
        assertEquals("Masterpiece cinema", response.getReviewText());

        verify(reviewRepository, times(1)).save(any(Review.class));
        verify(movieRepository, times(1)).save(any(Movie.class));
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
    }

    @Test
    void testGetUserReviews() {
        Review review = Review.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .movieId(movieId)
                .rating(BigDecimal.valueOf(5.0))
                .reviewText("All-time favorite")
                .build();

        PageRequest pageRequest = PageRequest.of(0, 10);
        when(reviewRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(review), pageRequest, 1));

        Movie movie = Movie.builder()
                .id(movieId)
                .title("Fight Club")
                .posterPath("/poster.jpg")
                .build();
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));
        when(tmdbService.buildPosterUrl("/poster.jpg", "w342")).thenReturn("https://image.tmdb.org/t/p/w342/poster.jpg");

        Page<ReviewResponseDTO> userReviews = reviewService.getUserReviews(userId, 0, 10);

        assertNotNull(userReviews);
        assertEquals(1, userReviews.getTotalElements());
        ReviewResponseDTO dto = userReviews.getContent().get(0);
        assertEquals(movieId, dto.getMovieId());
        assertEquals("Fight Club", dto.getMovieTitle());
        assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg", dto.getMoviePosterUrl());
        assertEquals(BigDecimal.valueOf(5.0), dto.getRating());
    }
}
