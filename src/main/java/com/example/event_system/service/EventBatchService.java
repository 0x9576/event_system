package com.example.event_system.service;

import com.example.event_system.domain.Event;
import com.example.event_system.domain.EventEntry;
import com.example.event_system.domain.WinningStatus;
import com.example.event_system.repository.EventEntryRepository;
import com.example.event_system.repository.EventLockRepository;
import com.example.event_system.repository.EventRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventBatchService {

    private final EventEntryRepository entryRepository;
    private final EventLockRepository eventLockRepository;
    private final EventRepository eventRepository;

    private static final int READ_PAGE_SIZE = 10000;
    private static final int UPDATE_BATCH_SIZE = 1000;

    /**
     * 특정 이벤트의 대기(PENDING) 인원 중 [선착순]으로 당첨 처리
     */
    @Transactional
    public int processWinners(Long eventId, int limit) {
        // 0. [안전 장치] 동시 실행 방지를 위한 락 획득 (선택 사항)
        eventLockRepository.findByLockKeyWithLock("EVENT_DRAW_" + eventId)
                .orElseThrow(() -> new EntityNotFoundException("락 설정이 없습니다."));

        // 1. PENDING 상태인 사람들을 선착순(ID순)으로 limit만큼 조회
        List<EventEntry> pendingEntries = entryRepository.findByEventIdAndStatus(
                eventId,
                WinningStatus.PENDING,
                PageRequest.of(0, limit, Sort.by(Sort.Direction.ASC, "id")));

        // 2. 당첨 처리 (엔티티 내부 로직 사용)
        for (EventEntry entry : pendingEntries) {
            entry.assignWinner();
        }

        return pendingEntries.size();
    }

    /**
     * 특정 이벤트의 대기(PENDING) 인원 중 [랜덤]으로 당첨 처리
     */
    @Transactional
    public int drawWinnersRandomly(Long eventId) {
        // 1. [인프라/락] 전용 락 테이블에서 권한 획득
        eventLockRepository.findByLockKeyWithLock("EVENT_DRAW_" + eventId)
                .orElseThrow(() -> new EntityNotFoundException("해당 이벤트의 락 설정이 없습니다."));

        // 2. [애그리거트 조회] 마스터 이벤트 조회
        Event event = eventRepository.findById(Objects.requireNonNull(eventId))
                .orElseThrow(() -> new EntityNotFoundException("이벤트를 찾을 수 없습니다."));

        // 3. [도메인 규칙 활용] 현재 당첨자 수를 조회하여 '부족분' 계산을 엔티티에 위임
        long currentWinnerCount = entryRepository.countByEventIdAndStatus(eventId, WinningStatus.WIN);
        int delta = event.calculateNeededWinnerCount(currentWinnerCount);

        if (delta <= 0) {
            log.info("이벤트 ID {}: 이미 목표 인원을 달성했거나 정원 정보가 없습니다.", eventId);
            return 0;
        }

        // 4. [대상 선정 & 추첨] Reservoir Sampling으로 메모리 효율적으로 당첨자 선정
        List<Long> winnerIds = selectWinnersUsingReservoirSampling(eventId, delta);
        
        if (winnerIds.isEmpty()) return 0;

        // 5. [결과 반영] 벌크 업데이트 실행
        return updateInPartitions(winnerIds);
    }

    /**
     * Reservoir Sampling 알고리즘을 사용하여 전체 데이터를 메모리에 로딩하지 않고 랜덤 당첨자를 선정합니다. (메모리 절약)
     * 당첨자 10명($k=10$)을 뽑는 상황을 가정해 보겠습니다.

    * 처음 도착한 10명은 일단 모두 "잠정 당첨자" 명단(저수지)에 넣습니다.
    * 이때까지의 당첨 확률은 100%입니다.

    * **11번째 사람($i=11$)**이 등장합니다.
    * 이 사람이 당첨될 확률은 $10/11$이어야 공평합니다.
    * 주사위를 굴려 $10/11$ 확률에 당첨되면, 기존 10명 중 한 명을 랜덤하게 밀어내고 그 자리를 차지합니다.
    * **100번째 사람($i=100$)**이 등장합니다.
    * 이 사람이 당첨될 확률은 $10/100$이어야 합니다.
    * 마찬가지로 확률 게임을 진행하여 당첨되면 기존 명단 중 한 명과 교체합니다.
    
    이 과정을 마지막 사람까지 반복하면, 수학적으로 모든 사람이 당첨될 확률은 $10/N$으로 동일해집니다.
     */
    private List<Long> selectWinnersUsingReservoirSampling(Long eventId, int limit) {
        List<Long> reservoir = new ArrayList<>(limit);
        int count = 0;

        Slice<Long> idSlice = entryRepository.findIdsByEventIdAndStatus(
                eventId, WinningStatus.PENDING, PageRequest.of(0, READ_PAGE_SIZE));

        while (true) {
            for (Long id : idSlice.getContent()) {
                count++;
                if (reservoir.size() < limit) {
                    reservoir.add(id);
                } else {
                    // i번째 요소(count)를 k/i 확률로 선택 (0부터 count-1 사이의 난수 생성)
                    int randomIndex = ThreadLocalRandom.current().nextInt(count);
                    if (randomIndex < limit) {
                        reservoir.set(randomIndex, id);
                    }
                }
            }

            if (!idSlice.hasNext()) {
                break;
            }
            idSlice = entryRepository.findIdsByEventIdAndStatus(
                    eventId, WinningStatus.PENDING, idSlice.nextPageable());
        }
        return reservoir;
    }

    private int updateInPartitions(List<Long> winnerIds) {
        int totalUpdated = 0;

        for (int i = 0; i < winnerIds.size(); i += UPDATE_BATCH_SIZE) {
            int end = Math.min(i + UPDATE_BATCH_SIZE, winnerIds.size());
            List<Long> subList = winnerIds.subList(i, end);
            totalUpdated += entryRepository.updateStatusToWinByIds(subList);
        }
        return totalUpdated;
    }
}