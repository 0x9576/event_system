package com.example.event_system.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;


/**
 * [테스트 전략: Redis 인프라 격리]
 * embedded-redis의 호환성 문제 방지책
 * 실제 Redis 서버 없이 비즈니스 로직을 검증하기 위한 가짜 Redis 설정입니다.
 */

@SuppressWarnings("all")
@TestConfiguration
public class FakeRedisConfig {

    @Bean
    @Primary // 실제 Redis 대신 이 Mock을 우선 주입
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate mockTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> mockValues = mock(ValueOperations.class);
        ZSetOperations<String, String> mockZSet = mock(ZSetOperations.class);

        // [Key-Value Store] 실제 Redis처럼 키별로 값을 관리하는 Map (Thread-Safe)
        Map<String, String> redisStore = new ConcurrentHashMap<>();

        when(mockTemplate.opsForValue()).thenReturn(mockValues);
        when(mockTemplate.opsForZSet()).thenReturn(mockZSet);

        // 1. hasKey 구현
        when(mockTemplate.hasKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return redisStore.containsKey(key);
        });

        // 2. set 구현
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            redisStore.put(key, value);
            return null;
        }).when(mockValues).set(anyString(), anyString());

        // 3. get 구현
        when(mockValues.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return redisStore.get(key);
        });

        // 4. increment (delta=1)
        when(mockValues.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return incrementValue(redisStore, key, 1L);
        });

        // 5. increment (delta=N)
        when(mockValues.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long delta = invocation.getArgument(1);
            return incrementValue(redisStore, key, delta);
        });

        // 6. expire 구현 (비동기 삭제)
        when(mockTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long timeout = invocation.getArgument(1);
            TimeUnit unit = invocation.getArgument(2);

            // 테스트 환경이므로 지연 실행을 통해 만료 시뮬레이션
            CompletableFuture.delayedExecutor(timeout, unit)
                .execute(() -> redisStore.remove(key));
            return true;
        });

        // 7. ZSet (Mock 유지)
        when(mockZSet.reverseRange(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of("이벤트B", "이벤트C", "이벤트A")); // B가 1등인 상황 가정

        return mockTemplate;
    }

    // Map 기반 원자적 증가 로직
    private Long incrementValue(Map<String, String> store, String key, long delta) {
        return Long.parseLong(store.compute(key, (k, v) -> {
            long val = (v == null) ? 0 : Long.parseLong(v);
            return String.valueOf(val + delta);
        }));
    }
}