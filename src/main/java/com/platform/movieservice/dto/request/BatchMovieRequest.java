package com.platform.movieservice.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
public class BatchMovieRequest {

    @JsonProperty("movieIds")
    @Builder.Default
    private List<Long> movieIds = new ArrayList<>();

    @JsonProperty("ids")
    public void setIdsAlias(List<Long> ids) {
        if (this.movieIds == null || this.movieIds.isEmpty()) {
            this.movieIds = ids;
        }
    }

    @JsonCreator
    public BatchMovieRequest(List<Long> movieIds) {
        this.movieIds = movieIds != null ? movieIds : new ArrayList<>();
    }
}
