package com.example.event_system.domain;

import java.time.LocalDateTime;

import com.example.event_system.domain.vo.ComplianceInfo;
import com.example.event_system.domain.vo.EventPeriod;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "events",
    indexes = {
        // 1. 현재 진행 중인 이벤트를 필터링하기 위한 복합 인덱스
        @Index(name = "idx_event_period", columnList = "start_date_time, end_date_time"),
        // 2. 이벤트 유형별 조회를 위한 인덱스
        @Index(name = "idx_event_type", columnList = "type")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    private EventType type;

    @Embedded
    private EventPeriod eventPeriod;

    @Embedded
    private ComplianceInfo complianceInfo;

    // 추가: 최대 당첨 가능 인원 (DDD 정합성 판단 기준)
    @Column(nullable = false)
    private Integer maxWinners;

    // 추가: 미션 달성 조건 (예: 목표 걸음 수, 달성 레벨 등) - 미션형 이벤트일 경우 사용
    private Integer targetCondition;

    private boolean isDuplicateParticipationAllowed;

    private LocalDateTime deletedAt; // 삭제 일시 기록

    /**
     * 당첨 필요 인원 계산
     */
    public int calculateNeededWinnerCount(long currentWinnerCount) {
        if (currentWinnerCount >= this.maxWinners) {
            return 0;
        }
        return this.maxWinners - (int) currentWinnerCount;
    }

    /**
     * 논리 삭제 메서드
     */
    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 삭제 여부 확인
     */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    @Builder
    public Event(String title, String content, EventType type, 
                 EventPeriod eventPeriod, ComplianceInfo complianceInfo, 
                 Integer maxWinners, Integer targetCondition, boolean isDuplicateParticipationAllowed) {
        
        validateCompliancePeriod(eventPeriod, complianceInfo);
        validateMaxWinners(maxWinners);

        this.title = title;
        this.content = content;
        this.type = type;
        this.eventPeriod = eventPeriod;
        this.complianceInfo = complianceInfo;
        this.maxWinners = maxWinners;
        this.targetCondition = targetCondition;
        this.isDuplicateParticipationAllowed = isDuplicateParticipationAllowed;
    }

    private void validateCompliancePeriod(EventPeriod eventPeriod, ComplianceInfo complianceInfo) {
        if (eventPeriod != null && complianceInfo != null && 
            !eventPeriod.isWithin(complianceInfo.approvalPeriod())) {
            throw new IllegalStateException("이벤트 기간은 준법 승인 기간 내에 있어야 합니다.");
        }
    }

    private void validateMaxWinners(Integer maxWinners) {
        if (maxWinners == null || maxWinners < 0) {
            throw new IllegalArgumentException("최대 당첨 인원은 필수이며, 0명 이상이어야 합니다.");
        }
    }
}