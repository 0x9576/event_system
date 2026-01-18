package com.example.event_system.Integration_Test;

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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.LocalDateTime;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * [ Kafka 실서버 신뢰성 및 장애 복구 테스트 ]
 * * 이 테스트는 실제 Kafka 브로커와의 통신을 전제로 합니다.
 * * 1. 테스트 전제 조건:
 * - 로컬 또는 외부 Kafka 서버가 실행 중이어야 합니다. (Port: 9092)
 * - Topic 'event-apply-topic'이 생성되어 있어야 합니다.
 * * 2. 테스트 방법:
 * - 정상 상황: 메시지 전송 후 Consumer가 정상적으로 DB에 반영하는지 확인.
 * - 장애 상황: Kafka를 강제로 중단시킨 후, 서비스의 Fail-back 로직(로그 기록 등)이 작동하는지 확인.
 * * 3. 주의사항:
 * - CI/CD 환경(예: Github Actions)에서 실행 시 Kafka 컨테이너 설정이 필요합니다.
 * - 인프라 상황에 따라 실행 속도가 느려질 수 있으므로 평소에는 @Disabled 처리를 권장합니다.
 */

@Disabled
@SuppressWarnings("null") // 테스트를 위한 null pointer 경고삭제
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=localhost:9092")
class KafkaRealServerReliabilityTest {

    @Autowired
    private EventApplyService eventApplyService;

    @Autowired
    private EventEntryRepository eventEntryRepository;

    @Autowired
    private EventStockRepository eventStockRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    private Long savedEventId;

    @BeforeEach
    void setUp() {
        eventEntryRepository.deleteAll();
        eventStockRepository.deleteAll();
        eventRepository.deleteAll();

        LocalDateTime now = LocalDateTime.now();

        Event event = eventRepository.save(Event.builder()
                .title("Real Kafka Test")
                .type(EventType.FIRST_COME) // Kafka 발행을 위해 선착순 타입 지정
                .maxWinners(100)
                .eventPeriod(new EventPeriod(now, now.plusDays(1)))
                .complianceInfo(new ComplianceInfo("TEST-REAL-KAFKA", new EventPeriod(now, now.plusDays(1))))
                .isDuplicateParticipationAllowed(false)
                .build());
        savedEventId = event.getId();

        eventStockRepository.save(new EventStock(savedEventId, 100));
        System.out.println("[SETUP] ID " + savedEventId + "에 대한 재고 100개를 DB에 생성했습니다.");
    }

    @Test
    @DisplayName("Real Kafka: Consumer 중단 중에도 데이터가 유실되지 않고 복구되는지 확인")
    void realKafkaPersistenceTest() {
        // Given
        Long eventId = savedEventId;
        int applyCount = 5;

        // 1. [장애 상황] 애플리케이션의 Consumer(리스너)만 강제 중지
        registry.getListenerContainers().forEach(MessageListenerContainer::stop);
        System.out.println("[NOTICE] Consumer 중지됨. 메시지는 Kafka 서버에 쌓이는 중...");

        // 2. [데이터 전송] 5명의 신청 메시지 발행
        for (long i = 1; i <= applyCount; i++) {
            eventApplyService.apply(eventId, i);
        }

        // 3. [중간 확인] Consumer가 꺼져있으므로 DB는 0명이어야 함
        long currentCount = eventEntryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN);
        assertEquals(0, currentCount, "Consumer가 정지된 동안에는 DB에 데이터가 쌓이면 안 됩니다.");
        System.out.println("[CHECK] DB 당첨자 수: " + currentCount + " (정상적으로 처리 지연 중)");

        // 4. [복구 시나리오] Consumer 재시작
        System.out.println("[RECOVERY] Consumer 재가동! Kafka로부터 밀린 데이터를 가져옵니다...");
        registry.getListenerContainers().forEach(MessageListenerContainer::start);

        // 5. [결과 검증] Awaitility로 데이터 복구 확인
        // Kafka 리밸런싱 및 메시지 처리를 위해 시간을 넉넉히 20초로 잡습니다.
        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofSeconds(1)) // 1초마다 확인
                .until(() -> {
                    long count = eventEntryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN);
                    System.out.println("복구 중... 현재 DB 당첨자 수: " + count);
                    return count;
                }, equalTo((long) applyCount));

        System.out.println("[SUCCESS] 실제 Kafka 서버를 통한 " + applyCount + "명 데이터 복구 확인 완료!");
    }
}