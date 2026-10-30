package com.susukkang.fgc.clawback.service;

import com.susukkang.fgc.clawback.repository.ClawbackCandidateWriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** FUN-054 원천 관측. 기존 상태사건 Writer와 차익거래 저장의 트랜잭션에 참여한다. */
@Service
@RequiredArgsConstructor
public class ClawbackCandidateCaptureService {
    private final ClawbackCandidateWriteRepository repository;

    @Transactional
    public int captureStatusEvents(List<Long> eventIds) {
        return repository.captureStatusEvents(List.copyOf(eventIds));
    }

    @Transactional
    public int captureArbitrageResult(long arbitrageCheckId) {
        return repository.captureArbitrageResult(arbitrageCheckId);
    }
}
