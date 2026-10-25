package com.platform.movieservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "movies",
       indexes = {
           @Index(name = "idx_movies_title", columnList = "title"),
           @Index(name = "idx_movies_original_language", columnList = "original_language"),
           @Index(name = "idx_movies_platform_rating", columnList = "platform_rating_average DESC")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Movie {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id; // TMDB Movie ID

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "original_title")
    private String originalTitle;

    @Column(name = "overview", columnDefinition = "TEXT")
    private String overview;

    @Column(name = "tagline")
    private String tagline;

    @Column(name = "poster_path")
    private String posterPath;

    @Column(name = "backdrop_path")
    private String backdropPath;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "runtime")
    private Integer runtime;

    @Column(name = "original_language", length = 10)
    private String originalLanguage;

    @Column(name = "tmdb_vote_average", precision = 4, scale = 2)
    private BigDecimal tmdbVoteAverage;

    @Column(name = "tmdb_vote_count")
    private Long tmdbVoteCount;

    @Column(name = "platform_rating_average", precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal platformRatingAverage = BigDecimal.ZERO;

    @Column(name = "platform_rating_count")
    @Builder.Default
    private Long platformRatingCount = 0L;

    @Column(name = "genres_json", columnDefinition = "TEXT")
    private String genresJson; // e.g. [{"id": 28, "name": "Action"}]

    @Column(name = "status", length = 30)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
