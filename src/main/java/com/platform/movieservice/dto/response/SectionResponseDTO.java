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
public class SectionResponseDTO implements Serializable {

    private String section;
    private String title;
    private int page;
    private int totalPages;
    private int totalResults;
    private List<MovieCardDTO> movies;
}
