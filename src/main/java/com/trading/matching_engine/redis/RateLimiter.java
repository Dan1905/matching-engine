package com.trading.matching_engine.redis;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
    private static final int MAX_ORDERS_PER_SECOND = 100;
    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLimited(String clientId) {
        String key = "ratelimit:" + clientId + ":" + (System.currentTimeMillis() / 1000);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(2));
        }
        return count != null && count > MAX_ORDERS_PER_SECOND;
    }
}
