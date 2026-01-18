package com.example.event_system.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @param key 이벤트 식별 키
     * @param limit 1초당 허용량
     * @return 허용 여부
     */
    public boolean isAllowed(String key, int limit) {
        Objects.requireNonNull(key);
        // Redis의 INCR 연산은 원자적(Atomic)으로 동작합니다.
        Long count = redisTemplate.opsForValue().increment(key);
        
        if (count != null && count == 1) {
            // 첫 번째 요청일 때 1초의 유효시간을 설정합니다.
            redisTemplate.expire(key, 1, TimeUnit.SECONDS);
        }
        
        return count != null && count <= limit;
    }

    /**
     * 동적 설정 적용: Redis에 저장된 설정값을 조회하여 허용량을 결정합니다.
     * @param key 이벤트 식별 키
     * @param limitConfigKey 허용량 설정값이 저장된 Redis 키
     * @param defaultLimit 설정값이 없을 경우 사용할 기본값
     */
    public boolean isAllowed(String key, String limitConfigKey, int defaultLimit) {
        String limitStr = redisTemplate.opsForValue().get(Objects.requireNonNull(limitConfigKey));
        int limit = defaultLimit;
        if (limitStr != null) {
            try {
                limit = Integer.parseInt(limitStr);
            } catch (NumberFormatException ignored) {}
        }
        return isAllowed(key, limit);
    }
}