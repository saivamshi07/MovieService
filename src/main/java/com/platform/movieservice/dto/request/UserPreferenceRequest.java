package com.platform.movieservice.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferenceRequest {

    @NotEmpty(message = "At least one industry preference is required")
    private Set<String> industries; // e.g. ["HOLLYWOOD", "TOLLYWOOD"]

    @NotEmpty(message = "At least one genre preference is required")
    private Set<String> genres; // e.g. ["ACTION", "THRILLER", "SCIENCE_FICTION"]
}
