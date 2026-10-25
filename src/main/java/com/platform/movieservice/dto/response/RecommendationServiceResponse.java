package com.platform.movieservice.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecommendationServiceResponse {

    private UUID userId;
    private String category;
    private List<PredictionItem> predictions;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PredictionItem {
        private String itemId;
        private Double score;
    }
}
