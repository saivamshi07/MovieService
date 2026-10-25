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
public class MovieDetailDTO implements Serializable {

    private Long id;
    private String title;
    private String originalTitle;
    private String overview;
    private String tagline;
    private String posterUrl;
    private String backdropUrl;
    private String releaseDate;
    private Integer releaseYear;
    private Integer runtime;
    private String originalLanguage;
    private String industry;
    private List<String> genres;
    private Double tmdbVoteAverage;
    private Long tmdbVoteCount;
    private Double platformRating;
    private Long platformRatingCount;
    private String status;
    private List<PersonSummaryDTO> directors;
    private List<PersonSummaryDTO> writers;
    private List<CastMemberDTO> topCast;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PersonSummaryDTO implements Serializable {
        private Long id;
        private String name;
        private String job;
        private String profileUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CastMemberDTO implements Serializable {
        private Long id;
        private String name;
        private String character;
        private String profileUrl;
        private Integer order;
    }
}
