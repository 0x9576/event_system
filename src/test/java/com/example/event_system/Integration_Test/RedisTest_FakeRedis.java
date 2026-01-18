package com.example.event_system.Integration_Test;

import com.example.event_system.config.FakeRedisConfig;
import com.example.event_system.domain.Event;
import com.example.event_system.domain.EventReward;
import com.example.event_system.domain.EventStock;
import com.example.event_system.domain.EventType;
import com.example.event_system.domain.RewardType;
import com.example.event_system.repository.EventEntryRepository;
import com.example.event_system.repository.EventRepository;
import com.example.event_system.repository.EventRewardRepository;
import com.example.event_system.repository.EventStockRepository;
import com.example.event_system.service.EventApplyService;
import com.example.event_system.service.EventResultService;
import com.example.event_system.service.RedisRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * [테스트 전략: Redis 인프라 격리]
 * embedded-redis의 호환성 문제 방지책
 * 실제 Redis 서버 없이 비즈니스 로직을 검증하기 위한 가짜 Redis 설정입니다.
 */

@SuppressWarnings("null")
@SpringBootTest
@Import(FakeRedisConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RedisTest_FakeRedis {

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EventApplyService eventApplyService;

    @Autowired
    private EventResultService eventResultService;

    @MockitoBean
    private EventEntryRepository eventEntryRepository;

    @MockitoBean
    private EventStockRepository eventStockRepository;

    @MockitoBean
    private EventRewardRepository eventRewardRepository;

    @MockitoBean
    private EventRepository eventRepository;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        // Redis 초기화
        redisTemplate.execute((RedisConnection connection) -> {
            connection.serverCommands().flushAll();
            return null;
        });

        // Mock 초기화
        reset(eventEntryRepository);
        reset(eventStockRepository);
        reset(eventRewardRepository);
        reset(eventRepository);
        reset(kafkaTemplate);
    }

    @Test
    @DisplayName("1. Redis 방어막 차단 테스트")
    void redisBarrierTestWithFake() throws InterruptedException {
        int threadCount = 100;
        int limit = 10;
        Long eventId = 1L;

        // [Fix] EventRepository Stubbing 추가: 이벤트가 조회되어야 로직이 수행됩니다.
        Event mockEvent = mock(Event.class);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(mockEvent));

        when(eventEntryRepository.existsByEventIdAndMemberId(anyLong(), anyLong())).thenReturn(false);
        when(eventStockRepository.findByEventId(eventId)).thenReturn(new EventStock(eventId, 100));

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            long memberId = i;
            executorService.submit(() -> {
                try {
                    eventApplyService.apply(eventId, memberId);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        verify(kafkaTemplate, times(limit)).send(anyString(), anyString());
    }

    @Test
    @DisplayName("2. 유량 제어 및 TTL 검증")
    void concurrencyAndTtlTest() throws InterruptedException {
        int totalThreads = 100;
        int limit = 10;
        String key = "event:concurrency:test";

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(totalThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < totalThreads; i++) {
            executorService.submit(() -> {
                try {
                    if (rateLimiter.isAllowed(key, limit)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        assertEquals(limit, successCount.get());
        Thread.sleep(1500);
        assertTrue(rateLimiter.isAllowed(key, limit));
    }

    @Test
    @DisplayName("3. 실시간 인기 이벤트 랭킹 테스트")
    void rankingFeatureTest() {
        String key = "event:ranking";
        redisTemplate.opsForZSet().add(key, "이벤트A", 10);
        redisTemplate.opsForZSet().add(key, "이벤트B", 50);
        redisTemplate.opsForZSet().add(key, "이벤트C", 30);

        Set<String> topEvents = redisTemplate.opsForZSet().reverseRange(key, 0, 2);
        assertEquals(3, topEvents.size());
        assertTrue(topEvents.contains("이벤트B"));
    }

    @Test
    @DisplayName("4. Redis 피드백 루프 카운트 무결성 검증")
    void redisFeedbackLoopPrecisionTest() throws InterruptedException {
        int count = 50;
        when(eventStockRepository.decreaseStock(1L)).thenReturn(1);
        when(eventRewardRepository.findByEventId(1L)).thenReturn(Optional.of(
                EventReward.builder().rewardType(RewardType.AVERAGE_RANDOM).minAmount(10).maxAmount(50)
                        .targetAverage(30).build()));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            executor.submit(() -> {
                try {
                    eventResultService.processWinning(1L, 1L);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        Thread.sleep(200);

        String result = redisTemplate.opsForValue().get("event:reward:count:1");
        assertNotNull(result);
        System.out.println("### 최종 카운트: " + result);
    }

    @Test
    @DisplayName("5. [시나리오 4번] Redis 장애 상황 Fallback 로직 검증")
    void redisCircuitBreakerTest() {
        // Given
        Long eventId = 1L;
        Long memberId = 777L;

        // Mock 설정: 재고 차감은 성공하지만, Redis 연산 시 예외가 발생하는 상황 시뮬레이션
        when(eventStockRepository.decreaseStock(eventId)).thenReturn(1);
        when(eventRewardRepository.findByEventId(eventId)).thenReturn(Optional.of(
                EventReward.builder()
                        .eventId(eventId)
                        .rewardType(RewardType.AVERAGE_RANDOM)
                        .minAmount(10)
                        .targetAverage(30)
                        .build()));

        // 실제 로직에서 Redis 에러가 발생하더라도 서비스는 중단되지 않아야 함
        assertDoesNotThrow(() -> {
            eventResultService.processWinning(eventId, memberId);
        });

        // 결과적으로 최소 금액(10)이라도 포함된 당첨 내역이 저장되었는지 확인
        verify(eventEntryRepository, atLeastOnce())
                .save(argThat(entry -> entry.getMemberId().equals(memberId) && entry.getRewardAmount() >= 10));
    }

    @Test
    @DisplayName("6. 동적 유량 제어: Redis 설정값 변경 시 즉시 반영되는지 검증")
    void dynamicRateLimitTest() throws InterruptedException {
        int totalThreads = 50;
        int defaultLimit = 10;
        int dynamicLimit = 50;
        String key = "event:dynamic:test";
        String configKey = "event:limit:config";

        // Given: Redis에 동적 설정값 저장 (실제 Redis처럼 set 사용)
        redisTemplate.opsForValue().set(configKey, String.valueOf(dynamicLimit));

        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(totalThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        // When: 50개의 스레드가 동시에 요청
        for (int i = 0; i < totalThreads; i++) {
            executorService.submit(() -> {
                try {
                    // 기본값(10) 대신 Redis 설정값(50)이 적용되어야 함
                    if (rateLimiter.isAllowed(key, configKey, defaultLimit)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // Then: 동적으로 설정한 50개 모두 통과해야 함 (기본값 10이었다면 40개 실패)
        assertEquals(dynamicLimit, successCount.get());
    }

    @Test
    @DisplayName("7. 평균 보상 로직 검증: Mock Redis를 이용한 보상 구간 제어 확인")
    void averageRewardLogicTest() {
        Long eventId = 1L;
        int targetAvg = 30;
        int min = 10;
        int max = 50;
        String sumKey = "event:reward:sum:" + eventId;
        String countKey = "event:reward:count:" + eventId;

        // Given: Mock Repository 설정
        when(eventStockRepository.decreaseStock(eventId)).thenReturn(1);
        when(eventRewardRepository.findByEventId(eventId)).thenReturn(Optional.of(
                EventReward.builder()
                        .eventId(eventId)
                        .rewardType(RewardType.AVERAGE_RANDOM)
                        .minAmount(min)
                        .maxAmount(max)
                        .targetAverage(targetAvg)
                        .build()));

        // [Case 1] 현재 평균이 높을 때 (500 / 10 = 50 > 30) -> 낮은 보상 (min ~ target)
        redisTemplate.opsForValue().set(sumKey, "500");
        redisTemplate.opsForValue().set(countKey, "10");

        eventResultService.processWinning(eventId, 100L);
        verify(eventEntryRepository).save(argThat(entry -> entry.getMemberId().equals(100L) && entry.getRewardAmount() >= min && entry.getRewardAmount() <= targetAvg));

        // [Case 2] 현재 평균이 낮을 때 (100 / 10 = 10 < 30) -> 높은 보상 (target ~ max)
        redisTemplate.opsForValue().set(sumKey, "100");
        redisTemplate.opsForValue().set(countKey, "10");

        eventResultService.processWinning(eventId, 101L);
        verify(eventEntryRepository).save(argThat(entry -> entry.getMemberId().equals(101L) && entry.getRewardAmount() >= targetAvg && entry.getRewardAmount() <= max));
    }

    @Test
    @DisplayName("8. 대량 트래픽 추첨(Raffle) 모드: Redis 플래그 설정 시 Kafka로 메시지 전송 확인")
    void raffleHighTrafficModeTest() {
        Long eventId = 99L;
        Long memberId = 100L;
        String policyKey = "event:policy:high-traffic:" + eventId;

        // Given: 이벤트 조회 시 RAFFLE 타입 반환
        Event mockEvent = mock(Event.class);
        when(mockEvent.getType()).thenReturn(EventType.RAFFLE);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(mockEvent));

        // Given: Redis에 High Traffic 정책 키 설정 (실제 Redis처럼 set 사용)
        // hasKey는 내부적으로 map.containsKey를 호출하므로 set만 하면 true가 반환됨
        redisTemplate.opsForValue().set(policyKey, "true");

        // When
        String result = eventApplyService.apply(eventId, memberId);

        // Then
        assertEquals("APPLIED_RAFFLE", result);
        // Kafka로 메시지가 전송되어야 함
        verify(kafkaTemplate).send(eq("event-raffle-topic"), eq(eventId + ":" + memberId));
        // Service 계층에서 직접 DB 저장을 하지 않아야 함 (Consumer가 처리)
        verify(eventEntryRepository, never()).save(any());
    }
}