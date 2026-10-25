package com.platform.movieservice.entity.enums;

import lombok.Getter;

@Getter
public enum CinemaIndustry {
    HOLLYWOOD("en", "Hollywood (English)"),
    TOLLYWOOD("te", "Tollywood (Telugu)"),
    BOLLYWOOD("hi", "Bollywood (Hindi)"),
    MOLLYWOOD("ml", "Mollywood (Malayalam)"),
    KOLLYWOOD("ta", "Kollywood (Tamil)"),
    ALL(null, "All Industries");

    private final String originalLanguageCode;
    private final String displayName;

    CinemaIndustry(String originalLanguageCode, String displayName) {
        this.originalLanguageCode = originalLanguageCode;
        this.displayName = displayName;
    }

    public static CinemaIndustry fromString(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        for (CinemaIndustry industry : values()) {
            if (industry.name().equalsIgnoreCase(value.trim()) ||
                (industry.originalLanguageCode != null && industry.originalLanguageCode.equalsIgnoreCase(value.trim()))) {
                return industry;
            }
        }
        return ALL;
    }
}
