package com.susukkang.fgc.transaction.service;

import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.transaction.dto.*;

/**
 * 설명 : 수수료 지급 건 등록·수정·확정 서비스 계약
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public interface CommissionPaymentService {

    CommissionPaymentResponse get(Long paymentId);

    CommissionPaymentResponse create(CommissionPaymentCreateRequest request);

    CommissionPaymentResponse update(Long paymentId, CommissionPaymentUpdateRequest request);

    void deleteDraft(Long paymentId);

    CommissionPaymentResponse confirm(Long paymentId, String idempotencyKey);

    /** 원장 정정으로 연결된 대체 지급만 기존 확정 게이트로 승인한다. */
    CommissionPaymentResponse confirmJournalCorrection(Long paymentId, String correctionGroupKey);

    // IF-API-24 — 저장·상태 변경 없이 확정 게이트를 미리 계산한다 (FGC-FUN-033)
    TransactionPrecheckResponse precheck(Long paymentId);

    PageResponse<CommissionPaymentListResponse> search(CommissionPaymentSearchCondition condition, int page, int size);
}
