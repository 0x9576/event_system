package com.example.event_system.Integration_Test;

import com.example.event_system.domain.*;
import com.example.event_system.domain.vo.ComplianceInfo;
import com.example.event_system.domain.vo.EventPeriod;
import com.example.event_system.repository.*;
import com.example.event_system.service.EventApplyService;
import com.example.event_system.service.MissionService;
import com.example.event_system.service.RedisRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
@SpringBootTest
@Transactional
class MissionEventTest {

    @Autowired
    private MissionService missionService;

    @Autowired
    private EventApplyService eventApplyService;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private MemberMissionRepository memberMissionRepository;

    @Autowired
    private EventStockRepository eventStockRepository;

    @Autowired
    private EventEntryRepository eventEntryRepository;

    @Autowired
    private EventRepository eventRepository;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private RedisRateLimiter redisRateLimiter;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        // 테스트 간 데이터 간섭 방지를 위한 초기화
        memberMissionRepository.deleteAll();
        missionRepository.deleteAll();
        eventEntryRepository.deleteAll();
        eventStockRepository.deleteAll();
        eventRepository.deleteAll();

        // Redis RateLimiter Mock 설정 (항상 통과하도록 설정)
        when(redisRateLimiter.isAllowed(anyString(), anyString(), anyInt())).thenReturn(true);
    }

    @Test
    @DisplayName("통합 테스트: 걸음 수 미션 달성 후 이벤트 응모 시나리오")
    void missionCompleteAndEventApplyTest() {
        // 1. [Given] 이벤트 및 재고 설정
        LocalDateTime now = LocalDateTime.now();
        Event event = eventRepository.save(Event.builder()
                .title("Mission Reward Event")
                .type(EventType.FIRST_COME)
                .maxWinners(10)
                .eventPeriod(new EventPeriod(now, now.plusDays(1)))
                .complianceInfo(new ComplianceInfo("MISSION-001", new EventPeriod(now, now.plusDays(1))))
                .isDuplicateParticipationAllowed(false)
                .build());
        
        eventStockRepository.save(new EventStock(event.getId(), 10));

        // 2. [Given] 미션 및 사용자 참여 설정
        Mission mission = missionRepository.save(Mission.builder()
                .eventId(event.getId())
                .title("일일 1만보 걷기")
                .missionType(MissionType.STEP_COUNT)
                .goalValue(10000L)
                .build());

        Long memberId = 100L;
        MemberMission memberMission = memberMissionRepository.save(MemberMission.builder()
                .memberId(memberId)
                .mission(mission)
                .build());

        // 3. [When] 미션 달성 (10,000보)
        missionService.processMemberActivity(memberId, MissionType.STEP_COUNT, 10000L);

        // 4. [Then] 미션 완료 상태 검증
        MemberMission updatedMission = memberMissionRepository.findById(memberMission.getId()).orElseThrow();
        assertTrue(updatedMission.isCompleted(), "10000보 달성 시 완료 처리되어야 합니다.");

        // 5. [When] 이벤트 응모 (미션 달성 보상)
        // 실제 환경에서는 EventListener가 처리하지만, @Transactional 테스트 한계상 직접 호출로 검증
        String result = eventApplyService.apply(event.getId(), memberId);

        // 6. [Then] 응모 결과 및 비동기 메시지 발행 검증
        assertEquals("APPLIED", result);
        verify(kafkaTemplate, times(1)).send(anyString(), anyString());
    }
}
