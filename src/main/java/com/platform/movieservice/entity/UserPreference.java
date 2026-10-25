package com.platform.movieservice.entity;

import com.platform.movieservice.entity.enums.CinemaIndustry;
import com.platform.movieservice.entity.enums.MovieGenre;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "user_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPreference {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_preferred_industries", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "industry")
    @Builder.Default
    private Set<CinemaIndustry> preferredIndustries = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_preferred_genres", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "genre")
    @Builder.Default
    private Set<MovieGenre> preferredGenres = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
