package com.example.event_system.event;

public record MissionCompletedEvent(Long memberId, Long eventId, String missionTitle) {
}
