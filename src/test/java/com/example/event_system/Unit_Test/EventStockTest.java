package com.example.event_system.Unit_Test;

import com.example.event_system.domain.EventStock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventStockTest {

    @Test
    @DisplayName("성공: 재고가 있을 때 차감하면 stockCount가 1 줄어든다")
    void decrease_Success() {
        // Given
        EventStock stock = new EventStock(1L, 10);

        // When
        stock.decrease();

        // Then
        assertEquals(9, stock.getStockCount());
    }

    @Test
    @DisplayName("실패: 재고가 0일 때 차감하면 IllegalStateException이 발생한다")
    void decrease_Fail_OutOfStock() {
        // Given
        EventStock stock = new EventStock(1L, 0);

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            stock.decrease();
        });
        assertEquals("남은 재고가 없습니다. (eventId: 1)", exception.getMessage());
    }

    @Test
    @DisplayName("실패: 초기 재고를 음수로 설정하면 생성 시 IllegalArgumentException이 발생한다")
    void constructor_Fail_NegativeStock() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            new EventStock(1L, -1);
        });
    }

    @Test
    @DisplayName("성공: 재고 유무 확인 로직이 정확히 동작한다")
    void hasStock_Logic() {
        EventStock stockWithItems = new EventStock(1L, 1);
        EventStock emptyStock = new EventStock(2L, 0);

        assertTrue(stockWithItems.hasStock());
        assertFalse(emptyStock.hasStock());
    }

    @Test
    @DisplayName("성공: 옵션명을 지정하여 EventStock을 생성할 수 있다")
    void constructor_Success_WithOptionName() {
        // Given
        String optionName = "Option A";
        EventStock stock = new EventStock(1L, optionName, 10);

        // Then
        assertEquals(optionName, stock.getOptionName());
        assertEquals(10, stock.getStockCount());
    }

    @Test
    @DisplayName("성공: 기본 생성자 호출 시 옵션명이 DEFAULT로 설정된다")
    void constructor_DefaultOption() {
        EventStock stock = new EventStock(1L, 10);
        assertEquals("DEFAULT", stock.getOptionName());
    }

    @Test
    @DisplayName("실패: 옵션명이 없으면 IllegalArgumentException이 발생한다")
    void constructor_Fail_InvalidOptionName() {
        assertThrows(IllegalArgumentException.class, () -> new EventStock(1L, null, 10));
        assertThrows(IllegalArgumentException.class, () -> new EventStock(1L, "", 10));
        assertThrows(IllegalArgumentException.class, () -> new EventStock(1L, "   ", 10));
    }

    @Test
    @DisplayName("성공: 재고 보충(increase) 로직 검증")
    void increase_Logic() {
        EventStock stock = new EventStock(1L, 10);
        stock.increase(5);
        assertEquals(15, stock.getStockCount());

        assertThrows(IllegalArgumentException.class, () -> stock.increase(0));
        assertThrows(IllegalArgumentException.class, () -> stock.increase(-1));
    }
}