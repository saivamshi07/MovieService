package com.platform.movieservice.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reviews",
       uniqueConstraints = @UniqueConstraint(name = "uq_user_movie_review", columnNames = {"user_id", "movie_id"}),
       indexes = {
           @Index(name = "idx_reviews_movie_created", columnList = "movie_id, created_at DESC"),
           @Index(name = "idx_reviews_user", columnList = "user_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "movie_id", nullable = false, updatable = false)
    private Long movieId;

    @DecimalMin("1.0")
    @DecimalMax("5.0")
    @Column(name = "rating", precision = 3, scale = 1, nullable = false)
    private BigDecimal rating;

    @Column(name = "review_text", columnDefinition = "TEXT")
    private String reviewText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
