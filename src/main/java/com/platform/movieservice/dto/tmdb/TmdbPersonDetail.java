package com.platform.movieservice.dto.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbPersonDetail {

    private Long id;
    private String name;
    private String biography;
    private String birthday;
    private String deathday;

    @JsonProperty("place_of_birth")
    private String placeOfBirth;

    @JsonProperty("profile_path")
    private String profilePath;

    @JsonProperty("known_for_department")
    private String knownForDepartment;

    @JsonProperty("movie_credits")
    private TmdbPersonMovieCredits movieCredits;
}
