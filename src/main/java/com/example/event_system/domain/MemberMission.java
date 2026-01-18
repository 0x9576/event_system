package com.example.event_system.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "member_mission", indexes = {
    @Index(name = "idx_member_mission_lookup", columnList = "memberId, isCompleted")
}, uniqueConstraints = {
    // 한 유저가 동일한 미션을 중복으로 가질 수 없도록 제약 조건 추가
    @UniqueConstraint(name = "uk_member_mission", columnNames = {"memberId", "mission_id"})
})
public class MemberMission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_id", nullable = false)
    private Mission mission;

    @Column(nullable = false)
    private Long currentValue; // 현재 진행 수치

    @Column(nullable = false)
    private boolean isCompleted; // 달성 여부

    @Builder
    public MemberMission(Long memberId, Mission mission) {
        if (memberId == null || mission == null) {
            throw new IllegalArgumentException("회원 ID와 미션 정보는 필수입니다.");
        }
        this.memberId = memberId;
        this.mission = mission;
        this.currentValue = 0L;
        this.isCompleted = false;
    }

    /**
     * [도메인 로직] 진행 상황 업데이트 및 달성 체크
     * 엔티티가 스스로 상태를 판단하고 변경합니다.
     */
    public void updateProgress(Long newValue) {
        // 달성 후에도 수치는 계속 업데이트 (예: 10000보 목표인데 15000보 걸었을 때 기록 유지)
        this.currentValue = newValue;
        
        // 아직 미달성 상태이고, 목표치에 도달했다면 완료 처리
        if (!this.isCompleted && this.currentValue >= mission.getGoalValue()) {
            this.isCompleted = true;
        }
    }
}
