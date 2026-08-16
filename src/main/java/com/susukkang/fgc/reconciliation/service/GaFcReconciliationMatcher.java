package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.reconciliation.dto.GaFcMatchCandidate;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;

import java.util.List;

/**
 * 설명 : GA→FC 예상 스케줄과 확정 지급 건의 정규화 매칭 계약
 *
 * @author yslee
 * @since 2026-08-13
 * @version 1.2
 */
public interface GaFcReconciliationMatcher {

    List<GaFcMatchCandidate> match(ReconciliationExecutionRequest request);
}
