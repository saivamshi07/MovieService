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
    private final UserAccessService userAccessService;

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

    public java.util.List<Long> getWatchlistMovieIds(UUID userId) {
        return watchlistItemRepository.findMovieIdsByUserId(userId);
    }

    public Page<MovieCardDTO> getMyWatchlist(UUID userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), Math.max(1, size));
        Page<WatchlistItem> itemsPage = watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest);

        List<Long> movieIds = itemsPage.getContent().stream()
                .map(WatchlistItem::getMovieId)
                .toList();

        List<MovieCardDTO> movieCards = catalogService.getMovieCardsBatch(movieIds);

        return new PageImpl<>(movieCards, pageRequest, itemsPage.getTotalElements());
    }

    /**
     * Retrieves target user's watchlist with privacy and friendship access authorization.
     */
    public Page<MovieCardDTO> getUserWatchlist(UUID targetUserId, UUID requesterId, int page, int size) {
        userAccessService.validateWatchlistAccess(targetUserId, requesterId);
        return getMyWatchlist(targetUserId, page, size);
    }
}
