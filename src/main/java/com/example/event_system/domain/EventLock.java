package com.example.event_system.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "event_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class EventLock {

    @Id
    @Column(name = "lock_key")
    private String lockKey;

    private Long eventId;

    public EventLock(Long eventId) {
        this.eventId = eventId;
        this.lockKey = "EVENT_DRAW_" + eventId; // 서비스에서 호출하는 키 형식과 일치시킴
    }
}