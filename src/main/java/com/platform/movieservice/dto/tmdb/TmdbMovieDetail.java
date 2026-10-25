package com.platform.movieservice.dto.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbMovieDetail {

    private Long id;
    private String title;

    @JsonProperty("original_title")
    private String originalTitle;

    private String overview;
    private String tagline;

    @JsonProperty("poster_path")
    private String posterPath;

    @JsonProperty("backdrop_path")
    private String backdropPath;

    @JsonProperty("release_date")
    private String releaseDate;

    private Integer runtime;

    @JsonProperty("original_language")
    private String originalLanguage;

    private List<TmdbGenre> genres;

    @JsonProperty("vote_average")
    private Double voteAverage;

    @JsonProperty("vote_count")
    private Long voteCount;

    private String status;

    private TmdbCredits credits;
}
