package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.dto.event.RecommendationRatingEvent;
import com.platform.movieservice.dto.request.CreateReviewRequest;
import com.platform.movieservice.dto.response.ReviewResponseDTO;
import com.platform.movieservice.entity.Movie;
import com.platform.movieservice.entity.OutboxEvent;
import com.platform.movieservice.entity.Review;
import com.platform.movieservice.entity.enums.OutboxStatus;
import com.platform.movieservice.exception.BadRequestException;
import com.platform.movieservice.repository.MovieRepository;
import com.platform.movieservice.repository.OutboxEventRepository;
import com.platform.movieservice.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final MovieRepository movieRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TmdbService tmdbService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ReviewResponseDTO submitOrUpdateReview(UUID userId, Long movieId, CreateReviewRequest request) {
        if (userId == null) {
            throw new BadRequestException("User ID is required");
        }
        if (movieId == null) {
            throw new BadRequestException("Movie ID is required");
        }

        boolean isUpdate = false;
        Optional<Review> existingOpt = reviewRepository.findByUserIdAndMovieId(userId, movieId);
        Review review;

        if (existingOpt.isPresent()) {
            review = existingOpt.get();
            review.setRating(request.getRating());
            review.setReviewText(request.getReviewText());
            isUpdate = true;
        } else {
            review = Review.builder()
                    .userId(userId)
                    .movieId(movieId)
                    .rating(request.getRating())
                    .reviewText(request.getReviewText())
                    .build();
        }

        Review savedReview = reviewRepository.save(review);

        // Update platform average rating in movie entity if present
        Double avg = reviewRepository.calculateAverageRating(movieId);
        long count = reviewRepository.countByMovieId(movieId);

        movieRepository.findById(movieId).ifPresent(m -> {
            m.setPlatformRatingAverage(BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP));
            m.setPlatformRatingCount(count);
            movieRepository.save(m);
        });

        // 3. Insert Outbox Event for Kafka Streaming to recommendation-service
        createRatingOutboxEvent(userId, movieId, request.getRating(), request.getReviewText(), isUpdate);

        return toDto(savedReview);
    }

    public Page<ReviewResponseDTO> getMovieReviews(Long movieId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), Math.max(1, size));
        return reviewRepository.findByMovieIdOrderByCreatedAtDesc(movieId, pageRequest)
                .map(this::toDto);
    }

    public Page<ReviewResponseDTO> getUserReviews(UUID userId, int page, int size) {
        if (userId == null) {
            throw new BadRequestException("User ID is required");
        }
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), Math.max(1, size));
        return reviewRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest)
                .map(this::toDto);
    }

    public ReviewResponseDTO getUserReview(UUID userId, Long movieId) {
        return reviewRepository.findByUserIdAndMovieId(userId, movieId)
                .map(this::toDto)
                .orElse(null);
    }

    private void createRatingOutboxEvent(UUID userId, Long movieId, BigDecimal rating, String reviewText, boolean isUpdate) {
        try {
            RecommendationRatingEvent.RatingPayload payload = RecommendationRatingEvent.RatingPayload.builder()
                    .userId(userId)
                    .itemId(String.valueOf(movieId))
                    .category("MOVIE")
                    .rating(rating)
                    .review(reviewText)
                    .isUpdate(isUpdate)
                    .build();

            RecommendationRatingEvent event = RecommendationRatingEvent.builder()
                    .eventId(UUID.randomUUID())
                    .eventType("USER_ITEM_RATED")
                    .aggregateType("MOVIE_RATING")
                    .timestamp(Instant.now())
                    .payload(payload)
                    .build();

            String jsonPayload = objectMapper.writeValueAsString(event);

            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("MOVIE_RATING")
                    .aggregateId(userId.toString()) // Partition key for Kafka
                    .eventType("USER_ITEM_RATED")
                    .payload(jsonPayload)
                    .status(OutboxStatus.PENDING)
                    .build();

            outboxEventRepository.save(outboxEvent);
            log.debug("Outbox event created for user {} rating movie {}", userId, movieId);
        } catch (Exception e) {
            log.error("Failed to serialize rating event for outbox: {}", e.getMessage());
        }
    }

    private ReviewResponseDTO toDto(Review review) {
        String movieTitle = null;
        String posterUrl = null;

        if (movieRepository != null) {
            Optional<Movie> movieOpt = movieRepository.findById(review.getMovieId());
            if (movieOpt.isPresent()) {
                Movie movie = movieOpt.get();
                movieTitle = movie.getTitle();
                if (tmdbService != null) {
                    posterUrl = tmdbService.buildPosterUrl(movie.getPosterPath(), "w342");
                }
            }
        }

        return ReviewResponseDTO.builder()
                .id(review.getId())
                .userId(review.getUserId())
                .movieId(review.getMovieId())
                .movieTitle(movieTitle)
                .moviePosterUrl(posterUrl)
                .rating(review.getRating())
                .reviewText(review.getReviewText())
                .createdAt(review.getCreatedAt() != null ? review.getCreatedAt().toString() : null)
                .updatedAt(review.getUpdatedAt() != null ? review.getUpdatedAt().toString() : null)
                .build();
    }
}
