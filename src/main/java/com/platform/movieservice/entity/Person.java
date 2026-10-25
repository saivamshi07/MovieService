package com.platform.movieservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "persons",
       indexes = {
           @Index(name = "idx_persons_name", columnList = "name"),
           @Index(name = "idx_persons_dept", columnList = "known_for_department")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Person {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id; // TMDB Person ID

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "biography", columnDefinition = "TEXT")
    private String biography;

    @Column(name = "birthday")
    private LocalDate birthday;

    @Column(name = "deathday")
    private LocalDate deathday;

    @Column(name = "place_of_birth")
    private String placeOfBirth;

    @Column(name = "profile_path")
    private String profilePath;

    @Column(name = "known_for_department", length = 50)
    private String knownForDepartment; // e.g. "Acting", "Directing"

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
