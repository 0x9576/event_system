package com.example.event_system.Unit_Test;

import com.example.event_system.domain.Event;
import com.example.event_system.domain.EventType;
import com.example.event_system.domain.vo.ComplianceInfo;
import com.example.event_system.domain.vo.EventPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EventTest {

    private LocalDateTime now;
    private EventPeriod validEventPeriod;
    private ComplianceInfo validComplianceInfo;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now();
        // 이벤트 기간: 오늘부터 5일간
        validEventPeriod = new EventPeriod(now, now.plusDays(5));
        // 준법 승인 기간: 어제부터 10일간 (이벤트 기간을 포함하도록 설정)
        validComplianceInfo = new ComplianceInfo("COMP-2024-001", 
            new EventPeriod(now.minusDays(1), now.plusDays(10)));
    }

    @Test
    @DisplayName("성공: 모든 도메인 규칙을 만족하면 이벤트가 생성된다")
    void createEvent_Success() {
        // When
        Event event = Event.builder()
                .title("신년 맞이 선착순 이벤트")
                .content("내용")
                .type(EventType.RAFFLE)
                .eventPeriod(validEventPeriod)
                .complianceInfo(validComplianceInfo)
                .maxWinners(100)
                .isDuplicateParticipationAllowed(false)
                .build();

        // Then
        assertNotNull(event);
        assertEquals("신년 맞이 선착순 이벤트", event.getTitle());
        // Record 타입인 ComplianceInfo의 데이터 확인
        assertEquals("COMP-2024-001", event.getComplianceInfo().reviewNumber());
    }

    

    @Test
    @DisplayName("실패: 이벤트 기간이 준법 승인 기간(ComplianceInfo)을 벗어나면 생성 시 예외가 발생한다")
    void createEvent_Fail_PeriodViolation() {
        // Given: 이벤트 기간은 10일간인데, 준법 승인은 3일만 난 경우
        EventPeriod longEventPeriod = new EventPeriod(now, now.plusDays(10));
        ComplianceInfo shortCompliance = new ComplianceInfo("COMP-SHORT", 
            new EventPeriod(now, now.plusDays(3)));

        // When & Then: 생성자 내부의 validateCompliancePeriod 로직 검증
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            Event.builder()
                    .title("실패하는 이벤트")
                    .eventPeriod(longEventPeriod)
                    .complianceInfo(shortCompliance)
                    .maxWinners(100)
                    .build();
        });

        assertEquals("이벤트 기간은 준법 승인 기간 내에 있어야 합니다.", exception.getMessage());
    }

    @Test
    @DisplayName("성공: delete() 호출 시 Soft Delete 상태로 전이된다")
    void softDelete_Logic() {
        // Given
        Event event = Event.builder()
                .title("삭제 대상")
                .maxWinners(10)
                .build();

        // When
        event.delete();

        // Then
        assertTrue(event.isDeleted(), "isDeleted()는 true를 반환해야 함");
        assertNotNull(event.getDeletedAt(), "deletedAt에 기록이 남아야 함");
    }

    @Test
    @DisplayName("성공: 준법 정보가 없는 이벤트(테스트용 등)도 생성이 가능해야 한다 (Null Safety)")
    void createEvent_NullSafety() {
        // Given & When: 준법 정보 없이 생성 시도
        Event event = Event.builder()
                .title("임시 이벤트")
                .maxWinners(10)
                .build();

        // Then: validateCompliancePeriod의 null 체크 로직에 의해 통과됨
        assertNotNull(event);
        assertNull(event.getComplianceInfo());
    }

    @Test
    @DisplayName("실패: 최대 당첨 인원이 없거나 음수면 예외가 발생한다")
    void createEvent_Fail_MaxWinners() {
        // Case 1: Null Check
        assertThrows(IllegalArgumentException.class, () -> {
            Event.builder().title("인원 미설정").maxWinners(null).build();
        });

        // Case 2: Negative Check
        assertThrows(IllegalArgumentException.class, () -> {
            Event.builder().title("음수 인원").maxWinners(-1).build();
        });
    }

    @Test
    @DisplayName("성공: 남은 당첨 인원 계산 로직(calculateNeededWinnerCount) 검증")
    void calculateNeededWinnerCount_Logic() {
        // Given
        int max = 10;
        Event event = Event.builder()
                .title("계산 테스트")
                .maxWinners(max)
                .build();

        // When & Then
        assertEquals(10, event.calculateNeededWinnerCount(0), "참여자가 없으면 전체 인원 반환");
        assertEquals(5, event.calculateNeededWinnerCount(5), "5명 당첨 시 5명 남음");
        assertEquals(0, event.calculateNeededWinnerCount(10), "꽉 찼으면 0명");
        assertEquals(0, event.calculateNeededWinnerCount(15), "초과 시에도 0명 반환 (음수 방지)");
    }
}