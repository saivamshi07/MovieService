package com.platform.movieservice.service;

import com.platform.movieservice.dto.response.MovieDetailDTO;
import com.platform.movieservice.dto.tmdb.TmdbMovieDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProactivePrewarmService {

    private final TmdbService tmdbService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AsyncCatalogPersistenceService asyncPersistenceService;

    @Value("${prewarm.enabled:true}")
    private boolean prewarmEnabled;

    @Value("${prewarm.top-movies-limit:10}")
    private int topMoviesLimit;

    @Value("${prewarm.throttle-delay-ms:250}")
    private int throttleDelayMs;

    @Value("${cache.ttl.movie-details:86400}")
    private long movieDetailsTtlSeconds;

    @Async
    public void prewarmTopMovies(List<Long> movieIds) {
        if (!prewarmEnabled || movieIds == null || movieIds.isEmpty()) {
            return;
        }

        log.info("Starting proactive pre-warming for top {} movies...", Math.min(movieIds.size(), topMoviesLimit));
        int count = 0;

        for (Long movieId : movieIds) {
            if (count >= topMoviesLimit) {
                break;
            }

            String cacheKey = "movie:details:" + movieId;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey))) {
                count++;
                continue; // Already warm!
            }

            try {
                TmdbMovieDetail tmdbDetail = tmdbService.fetchMovieDetail(movieId);
                if (tmdbDetail != null) {
                    MovieDetailDTO dto = mapToDetailDTO(tmdbDetail);
                    redisTemplate.opsForValue().set(cacheKey, dto, Duration.ofSeconds(movieDetailsTtlSeconds));
                    asyncPersistenceService.persistMovieAndCredits(tmdbDetail);
                    log.debug("Proactively pre-warmed movie ID: {}", movieId);
                }

                count++;
                Thread.sleep(throttleDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Failed to pre-warm movie ID {}: {}", movieId, e.getMessage());
            }
        }

        log.info("Finished proactive pre-warming cycle.");
    }

    private MovieDetailDTO mapToDetailDTO(TmdbMovieDetail detail) {
        Integer releaseYear = null;
        if (detail.getReleaseDate() != null && detail.getReleaseDate().length() >= 4) {
            try {
                releaseYear = Integer.parseInt(detail.getReleaseDate().substring(0, 4));
            } catch (Exception ignored) {}
        }

        List<String> genres = detail.getGenres() != null ?
                detail.getGenres().stream().map(g -> g.getName()).toList() : List.of();

        List<MovieDetailDTO.PersonSummaryDTO> directors = List.of();
        List<MovieDetailDTO.PersonSummaryDTO> writers = List.of();
        List<MovieDetailDTO.CastMemberDTO> topCast = List.of();

        if (detail.getCredits() != null) {
            if (detail.getCredits().getCrew() != null) {
                directors = detail.getCredits().getCrew().stream()
                        .filter(c -> "Director".equalsIgnoreCase(c.getJob()))
                        .map(c -> MovieDetailDTO.PersonSummaryDTO.builder()
                                .id(c.getId())
                                .name(c.getName())
                                .job(c.getJob())
                                .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                                .build())
                        .toList();

                writers = detail.getCredits().getCrew().stream()
                        .filter(c -> "Writer".equalsIgnoreCase(c.getJob()) || "Screenplay".equalsIgnoreCase(c.getJob()))
                        .map(c -> MovieDetailDTO.PersonSummaryDTO.builder()
                                .id(c.getId())
                                .name(c.getName())
                                .job(c.getJob())
                                .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                                .build())
                        .toList();
            }

            if (detail.getCredits().getCast() != null) {
                topCast = detail.getCredits().getCast().stream()
                        .limit(10)
                        .map(c -> MovieDetailDTO.CastMemberDTO.builder()
                                .id(c.getId())
                                .name(c.getName())
                                .character(c.getCharacter())
                                .profileUrl(tmdbService.buildProfileUrl(c.getProfilePath(), "w185"))
                                .order(c.getOrder())
                                .build())
                        .toList();
            }
        }

        return MovieDetailDTO.builder()
                .id(detail.getId())
                .title(detail.getTitle())
                .originalTitle(detail.getOriginalTitle())
                .overview(detail.getOverview())
                .tagline(detail.getTagline())
                .posterUrl(tmdbService.buildPosterUrl(detail.getPosterPath(), "w500"))
                .backdropUrl(tmdbService.buildBackdropUrl(detail.getBackdropPath(), "original"))
                .releaseDate(detail.getReleaseDate())
                .releaseYear(releaseYear)
                .runtime(detail.getRuntime())
                .originalLanguage(detail.getOriginalLanguage())
                .genres(genres)
                .tmdbVoteAverage(detail.getVoteAverage())
                .tmdbVoteCount(detail.getVoteCount())
                .platformRating(0.0)
                .platformRatingCount(0L)
                .status(detail.getStatus())
                .directors(directors)
                .writers(writers)
                .topCast(topCast)
                .build();
    }
}
