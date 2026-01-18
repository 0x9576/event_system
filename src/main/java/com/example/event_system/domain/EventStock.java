package com.example.event_system.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "event_stock", uniqueConstraints = {
    @UniqueConstraint(name = "uk_event_stock_option", columnNames = {"eventId", "optionName"})
})
public class EventStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 1:N 구조
    @Column(nullable = false)
    private Long eventId;

    // 경품 옵션 구분 (예: "A타입", "B타입" 등)
    @Column(nullable = false)
    private String optionName;

    @Column(nullable = false)
    private Integer stockCount;

    /**
     * 초기 재고 설정을 위한 생성자
     * (기본 옵션용 - 하위 호환성 유지)
     */
    public EventStock(Long eventId, Integer stockCount) {
        this(eventId, "DEFAULT", stockCount);
    }

    /**
     * 옵션별 재고 설정을 위한 생성자
     */
    public EventStock(Long eventId, String optionName, Integer stockCount) {
        if (stockCount == null || stockCount < 0) {
            throw new IllegalArgumentException("초기 재고는 0 이상이어야 합니다.");
        }
        if (optionName == null || optionName.isBlank()) {
            throw new IllegalArgumentException("옵션 명은 필수입니다.");
        }
        this.eventId = eventId;
        this.optionName = optionName;
        this.stockCount = stockCount;
    }

    /**
     * 재고가 남아있는지 확인합니다.
     */
    public boolean hasStock() {
        return this.stockCount != null && this.stockCount > 0;
    }

    /**
     * 재고를 1개 차감합니다. 
     * 도메인 모델 내에서 상태 변경의 정당성을 체크합니다.
     */
    public void decrease() {
        if (!hasStock()) {
            throw new IllegalStateException("남은 재고가 없습니다. (eventId: " + this.eventId + ")");
        }
        this.stockCount--;
    }

    /**
     * (선택사항) 관리자 기능을 위한 재고 보충 로직
     */
    public void increase(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("보충할 재고는 1개 이상이어야 합니다.");
        }
        this.stockCount += count;
    }
}