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
public class MultiSearchResponseDTO implements Serializable {

    private String query;
    private int totalMatches;
    private List<MovieCardDTO> movies;
    private List<ActorSearchResultDTO> actors;
    private List<CrewSearchResultDTO> crew;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActorSearchResultDTO implements Serializable {
        private Long id;
        private String name;
        private String profileUrl;
        private List<String> knownFor;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CrewSearchResultDTO implements Serializable {
        private Long id;
        private String name;
        private String department;
        private String profileUrl;
        private List<String> knownFor;
    }
}
