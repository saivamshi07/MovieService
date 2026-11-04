package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.platform.movieservice.dto.request.CreateReviewRequest;
import com.platform.movieservice.dto.response.MovieDetailDTO;
import com.platform.movieservice.dto.response.ReviewResponseDTO;
import com.platform.movieservice.entity.Movie;
import com.platform.movieservice.entity.OutboxEvent;
import com.platform.movieservice.entity.Review;
import com.platform.movieservice.exception.ForbiddenException;
import com.platform.movieservice.repository.MovieRepository;
import com.platform.movieservice.repository.OutboxEventRepository;
import com.platform.movieservice.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private UserAccessService userAccessService;

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
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
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
    void testSubmitReview_UpdatesRedisCacheIfPresent() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .rating(BigDecimal.valueOf(4.5))
                .reviewText("Amazing film")
                .build();

        MovieDetailDTO cachedDto = MovieDetailDTO.builder()
                .id(movieId)
                .title("Fight Club")
                .platformRating(3.0)
                .platformRatingCount(5L)
                .build();

        when(reviewRepository.findByUserIdAndMovieId(userId, movieId)).thenReturn(Optional.empty());
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review r = invocation.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(reviewRepository.calculateAverageRating(movieId)).thenReturn(4.5);
        when(reviewRepository.countByMovieId(movieId)).thenReturn(6L);
        when(movieRepository.findById(movieId)).thenReturn(Optional.of(Movie.builder().id(movieId).build()));
        when(valueOperations.get("movie:details:" + movieId)).thenReturn(cachedDto);
        when(redisTemplate.getExpire("movie:details:" + movieId, TimeUnit.SECONDS)).thenReturn(3600L);

        ReviewResponseDTO response = reviewService.submitOrUpdateReview(userId, movieId, request);

        assertNotNull(response);

        ArgumentCaptor<MovieDetailDTO> dtoCaptor = ArgumentCaptor.forClass(MovieDetailDTO.class);
        verify(valueOperations, times(1)).set(eq("movie:details:" + movieId), dtoCaptor.capture(), eq(Duration.ofSeconds(3600)));
        MovieDetailDTO updated = dtoCaptor.getValue();
        assertEquals(4.5, updated.getPlatformRating());
        assertEquals(6L, updated.getPlatformRatingCount());
    }

    @Test
    void testSubmitReview_RedisCacheMiss_DoesNotFail() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .rating(BigDecimal.valueOf(4.5))
                .reviewText("Amazing film")
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
        when(valueOperations.get("movie:details:" + movieId)).thenReturn(null);

        ReviewResponseDTO response = reviewService.submitOrUpdateReview(userId, movieId, request);

        assertNotNull(response);
        verify(valueOperations, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void testGetUserReviews_DefaultLatest() {
        Review review = Review.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .movieId(movieId)
                .rating(BigDecimal.valueOf(5.0))
                .reviewText("All-time favorite")
                .build();

        when(reviewRepository.findByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review)));

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

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findByUserId(eq(userId), pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("createdAt");
        assertNotNull(order);
        assertTrue(order.isDescending());
    }

    @Test
    void testGetMovieReviews_TopSorted() {
        when(reviewRepository.findByMovieId(eq(movieId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<ReviewResponseDTO> result = reviewService.getMovieReviews(movieId, 0, 10, "top");

        assertNotNull(result);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findByMovieId(eq(movieId), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        Sort.Order ratingOrder = pageable.getSort().getOrderFor("rating");
        assertNotNull(ratingOrder);
        assertTrue(ratingOrder.isDescending());
    }

    @Test
    void testGetMovieReviews_LowSorted() {
        when(reviewRepository.findByMovieId(eq(movieId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<ReviewResponseDTO> result = reviewService.getMovieReviews(movieId, 0, 10, "low");

        assertNotNull(result);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findByMovieId(eq(movieId), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        Sort.Order ratingOrder = pageable.getSort().getOrderFor("rating");
        assertNotNull(ratingOrder);
        assertTrue(ratingOrder.isAscending());
    }

    @Test
    void testGetMovieReviews_SkipsUnaccessedPrivateReviews() {
        UUID viewerId = UUID.randomUUID();
        UUID author1 = UUID.randomUUID();
        UUID author2 = UUID.randomUUID();
        UUID author3 = UUID.randomUUID();

        Review r1 = Review.builder().id(UUID.randomUUID()).userId(author1).movieId(movieId).rating(BigDecimal.valueOf(5.0)).build();
        Review r2 = Review.builder().id(UUID.randomUUID()).userId(author2).movieId(movieId).rating(BigDecimal.valueOf(4.0)).build();
        Review r3 = Review.builder().id(UUID.randomUUID()).userId(author3).movieId(movieId).rating(BigDecimal.valueOf(3.0)).build();

        when(reviewRepository.findByMovieId(eq(movieId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r1, r2, r3)));

        when(userAccessService.canAccessUser(author1, viewerId)).thenReturn(true);
        when(userAccessService.canAccessUser(author2, viewerId)).thenReturn(false); // Private unfollowed -> skip!
        when(userAccessService.canAccessUser(author3, viewerId)).thenReturn(true);

        Page<ReviewResponseDTO> result = reviewService.getMovieReviews(movieId, viewerId, 0, 10, "latest");

        assertNotNull(result);
        assertEquals(2, result.getContent().size());
        assertEquals(author1, result.getContent().get(0).getUserId());
        assertEquals(author3, result.getContent().get(1).getUserId());
        verify(userAccessService).canAccessUser(author2, viewerId);
    }

    @Test
    void testGetUserReviews_PrivateUser_Forbidden() {
        UUID targetUserId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        doThrow(new ForbiddenException("This user's profile is private. Follow this user to view their reviews."))
                .when(userAccessService).validateReviewAccess(targetUserId, requesterId);

        assertThrows(ForbiddenException.class, () ->
                reviewService.getUserReviews(targetUserId, requesterId, 0, 10, "latest"));
        verify(reviewRepository, never()).findByUserId(any(), any());
    }

    @Test
    void testGetUserReviews_TopSorted() {
        when(reviewRepository.findByUserId(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<ReviewResponseDTO> result = reviewService.getUserReviews(userId, 0, 10, "top");

        assertNotNull(result);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findByUserId(eq(userId), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        Sort.Order ratingOrder = pageable.getSort().getOrderFor("rating");
        assertNotNull(ratingOrder);
        assertTrue(ratingOrder.isDescending());
    }

    @Test
    void testGetUserReview_MovieSpecific_PaginatedAndSorted() {
        when(reviewRepository.findByUserIdAndMovieId(eq(userId), eq(movieId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<ReviewResponseDTO> result = reviewService.getUserReview(userId, movieId, 0, 10, "top");

        assertNotNull(result);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findByUserIdAndMovieId(eq(userId), eq(movieId), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        Sort.Order ratingOrder = pageable.getSort().getOrderFor("rating");
        assertNotNull(ratingOrder);
        assertTrue(ratingOrder.isDescending());
    }
}
