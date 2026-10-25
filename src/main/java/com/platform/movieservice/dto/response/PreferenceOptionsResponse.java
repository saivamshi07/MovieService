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
public class PreferenceOptionsResponse implements Serializable {

    private List<IndustryOption> industries;
    private List<GenreOption> genres;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IndustryOption implements Serializable {
        private String code;
        private String displayName;
        private String languageCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenreOption implements Serializable {
        private String code;
        private int tmdbId;
        private String displayName;
    }
}
