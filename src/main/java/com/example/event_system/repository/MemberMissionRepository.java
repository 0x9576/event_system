package com.example.event_system.repository;

import com.example.event_system.domain.MemberMission;
import com.example.event_system.domain.MissionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MemberMissionRepository extends JpaRepository<MemberMission, Long> {

    // 특정 유저가 참여 중인 미션 중, 특정 타입이면서 아직 완료하지 않은 미션 조회
    @Query("SELECT mm FROM MemberMission mm " +
           "JOIN FETCH mm.mission m " +
           "WHERE mm.memberId = :memberId " +
           "AND m.missionType = :missionType " +
           "AND mm.isCompleted = false")
    List<MemberMission> findActiveMissionsByMemberAndType(
            @Param("memberId") Long memberId, 
            @Param("missionType") MissionType missionType
    );
}
