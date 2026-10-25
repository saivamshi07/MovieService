package com.platform.movieservice.dto.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbPersonMovieCredits {
    private List<TmdbMovieResult> cast;
    private List<TmdbCrewWorkedMovie> crew;

    @Data
    @EqualsAndHashCode(callSuper = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TmdbCrewWorkedMovie extends TmdbMovieResult {
        private String department;
        private String job;
    }
}
