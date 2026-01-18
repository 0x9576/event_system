package com.example.event_system.service;

import com.example.event_system.domain.EventEntry;
import com.example.event_system.domain.EventReward;
import com.example.event_system.domain.WinningStatus;
import com.example.event_system.repository.EventEntryRepository;
import com.example.event_system.repository.EventRewardRepository;
import com.example.event_system.repository.EventStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventResultService {

    private final EventEntryRepository entryRepository;
    private final EventStockRepository stockRepository;
    private final EventRewardRepository rewardRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * Kafka Consumer로부터 호출되는 실제 당첨 처리 로직
     */
    @Transactional
    public void processWinning(Long eventId, Long memberId) {
        // 1. 실제 DB 재고 차감 (Atomic Update 쿼리 사용)
        // 쿼리 예시: UPDATE event_stock SET stock_count = stock_count - 1 WHERE event_id = :eventId AND stock_count > 0
        int updatedRows = stockRepository.decreaseStock(eventId);

        if (updatedRows > 0) {
            // 2. 보상 정책 조회
            EventReward rewardPolicy = rewardRepository.findByEventId(eventId)
                    .orElseGet(() -> null); // 정책이 없으면 기본 처리

            // 3. 보상 금액 계산
            int finalReward = calculateRewardAmount(eventId, rewardPolicy);

            // 4. 당첨 내역 저장
            EventEntry entry = EventEntry.builder()
                    .eventId(eventId)
                    .memberId(memberId)
                    .status(WinningStatus.WIN)
                    .rewardAmount(finalReward)
                    .build();

            entryRepository.save(Objects.requireNonNull(entry));
            log.info("### [최종 승인] 이벤트:{}, 회원:{}, 보상:{}P", eventId, memberId, finalReward);
        } else {
            log.warn("### [재고 소진] 이벤트:{}, 회원:{} - 처리 실패", eventId, memberId);
        }
    }

    private int calculateRewardAmount(Long eventId, EventReward policy) {
        if (policy == null) return 1; // 정책 미설정 시 기본 1포인트

        return switch (policy.getRewardType()) {
            case FIXED -> policy.getFixedAmount();
            case RANDOM -> ThreadLocalRandom.current().nextInt(policy.getMinAmount(), policy.getMaxAmount() + 1);
            case AVERAGE_RANDOM -> calculateAverageFeedbackReward(eventId, policy);
            default -> 1;
        };
    }

    /**
     * [설계 의도]
     * 단순 랜덤(min~max) 방식은 평균값이 (min+max)/2로 고정되어,
     * 낮은 평균을 유지하려면 최대 당첨금도 낮게 설정해야 하는 한계가 있습니다.
     * 이 로직은 실시간으로 지급된 평균을 추적하여,
     * 목표 평균보다 높으면 낮은 금액 구간에서, 낮으면 높은 금액 구간에서 랜덤 추출합니다.
     * 이를 통해 '최소 1P ~ 최대 2000P'와 같이 넓은 보상 범위를 제공하면서도,
     * 최종적으로는 목표 평균(예: 50P)에 수렴하도록 예산을 통제할 수 있습니다.
     */
    private int calculateAverageFeedbackReward(Long eventId, EventReward policy) {
        String sumKey = "event:reward:sum:" + eventId;
        String countKey = "event:reward:count:" + eventId;

        try {
            // Redis에서 현재 누적 데이터 조회
            String sumStr = redisTemplate.opsForValue().get(sumKey);
            String countStr = redisTemplate.opsForValue().get(countKey);

            long totalSum = (sumStr != null) ? Long.parseLong(sumStr) : 0;
            long totalCount = (countStr != null) ? Long.parseLong(countStr) : 0;

            int reward;
            if (totalCount == 0) {
                reward = policy.getTargetAverage(); // 첫 참여자는 목표 평균값 지급
            } else {
                double currentAvg = (double) totalSum / totalCount;

                // 평균이 목표보다 높으면 낮은 쪽에서, 낮으면 높은 쪽에서 랜덤 추출
                if (currentAvg > policy.getTargetAverage()) {
                    reward = ThreadLocalRandom.current().nextInt(policy.getMinAmount(), policy.getTargetAverage() + 1);
                } else {
                    reward = ThreadLocalRandom.current().nextInt(policy.getTargetAverage(), policy.getMaxAmount() + 1);
                }
            }

            // Redis 통계 업데이트
            redisTemplate.opsForValue().increment(sumKey, reward);
            redisTemplate.opsForValue().increment(countKey, 1);

            return reward;

        } catch (Exception e) {
            log.error("### Redis 오류로 인한 Fallback(최소포인트) 지급: {}", e.getMessage());
            return policy.getMinAmount(); // 장애 시 최소 포인트 방어선
        }
    }

    /**
     * [추가] Kafka를 통해 들어온 추첨(Raffle) 응모를 DB에 저장합니다.
     * 당첨 여부는 결정하지 않고 PENDING 상태로 저장합니다.
     */
    @Transactional
    public void processRaffleEntry(Long eventId, Long memberId) {
        // Kafka 중복 메시지 방어
        if (entryRepository.existsByEventIdAndMemberId(eventId, memberId)) return;

        EventEntry entry = EventEntry.builder()
                .eventId(eventId)
                .memberId(memberId)
                .status(WinningStatus.PENDING)
                .build();
        entryRepository.save(Objects.requireNonNull(entry));
    }
}