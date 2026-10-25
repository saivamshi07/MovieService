package com.platform.movieservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrewDetailDTO implements Serializable {

    private Long id;
    private String name;
    private String biography;
    private String birthday;
    private String deathday;
    private String knownForDepartment;
    private String profileUrl;
    private List<String> primaryDesignations;
    private List<CrewWorkedFilmDTO> filmsWorked;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrewWorkedFilmDTO implements Serializable {
        private Long id;
        private String title;
        private String department;
        private String job;
        private String releaseDate;
        private Integer releaseYear;
        private String posterUrl;
        private Double tmdbVoteAverage;
    }
}
