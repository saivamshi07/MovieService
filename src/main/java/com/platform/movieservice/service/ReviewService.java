package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.event.RecommendationRatingEvent;
import com.platform.movieservice.dto.request.CreateReviewRequest;
import com.platform.movieservice.dto.response.MovieDetailDTO;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final MovieRepository movieRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TmdbService tmdbService;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserAccessService userAccessService;

    @Value("${cache.ttl.movie-details:86400}")
    private long movieDetailsTtl = 86400L;

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
            m.setPlatformRatingAverage(avg != null ? BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            m.setPlatformRatingCount(count);
            movieRepository.save(m);
        });

        // Synchronize Redis movie details cache if present
        updateMovieRedisCache(movieId, avg, count);

        // 3. Insert Outbox Event for Kafka Streaming to recommendation-service
        createRatingOutboxEvent(userId, movieId, request.getRating(), request.getReviewText(), isUpdate);

        return toDto(savedReview);
    }

    private void updateMovieRedisCache(Long movieId, Double avg, long count) {
        if (redisTemplate == null || movieId == null) {
            return;
        }
        try {
            String cacheKey = "movie:details:" + movieId;
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof MovieDetailDTO dto) {
                dto.setPlatformRating(avg != null ? Math.round(avg * 10.0) / 10.0 : 0.0);
                dto.setPlatformRatingCount(count);
                Long expire = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
                if (expire != null && expire > 0) {
                    redisTemplate.opsForValue().set(cacheKey, dto, Duration.ofSeconds(expire));
                } else {
                    redisTemplate.opsForValue().set(cacheKey, dto, Duration.ofSeconds(movieDetailsTtl));
                }
                log.debug("Synchronized Redis movie details rating for movie {}", movieId);
            }
        } catch (Exception e) {
            log.warn("Failed to synchronize Redis movie details for movie {}: {}", movieId, e.getMessage());
        }
    }

    public Page<ReviewResponseDTO> getMovieReviews(Long movieId, int page, int size) {
        return getMovieReviews(movieId, UserContext.getUserId(), page, size, "latest");
    }

    public Page<ReviewResponseDTO> getMovieReviews(Long movieId, int page, int size, String sort) {
        return getMovieReviews(movieId, UserContext.getUserId(), page, size, sort);
    }

    public Page<ReviewResponseDTO> getMovieReviews(Long movieId, UUID viewerId, int page, int size, String sort) {
        Pageable pageable = createPageable(page, size, sort);
        Page<Review> reviewsPage = reviewRepository.findByMovieId(movieId, pageable);

        List<ReviewResponseDTO> filtered = reviewsPage.getContent().stream()
                .filter(review -> userAccessService == null || userAccessService.canAccessUser(review.getUserId(), viewerId))
                .map(this::toDto)
                .toList();

        return new PageImpl<>(filtered, pageable, reviewsPage.getTotalElements());
    }

    public Page<ReviewResponseDTO> getUserReviews(UUID userId, int page, int size) {
        return getUserReviews(userId, UserContext.getUserId(), page, size, "latest");
    }

    public Page<ReviewResponseDTO> getUserReviews(UUID userId, int page, int size, String sort) {
        return getUserReviews(userId, UserContext.getUserId(), page, size, sort);
    }

    public Page<ReviewResponseDTO> getUserReviews(UUID userId, UUID requesterId, int page, int size, String sort) {
        if (userId == null) {
            throw new BadRequestException("User ID is required");
        }
        if (userAccessService != null) {
            userAccessService.validateReviewAccess(userId, requesterId);
        }
        Pageable pageable = createPageable(page, size, sort);
        return reviewRepository.findByUserId(userId, pageable)
                .map(this::toDto);
    }

    public Page<ReviewResponseDTO> getUserReview(UUID userId, Long movieId, int page, int size, String sort) {
        return getUserReview(userId, UserContext.getUserId(), movieId, page, size, sort);
    }

    public Page<ReviewResponseDTO> getUserReview(UUID userId, UUID requesterId, Long movieId, int page, int size, String sort) {
        if (userId == null) {
            throw new BadRequestException("User ID is required");
        }
        if (movieId == null) {
            throw new BadRequestException("Movie ID is required");
        }
        if (userAccessService != null) {
            userAccessService.validateReviewAccess(userId, requesterId);
        }
        Pageable pageable = createPageable(page, size, sort);
        return reviewRepository.findByUserIdAndMovieId(userId, movieId, pageable)
                .map(this::toDto);
    }

    public Page<ReviewResponseDTO> getUserReview(UUID userId, Long movieId, int page, int size) {
        return getUserReview(userId, movieId, page, size, "latest");
    }

    public ReviewResponseDTO getUserReview(UUID userId, Long movieId) {
        return reviewRepository.findByUserIdAndMovieId(userId, movieId)
                .map(this::toDto)
                .orElse(null);
    }

    private Pageable createPageable(int page, int size, String sort) {
        int pageNumber = Math.max(0, page);
        int pageSize = Math.max(1, size);
        Sort sortOrder = resolveSort(sort);
        return PageRequest.of(pageNumber, pageSize, sortOrder);
    }

    private Sort resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }

        String normalized = sort.trim().toLowerCase();
        return switch (normalized) {
            case "top", "highest", "best" ->
                    Sort.by(Sort.Direction.DESC, "rating")
                            .and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "low", "lowest", "worst" ->
                    Sort.by(Sort.Direction.ASC, "rating")
                            .and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "latest", "newest", "recent" ->
                    Sort.by(Sort.Direction.DESC, "createdAt");
            case "oldest" ->
                    Sort.by(Sort.Direction.ASC, "createdAt");
            default -> {
                if (normalized.contains(",")) {
                    String[] parts = normalized.split(",");
                    String prop = parts[0].trim();
                    Sort.Direction dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
                            ? Sort.Direction.ASC : Sort.Direction.DESC;
                    if ("rating".equalsIgnoreCase(prop) || "createdat".equalsIgnoreCase(prop) || "updatedat".equalsIgnoreCase(prop)) {
                        yield Sort.by(dir, prop);
                    }
                }
                yield Sort.by(Sort.Direction.DESC, "createdAt");
            }
        };
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
