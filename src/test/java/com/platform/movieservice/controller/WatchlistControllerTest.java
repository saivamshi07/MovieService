package com.platform.movieservice.controller;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.dto.response.MovieCardDTO;
import com.platform.movieservice.dto.response.WatchlistStatusResponse;
import com.platform.movieservice.exception.ForbiddenException;
import com.platform.movieservice.exception.UnauthorizedException;
import com.platform.movieservice.service.WatchlistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WatchlistControllerTest {

    @Mock
    private WatchlistService watchlistService;

    @InjectMocks
    private WatchlistController watchlistController;

    private UUID userId;
    private UUID targetUserId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        targetUserId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testGetUserWatchlist_Unauthenticated_ThrowsUnauthorized() {
        // No user set in UserContext
        assertThrows(UnauthorizedException.class, () ->
                watchlistController.getUserWatchlist(targetUserId, 0, 20));
        verifyNoInteractions(watchlistService);
    }

    @Test
    void testGetUserWatchlist_Authenticated_Success() {
        UserContext.setUserId(userId);
        Page<MovieCardDTO> page = new PageImpl<>(List.of());
        when(watchlistService.getUserWatchlist(targetUserId, userId, 0, 20)).thenReturn(page);

        ResponseEntity<Page<MovieCardDTO>> response = watchlistController.getUserWatchlist(targetUserId, 0, 20);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(watchlistService, times(1)).getUserWatchlist(targetUserId, userId, 0, 20);
    }

    @Test
    void testGetUserWatchlist_PrivateAndNotFollowing_ThrowsForbidden() {
        UserContext.setUserId(userId);
        when(watchlistService.getUserWatchlist(targetUserId, userId, 0, 20))
                .thenThrow(new ForbiddenException("This user's profile is private."));

        assertThrows(ForbiddenException.class, () ->
                watchlistController.getUserWatchlist(targetUserId, 0, 20));
        verify(watchlistService, times(1)).getUserWatchlist(targetUserId, userId, 0, 20);
    }

    @Test
    void testGetMyWatchlist_Authenticated() {
        UserContext.setUserId(userId);
        Page<MovieCardDTO> page = new PageImpl<>(List.of());
        when(watchlistService.getMyWatchlist(userId, 0, 20)).thenReturn(page);

        ResponseEntity<Page<MovieCardDTO>> response = watchlistController.getMyWatchlist(0, 20);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        verify(watchlistService, times(1)).getMyWatchlist(userId, 0, 20);
    }

    @Test
    void testAddToWatchlist() {
        UserContext.setUserId(userId);
        WatchlistStatusResponse expected = WatchlistStatusResponse.builder().movieId(100L).inWatchlist(true).build();
        when(watchlistService.addToWatchlist(userId, 100L)).thenReturn(expected);

        ResponseEntity<WatchlistStatusResponse> response = watchlistController.addToWatchlist(100L);

        assertEquals(201, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
    }

    @Test
    void testRemoveFromWatchlist() {
        UserContext.setUserId(userId);
        WatchlistStatusResponse expected = WatchlistStatusResponse.builder().movieId(100L).inWatchlist(false).build();
        when(watchlistService.removeFromWatchlist(userId, 100L)).thenReturn(expected);

        ResponseEntity<WatchlistStatusResponse> response = watchlistController.removeFromWatchlist(100L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
    }

    @Test
    void testGetWatchlistStatus() {
        UserContext.setUserId(userId);
        WatchlistStatusResponse expected = WatchlistStatusResponse.builder().movieId(100L).inWatchlist(true).build();
        when(watchlistService.checkWatchlistStatus(userId, 100L)).thenReturn(expected);

        ResponseEntity<WatchlistStatusResponse> response = watchlistController.getWatchlistStatus(100L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
    }
}
