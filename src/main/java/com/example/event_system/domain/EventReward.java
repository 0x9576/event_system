package com.example.event_system.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "event_reward")
public class EventReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    private RewardType rewardType;

    private Integer minAmount;     // RANDOM, AVERAGE_RANDOM 시 사용
    private Integer maxAmount;     // RANDOM, AVERAGE_RANDOM 시 사용
    private Integer targetAverage; // AVERAGE_RANDOM 시 목표 평균값
    private Integer fixedAmount;   // FIXED 시 사용

    @Builder
    public EventReward(Long eventId, RewardType rewardType, Integer minAmount, 
                       Integer maxAmount, Integer targetAverage, Integer fixedAmount) {
        this.eventId = eventId;
        this.rewardType = rewardType;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.targetAverage = targetAverage;
        this.fixedAmount = fixedAmount;
    }
}