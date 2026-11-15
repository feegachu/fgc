package com.susukkang.fgc.transaction.service;

import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.transaction.dto.CommissionPaymentBatchRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reuse single-payment validation/auditing, with one transaction for the entire submission. */
@Service
@RequiredArgsConstructor
public class CommissionPaymentBatchService {
    private final CommissionPaymentService payments;

    @Transactional
    @PreAuthorize(Roles.CAN_PROCESS)
    public List<CommissionPaymentResponse> create(CommissionPaymentBatchRequest request) {
        var first = request.payments().get(0);
        for (var payment : request.payments()) {
            if (first.contractId() == null || !Objects.equals(first.contractId(), payment.contractId())
                    || first.paymentStage() != payment.paymentStage()
                    || !Objects.equals(first.settlementMonth(), payment.settlementMonth())
                    || payment.attributions().size() != 1
                    || !Objects.equals(first.contractId(), payment.attributions().get(0).contractId())) {
                throw new FgcBusinessException(FgcErrorCode.COMMON_002, "payments", Map.of("field", "payments"),
                        "같은 계약, 지급단계 및 정산월의 지급 건만 함께 등록할 수 있습니다.");
            }
        }
        var result = new ArrayList<CommissionPaymentResponse>();
        for (var payment : request.payments()) result.add(payments.create(payment));
        return result;
    }
}
