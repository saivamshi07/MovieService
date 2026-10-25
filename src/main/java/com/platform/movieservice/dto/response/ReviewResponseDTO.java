package com.platform.movieservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResponseDTO implements Serializable {

    private UUID id;
    private UUID userId;
    private Long movieId;
    private String movieTitle;
    private String moviePosterUrl;
    private BigDecimal rating;
    private String reviewText;
    private String createdAt;
    private String updatedAt;
}
