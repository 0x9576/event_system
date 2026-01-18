package com.example.event_system.Unit_Test;

import org.junit.jupiter.api.Test;

import com.example.event_system.domain.vo.EventPeriod;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class EventPeriodTest {

    @Test
    void 종료일이_시작일보다_빠르면_예외() {
        assertThatThrownBy(() ->
                new EventPeriod(
                        LocalDateTime.of(2026, 1, 10, 0, 0),
                        LocalDateTime.of(2026, 1, 5, 0, 0)
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 기간을_포함하면_true() {
        EventPeriod base = new EventPeriod(
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 31, 0, 0)
        );

        EventPeriod target = new EventPeriod(
                LocalDateTime.of(2026, 1, 10, 0, 0),
                LocalDateTime.of(2026, 1, 20, 0, 0)
        );

        assertThat(target.isWithin(base)).isTrue();
    }
}
