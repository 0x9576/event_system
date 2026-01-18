package com.example.event_system.Integration_Test;

import com.example.event_system.config.FakeRedisConfig;
import com.example.event_system.domain.Event;
import com.example.event_system.domain.EventStock;
import com.example.event_system.domain.EventType;
import com.example.event_system.domain.WinningStatus;
import com.example.event_system.domain.vo.ComplianceInfo;
import com.example.event_system.domain.vo.EventPeriod;
import com.example.event_system.repository.EventEntryRepository;
import com.example.event_system.repository.EventRepository;
import com.example.event_system.repository.EventStockRepository;
import com.example.event_system.service.EventApplyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@SuppressWarnings("null") // 테스트를 위한 null pointer 경고삭제
@Import(FakeRedisConfig.class) // 테스트를 위한 가상 Redis 설정 임포트
@EmbeddedKafka(
    partitions = 1,
    brokerProperties = { "listeners=PLAINTEXT://localhost:9092", "port=9092" },
    topics = {"event-apply-topic"}
)
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "logging.level.org.apache.kafka=ERROR"
})

class KafkaEmbeddedLogicTest {

    @Autowired private EventApplyService eventApplyService;
    @Autowired private EventEntryRepository eventEntryRepository;
    @Autowired private EventStockRepository eventStockRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // 매 테스트 시작 전 가상 Redis 초기화
        redisTemplate.execute((RedisConnection connection) -> {
            connection.serverCommands().flushAll();
            return null;
        });
        // 매 테스트 시작 전 DB 초기화
        eventEntryRepository.deleteAll();
        eventStockRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    @DisplayName("단일 응모 테스트: 메시지 발행 및 소비 로직이 정상 동작하는지 확인")
    void singleApplyTest() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        Event event = Event.builder()
                .title("Single Apply Test")
                .type(EventType.FIRST_COME)
                .maxWinners(10)
                .eventPeriod(new EventPeriod(now, now.plusDays(1)))
                .complianceInfo(new ComplianceInfo("TEST-001", new EventPeriod(now, now.plusDays(1))))
                .isDuplicateParticipationAllowed(false)
                .build();
        event = eventRepository.save(event);
        Long eventId = event.getId();
        Long memberId = 100L;
        eventStockRepository.save(new EventStock(eventId, 10));

        // When
        eventApplyService.apply(eventId, memberId);

        // Then
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                long count = eventEntryRepository.count();
                assertEquals(1, count);
                
                boolean isWinner = eventEntryRepository.findAll().stream()
                        .anyMatch(e -> e.getMemberId().equals(memberId) && e.getStatus() == WinningStatus.WIN);
                assertTrue(isWinner);
            });
    }

    @Test
    @DisplayName("동시성 테스트: 100명이 동시에 응모해도 재고(10개)만큼만 당첨되어야 한다")
    void concurrencyApplyTest() throws InterruptedException {
        // Given
        LocalDateTime now = LocalDateTime.now();
        Event event = Event.builder()
                .title("Concurrency Test")
                .type(EventType.FIRST_COME)
                .maxWinners(10)
                .eventPeriod(new EventPeriod(now, now.plusDays(1)))
                .complianceInfo(new ComplianceInfo("TEST-002", new EventPeriod(now, now.plusDays(1))))
                .isDuplicateParticipationAllowed(false)
                .build();
        event = eventRepository.save(event);
        Long eventId = event.getId();
        eventStockRepository.save(new EventStock(eventId, 10)); // 재고 10개 설정
        
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When
        for (int i = 0; i < threadCount; i++) {
            long memberId = (long) i;
            executorService.submit(() -> {
                try {
                    eventApplyService.apply(eventId, memberId);
                } catch (Exception e) {
                    System.err.println("응모 실패: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(); 

        // Then
        await()
            .atMost(Duration.ofSeconds(15)) // 동시성 처리는 조금 더 넉넉하게 대기
            .untilAsserted(() -> {
                long winCount = eventEntryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN);
                System.out.println("### 현재 실시간 당첨자 수: " + winCount);
                
                // Consumer의 DB 재고 차감 로직이 원자적이라면 정확히 10이어야 함
                assertEquals(10, winCount, "당첨자 수는 재고 수량과 일치해야 합니다.");
            });
            
        System.out.println("### 동시성 테스트 최종 성공!");
    }
}