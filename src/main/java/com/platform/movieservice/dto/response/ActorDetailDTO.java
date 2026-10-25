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
public class ActorDetailDTO implements Serializable {

    private Long id;
    private String name;
    private String biography;
    private String birthday;
    private String deathday;
    private Integer age;
    private String placeOfBirth;
    private String profileUrl;
    private List<MovieCardDTO> filmsActed;
}
