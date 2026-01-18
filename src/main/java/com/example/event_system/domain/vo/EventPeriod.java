package com.example.event_system.domain.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDateTime;

@Embeddable
public record EventPeriod(
    @Column(nullable = false) LocalDateTime startDateTime,
    @Column(nullable = false) LocalDateTime endDateTime
) {
    public EventPeriod {
        if (startDateTime != null && endDateTime != null && startDateTime.isAfter(endDateTime)) {
            throw new IllegalArgumentException("시작일은 종료일보다 빨라야 합니다.");
        }
    }

    public boolean isWithin(EventPeriod other) {
        return !this.startDateTime.isBefore(other.startDateTime) 
            && !this.endDateTime.isAfter(other.endDateTime);
    }
}