package com.platform.movieservice.context;

import com.platform.movieservice.exception.UnauthorizedException;

import java.util.UUID;

/**
 * Thread-local context storing the authenticated caller's identity (UUID userId)
 * across the request execution lifecycle.
 */
public final class UserContext {

    private static final ThreadLocal<UUID> CURRENT_USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(UUID userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static UUID getUserId() {
        return CURRENT_USER_ID.get();
    }

    public static UUID getRequiredUserId() {
        UUID userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw new UnauthorizedException("Authentication required. Please log in.");
        }
        return userId;
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
