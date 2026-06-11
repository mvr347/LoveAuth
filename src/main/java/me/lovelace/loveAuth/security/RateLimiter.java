package me.lovelace.loveAuth.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

public final class RateLimiter {
    private static final int LIMIT = 20;
    private static final Duration WINDOW = Duration.ofSeconds(10);
    private final Cache<String, Integer> attempts = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .build();

    public boolean tryConsume(String ip) {
        Integer value = attempts.asMap().merge(ip, 1, Integer::sum);
        return value <= LIMIT;
    }

    public void reset(String ip) {
        attempts.invalidate(ip);
    }
}
