package com.example.event_system.dto;

import com.example.event_system.domain.EventType;
import java.time.LocalDateTime;

/**
 * 이벤트 생성을 위한 요청 DTO
 */
public record EventCreateRequest(
    String title,
    String content,
    EventType type,

    // Event 자체 진행 기간 (EventPeriod)
    LocalDateTime startDateTime,
    LocalDateTime endDateTime,

    // 준법 승인 정보 (ComplianceInfo)
    String reviewNumber,
    LocalDateTime approvalStartDateTime, // 준법 승인 시작일
    LocalDateTime approvalEndDateTime,   // 준법 승인 종료일

    Integer maxWinners,                  // 최대 당첨 가능 인원
    boolean isDuplicateParticipationAllowed
) {}