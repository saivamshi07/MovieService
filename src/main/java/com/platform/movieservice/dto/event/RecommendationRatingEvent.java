package com.platform.movieservice.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationRatingEvent implements Serializable {

    private UUID eventId;
    private String eventType; // "USER_ITEM_RATED"
    private String aggregateType; // "MOVIE_RATING"
    private Instant timestamp;
    private RatingPayload payload;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RatingPayload implements Serializable {
        private UUID userId;
        private String itemId; // TMDB movie ID
        private String category; // "MOVIE"
        private BigDecimal rating;
        private String review;
        private boolean isUpdate;
    }
}
