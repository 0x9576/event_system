package com.example.event_system.service.strategy;

import com.example.event_system.domain.MemberMission;
import com.example.event_system.domain.MissionType;

public interface MissionStrategy {
    /**
     * 이 전략이 처리하는 미션 타입 반환
     */
    MissionType getMissionType();
    
    /**
     * 미션 진행도 평가 및 업데이트
     * @param memberMission 유저의 미션 상태 엔티티
     * @param activityData 활동 데이터 (걸음 수, 출석 정보 등)
     * @return 달성 완료 여부 (이번 업데이트로 인해 달성되었으면 true)
     */
    boolean evaluate(MemberMission memberMission, Object activityData);
}
