package com.example.event_system.repository;

import com.example.event_system.domain.EventStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventStockRepository extends JpaRepository<EventStock, Long> {

    /* 원자적으로 stock 개수 감소시키기 */
    @Modifying
    @Query("UPDATE EventStock s SET s.stockCount = s.stockCount - 1 WHERE s.eventId = :eventId AND s.stockCount > 0")
    int decreaseStock(@Param("eventId") Long eventId);

    EventStock findByEventId(Long eventId);
}