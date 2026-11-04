package com.platform.movieservice.service;

import com.platform.movieservice.dto.response.UserAccessCheckResponse;
import com.platform.movieservice.exception.BadRequestException;
import com.platform.movieservice.exception.ForbiddenException;
import com.platform.movieservice.exception.ResourceNotFoundException;
import com.platform.movieservice.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class UserAccessService {

    public static final String GRAPH_PRIVACY_PREFIX = "graph:user:";
    public static final String GRAPH_PRIVACY_SUFFIX = ":privacy";
    public static final String GRAPH_FOLLOWING_PREFIX = "graph:user:";
    public static final String GRAPH_FOLLOWING_SUFFIX = ":following";

    private final StringRedisTemplate graphRedisTemplate;
    private final RestClient restClient;

    public UserAccessService(
            @Qualifier("graphRedisTemplate") StringRedisTemplate graphRedisTemplate,
            @Value("${user.service.base-url:http://localhost:8081}") String userServiceBaseUrl,
            @Value("${user.service.connect-timeout-ms:1500}") int connectTimeoutMs,
            @Value("${user.service.read-timeout-ms:2000}") int readTimeoutMs,
            @Value("${gateway.secret:local-dev-gateway-secret}") String gatewaySecret) {
        this.graphRedisTemplate = graphRedisTemplate;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(userServiceBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("X-Gateway-Secret", gatewaySecret)
                .build();
    }

    /**
     * Validates if requester has permission to view target user's watchlist.
     * Throws:
     * - UnauthorizedException if requesterId is null
     * - BadRequestException if targetUserId is null
     * - ResourceNotFoundException if target user does not exist
     * - ForbiddenException if target user is private and requester is not following them
     */
    public void validateWatchlistAccess(UUID targetUserId, UUID requesterId) {
        if (requesterId == null) {
            throw new UnauthorizedException("Authentication required to access user watchlist");
        }
        if (targetUserId == null) {
            throw new BadRequestException("Target user ID cannot be null");
        }

        // 1. Self-user bypass: a user can always view their own watchlist
        if (targetUserId.equals(requesterId)) {
            return;
        }

        // 2. Try consuming global graph Redis from user-service
        Boolean canAccessFromRedis = checkAccessViaRedis(targetUserId, requesterId);
        if (canAccessFromRedis != null) {
            if (canAccessFromRedis) {
                return;
            } else {
                throw new ForbiddenException("This user's profile is private. Follow this user to view their watchlist.");
            }
        }

        // 3. Fallback: Hit user-service access-check API
        boolean canAccessFromApi = checkAccessViaUserService(targetUserId, requesterId);
        if (!canAccessFromApi) {
            throw new ForbiddenException("This user's profile is private. Follow this user to view their watchlist.");
        }
    }

    /**
     * Validates if requester has permission to view target user's reviews and ratings.
     * Rules:
     * - Self-user (me) is always allowed.
     * - Public user can be viewed by all (authenticated or guest).
     * - Private user can only be viewed by accepted followers.
     * - Unaccessed requests throw ForbiddenException (403 Forbidden).
     */
    public void validateReviewAccess(UUID targetUserId, UUID requesterId) {
        if (targetUserId == null) {
            throw new BadRequestException("Target user ID cannot be null");
        }

        // 1. Self-user bypass: a user can always view their own reviews
        if (requesterId != null && targetUserId.equals(requesterId)) {
            return;
        }

        // 2. Try consuming global graph Redis from user-service
        Boolean canAccessFromRedis = checkAccessViaRedis(targetUserId, requesterId);
        if (canAccessFromRedis != null) {
            if (canAccessFromRedis) {
                return;
            } else {
                throw new ForbiddenException("This user's profile is private. Follow this user to view their reviews.");
            }
        }

        // 3. Fallback: Hit user-service access-check API
        boolean canAccessFromApi = checkAccessViaUserService(targetUserId, requesterId);
        if (!canAccessFromApi) {
            throw new ForbiddenException("This user's profile is private. Follow this user to view their reviews.");
        }
    }

    /**
     * Non-throwing access checker for filtering unaccessed users in collections
     * (e.g. Movie reviews list where unaccessed reviews must be skipped).
     */
    public boolean canAccessUser(UUID targetUserId, UUID viewerId) {
        if (targetUserId == null) {
            return false;
        }

        // 1. Self bypass
        if (viewerId != null && targetUserId.equals(viewerId)) {
            return true;
        }

        // 2. Try Redis
        Boolean canAccessFromRedis = checkAccessViaRedis(targetUserId, viewerId);
        if (canAccessFromRedis != null) {
            return canAccessFromRedis;
        }

        // 3. Fallback to API
        return checkAccessViaUserService(targetUserId, viewerId);
    }

    /**
     * Checks Redis for privacy and following relationship.
     * Returns:
     * - Boolean.TRUE if access is confirmed granted (user is public, or private & requester follows them)
     * - Boolean.FALSE if access is confirmed denied (user is private & requester definitely does not follow them)
     * - null if cache miss occurs on either privacy or following set -> fallback to API
     */
    public Boolean checkAccessViaRedis(UUID targetUserId, UUID requesterId) {
        try {
            String privacyKey = GRAPH_PRIVACY_PREFIX + targetUserId + GRAPH_PRIVACY_SUFFIX;
            String privacyVal = graphRedisTemplate.opsForValue().get(privacyKey);

            if (privacyVal == null) {
                log.debug("Cache miss for user privacy in Redis: {}", privacyKey);
                return null; // Cache miss -> fallback to API
            }

            if ("PUBLIC".equalsIgnoreCase(privacyVal)) {
                log.debug("User {} is PUBLIC according to Redis graph cache", targetUserId);
                return true;
            }

            if ("PRIVATE".equalsIgnoreCase(privacyVal)) {
                if (requesterId == null) {
                    log.debug("User {} is PRIVATE and requester is unauthenticated", targetUserId);
                    return false;
                }
                String followingKey = GRAPH_FOLLOWING_PREFIX + requesterId + GRAPH_FOLLOWING_SUFFIX;
                if (!Boolean.TRUE.equals(graphRedisTemplate.hasKey(followingKey))) {
                    log.debug("Cache miss for viewer following set in Redis: {}", followingKey);
                    return null; // Cache miss -> fallback to API
                }

                Boolean isFollowing = graphRedisTemplate.opsForSet().isMember(followingKey, targetUserId.toString());
                log.debug("User {} is PRIVATE. Viewer {} following status in Redis: {}", targetUserId, requesterId, isFollowing);
                return Boolean.TRUE.equals(isFollowing);
            }

            return null;
        } catch (Exception e) {
            log.warn("Error reading user graph Redis for target {} by viewer {}: {}. Triggering API fallback.",
                    targetUserId, requesterId, e.getMessage());
            return null; // Fallback to API on Redis error
        }
    }

    /**
     * Hits user-service API fallback: GET /api/v1/users/{authorId}/access-check?viewerId={viewerId}
     */
    public boolean checkAccessViaUserService(UUID targetUserId, UUID requesterId) {
        try {
            log.debug("Calling user-service API fallback /api/v1/users/{}/access-check?viewerId={}", targetUserId, requesterId);
            var request = restClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path("/api/v1/users/{id}/access-check");
                        if (requesterId != null) {
                            b.queryParam("viewerId", requesterId);
                        }
                        return b.build(targetUserId);
                    });

            if (requesterId != null) {
                request.header("X-User-Id", requesterId.toString());
            }

            UserAccessCheckResponse response = request.retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new ResourceNotFoundException("User not found: " + targetUserId);
                    })
                    .body(UserAccessCheckResponse.class);

            if (response != null) {
                log.debug("user-service access-check response: canView={}, isPrivate={}, isFollowing={}",
                        response.isCanView(), response.isPrivate(), response.isFollowing());
                return response.isCanView();
            }
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to check access with user-service for user {} (viewer {}): {}",
                    targetUserId, requesterId, e.getMessage());
        }

        // Fail-safe security default: deny access if verification fails
        return false;
    }
}
