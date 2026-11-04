package com.platform.movieservice.repository;

import com.platform.movieservice.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByMovieId(Long movieId, Pageable pageable);

    Page<Review> findByUserId(UUID userId, Pageable pageable);

    Page<Review> findByUserIdAndMovieId(UUID userId, Long movieId, Pageable pageable);

    Page<Review> findByMovieIdOrderByCreatedAtDesc(Long movieId, Pageable pageable);

    Page<Review> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<Review> findByUserIdAndMovieId(UUID userId, Long movieId);

    boolean existsByUserIdAndMovieId(UUID userId, Long movieId);

    @Query("SELECT r.movieId FROM Review r WHERE r.userId = :userId")
    List<Long> findMovieIdsByUserId(@Param("userId") UUID userId);

    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM Review r WHERE r.movieId = :movieId")
    Double calculateAverageRating(@Param("movieId") Long movieId);

    long countByMovieId(Long movieId);
}
