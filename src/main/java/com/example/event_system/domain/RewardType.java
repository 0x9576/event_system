package com.example.event_system.domain;

import lombok.Getter;

@Getter
public enum RewardType {
    FIXED,          // 고정 지급
    RANDOM,         // 단순 랜덤 (min ~ max)
    AVERAGE_RANDOM  // 평균 지정 랜덤 (실시간 피드백 루프 활용)
}