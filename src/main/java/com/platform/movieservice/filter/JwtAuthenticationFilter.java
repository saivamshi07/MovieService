package com.platform.movieservice.filter;

import com.platform.movieservice.context.UserContext;
import com.platform.movieservice.service.JwtTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@Order(2) // Runs after CorsFilter (Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";

    private final JwtTokenService jwtTokenService;

    @Value("${gateway.secret:local-dev-gateway-secret}")
    private String gatewaySharedSecret;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            UUID authenticatedUserId = resolveAuthenticatedUser(request);
            if (authenticatedUserId != null) {
                UserContext.setUserId(authenticatedUserId);
            }

            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private UUID resolveAuthenticatedUser(HttpServletRequest request) {
        // 1. Check for API Gateway call with matching shared secret
        String incomingGatewaySecret = request.getHeader("X-Gateway-Secret");
        String incomingUserIdHeader = request.getHeader("X-User-Id");

        if (StringUtils.hasText(gatewaySharedSecret)
                && gatewaySharedSecret.equals(incomingGatewaySecret)
                && StringUtils.hasText(incomingUserIdHeader)) {
            try {
                return UUID.fromString(incomingUserIdHeader);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid UUID in trusted X-User-Id header: {}", incomingUserIdHeader);
            }
        }

        // 2. Extract from Authorization Bearer header
        String token = extractTokenFromHeader(request);

        // 3. Fallback: extract from access_token cookie
        if (!StringUtils.hasText(token)) {
            token = extractTokenFromCookie(request);
        }

        if (StringUtils.hasText(token) && jwtTokenService.validateToken(token)) {
            try {
                return jwtTokenService.extractUserId(token);
            } catch (Exception e) {
                log.warn("Failed to extract userId from valid JWT token: {}", e.getMessage());
            }
        }

        return null;
    }

    private String extractTokenFromHeader(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
