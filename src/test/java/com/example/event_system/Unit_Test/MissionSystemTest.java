package com.example.event_system.Unit_Test;

import com.example.event_system.domain.MemberMission;
import com.example.event_system.domain.Mission;
import com.example.event_system.domain.MissionType;
import com.example.event_system.event.MissionCompletedEvent;
import com.example.event_system.repository.MemberMissionRepository;
import com.example.event_system.service.MissionService;
import com.example.event_system.service.strategy.MissionStrategy;
import com.example.event_system.service.strategy.MissionStrategyFactory;
import com.example.event_system.service.strategy.StepCountMissionStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MissionSystemTest {

    // --- 1. Domain Entity Logic Tests ---
    @Nested
    @DisplayName("Domain: MemberMission Entity")
    class MemberMissionEntityTest {
        @Test
        @DisplayName("성공: 목표치에 도달하면 isCompleted가 true로 변경된다")
        void updateProgress_Success() {
            // Given
            Mission mission = Mission.builder()
                    .title("10000보 걷기")
                    .missionType(MissionType.STEP_COUNT)
                    .goalValue(10000L)
                    .build();
            
            MemberMission memberMission = MemberMission.builder()
                    .memberId(1L)
                    .mission(mission)
                    .build();

            // When
            memberMission.updateProgress(10000L);

            // Then
            assertTrue(memberMission.isCompleted());
            assertEquals(10000L, memberMission.getCurrentValue());
        }

        @Test
        @DisplayName("성공: 목표치 미달 시에는 진행도만 업데이트되고 완료되지 않는다")
        void updateProgress_NotCompleted() {
            // Given
            Mission mission = Mission.builder().goalValue(10000L).build();
            MemberMission memberMission = MemberMission.builder()
                    .memberId(1L)
                    .mission(mission)
                    .build();

            // When
            memberMission.updateProgress(9999L);

            // Then
            assertFalse(memberMission.isCompleted());
            assertEquals(9999L, memberMission.getCurrentValue());
        }

        @Test
        @DisplayName("로직 변경: 완료된 미션이라도 수치는 계속 업데이트되며(초과 달성), 완료 상태는 유지된다")
        void updateProgress_UpdatesValue_StickyCompleted() {
            // Given
            Mission mission = Mission.builder().goalValue(100L).build();
            MemberMission memberMission = MemberMission.builder()
                    .memberId(1L)
                    .mission(mission)
                    .build();
            
            memberMission.updateProgress(100L); // 1. 완료 처리
            assertTrue(memberMission.isCompleted());

            // When: 값이 변경되어도(심지어 목표보다 낮아져도)
            memberMission.updateProgress(50L);

            // Then: 수치는 반영되지만, 완료 상태는 취소되지 않아야 함 (Sticky)
            assertEquals(50L, memberMission.getCurrentValue(), "수치는 최신 값으로 업데이트되어야 함");
            assertTrue(memberMission.isCompleted(), "한 번 달성된 미션은 취소되지 않아야 함");
        }
    }

    // --- 2. Strategy Tests ---
    @Nested
    @DisplayName("Strategy: StepCountMissionStrategy")
    class StepCountStrategyTest {
        
        private final StepCountMissionStrategy strategy = new StepCountMissionStrategy();

        @Test
        @DisplayName("실패: Long 타입이 아닌 데이터가 들어오면 예외 발생")
        void evaluate_Fail_InvalidType() {
            MemberMission mm = mock(MemberMission.class);
            assertThrows(IllegalArgumentException.class, () -> strategy.evaluate(mm, "StringData"));
        }

        @Test
        @DisplayName("성공: 이번 업데이트로 새롭게 달성한 경우 true 반환")
        void evaluate_ReturnsTrue_WhenNewlyCompleted() {
            // Given
            Mission mission = Mission.builder().goalValue(100L).build();
            MemberMission memberMission = new MemberMission(1L, mission); // 초기상태

            // When
            boolean result = strategy.evaluate(memberMission, 100L);

            // Then
            assertTrue(result, "새롭게 달성했으므로 true여야 함");
            assertTrue(memberMission.isCompleted());
        }

        @Test
        @DisplayName("성공: 이미 달성된 상태였다면 false 반환 (중복 달성 이벤트 방지)")
        void evaluate_ReturnsFalse_WhenAlreadyCompleted() {
            // Given
            Mission mission = Mission.builder().goalValue(100L).build();
            MemberMission memberMission = new MemberMission(1L, mission);
            memberMission.updateProgress(100L); // 미리 완료 상태로 만듦

            // When
            boolean result = strategy.evaluate(memberMission, 150L);

            // Then
            assertFalse(result, "이미 완료된 건은 false여야 함");
        }
    }

    // --- 3. Service & Factory Tests ---
    @Nested
    @DisplayName("Service: MissionService Integration Logic")
    class MissionServiceTest {

        @Mock private MemberMissionRepository memberMissionRepository;
        @Mock private MissionStrategyFactory strategyFactory;
        @Mock private ApplicationEventPublisher eventPublisher; // 이벤트 발행기 Mock 추가
        @InjectMocks private MissionService missionService;

        @SuppressWarnings("null")
        @Test
        @DisplayName("성공: 미션 달성 시 로그(또는 보상로직) 흐름이 정상 실행된다")
        void process_MissionCompleted() {
            // Given
            Long memberId = 1L;
            MissionType type = MissionType.STEP_COUNT;
            Long activityData = 10000L;

            Mission mission = Mission.builder().title("Test Mission").build();
            MemberMission memberMission = mock(MemberMission.class);
            when(memberMission.getMission()).thenReturn(mission);

            MissionStrategy strategy = mock(MissionStrategy.class);
            when(strategy.evaluate(memberMission, activityData)).thenReturn(true); // 전략이 '달성 성공' 리턴

            when(memberMissionRepository.findActiveMissionsByMemberAndType(memberId, type))
                    .thenReturn(List.of(memberMission));
            when(strategyFactory.getStrategy(type)).thenReturn(strategy);

            // When
            missionService.processMemberActivity(memberId, type, activityData);

            // Then
            verify(strategy).evaluate(memberMission, activityData);
            // 핵심 검증: 미션 달성 시 이벤트가 발행되었는지 확인
            verify(eventPublisher).publishEvent(any(MissionCompletedEvent.class));
        }
    }
}