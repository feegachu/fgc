package com.susukkang.fgc.transaction.service;

import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;

public interface CommissionPaymentService {

    CommissionPaymentResponse create(CommissionPaymentCreateRequest request);

    CommissionPaymentResponse update(Long paymentId, CommissionPaymentUpdateRequest request);

    CommissionPaymentResponse confirm(Long paymentId);
}
