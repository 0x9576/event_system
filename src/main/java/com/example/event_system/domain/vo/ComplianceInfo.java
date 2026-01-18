package com.example.event_system.domain.vo;

import jakarta.persistence.*;

@Embeddable
public record ComplianceInfo(
    @Column(name = "compliance_review_number")
    String reviewNumber,
    
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "startDateTime", column = @Column(name = "compliance_start_date")),
        @AttributeOverride(name = "endDateTime", column = @Column(name = "compliance_end_date"))
    })
    EventPeriod approvalPeriod
) {
    public ComplianceInfo {
        if (reviewNumber == null || reviewNumber.isBlank()) {
            throw new IllegalArgumentException("준법심의번호는 필수입니다.");
        }
    }
}