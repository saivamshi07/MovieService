package com.platform.movieservice.service;

import com.platform.movieservice.dto.request.UserPreferenceRequest;
import com.platform.movieservice.dto.response.PreferenceOptionsResponse;
import com.platform.movieservice.dto.response.UserPreferenceResponse;
import com.platform.movieservice.entity.UserPreference;
import com.platform.movieservice.repository.UserPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPreferenceServiceTest {

    @Mock
    private UserPreferenceRepository userPreferenceRepository;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private UserPreferenceService userPreferenceService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testSavePreferences_Success() {
        UserPreferenceRequest request = UserPreferenceRequest.builder()
                .industries(Set.of("TOLLYWOOD", "HOLLYWOOD"))
                .genres(Set.of("ACTION", "THRILLER"))
                .build();

        when(userPreferenceRepository.findById(userId)).thenReturn(Optional.empty());
        when(userPreferenceRepository.save(any(UserPreference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserPreferenceResponse response = userPreferenceService.savePreferences(userId, request);

        assertNotNull(response);
        assertEquals(userId, response.getUserId());
        assertTrue(response.getIndustries().contains("TOLLYWOOD"));
        assertTrue(response.getIndustries().contains("HOLLYWOOD"));
        assertTrue(response.getGenres().contains("ACTION"));
        assertTrue(response.getGenres().contains("THRILLER"));

        verify(userPreferenceRepository, times(1)).save(any(UserPreference.class));
        verify(valueOperations, times(1)).set(eq("user:pref:" + userId), any(), any(Duration.class));
    }

    @Test
    void testGetAllOptions() {
        PreferenceOptionsResponse options = userPreferenceService.getAllOptions();
        assertNotNull(options);
        assertEquals(5, options.getIndustries().size());
        assertEquals(11, options.getGenres().size());
    }
}
