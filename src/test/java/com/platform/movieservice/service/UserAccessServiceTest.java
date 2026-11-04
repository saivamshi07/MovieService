package com.platform.movieservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.movieservice.dto.response.UserAccessCheckResponse;
import com.platform.movieservice.exception.BadRequestException;
import com.platform.movieservice.exception.ForbiddenException;
import com.platform.movieservice.exception.ResourceNotFoundException;
import com.platform.movieservice.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(MockitoExtension.class)
class UserAccessServiceTest {

    @Mock
    private StringRedisTemplate graphRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private UserAccessService userAccessService;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;

    private UUID targetUserId;
    private UUID requesterId;

    @BeforeEach
    void setUp() throws Exception {
        targetUserId = UUID.randomUUID();
        requesterId = UUID.randomUUID();
        objectMapper = new ObjectMapper();

        userAccessService = new UserAccessService(
                graphRedisTemplate,
                "http://localhost:8081",
                1500,
                2000,
                "test-gateway-secret"
        );

        // Bind MockRestServiceServer to the internal RestClient
        Field restClientField = UserAccessService.class.getDeclaredField("restClient");
        restClientField.setAccessible(true);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:8081")
                .defaultHeader("X-Gateway-Secret", "test-gateway-secret");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        restClientField.set(userAccessService, builder.build());
    }

    @Test
    void testSelfUserAccess_BypassesRedisAndApi() {
        assertDoesNotThrow(() -> userAccessService.validateWatchlistAccess(requesterId, requesterId));
        verifyNoInteractions(graphRedisTemplate);
    }

    @Test
    void testNullRequester_ThrowsUnauthorized() {
        assertThrows(UnauthorizedException.class, () ->
                userAccessService.validateWatchlistAccess(targetUserId, null));
    }

    @Test
    void testNullTargetUser_ThrowsBadRequest() {
        assertThrows(BadRequestException.class, () ->
                userAccessService.validateWatchlistAccess(null, requesterId));
    }

    @Test
    void testPublicUser_FromRedis_Allowed() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PUBLIC");

        assertDoesNotThrow(() -> userAccessService.validateWatchlistAccess(targetUserId, requesterId));
    }

    @Test
    void testPrivateUser_FollowingInRedis_Allowed() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PRIVATE");

        when(graphRedisTemplate.hasKey("graph:user:" + requesterId + ":following")).thenReturn(true);
        when(graphRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("graph:user:" + requesterId + ":following", targetUserId.toString()))
                .thenReturn(true);

        assertDoesNotThrow(() -> userAccessService.validateWatchlistAccess(targetUserId, requesterId));
    }

    @Test
    void testPrivateUser_NotFollowingInRedis_ThrowsForbidden() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PRIVATE");

        when(graphRedisTemplate.hasKey("graph:user:" + requesterId + ":following")).thenReturn(true);
        when(graphRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("graph:user:" + requesterId + ":following", targetUserId.toString()))
                .thenReturn(false);

        ForbiddenException ex = assertThrows(ForbiddenException.class, () ->
                userAccessService.validateWatchlistAccess(targetUserId, requesterId));

        assertTrue(ex.getMessage().contains("profile is private"));
    }

    @Test
    void testRedisCacheMiss_FallbackToApi_CanViewTrue_Allowed() throws Exception {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn(null);

        UserAccessCheckResponse apiResponse = UserAccessCheckResponse.builder()
                .authorId(targetUserId)
                .viewerId(requesterId)
                .isPrivate(false)
                .isFollowing(false)
                .canView(true)
                .build();

        mockServer.expect(requestTo("http://localhost:8081/api/v1/users/" + targetUserId + "/access-check?viewerId=" + requesterId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Gateway-Secret", "test-gateway-secret"))
                .andExpect(header("X-User-Id", requesterId.toString()))
                .andRespond(withSuccess(objectMapper.writeValueAsString(apiResponse), MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> userAccessService.validateWatchlistAccess(targetUserId, requesterId));
        mockServer.verify();
    }

    @Test
    void testRedisCacheMiss_FallbackToApi_CanViewFalse_ThrowsForbidden() throws Exception {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn(null);

        UserAccessCheckResponse apiResponse = UserAccessCheckResponse.builder()
                .authorId(targetUserId)
                .viewerId(requesterId)
                .isPrivate(true)
                .isFollowing(false)
                .canView(false)
                .build();

        mockServer.expect(requestTo("http://localhost:8081/api/v1/users/" + targetUserId + "/access-check?viewerId=" + requesterId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(apiResponse), MediaType.APPLICATION_JSON));

        assertThrows(ForbiddenException.class, () ->
                userAccessService.validateWatchlistAccess(targetUserId, requesterId));
        mockServer.verify();
    }

    @Test
    void testRedisCacheMiss_FallbackToApi_UserNotFound_ThrowsResourceNotFound() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn(null);

        mockServer.expect(requestTo("http://localhost:8081/api/v1/users/" + targetUserId + "/access-check?viewerId=" + requesterId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(ResourceNotFoundException.class, () ->
                userAccessService.validateWatchlistAccess(targetUserId, requesterId));
        mockServer.verify();
    }

    @Test
    void testRedisException_FallsBackToApi() throws Exception {
        when(graphRedisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        UserAccessCheckResponse apiResponse = UserAccessCheckResponse.builder()
                .authorId(targetUserId)
                .viewerId(requesterId)
                .isPrivate(false)
                .canView(true)
                .build();

        mockServer.expect(requestTo("http://localhost:8081/api/v1/users/" + targetUserId + "/access-check?viewerId=" + requesterId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(apiResponse), MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> userAccessService.validateWatchlistAccess(targetUserId, requesterId));
        mockServer.verify();
    }

    // --- Review Access Tests ---

    @Test
    void testValidateReviewAccess_SelfUser_AlwaysAllowed() {
        assertDoesNotThrow(() -> userAccessService.validateReviewAccess(requesterId, requesterId));
        verifyNoInteractions(graphRedisTemplate);
    }

    @Test
    void testValidateReviewAccess_PublicUser_AllowedForGuest() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PUBLIC");

        assertDoesNotThrow(() -> userAccessService.validateReviewAccess(targetUserId, null));
    }

    @Test
    void testValidateReviewAccess_PrivateUser_Guest_ThrowsForbidden() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PRIVATE");

        ForbiddenException ex = assertThrows(ForbiddenException.class, () ->
                userAccessService.validateReviewAccess(targetUserId, null));
        assertTrue(ex.getMessage().contains("profile is private"));
    }

    @Test
    void testValidateReviewAccess_PrivateUser_FollowedInRedis_Allowed() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PRIVATE");
        when(graphRedisTemplate.hasKey("graph:user:" + requesterId + ":following")).thenReturn(true);
        when(graphRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("graph:user:" + requesterId + ":following", targetUserId.toString()))
                .thenReturn(true);

        assertDoesNotThrow(() -> userAccessService.validateReviewAccess(targetUserId, requesterId));
    }

    @Test
    void testValidateReviewAccess_PrivateUser_NotFollowedInRedis_ThrowsForbidden() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PRIVATE");
        when(graphRedisTemplate.hasKey("graph:user:" + requesterId + ":following")).thenReturn(true);
        when(graphRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("graph:user:" + requesterId + ":following", targetUserId.toString()))
                .thenReturn(false);

        assertThrows(ForbiddenException.class, () ->
                userAccessService.validateReviewAccess(targetUserId, requesterId));
    }

    @Test
    void testCanAccessUser_PublicUser_ReturnsTrue() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PUBLIC");

        assertTrue(userAccessService.canAccessUser(targetUserId, null));
    }

    @Test
    void testCanAccessUser_PrivateUser_Guest_ReturnsFalse() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PRIVATE");

        assertFalse(userAccessService.canAccessUser(targetUserId, null));
    }

    @Test
    void testCanAccessUser_PrivateUser_Followed_ReturnsTrue() {
        when(graphRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("graph:user:" + targetUserId + ":privacy")).thenReturn("PRIVATE");
        when(graphRedisTemplate.hasKey("graph:user:" + requesterId + ":following")).thenReturn(true);
        when(graphRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("graph:user:" + requesterId + ":following", targetUserId.toString()))
                .thenReturn(true);

        assertTrue(userAccessService.canAccessUser(targetUserId, requesterId));
    }
}
