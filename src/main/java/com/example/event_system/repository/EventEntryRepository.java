package com.example.event_system.repository;

import com.example.event_system.domain.EventEntry;
import com.example.event_system.domain.WinningStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventEntryRepository extends JpaRepository<EventEntry, Long> {

    // 중복 응모 확인
    boolean existsByEventIdAndMemberId(Long eventId, Long memberId);

    // 당첨자 수 합계 조회
    long countByEventIdAndStatus(Long eventId, WinningStatus status);

    // 이벤트 ID로 모든 참여자 목록을 조회
    List<EventEntry> findAllByEventId(Long eventId);

    // 당첨자 수 합계 조회 - 다른 트랜잭션이 조회를 시도하면 현재 트랜잭션이 끝날 때까지 대기시킴
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT COUNT(e) FROM EventEntry e WHERE e.eventId = :eventId AND e.status = :status")
    long countByEventIdAndStatusWithLock(@Param("eventId") Long eventId, @Param("status") WinningStatus status);
    
    // 사후 추첨을 위한 페이징 조회
    List<EventEntry> findByEventIdAndStatus(Long eventId, WinningStatus status, Pageable pageable);

    // Slice를 사용하여 ID만 페이징 조회
    @Query("SELECT e.id FROM EventEntry e WHERE e.eventId = :eventId AND e.status = :status")
    Slice<Long> findIdsByEventIdAndStatus(@Param("eventId") Long eventId,
            @Param("status") WinningStatus status,
            Pageable pageable);

    @Modifying
    @Query("UPDATE EventEntry e SET e.status = 'WIN' WHERE e.id IN :ids")
    int updateStatusToWinByIds(@Param("ids") List<Long> ids);
}