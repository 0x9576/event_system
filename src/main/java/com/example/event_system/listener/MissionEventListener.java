package com.example.event_system.listener;

import com.example.event_system.event.MissionCompletedEvent;
import com.example.event_system.service.EventApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class MissionEventListener {

    private final EventApplyService eventApplyService;

    // 미션 달성 트랜잭션이 성공적으로 커밋된 후에만 실행 (데이터 정합성 보장)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMissionCompleted(MissionCompletedEvent event) {
        log.info("이벤트 리스너 수신: 유저={}, 이벤트ID={}", event.memberId(), event.eventId());
        String result = eventApplyService.apply(event.eventId(), event.memberId());
        log.info("자동 응모 결과: {}", result);
    }
}
