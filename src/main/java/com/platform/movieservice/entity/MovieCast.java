package com.platform.movieservice.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "movie_cast",
       indexes = {
           @Index(name = "idx_cast_movie_order", columnList = "movie_id, cast_order"),
           @Index(name = "idx_cast_person", columnList = "person_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovieCast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movie_id", nullable = false)
    private Long movieId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "person_name", nullable = false)
    private String personName;

    @Column(name = "character_name")
    private String characterName;

    @Column(name = "profile_path")
    private String profilePath;

    @Column(name = "cast_order")
    private Integer castOrder;
}
