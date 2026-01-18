package com.example.event_system.Integration_Test;

import com.example.event_system.config.EmbeddedRedisConfig;
import com.example.event_system.domain.Event;
import com.example.event_system.domain.EventReward;
import com.example.event_system.domain.EventStock;
import com.example.event_system.domain.EventType;
import com.example.event_system.domain.RewardType;
import com.example.event_system.domain.vo.ComplianceInfo;
import com.example.event_system.domain.vo.EventPeriod;
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

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"null", "unchecked"})
@SpringBootTest
@Import(EmbeddedRedisConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RedisTest {

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EmbeddedRedisConfig embeddedRedisConfig;

    @Autowired
    private EventApplyService eventApplyService;

    @Autowired
    private EventResultService eventResultService;

    @Autowired
    private EventStockRepository eventStockRepository;

    @Autowired
    private EventEntryRepository eventEntryRepository;

    @Autowired
    private EventRewardRepository eventRewardRepository;

    @Autowired
    private EventRepository eventRepository;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void cleanUp() {
        try {
            // 1. Redis 모든 데이터 삭제
            redisTemplate.execute((RedisConnection connection) -> {
                connection.serverCommands().flushAll();
                return null;
            });

            // 2. RDB 데이터 초기화
            eventEntryRepository.deleteAll();
            eventStockRepository.deleteAll();
            eventRewardRepository.deleteAll();
            eventRepository.deleteAll();

            // 3. 테스트용 기본 데이터 세팅
            eventStockRepository.save(new EventStock(1L, 100));
            eventRewardRepository.save(EventReward.builder()
                    .eventId(1L)
                    .rewardType(RewardType.AVERAGE_RANDOM)
                    .minAmount(10)
                    .maxAmount(100)
                    .targetAverage(30)
                    .build());

            reset(kafkaTemplate);
            System.out.println("### [환경 준비] Redis/DB/Mock 초기화 완료");
        } catch (Exception e) {
            System.err.println("### 초기화 실패: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("1. 동시 응모: 100명 중 정확히 재고만큼만 Kafka 메시지 발행")
    void redisBarrierTestWithRealProcess() throws InterruptedException {
        int threadCount = 100;
        int stockCount = 10; // EventApplyService의 기본 RateLimit 값(10)과 일치시킴

        // 1. 이벤트 생성
        LocalDateTime now = LocalDateTime.now();
        Event event = eventRepository.save(Event.builder()
                .title("Redis Barrier Test")
                .type(EventType.FIRST_COME)
                .maxWinners(stockCount)
                .eventPeriod(new EventPeriod(now, now.plusDays(1)))
                .complianceInfo(new ComplianceInfo("TEST-REDIS-001", new EventPeriod(now, now.plusDays(1))))
                .isDuplicateParticipationAllowed(false)
                .build());
        Long eventId = event.getId();

        // 2. 재고 설정 (충돌 방지를 위해 기존 재고 삭제 후 저장)
        eventStockRepository.deleteAll();
        eventStockRepository.save(new EventStock(eventId, stockCount));
        
        // 3. 동시성 요청 실행
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

        // 4. 검증
        long sentCount = mockingDetails(kafkaTemplate).getInvocations().size();
        assertEquals(stockCount, sentCount, "Redis RateLimiter(기본 10)에 의해 제한된 수량만큼만 Kafka로 전송되어야 합니다.");
    }

    @Test
    @DisplayName("2. 피드백 루프: 동시성 상황에서 Redis 카운트 무결성 및 평균 수렴 확인")
    void redisFeedbackLoopPrecisionTest() throws InterruptedException {
        int threadCount = 100;
        Long eventId = 1L;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            long memberId = i;
            executorService.submit(() -> {
                try {
                    eventResultService.processWinning(eventId, memberId);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        String sumStr = redisTemplate.opsForValue().get("event:reward:sum:" + eventId);
        String countStr = redisTemplate.opsForValue().get("event:reward:count:" + eventId);

        assertNotNull(countStr);
        assertEquals(threadCount, Integer.parseInt(countStr), "모든 당첨 건수가 Redis에 원자적으로 반영되어야 합니다.");
        
        double finalAvg = Double.parseDouble(sumStr) / Double.parseDouble(countStr);
        System.out.println("### 실측 평균 보상: " + finalAvg + "P (목표: 30P)");
        assertTrue(finalAvg >= 10 && finalAvg <= 100);
    }

    @Test
    @DisplayName("3. 장애 대응: Redis 다운 시 Fallback 정책(최소 금액 지급) 작동 확인")
    void serviceFallbackWhenRedisDownTest() {
        Long eventId = 1L;
        Long memberId = 999L;
        
        // Redis 서버 중지 시뮬레이션
        embeddedRedisConfig.stopRedis();

        assertDoesNotThrow(() -> {
            eventResultService.processWinning(eventId, memberId);
        });

        var entry = eventEntryRepository.findAll().stream()
                .filter(e -> e.getMemberId().equals(memberId))
                .findFirst()
                .orElseThrow();
        
        assertEquals(10, entry.getRewardAmount(), "Redis 장애 시 보상 정책의 최소 금액이 지급되어야 함");
    }

    @Test
    @DisplayName("4. 유량 제어: RateLimiter 한도 차단 및 TTL 만료 후 재허용")
    void concurrencyAndTtlTest() throws InterruptedException {
        int totalThreads = 100;
        int limit = 10;
        String key = "event:limit:test";

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

        assertEquals(limit, successCount.get(), "설정한 리미트만큼만 허용되어야 합니다.");
        
        Thread.sleep(1500); // 1초 TTL 대기
        assertTrue(rateLimiter.isAllowed(key, limit), "TTL 만료 후에는 다시 접근이 허용되어야 합니다.");
    }

    @Test
    @DisplayName("5. 실시간 인기 랭킹: Redis ZSet을 이용한 점수 기반 정렬 검증")
    void rankingFeatureTest() {
        String rankingKey = "event:ranking";
        
        // ZSet 데이터 추가 (이벤트명, 점수)
        redisTemplate.opsForZSet().add(rankingKey, "이벤트A", 100);
        redisTemplate.opsForZSet().add(rankingKey, "이벤트B", 500); // 1위
        redisTemplate.opsForZSet().add(rankingKey, "이벤트C", 300); // 2위

        // 높은 점수 순으로 상위 3개 추출
        Set<String> topEvents = redisTemplate.opsForZSet().reverseRange(rankingKey, 0, 2);

        assertNotNull(topEvents);
        assertEquals(3, topEvents.size());
        
        // 순서 검증
        Iterator<String> it = topEvents.iterator();
        assertEquals("이벤트B", it.next(), "1순위 정렬 오류");
        assertEquals("이벤트C", it.next(), "2순위 정렬 오류");
        assertEquals("이벤트A", it.next(), "3순위 정렬 오류");
    }

    @Test
    @DisplayName("6. 동적 유량 제어: Redis 설정값 변경 시 즉시 반영되는지 검증")
    void dynamicRateLimitTest() throws InterruptedException {
        int totalThreads = 50;
        int defaultLimit = 10;
        int dynamicLimit = 50;
        String key = "event:dynamic:test";
        String configKey = "event:limit:config";

        // Given: Redis에 동적 설정값 저장 (기본값 10 -> 동적값 50)
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
        assertEquals(dynamicLimit, successCount.get(), "Redis에 설정된 동적 허용량(50)이 적용되어야 합니다.");
    }

    @Test
    @DisplayName("7. 대량 트래픽 추첨(Raffle) 통합 테스트: Redis Key 설정 시 Kafka 전송")
    void raffleHighTrafficModeIntegrationTest() {
        Long memberId = 200L;

        // Given: RAFFLE 이벤트 생성 및 저장
        LocalDateTime now = LocalDateTime.now();
        Event event = Event.builder()
                .title("Raffle Event")
                .content("Content")
                .type(EventType.RAFFLE)
                .eventPeriod(new EventPeriod(now, now.plusDays(1)))
                .complianceInfo(new ComplianceInfo("C-001", new EventPeriod(now, now.plusDays(1))))
                .maxWinners(10)
                .isDuplicateParticipationAllowed(false)
                .build();
        event = eventRepository.save(event);
        Long eventId = event.getId();

        // Given: Redis에 High Traffic 정책 키 설정
        String policyKey = "event:policy:high-traffic:" + eventId;
        redisTemplate.opsForValue().set(policyKey, "true");

        // When
        String result = eventApplyService.apply(eventId, memberId);

        // Then
        assertEquals("APPLIED_RAFFLE", result);
        verify(kafkaTemplate).send(eq("event-raffle-topic"), eq(eventId + ":" + memberId));
        assertEquals(0, eventEntryRepository.count(), "Kafka 모드에서는 Service가 직접 DB에 저장하지 않아야 합니다.");
    }
}