package com.example.event_system.service.strategy;

import com.example.event_system.domain.MissionType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class MissionStrategyFactory {
    
    private final Map<MissionType, MissionStrategy> strategies = new EnumMap<>(MissionType.class);

    // Spring이 MissionStrategy를 구현한 모든 Bean을 List로 주입해줍니다.
    public MissionStrategyFactory(List<MissionStrategy> strategyList) {
        for (MissionStrategy strategy : strategyList) {
            strategies.put(strategy.getMissionType(), strategy);
        }
    }

    public MissionStrategy getStrategy(MissionType type) {
        return Optional.ofNullable(strategies.get(type))
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 미션 전략입니다: " + type));
    }
}
