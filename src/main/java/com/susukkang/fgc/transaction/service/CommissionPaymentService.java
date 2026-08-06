package com.susukkang.fgc.transaction.service;

import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;

/**
 * 설명 : 수수료 지급 건 등록·수정·확정 서비스 계약
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public interface CommissionPaymentService {

    CommissionPaymentResponse create(CommissionPaymentCreateRequest request);

    CommissionPaymentResponse update(Long paymentId, CommissionPaymentUpdateRequest request);

    CommissionPaymentResponse confirm(Long paymentId);
}
