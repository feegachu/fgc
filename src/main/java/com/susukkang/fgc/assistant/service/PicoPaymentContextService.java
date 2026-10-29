package com.susukkang.fgc.assistant.service;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Objects;

/** Internal snapshot only: no identifiers, amounts or stored state are sent to the model. */
@Service
@RequiredArgsConstructor
public class PicoPaymentContextService {
    private final CommissionPaymentService payments;
    public record Snapshot(CommissionPaymentStatus status) {}

    @PreAuthorize(Roles.ANY_ROLE)
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot read(Long paymentId) {
        if (paymentId == null || paymentId <= 0)
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "entityRefs", Map.of("field", "entityRefs"), "지급 참조를 확인하세요.");
        try {
            var payment = payments.get(paymentId);
            if (!Objects.equals(payment.paymentId(), paymentId) || payment.status() == null)
                throw new IllegalStateException("Invalid payment context snapshot");
            return new Snapshot(payment.status());
        } catch (FgcBusinessException error) {
            // Translate only the existing payment service's missing-row error; preserve other validation and authorization.
            if (error.getErrorCode() == FgcErrorCode.COMMON_002 && "paymentId".equals(error.getField()))
                throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", paymentId));
            throw error;
        }
    }
}
