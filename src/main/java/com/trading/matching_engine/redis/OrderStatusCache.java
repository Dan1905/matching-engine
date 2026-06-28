package com.trading.matching_engine.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderStatusCache {
    private static final int TTL_SECONDS = 60;
    private final StringRedisTemplate redis;

    public OrderStatusCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void put(String orderId, String status) {
        redis.opsForValue().set(
            "order:status:" + orderId,
            status,
            Duration.ofSeconds(TTL_SECONDS)
        );
    }

    public Optional<String> get(String orderId) {
        return Optional.ofNullable(
            redis.opsForValue().get("order:status:" + orderId)
        );
    }
}
