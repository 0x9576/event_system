package com.example.event_system.Unit_Test;

import com.example.event_system.domain.EventEntry;
import com.example.event_system.domain.WinningStatus;
import com.example.event_system.domain.vo.ApplicantContact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventEntryTest {

    @Test
    @DisplayName("성공: 개인정보를 포함한 응모 기록이 정상 생성된다")
    void createEventEntry_WithContact() {
        // Given
        ApplicantContact contact = new ApplicantContact("010-1234-5678", "user@test.com", "서울시");

        // When
        EventEntry entry = EventEntry.builder()
                .eventId(1L)
                .memberId(100L)
                .contact(contact)
                .status(WinningStatus.PENDING)
                .build();

        // Then
        assertNotNull(entry.getContact());
        assertFalse(entry.getContact().isEmpty(), "데이터가 있으므로 isEmpty는 false여야 함");
        assertEquals("user@test.com", entry.getContact().getEmail());
    }

    

    @Test
    @DisplayName("성공: clearContactInfo 호출 시 contact 필드가 null이 되어 개인정보가 파기된다")
    void clearContactInfo_Success() {
        // Given
        ApplicantContact contact = new ApplicantContact("010-1234-5678", "user@test.com", "서울시");
        EventEntry entry = EventEntry.builder()
                .eventId(1L)
                .memberId(100L)
                .contact(contact)
                .status(WinningStatus.WIN)
                .build();

        // When
        entry.clearContactInfo();

        // Then
        assertNull(entry.getContact(), "개인정보 파기 후 contact 객체 자체가 null이어야 함");
        assertEquals(WinningStatus.WIN, entry.getStatus(), "당첨 상태 등 통계 데이터는 유지되어야 함");
    }

    @Test
    @DisplayName("성공: assignWinner 호출 시 응모 상태가 WIN으로 변경된다")
    void assignWinner_Success() {
        // Given
        EventEntry entry = EventEntry.builder()
                .eventId(1L)
                .memberId(100L)
                .status(WinningStatus.PENDING)
                .build();

        // When
        entry.assignWinner();

        // Then
        assertEquals(WinningStatus.WIN, entry.getStatus());
    }

    @Test
    @DisplayName("실패: 이미 처리된(WIN/LOSE) 내역에 대해 당첨 처리를 시도하면 예외가 발생한다")
    void assignWinner_Fail_AlreadyProcessed() {
        // Given
        EventEntry entry = EventEntry.builder()
                .eventId(1L)
                .memberId(100L)
                .status(WinningStatus.WIN) // 이미 당첨된 상태
                .build();

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, entry::assignWinner);
        assertEquals("이미 당첨 여부가 결정된 응모 내역입니다.", exception.getMessage());
    }

    @Test
    @DisplayName("실패: 필수값(EventId, MemberId)이 누락되면 생성 시 예외가 발생한다")
    void createEventEntry_Fail_RequiredFields() {
        // Case 1: EventId 누락
        assertThrows(IllegalArgumentException.class, () -> {
            EventEntry.builder().memberId(100L).build();
        });

        // Case 2: MemberId 누락
        assertThrows(IllegalArgumentException.class, () -> {
            EventEntry.builder().eventId(1L).build();
        });
    }

    @Test
    @DisplayName("단위 테스트: ApplicantContact의 isEmpty 로직 검증")
    void applicantContact_isEmpty_Logic() {
        // 1. 모든 정보가 없는 경우
        ApplicantContact emptyContact = new ApplicantContact(null, null, null);
        assertTrue(emptyContact.isEmpty());

        // 2. 정보가 하나라도 있는 경우
        ApplicantContact partialContact = new ApplicantContact("010-1111-2222", null, null);
        assertFalse(partialContact.isEmpty());
    }
}