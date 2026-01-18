package com.example.event_system.repository;

import com.example.event_system.domain.Event;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
    // findById는 이미 내장.
}
