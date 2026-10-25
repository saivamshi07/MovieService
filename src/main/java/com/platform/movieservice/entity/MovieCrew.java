package com.platform.movieservice.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "movie_crew",
       indexes = {
           @Index(name = "idx_crew_movie_dept", columnList = "movie_id, department"),
           @Index(name = "idx_crew_person", columnList = "person_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovieCrew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movie_id", nullable = false)
    private Long movieId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "person_name", nullable = false)
    private String personName;

    @Column(name = "department", length = 50)
    private String department; // e.g. "Directing", "Writing", "Production"

    @Column(name = "job_designation", length = 100)
    private String jobDesignation; // e.g. "Director", "Screenplay", "Original Music Composer"

    @Column(name = "profile_path")
    private String profilePath;
}
