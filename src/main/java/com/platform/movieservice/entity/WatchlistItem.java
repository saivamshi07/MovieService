package com.platform.movieservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "watchlist_items",
       uniqueConstraints = @UniqueConstraint(name = "uq_user_movie_watchlist", columnNames = {"user_id", "movie_id"}),
       indexes = {
           @Index(name = "idx_watchlist_user_created", columnList = "user_id, created_at DESC"),
           @Index(name = "idx_watchlist_movie", columnList = "movie_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "movie_id", nullable = false, updatable = false)
    private Long movieId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
