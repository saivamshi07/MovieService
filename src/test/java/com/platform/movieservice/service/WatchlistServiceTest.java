package com.platform.movieservice.service;

import com.platform.movieservice.dto.response.WatchlistStatusResponse;
import com.platform.movieservice.entity.WatchlistItem;
import com.platform.movieservice.repository.WatchlistItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    @Mock
    private WatchlistItemRepository watchlistItemRepository;

    @Mock
    private CatalogService catalogService;

    @InjectMocks
    private WatchlistService watchlistService;

    private UUID userId;
    private Long movieId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        movieId = 822119L;
    }

    @Test
    void testAddToWatchlist() {
        when(watchlistItemRepository.existsByUserIdAndMovieId(userId, movieId)).thenReturn(false);

        WatchlistStatusResponse response = watchlistService.addToWatchlist(userId, movieId);

        assertNotNull(response);
        assertEquals(movieId, response.getMovieId());
        assertTrue(response.isInWatchlist());

        verify(watchlistItemRepository, times(1)).save(any(WatchlistItem.class));
    }

    @Test
    void testRemoveFromWatchlist() {
        WatchlistStatusResponse response = watchlistService.removeFromWatchlist(userId, movieId);

        assertNotNull(response);
        assertEquals(movieId, response.getMovieId());
        assertFalse(response.isInWatchlist());

        verify(watchlistItemRepository, times(1)).deleteByUserIdAndMovieId(userId, movieId);
    }

    @Test
    void testCheckStatus() {
        when(watchlistItemRepository.existsByUserIdAndMovieId(userId, movieId)).thenReturn(true);

        WatchlistStatusResponse response = watchlistService.checkWatchlistStatus(userId, movieId);

        assertNotNull(response);
        assertTrue(response.isInWatchlist());
    }
}
