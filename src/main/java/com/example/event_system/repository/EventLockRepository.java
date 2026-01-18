package com.example.event_system.repository;

import com.example.event_system.domain.EventLock;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;


public interface EventLockRepository extends JpaRepository<EventLock, String> {
    // 락 전용 테이블의 로우를 비관적 락으로 점유.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM EventLock l WHERE l.lockKey = :lockKey")
    Optional<EventLock> findByLockKeyWithLock(@Param("lockKey") String lockKey);
}