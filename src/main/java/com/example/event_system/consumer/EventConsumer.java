package com.example.event_system.consumer;

import com.example.event_system.service.EventResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventConsumer {

    private final EventResultService eventResultService;

    /**
     * Kafka로부터 이벤트를 수신하여 최종 당첨 처리를 진행합니다.
     * 비즈니스 로직(재고 차감, 포인트 계산)은 인프라와 분리하기 위해 Service 계층에 위임합니다.
     */
    @KafkaListener(topics = "event-apply-topic", groupId = "event-group")
    public void consume(String message) {
        log.info("### Kafka 메시지 수신: {}", message);

        try {
            String[] data = message.split(":");
            if (data.length < 2) {
                log.error("### 잘못된 메시지 형식입니다: {}", message);
                return;
            }

            Long eventId = Long.parseLong(data[0]);
            Long memberId = Long.parseLong(data[1]);

            // 핵심 비즈니스 로직(재고 차감 + 포인트 결정 + 저장) 호출
            eventResultService.processWinning(eventId, memberId);

        } catch (NumberFormatException e) {
            log.error("### 메시지 형식 오류(재시도 제외): {}, message: {}", e.getMessage(), message);
        } catch (Exception e) {
            log.error("### 메시지 처리 중 시스템 오류 발생(재시도): {}, message: {}", e.getMessage(), message);
            // 예외를 다시 던져야 Spring Kafka가 에러를 감지하고 재시도(Retry) 또는 DLQ 처리를 수행합니다.
            throw e;
        }
    }

    /**
     * [추가] 대량 트래픽 추첨(Raffle) 이벤트를 위한 컨슈머
     * 선착순과 달리 즉시 당첨 처리가 아니라 '응모 접수(PENDING)'만 수행합니다.
     */
    @KafkaListener(topics = "event-raffle-topic", groupId = "event-group")
    public void consumeRaffle(String message) {
        log.info("### Kafka Raffle 메시지 수신: {}", message);
        try {
            String[] data = message.split(":");
            if (data.length < 2) return;

            Long eventId = Long.parseLong(data[0]);
            Long memberId = Long.parseLong(data[1]);

            // 추첨 응모 저장 로직 호출
            eventResultService.processRaffleEntry(eventId, memberId);
        } catch (NumberFormatException e) {
            log.error("### Raffle 메시지 형식 오류(재시도 제외): {}", e.getMessage());
        } catch (Exception e) {
            log.error("### Raffle 처리 중 시스템 오류(재시도): {}", e.getMessage());
            throw e;
        }
    }
}