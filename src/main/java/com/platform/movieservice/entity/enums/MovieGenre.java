package com.platform.movieservice.entity.enums;

import lombok.Getter;

@Getter
public enum MovieGenre {
    ACTION(28, "Action"),
    ADVENTURE(12, "Adventure"),
    THRILLER(53, "Thriller"),
    HORROR(27, "Horror"),
    DRAMA(18, "Drama"),
    COMEDY(35, "Comedy"),
    CRIME(80, "Crime"),
    DOCUMENTARY(99, "Documentary"),
    FAMILY(10751, "Family"),
    ROMANCE(10749, "Romance"),
    SCIENCE_FICTION(878, "Science Fiction");

    private final int tmdbId;
    private final String displayName;

    MovieGenre(int tmdbId, String displayName) {
        this.tmdbId = tmdbId;
        this.displayName = displayName;
    }

    public static MovieGenre fromTmdbId(int id) {
        for (MovieGenre genre : values()) {
            if (genre.tmdbId == id) {
                return genre;
            }
        }
        return null;
    }

    public static MovieGenre fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.trim().replace(" ", "_").toUpperCase();
        for (MovieGenre genre : values()) {
            if (genre.name().equalsIgnoreCase(clean) || 
                String.valueOf(genre.tmdbId).equals(value.trim()) ||
                genre.displayName.equalsIgnoreCase(value.trim())) {
                return genre;
            }
        }
        return null;
    }
}
