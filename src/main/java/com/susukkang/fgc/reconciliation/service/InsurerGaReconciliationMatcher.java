package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.reconciliation.dto.InsurerGaMatchCandidate;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;

import java.util.List;

/**
 * 설명 : 보험사→GA 예상 스케줄·실제 명세 매칭 서비스 계약
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public interface InsurerGaReconciliationMatcher {

    List<InsurerGaMatchCandidate> match(ReconciliationExecutionRequest request);
}
