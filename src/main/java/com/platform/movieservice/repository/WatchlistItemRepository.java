package com.platform.movieservice.repository;

import com.platform.movieservice.entity.WatchlistItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, UUID> {

    Page<WatchlistItem> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    boolean existsByUserIdAndMovieId(UUID userId, Long movieId);

    Optional<WatchlistItem> findByUserIdAndMovieId(UUID userId, Long movieId);

    void deleteByUserIdAndMovieId(UUID userId, Long movieId);
}
