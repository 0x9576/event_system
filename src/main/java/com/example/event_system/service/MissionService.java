package com.example.event_system.service;

import com.example.event_system.domain.MemberMission;
import com.example.event_system.domain.MissionType;
import com.example.event_system.event.MissionCompletedEvent;
import com.example.event_system.repository.MemberMissionRepository;
import com.example.event_system.service.strategy.MissionStrategy;
import com.example.event_system.service.strategy.MissionStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MissionService {

    private final MemberMissionRepository memberMissionRepository;
    private final MissionStrategyFactory strategyFactory;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 유저의 활동(Activity)을 기록하고 미션 달성 여부를 판단합니다.
     * @param memberId 유저 ID
     * @param type 미션 타입 (예: STEP_COUNT)
     * @param activityData 활동 데이터 (예: 10000L)
     */
    @Transactional
    public void processMemberActivity(Long memberId, MissionType type, Object activityData) {
        // 1. 유저가 진행 중인 미션들 조회 (아직 완료되지 않은 것만)
        List<MemberMission> activeMissions = memberMissionRepository
                .findActiveMissionsByMemberAndType(memberId, type);

        if (activeMissions.isEmpty()) {
            return;
        }

        // 2. 해당 타입에 맞는 전략 가져오기
        MissionStrategy strategy = strategyFactory.getStrategy(type);

        // 3. 각 미션 진행도 평가
        for (MemberMission memberMission : activeMissions) {
            boolean isNewlyCompleted = strategy.evaluate(memberMission, activityData);
            
            if (isNewlyCompleted) {
                Long eventId = memberMission.getMission().getEventId();
                log.info("🎉 미션 달성! 유저: {}, 미션: {}, 이벤트ID: {}", memberId, memberMission.getMission().getTitle(), eventId);
                
                // 미션 달성 이벤트 발행 (보상 로직과 격리)
                eventPublisher.publishEvent(new MissionCompletedEvent(memberId, eventId, memberMission.getMission().getTitle()));
            }
        }
    }
}
