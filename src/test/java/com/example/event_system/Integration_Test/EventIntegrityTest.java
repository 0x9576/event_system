package com.example.event_system.Integration_Test;

import com.example.event_system.config.FakeRedisConfig;
import com.example.event_system.domain.*;
import com.example.event_system.domain.vo.ApplicantContact;
import com.example.event_system.dto.EventCreateRequest;
import com.example.event_system.repository.*;
import com.example.event_system.service.EventService;
import com.example.event_system.service.EventApplyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("null") // 테스트를 위한 null pointer 경고삭제
@SpringBootTest
@Transactional
@Import(FakeRedisConfig.class) // 테스트를 위한 가상 Redis 설정 임포트
class EventIntegrityTest {

        @Autowired
        private EventService eventService;
        @Autowired
        private EventApplyService eventApplyService;
        @Autowired
        private EventRepository eventRepository;
        @Autowired
        private EventEntryRepository entryRepository;

        @MockitoBean
        private KafkaTemplate<String, String> kafkaTemplate;

        private Long savedEventId;

        @BeforeEach
        void setup() {
                // 1. 테스트용 이벤트 생성
                EventCreateRequest request = new EventCreateRequest(
                                "개인정보 파기 테스트 이벤트",
                                "삭제 시 개인정보만 null 처리됨",
                                EventType.RAFFLE,
                                LocalDateTime.now(),
                                LocalDateTime.now().plusDays(7),
                                "COMP-INT-002",
                                LocalDateTime.now().minusDays(1),
                                LocalDateTime.now().plusMonths(1),
                                100, // 당첨인원
                                false // isDuplicateParticipationAllowed
                );
                savedEventId = eventService.createEvent(request);

                // 2. 테스트용 참가자 등록
                // 참가자 1: 기본 응모
                eventApplyService.apply(savedEventId, 101L);

                // 참가자 2: 개인정보 포함 당첨자
                ApplicantContact contact = new ApplicantContact("010-1234-5678", "user@test.com", "서울시 강남구");
                entryRepository.save(EventEntry.builder()
                                .eventId(savedEventId)
                                .memberId(102L)
                                .contact(contact)
                                .status(WinningStatus.WIN)
                                .build());
        }

        @Test
        @DisplayName("Soft Delete 검증: 이벤트 삭제 시 deleted_at만 업데이트되고 데이터는 남아야 한다")
        void eventSoftDeleteTest() {
                eventService.deleteEvent(savedEventId);

                Event deletedEvent = eventRepository.findById(savedEventId).orElseThrow();
                assertNotNull(deletedEvent.getDeletedAt());
                assertEquals("개인정보 파기 테스트 이벤트", deletedEvent.getTitle());
        }

        @Test
        @DisplayName("데이터 보존 및 개인정보 파기 검증: 이벤트 삭제 시 참가자 목록은 유지되나 개인정보는 null 처리되어야 함")
        void entriesPreservedAfterEventDeleteTest() {
                // Given: 서비스 메서드를 통해 이벤트 삭제 및 개인정보 파기 수행
                eventService.deleteEvent(savedEventId);

                // When: 재조회
                List<EventEntry> results = entryRepository.findAllByEventId(savedEventId);

                // Then 1: 전체 데이터 건수는 2건으로 유지 (통계 보존)
                assertEquals(2, results.size(), "참가자 레코드 자체는 DB에 남아있어야 함");

                // Then 2: 모든 참가자의 개인정보(Contact VO)가 null인지 확인 (보안 강화)
                boolean hasAnyContactInfo = results.stream()
                                .anyMatch(e -> e.getContact() != null);
                assertFalse(hasAnyContactInfo, "이벤트가 삭제된 후에는 모든 개인정보가 null이어야 합니다.");

                // Then 3: 당첨 상태 등 비식별 데이터는 유지되었는지 확인
                long winCount = results.stream()
                                .filter(e -> e.getStatus() == WinningStatus.WIN)
                                .count();
                assertEquals(1, winCount, "당첨 기록 같은 통계 데이터는 보존되어야 함");

                System.out.println("### 검증 완료: 참여 기록은 유지하되 개인정보(Contact)만 성공적으로 파기됨");
        }
}