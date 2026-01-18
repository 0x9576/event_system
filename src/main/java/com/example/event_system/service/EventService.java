package com.example.event_system.service;

import com.example.event_system.domain.Event;
import com.example.event_system.domain.EventEntry;
import com.example.event_system.domain.EventLock;
import com.example.event_system.domain.vo.ComplianceInfo;
import com.example.event_system.domain.vo.EventPeriod;
import com.example.event_system.dto.EventCreateRequest;
import com.example.event_system.repository.EventEntryRepository;
import com.example.event_system.repository.EventLockRepository;
import com.example.event_system.repository.EventRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final EventLockRepository eventLockRepository;
    private final EventEntryRepository eventEntryRepository;

    /**
     * 신규 이벤트를 생성하고 추첨을 위한 락 레코드를 함께 등록합니다.
     */
    @Transactional
    public Long createEvent(EventCreateRequest request) {
        log.info("이벤트 생성 시작: title={}", request.title());

        // 1. DTO 데이터를 바탕으로 도메인 구성 요소(VO) 조립
        EventPeriod eventPeriod = new EventPeriod(
                request.startDateTime(), 
                request.endDateTime()
        );

        ComplianceInfo complianceInfo = new ComplianceInfo(
                request.reviewNumber(),
                new EventPeriod(request.approvalStartDateTime(), request.approvalEndDateTime())
        );

        // 2. 도메인 엔티티 생성 
        // @Builder를 호출하여 객체를 생성할 때, Event 내부의 validateCompliancePeriod가 실행되어 
        // 기간 정합성을 즉시 검증합니다.
        Event event = Event.builder()
                .title(request.title())
                .content(request.content())
                .type(request.type())
                .eventPeriod(eventPeriod)
                .complianceInfo(complianceInfo)
                .maxWinners(request.maxWinners())
                .isDuplicateParticipationAllowed(request.isDuplicateParticipationAllowed())
                .build();

        // 3. 엔티티 저장
        Event savedEvent = eventRepository.save(Objects.requireNonNull(event));

        // 4. [중요] 동시성 제어를 위한 락 레코드 생성
        // 추첨 배치 로직이 안전하게 줄을 설 수 있도록 이 시점에 미리 생성해 둡니다.
        eventLockRepository.save(new EventLock(savedEvent.getId()));

        log.info("이벤트 생성 및 락 레코드 등록 완료: ID={}", savedEvent.getId());
        return savedEvent.getId();
    }

    /**
     * 이벤트를 삭제(Soft Delete)하고, 컴플라이언스 규정에 따라 참여자의 개인정보를 파기합니다.
     */
    @Transactional
    public void deleteEvent(Long eventId) {
        Event event = eventRepository.findById(Objects.requireNonNull(eventId))
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다."));

        // 1. 이벤트 논리 삭제 (Soft Delete)
        event.delete();

        // 2. 개인정보 파기 (Privacy Masking)
        List<EventEntry> entries = eventEntryRepository.findAllByEventId(eventId);
        entries.forEach(EventEntry::clearContactInfo);
        
        log.info("이벤트 삭제 및 개인정보 파기 완료: ID={}, 대상={}명", eventId, entries.size());
    }
}