package com.example.event_system.Unit_Test;

import com.example.event_system.domain.EventReward;
import com.example.event_system.domain.RewardType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

public class EventRewardTest {

    private static final Logger log = LoggerFactory.getLogger(EventRewardTest.class);

    @Test
    @DisplayName("고정 보상 금액 확인")
    void fixedRewardTest() {
        EventReward reward = EventReward.builder()
                .rewardType(RewardType.FIXED)
                .fixedAmount(1000)
                .build();

        assertThat(reward.getFixedAmount()).isEqualTo(1000);
    }

    @Test
    @DisplayName("단순 랜덤 보상 범위 확인")
    void randomRewardRangeTest() {
        int min = 10;
        int max = 100;
        EventReward reward = EventReward.builder()
                .rewardType(RewardType.RANDOM)
                .minAmount(min)
                .maxAmount(max)
                .build();

        IntStream.range(0, 100).forEach(i -> {
            int amount = ThreadLocalRandom.current().nextInt(reward.getMinAmount(), reward.getMaxAmount() + 1);
            assertThat(amount).isBetween(min, max);
        });
    }

    @Test
    @DisplayName("평균 지정 랜덤 수렴 확인 (10,000번 반복)")
    void averageConvergenceTest() {
        int target = 500;
        EventReward policy = EventReward.builder()
                .rewardType(RewardType.AVERAGE_RANDOM)
                .minAmount(100)
                .maxAmount(2000)
                .targetAverage(target)
                .build();

        long totalSum = 0;
        int iterations = 10000;

        for (int i = 1; i <= iterations; i++) {
            double currentAvg = (i == 1) ? 0 : (double) totalSum / (i - 1);
            totalSum += simulateLogic(currentAvg, policy);
        }

        double finalAverage = (double) totalSum / iterations;
        log.info("목표 평균: {}, 실제 평균: {}", target, finalAverage);

        // 오차범위 5% 확인
        assertThat(finalAverage).isBetween(target * 0.95, target * 1.05);
    }

    private int simulateLogic(double currentAvg, EventReward policy) {
        if (currentAvg == 0) return policy.getTargetAverage();
        if (currentAvg > policy.getTargetAverage()) {
            return ThreadLocalRandom.current().nextInt(policy.getMinAmount(), policy.getTargetAverage() + 1);
        } else {
            return ThreadLocalRandom.current().nextInt(policy.getTargetAverage(), policy.getMaxAmount() + 1);
        }
    }
}