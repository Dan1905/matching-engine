package com.trading.matching_engine.redis;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
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

    public Map<String, String> getAll(Collection<String> orderIds) {
        List<String> ids = List.copyOf(orderIds);
        List<String> keys = ids.stream()
            .map(id -> "order:status:" + id)
            .toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        Map<String, String> statuses = new HashMap<>();

        if (values == null) return statuses;
        int index = 0;
        for (String orderId : ids) {
            String value = values.get(index++);
            if (value != null) statuses.put(orderId, value);
        }
        return statuses;
    }

    public void putAll(Map<String, String> statusUpdates) {
    redis.executePipelined((RedisCallback<Object>) connection -> {
        statusUpdates.forEach((orderId, status) ->
            connection.stringCommands().set(
                ("order:status:" + orderId).getBytes(),
                status.getBytes(),
                Expiration.seconds(TTL_SECONDS),
                RedisStringCommands.SetOption.UPSERT
            ));
        return null;
    });
}
}
