package com.platform.movieservice.entity.enums;

import lombok.Getter;

@Getter
public enum MovieGenre {
    ACTION(28, "Action"),
    ADVENTURE(12, "Adventure"),
    ANIMATION(16, "Animation"),
    COMEDY(35, "Comedy"),
    CRIME(80, "Crime"),
    DOCUMENTARY(99, "Documentary"),
    DRAMA(18, "Drama"),
    FAMILY(10751, "Family"),
    FANTASY(14, "Fantasy"),
    HISTORY(36, "History"),
    HORROR(27, "Horror"),
    MUSIC(10402, "Music"),
    MYSTERY(9648, "Mystery"),
    ROMANCE(10749, "Romance"),
    SCIENCE_FICTION(878, "Science Fiction"),
    TV_MOVIE(10770, "TV Movie"),
    THRILLER(53, "Thriller"),
    WAR(10752, "War"),
    WESTERN(37, "Western");

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
        String clean = value.trim().replace(" ", "_").replace("-", "_").toUpperCase();
        if (clean.equals("SCI_FI") || clean.equals("SCIFI")) {
            return SCIENCE_FICTION;
        }
        if (clean.equals("TV") || clean.equals("TVMOVIE")) {
            return TV_MOVIE;
        }
        for (MovieGenre genre : values()) {
            if (genre.name().equalsIgnoreCase(clean) || 
                String.valueOf(genre.tmdbId).equals(value.trim()) ||
                genre.displayName.equalsIgnoreCase(value.trim()) ||
                genre.displayName.replace(" ", "_").equalsIgnoreCase(clean)) {
                return genre;
            }
        }
        return null;
    }
}
