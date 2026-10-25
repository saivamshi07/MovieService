package com.platform.movieservice.service;

import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.MovieDetailDTO;
import com.platform.movieservice.dto.response.WatchlistStatusResponse;
import com.platform.movieservice.entity.WatchlistItem;
import com.platform.movieservice.repository.WatchlistItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchlistItemRepository watchlistItemRepository;
    private final CatalogService catalogService;

    @Transactional
    public WatchlistStatusResponse addToWatchlist(UUID userId, Long movieId) {
        if (!watchlistItemRepository.existsByUserIdAndMovieId(userId, movieId)) {
            WatchlistItem item = WatchlistItem.builder()
                    .userId(userId)
                    .movieId(movieId)
                    .build();
            watchlistItemRepository.save(item);
            log.debug("Added movie {} to watchlist for user {}", movieId, userId);
        }

        return WatchlistStatusResponse.builder()
                .movieId(movieId)
                .inWatchlist(true)
                .build();
    }

    @Transactional
    public WatchlistStatusResponse removeFromWatchlist(UUID userId, Long movieId) {
        watchlistItemRepository.deleteByUserIdAndMovieId(userId, movieId);
        log.debug("Removed movie {} from watchlist for user {}", movieId, userId);

        return WatchlistStatusResponse.builder()
                .movieId(movieId)
                .inWatchlist(false)
                .build();
    }

    public WatchlistStatusResponse checkWatchlistStatus(UUID userId, Long movieId) {
        boolean inWatchlist = watchlistItemRepository.existsByUserIdAndMovieId(userId, movieId);
        return WatchlistStatusResponse.builder()
                .movieId(movieId)
                .inWatchlist(inWatchlist)
                .build();
    }

    public Page<MovieCardDTO> getMyWatchlist(UUID userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), Math.max(1, size));
        Page<WatchlistItem> itemsPage = watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest);

        List<MovieCardDTO> movieCards = new ArrayList<>();
        for (WatchlistItem item : itemsPage.getContent()) {
            try {
                MovieDetailDTO detail = catalogService.getMovieDetail(item.getMovieId());
                if (detail != null) {
                    movieCards.add(MovieCardDTO.builder()
                            .id(detail.getId())
                            .title(detail.getTitle())
                            .overview(detail.getOverview())
                            .posterUrl(detail.getPosterUrl())
                            .backdropUrl(detail.getBackdropUrl())
                            .releaseDate(detail.getReleaseDate())
                            .releaseYear(detail.getReleaseYear())
                            .originalLanguage(detail.getOriginalLanguage())
                            .industry(detail.getIndustry())
                            .genres(detail.getGenres())
                            .tmdbVoteAverage(detail.getTmdbVoteAverage())
                            .platformRating(detail.getPlatformRating())
                            .platformRatingCount(detail.getPlatformRatingCount())
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to hydrate movie {} in watchlist: {}", item.getMovieId(), e.getMessage());
            }
        }

        return new PageImpl<>(movieCards, pageRequest, itemsPage.getTotalElements());
    }
}
