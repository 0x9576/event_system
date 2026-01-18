package com.example.event_system.Unit_Test;

import org.junit.jupiter.api.Test;

import com.example.event_system.domain.vo.ComplianceInfo;
import com.example.event_system.domain.vo.EventPeriod;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class ComplianceInfoTest {

    @Test
    void 준법정보_정상_생성() {
        EventPeriod period = new EventPeriod(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 31, 0, 0)
        );

        ComplianceInfo info = new ComplianceInfo(
                "CP-2026-0001",
                period
        );

        assertThat(info.reviewNumber()).isEqualTo("CP-2026-0001");
        assertThat(info.approvalPeriod()).isEqualTo(period);
    }

    @Test
    void 준법심의번호가_null이면_예외() {
        EventPeriod period = new EventPeriod(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 31, 0, 0)
        );

        assertThatThrownBy(() ->
                new ComplianceInfo(null, period)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("준법심의번호");
    }

    @Test
    void 준법심의번호가_빈문자열이면_예외() {
        EventPeriod period = new EventPeriod(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 31, 0, 0)
        );

        assertThatThrownBy(() ->
                new ComplianceInfo("   ", period)
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
