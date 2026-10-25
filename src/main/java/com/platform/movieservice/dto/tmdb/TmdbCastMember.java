package com.platform.movieservice.dto.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbCastMember {
    private Long id;
    private String name;
    private String character;

    @JsonProperty("profile_path")
    private String profilePath;

    private Integer order;
}
