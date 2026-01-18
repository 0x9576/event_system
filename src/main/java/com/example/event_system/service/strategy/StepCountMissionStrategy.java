package com.example.event_system.service.strategy;

import com.example.event_system.domain.MemberMission;
import com.example.event_system.domain.MissionType;
import org.springframework.stereotype.Component;

@Component
public class StepCountMissionStrategy implements MissionStrategy {

    @Override
    public MissionType getMissionType() {
        return MissionType.STEP_COUNT;
    }

    @Override
    public boolean evaluate(MemberMission memberMission, Object activityData) {
        // 데이터 타입 검증
        if (!(activityData instanceof Long)) {
            throw new IllegalArgumentException("걸음 수 데이터는 Long 타입이어야 합니다.");
        }

        Long currentSteps = (Long) activityData;
        
        // 이전 상태 저장 (달성 여부 변화 감지용)
        boolean wasCompleted = memberMission.isCompleted();
        
        // 엔티티에게 상태 업데이트 위임
        memberMission.updateProgress(currentSteps);
        
        // 이번 호출로 인해 '새롭게' 달성되었는지 확인
        return !wasCompleted && memberMission.isCompleted();
    }
}
