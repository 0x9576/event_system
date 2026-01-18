package com.example.event_system.service;

import com.example.event_system.domain.Event;
import com.example.event_system.domain.EventEntry;
import com.example.event_system.domain.EventStock;
import com.example.event_system.domain.EventType;
import com.example.event_system.domain.WinningStatus;
import com.example.event_system.repository.EventEntryRepository;
import com.example.event_system.repository.EventRepository;
import com.example.event_system.repository.EventStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventApplyService {
    private final EventEntryRepository entryRepository;
    private final EventStockRepository stockRepository;
    private final EventRepository eventRepository;
    private final RedisRateLimiter rateLimiter;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;

    public String apply(Long eventId, Long memberId) {
        // 1. 이벤트 조회 및 타입 확인
        Event event = eventRepository.findById(Objects.requireNonNull(eventId))
                .orElseThrow(() -> new IllegalArgumentException("이벤트를 찾을 수 없습니다."));

        // 2. 중복 응모 확인 (공통 로직)
        if (entryRepository.existsByEventIdAndMemberId(eventId, memberId)) return "ALREADY_APPLIED";

        // 3. 타입별 로직 분기
        if (event.getType() == EventType.RAFFLE) {
            return applyRaffle(eventId, memberId);
        } else {
            return applyFirstCome(eventId, memberId);
        }
    }

    /**
     * [단순 응모] 기본적으로 DB에 바로 저장하지만, High Traffic 이벤트인 경우 Kafka를 경유함
     */
    private String applyRaffle(Long eventId, Long memberId) {
        // 1. 대량 트래픽 제어 모드 확인 (Redis Flag)
        // 관리자가 "event:policy:high-traffic:{eventId}" 키를 설정해둔 경우에만 Kafka를 태움
        if (Boolean.TRUE.equals(redisTemplate.hasKey("event:policy:high-traffic:" + eventId))) {
            // 1-1. 유량 제어 (선택 사항이지만 대량 트래픽 보호를 위해 적용)
            boolean isAllowed = rateLimiter.isAllowed("event:raffle:" + eventId, "event:limit:raffle:" + eventId, 1000); // 넉넉하게 1000 TPS
            if (!isAllowed) return "TRY_AGAIN";

            // 1-2. Kafka 메시지 발행 (추첨 전용 토픽)
            kafkaTemplate.send("event-raffle-topic", eventId + ":" + memberId);
            return "APPLIED_RAFFLE"; // 사용자에게는 동일하게 응모 완료로 응답
        }

        // 2. 일반 모드: DB 직접 저장
        EventEntry entry = EventEntry.builder()
                .eventId(eventId)
                .memberId(memberId)
                .status(WinningStatus.PENDING)
                .build();
        entryRepository.save(Objects.requireNonNull(entry));
        return "APPLIED_RAFFLE";
    }

    /**
     * [실시간/선착순 응모] Redis 유량 제어 -> Kafka 대기열 -> 비동기 처리
     */
    private String applyFirstCome(Long eventId, Long memberId) {
        // 2. Redis 방어막 (장애 시 로직 진행)
        boolean isAllowed = true;
        try {
            // Redis에 "event:limit:{eventId}" 키로 값을 설정하면 실시간으로 유량 제어 변경 가능 (기본값 10)
            isAllowed = rateLimiter.isAllowed("event:" + eventId, "event:limit:" + eventId, 10);
        } catch (Exception e) {
            log.error("Redis 장애 - 도메인 로직으로 검증");
        }
        if (!isAllowed) return "LOSE";

        // 3. 재고확인
        EventStock stock = stockRepository.findByEventId(eventId);
        if (stock != null && !stock.hasStock()) {
            return "LOSE"; // DB UPDATE 쿼리 전 애플리케이션 단계에서 차단
        }

        // 4. Kafka 메시지 발행 (비동기 처리)
        try {
            kafkaTemplate.send("event-apply-topic", eventId + ":" + memberId);
            return "APPLIED";
        } catch (Exception e) {
            return "Kafka ERROR";
        }
    }
}