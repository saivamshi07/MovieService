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
public class MovieCardDTO implements Serializable {

    private Long id;
    private String title;
    private String overview;
    private String posterUrl;
    private String backdropUrl;
    private String releaseDate;
    private Integer releaseYear;
    private String originalLanguage;
    private String industry;
    private List<String> genres;
    private Double tmdbVoteAverage;
    private Long tmdbVoteCount;
    private Double popularity;
    private Long revenue;
    private Double platformRating;
    private Long platformRatingCount;
    private String matchScore;
    private String matchReason;
}
