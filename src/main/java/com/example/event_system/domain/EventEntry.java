package com.example.event_system.domain;

import com.example.event_system.domain.vo.ApplicantContact;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "event_entry", indexes = {
    @Index(name = "idx_event_member", columnList = "eventId, memberId"),
    @Index(name = "idx_event_status", columnList = "eventId, status")
})
public class EventEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private Long memberId;

    // 지급된 보상 포인트 금액
    @Column(nullable = false)
    private int rewardAmount;

    @Embedded
    private ApplicantContact contact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WinningStatus status;

    // 데이터 생성 시간 (정산 및 로그용)
    private LocalDateTime createdAt;

    @Builder
    public EventEntry(Long eventId, Long memberId, ApplicantContact contact, WinningStatus status, int rewardAmount) {
        if (eventId == null || memberId == null) {
            throw new IllegalArgumentException("이벤트 ID와 회원 ID는 필수값입니다.");
        }
        this.eventId = eventId;
        this.memberId = memberId;
        this.contact = contact;
        this.status = status == null ? WinningStatus.PENDING : status;
        this.rewardAmount = rewardAmount;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 당첨 상태로 변경하며 보상 금액을 할당합니다.
     * 금액을 지정하지 않으면 기본값인 0이 할당됩니다.
     */
    public void assignWinner() {
        assignWinner(0); // 기본값 0 사용
    }

    /**
     * 특정 금액을 지정하여 당첨 처리를 합니다.
     */
    public void assignWinner(int amount) {
        if (this.status != WinningStatus.PENDING) {
            throw new IllegalStateException("이미 당첨 여부가 결정된 응모 내역입니다.");
        }
        this.status = WinningStatus.WIN;
        this.rewardAmount = amount;
    }

    /**
     * 이벤트 삭제 시 등, 개인정보 파기 (개인정보 보호법 준수)
     */
    public void clearContactInfo() {
        this.contact = null;
    }
}