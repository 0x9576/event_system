package com.example.event_system.Integration_Test;

import com.example.event_system.config.FakeRedisConfig;
import com.example.event_system.domain.*;
import com.example.event_system.dto.EventCreateRequest;
import com.example.event_system.repository.*;
import com.example.event_system.service.*;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SuppressWarnings("null")
@SpringBootTest
@Import(FakeRedisConfig.class) // 테스트를 위한 가상 Redis 설정 임포트
class EventBatchTest {

    @Autowired
    private EventBatchService batchService;

    @Autowired
    private EventService eventService;

    @Autowired
    private EventApplyService eventApplyService;

    @Autowired
    private EventEntryRepository entryRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private EventLockRepository eventLockRepository;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void clear() {
        entryRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
    }

    /**
     * 공통 메서드 수정: maxWinners를 파라미터로 받도록 변경
     */
    private Long createTestEvent(String title, int maxWinners) {
        EventCreateRequest request = new EventCreateRequest(
                title,
                "테스트 내용",
                EventType.RAFFLE,
                LocalDateTime.now(),
                LocalDateTime.now().plusDays(7),
                "COMP-TEST-CODE",
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusMonths(1),
                maxWinners, // [수정] maxWinners 추가
                false
        );
        return eventService.createEvent(request);
    }

    private void insertBulkEntries(Long eventId, int count) {
        String sql = "INSERT INTO event_entry (event_id, member_id, status, reward_amount) VALUES (?, ?, 'PENDING', 0)";
        jdbcTemplate.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setLong(1, eventId);
                ps.setLong(2, (long) i);
            }
            @Override
            public int getBatchSize() { return count; }
        });
    }

    @Test
    @DisplayName("1. 트랜잭션 롤백 테스트: 락 생성 실패 시 이벤트 저장도 취소되어야 함")
    void transactionRollbackTest() {
        doThrow(new RuntimeException("LOCK_DB_FAILURE"))
                .when(eventLockRepository).save(any(EventLock.class));

        EventCreateRequest request = new EventCreateRequest(
                "ROLLBACK_EVENT", "내용", EventType.RAFFLE,
                LocalDateTime.now(), LocalDateTime.now().plusDays(1),
                "COMP-ROLL-001", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusMonths(1),
                100, false
        );

        try {
            eventService.createEvent(request);
        } catch (Exception e) {
            System.out.println("### 예외 포착: " + e.getMessage());
        }

        long count = eventRepository.findAll().stream()
                .filter(e -> e.getTitle().equals("ROLLBACK_EVENT"))
                .count();
        assertEquals(0, count);
    }

    @Test
    @DisplayName("2. 도메인 검증: 정원이 0명일 때 당첨자가 0명이어야 함")
    void invalidMaxWinnersTest() {
        // Given: 정원을 0으로 설정하여 이벤트 생성
        Long eventId = createTestEvent("정원 0명 테스트", 0);
        for (int i = 0; i < 10; i++) {
            eventApplyService.apply(eventId, (long) i);
        }

        // When: 추첨 실행 (인자에서 limit 제거됨)
        int processed = batchService.drawWinnersRandomly(eventId);

        // Then
        assertEquals(0, processed);
        long winCount = entryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN);
        assertEquals(0, winCount);
    }

    @Test
    @DisplayName("3. 사후 추첨 배치: 정원 30명 설정 시 30명만 랜덤 당첨 처리한다")
    void 배치를_통해_지정된_수만큼_당첨자를_확정한다() {
        // Given: 정원을 30으로 설정
        Long eventId = createTestEvent("기본 추첨 테스트", 30);
        for (int i = 0; i < 50; i++) {
            eventApplyService.apply(eventId, (long) i);
        }

        // When
        int processedCount = batchService.drawWinnersRandomly(eventId);

        // Then
        assertEquals(30, processedCount);
        assertEquals(30, entryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN));
    }

    @Test
    @DisplayName("4. 동시성 테스트: 두 개의 스레드가 동시에 배치를 실행해도 정원을 초과하지 않아야 함")
    void concurrencyTest() throws InterruptedException {
        // Given: 정원을 20으로 설정
        int targetLimit = 20;
        Long eventId = createTestEvent("동시성 제어 테스트", targetLimit);

        for (int i = 0; i < 50; i++) {
            eventApplyService.apply(eventId, (long) i);
        }
        entryRepository.flush();

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        // When
        for (int i = 0; i < 2; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    batchService.drawWinnersRandomly(eventId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();

        // Then
        long finalCount = entryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN);
        assertEquals(targetLimit, (int) finalCount);
    }

    @Test
    @DisplayName("5. 에지 케이스: 응모자가 정원보다 적을 때 전원 당첨 처리 확인")
    void insufficientApplicantsTest() {
        // Given: 정원 100명, 실제 응모 40명
        Long eventId = createTestEvent("응모자 부족 테스트", 100);
        int actualApplicants = 40;

        for (int i = 0; i < actualApplicants; i++) {
            eventApplyService.apply(eventId, (long) i);
        }

        // When
        int processed = batchService.drawWinnersRandomly(eventId);

        // Then
        assertEquals(actualApplicants, processed);
        assertEquals(actualApplicants, entryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN));
    }

    @Test
    @DisplayName("6. 부하 테스트: 10만 건 데이터 중 1만 명 추첨 성능 측정")
    void loadTest_100k_Entries() {
        int winnerCount = 10000;
        Long eventId = createTestEvent("대용량 부하 테스트", winnerCount);
        int totalCount = 100000;

        insertBulkEntries(eventId, totalCount);

        long startTime = System.currentTimeMillis();
        int processed = batchService.drawWinnersRandomly(eventId);
        long endTime = System.currentTimeMillis();
        
        System.out.println("### 10만 건 소요 시간: " + (endTime - startTime) + "ms");
        assertEquals(winnerCount, processed);
        assertTrue((endTime - startTime) < 10000); // 10초 이내 완료 검증
    }

    @Test
    @DisplayName("7. 선착순 당첨 처리: ID가 빠른 순서대로 당첨되어야 한다")
    void fcfsWinningTest() {
        // Given
        Long eventId = createTestEvent("선착순 이벤트", 5);
        // 10명 등록 (ID 0~9 가정)
        for (int i = 0; i < 10; i++) {
            eventApplyService.apply(eventId, (long) i);
        }

        // When: 5명 선착순 처리
        int processed = batchService.processWinners(eventId, 5);

        // Then
        assertEquals(5, processed);

        // 당첨된 목록 조회
        List<EventEntry> winners = entryRepository.findByEventIdAndStatus(eventId, WinningStatus.WIN, PageRequest.of(0, 10));
        assertEquals(5, winners.size());

        // 먼저 등록된 멤버(0~4)가 당첨되었는지 확인 (ID 오름차순 보장 확인)
        boolean allEarlyMembersWon = winners.stream()
                .allMatch(e -> e.getMemberId() < 5);
        assertTrue(allEarlyMembersWon, "먼저 응모한 멤버(ID 0~4)가 당첨되어야 합니다.");
    }

    @Test
    @DisplayName("8. 예외 처리: 존재하지 않는 이벤트 ID로 배치를 실행하면 실패해야 한다")
    void eventNotFoundExceptionTest() {
        assertThrows(EntityNotFoundException.class, () -> {
            batchService.drawWinnersRandomly(-999L);
        });
    }

    @Test
    @DisplayName("9. 멱등성 테스트: 이미 정원이 찬 이벤트에 대해 배치를 반복 실행해도 추가 당첨자가 없어야 한다")
    void idempotencyTest() {
        // Given
        Long eventId = createTestEvent("멱등성 테스트", 10);
        insertBulkEntries(eventId, 20); // 20명 응모

        // When 1: 1차 실행 (정원 달성)
        int firstRun = batchService.drawWinnersRandomly(eventId);
        assertEquals(10, firstRun);

        // When 2: 2차 실행 (이미 완료됨)
        int secondRun = batchService.drawWinnersRandomly(eventId);

        // Then
        assertEquals(0, secondRun, "이미 정원이 찼으므로 0명이 처리되어야 함");
        long totalWinners = entryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN);
        assertEquals(10, totalWinners);
    }

    @Test
    @DisplayName("10. 대량 업데이트 분할 처리: 당첨자가 1000명을 넘을 때(Batch Size 초과) 정상 처리 확인")
    void largeBatchUpdateTest() {
        // Given: 당첨 정원 2500명 (updateInPartitions의 batchSize 1000을 넘기는 수치)
        int maxWinners = 2500;
        Long eventId = createTestEvent("대량 업데이트 테스트", maxWinners);
        insertBulkEntries(eventId, 3000); // 3000명 응모

        // When
        int processed = batchService.drawWinnersRandomly(eventId);

        // Then
        assertEquals(maxWinners, processed);
        assertEquals(maxWinners, entryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN));
    }

    @Test
    @DisplayName("11. 동시성 확장 테스트: 서로 다른 이벤트는 락이 독립적이므로 병렬 처리가 가능하다")
    void parallelProcessingDifferentEventsTest() throws InterruptedException {
        // Given: 서로 다른 이벤트 2개 생성
        Long eventId1 = createTestEvent("이벤트 A", 10);
        Long eventId2 = createTestEvent("이벤트 B", 10);

        insertBulkEntries(eventId1, 50);
        insertBulkEntries(eventId2, 50);

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        // When: 별도의 스레드에서 동시에 실행 (서로 다른 락 키를 사용하므로 블로킹되지 않음)
        executorService.submit(() -> {
            try {
                batchService.drawWinnersRandomly(eventId1);
            } finally {
                latch.countDown();
            }
        });
        executorService.submit(() -> {
            try {
                batchService.drawWinnersRandomly(eventId2);
            } finally {
                latch.countDown();
            }
        });

        latch.await();

        // Then: 두 이벤트 모두 정상적으로 당첨 처리가 완료되어야 함
        assertEquals(10, entryRepository.countByEventIdAndStatus(eventId1, WinningStatus.WIN));
        assertEquals(10, entryRepository.countByEventIdAndStatus(eventId2, WinningStatus.WIN));
    }
}