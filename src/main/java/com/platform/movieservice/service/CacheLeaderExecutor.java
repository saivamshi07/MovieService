package com.platform.movieservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheLeaderExecutor {

    private final StringRedisTemplate stringRedisTemplate;

    public <T> T executeWithLeader(
            String cacheKey,
            Duration lockTimeout,
            Supplier<T> redisFetcher,
            Supplier<T> externalLoader,
            Consumer<T> cacheWriter) {

        // 1. Initial check: Is it already cached?
        T cached = redisFetcher.get();
        if (cached != null) {
            return cached;
        }

        String lockKey = "lock:" + cacheKey;
        String lockToken = UUID.randomUUID().toString();

        // 2. Compete for Leader Election
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, lockTimeout);

        if (Boolean.TRUE.equals(acquired)) {
            try {
                // Double-check cache in case another leader just populated it
                cached = redisFetcher.get();
                if (cached != null) {
                    return cached;
                }

                log.debug("Elected as LEADER for key: {}", cacheKey);
                T freshData = externalLoader.get();
                if (freshData != null) {
                    cacheWriter.accept(freshData);
                }
                return freshData;
            } finally {
                releaseLock(lockKey, lockToken);
            }
        } else {
            // Follower thread: sleep briefly, then re-check cache
            log.debug("Follower thread waiting on leader for key: {}", cacheKey);
            try {
                Thread.sleep(85);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            cached = redisFetcher.get();
            if (cached != null) {
                return cached;
            }

            // Fallback load if leader timed out
            return externalLoader.get();
        }
    }

    private void releaseLock(String lockKey, String lockToken) {
        try {
            String currentVal = stringRedisTemplate.opsForValue().get(lockKey);
            if (lockToken.equals(currentVal)) {
                stringRedisTemplate.delete(lockKey);
            }
        } catch (Exception e) {
            log.warn("Failed to release lock for key {}: {}", lockKey, e.getMessage());
        }
    }
}
