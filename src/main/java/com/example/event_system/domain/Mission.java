package com.example.event_system.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "mission", indexes = @Index(name = "idx_mission_event", columnList = "eventId"))
public class Mission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId; // 어떤 이벤트에 속한 미션인지 식별

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MissionType missionType;

    @Column(nullable = false)
    private Long goalValue; // 목표치 (예: 10,000보, 7일 등)

    @Builder
    public Mission(Long eventId, String title, MissionType missionType, Long goalValue) {
        this.eventId = eventId;
        this.title = title;
        this.missionType = missionType;
        this.goalValue = goalValue;
    }
}
