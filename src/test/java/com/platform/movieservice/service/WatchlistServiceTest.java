package com.platform.movieservice.service;

import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.WatchlistStatusResponse;
import com.platform.movieservice.entity.WatchlistItem;
import com.platform.movieservice.exception.ForbiddenException;
import com.platform.movieservice.repository.WatchlistItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
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

    @Mock
    private UserAccessService userAccessService;

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

    @Test
    void testGetUserWatchlistSuccess() {
        UUID requesterId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 20);
        when(watchlistItemRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest))
                .thenReturn(new PageImpl<>(List.of()));

        Page<MovieCardDTO> result = watchlistService.getUserWatchlist(userId, requesterId, 0, 20);

        assertNotNull(result);
        verify(userAccessService, times(1)).validateWatchlistAccess(userId, requesterId);
    }

    @Test
    void testGetUserWatchlistForbidden() {
        UUID requesterId = UUID.randomUUID();
        doThrow(new ForbiddenException("This user's profile is private."))
                .when(userAccessService).validateWatchlistAccess(userId, requesterId);

        assertThrows(ForbiddenException.class, () ->
                watchlistService.getUserWatchlist(userId, requesterId, 0, 20));
    }
}
