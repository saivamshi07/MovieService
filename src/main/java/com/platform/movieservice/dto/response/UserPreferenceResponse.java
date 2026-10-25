package com.platform.movieservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferenceResponse implements Serializable {

    private UUID userId;
    private Set<String> industries;
    private Set<String> genres;
    private String updatedAt;
}
