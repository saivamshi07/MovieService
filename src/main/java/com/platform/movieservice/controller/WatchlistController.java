package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.WatchlistStatusResponse;
import com.platform.movieservice.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/movies")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @PostMapping("/{id}/watchlist")
    public ResponseEntity<WatchlistStatusResponse> addToWatchlist(@PathVariable Long id) {
        UUID userId = UserContext.getRequiredUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(watchlistService.addToWatchlist(userId, id));
    }

    @DeleteMapping("/{id}/watchlist")
    public ResponseEntity<WatchlistStatusResponse> removeFromWatchlist(@PathVariable Long id) {
        UUID userId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(watchlistService.removeFromWatchlist(userId, id));
    }

    @GetMapping("/{id}/watchlist/status")
    public ResponseEntity<WatchlistStatusResponse> getWatchlistStatus(@PathVariable Long id) {
        UUID userId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(watchlistService.checkWatchlistStatus(userId, id));
    }

    @GetMapping("/watchlist/me")
    public ResponseEntity<Page<MovieCardDTO>> getMyWatchlist(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = UserContext.getRequiredUserId();
        return ResponseEntity.ok(watchlistService.getMyWatchlist(userId, page, size));
    }
}
