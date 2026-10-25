package com.platform.movieservice.service;

import com.platform.movieservice.dto.request.UserPreferenceRequest;
import com.platform.movieservice.dto.response.PreferenceOptionsResponse;
import com.platform.movieservice.dto.response.UserPreferenceResponse;
import com.platform.movieservice.entity.UserPreference;
import com.platform.movieservice.entity.enums.CinemaIndustry;
import com.platform.movieservice.entity.enums.MovieGenre;
import com.platform.movieservice.exception.BadRequestException;
import com.platform.movieservice.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private final UserPreferenceRepository userPreferenceRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String PREF_CACHE_PREFIX = "user:pref:";
    private static final Duration PREF_CACHE_TTL = Duration.ofDays(7);

    @Transactional
    public UserPreferenceResponse savePreferences(UUID userId, UserPreferenceRequest request) {
        if (userId == null) {
            throw new BadRequestException("User ID is required");
        }

        Set<CinemaIndustry> industries = new HashSet<>();
        for (String indStr : request.getIndustries()) {
            CinemaIndustry industry = CinemaIndustry.fromString(indStr);
            if (industry != CinemaIndustry.ALL) {
                industries.add(industry);
            }
        }
        if (industries.isEmpty()) {
            throw new BadRequestException("At least one valid industry (HOLLYWOOD, TOLLYWOOD, BOLLYWOOD, MOLLYWOOD, KOLLYWOOD) must be selected");
        }

        Set<MovieGenre> genres = new HashSet<>();
        for (String genreStr : request.getGenres()) {
            MovieGenre genre = MovieGenre.fromString(genreStr);
            if (genre != null) {
                genres.add(genre);
            }
        }
        if (genres.isEmpty()) {
            throw new BadRequestException("At least one valid genre must be selected");
        }

        UserPreference preference = userPreferenceRepository.findById(userId)
                .orElse(UserPreference.builder().userId(userId).build());

        preference.setPreferredIndustries(industries);
        preference.setPreferredGenres(genres);

        UserPreference saved = userPreferenceRepository.save(preference);
        UserPreferenceResponse response = toResponse(saved);

        // Warm Redis
        try {
            redisTemplate.opsForValue().set(PREF_CACHE_PREFIX + userId, response, PREF_CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache preferences for user {}: {}", userId, e.getMessage());
        }

        return response;
    }

    public UserPreferenceResponse getPreferences(UUID userId) {
        if (userId == null) {
            return null;
        }

        String cacheKey = PREF_CACHE_PREFIX + userId;
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof UserPreferenceResponse) {
                return (UserPreferenceResponse) cached;
            }
        } catch (Exception e) {
            log.warn("Failed to read user preferences from cache: {}", e.getMessage());
        }

        return userPreferenceRepository.findById(userId)
                .map(pref -> {
                    UserPreferenceResponse res = toResponse(pref);
                    try {
                        redisTemplate.opsForValue().set(cacheKey, res, PREF_CACHE_TTL);
                    } catch (Exception ignored) {}
                    return res;
                })
                .orElse(null);
    }

    public PreferenceOptionsResponse getAllOptions() {
        List<PreferenceOptionsResponse.IndustryOption> industries = Arrays.stream(CinemaIndustry.values())
                .filter(i -> i != CinemaIndustry.ALL)
                .map(i -> PreferenceOptionsResponse.IndustryOption.builder()
                        .code(i.name())
                        .displayName(i.getDisplayName())
                        .languageCode(i.getOriginalLanguageCode())
                        .build())
                .toList();

        List<PreferenceOptionsResponse.GenreOption> genres = Arrays.stream(MovieGenre.values())
                .map(g -> PreferenceOptionsResponse.GenreOption.builder()
                        .code(g.name())
                        .tmdbId(g.getTmdbId())
                        .displayName(g.getDisplayName())
                        .build())
                .toList();

        return PreferenceOptionsResponse.builder()
                .industries(industries)
                .genres(genres)
                .build();
    }

    private UserPreferenceResponse toResponse(UserPreference entity) {
        Set<String> indStrings = entity.getPreferredIndustries().stream()
                .map(Enum::name)
                .collect(Collectors.toSet());

        Set<String> genreStrings = entity.getPreferredGenres().stream()
                .map(Enum::name)
                .collect(Collectors.toSet());

        return UserPreferenceResponse.builder()
                .userId(entity.getUserId())
                .industries(indStrings)
                .genres(genreStrings)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null)
                .build();
    }
}
