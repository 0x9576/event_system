package com.example.event_system.repository;

import com.example.event_system.domain.EventReward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EventRewardRepository extends JpaRepository<EventReward, Long> {

    /**
     * 특정 이벤트에 설정된 보상 정책을 조회합니다.
     * @param eventId 조회할 이벤트 ID
     * @return 보상 정책 (Optional)
     */
    Optional<EventReward> findByEventId(Long eventId);
}