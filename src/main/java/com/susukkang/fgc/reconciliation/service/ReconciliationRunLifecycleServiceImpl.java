package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/**
 * 설명 : 기존 DB 상태전이 가드를 사용하는 대사 실행 상태 전이 서비스
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class ReconciliationRunLifecycleServiceImpl implements ReconciliationRunLifecycleService {

    private final ReconciliationRunMapper reconciliationRunMapper;

    @Override
    @Transactional
    public void start(Long reconciliationRunId) {
        requireAffected(reconciliationRunMapper.transitionToRunning(reconciliationRunId));
    }

    @Override
    @Transactional
    public void complete(Long reconciliationRunId) {
        requireAffected(reconciliationRunMapper.transitionToCompleted(reconciliationRunId));
    }

    @Override
    @Transactional
    public void fail(Long reconciliationRunId) {
        requireAffected(reconciliationRunMapper.transitionToFailed(reconciliationRunId));
    }

    private static void requireAffected(int affected) {
        if (affected != 1) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_500,
                    Map.of("requestId", Objects.toString(RequestIdContext.current(), ""))
            );
        }
    }
}
