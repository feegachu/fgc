package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import java.util.List;

public record ExceptionPaymentContext(List<CommissionPaymentResponse> payments,
        CommissionPaymentCreateRequest suggestedPayment,
        com.susukkang.fgc.cap.dto.CapCheckBasisResponse capBasis,
        boolean scheduledBasis, String scheduleLink, String policyLink,
        String correctionTarget, com.susukkang.fgc.common.code.PaymentStage paymentStage, String contractLink,
        List<ExceptionReconciliationIssue> remainingIssues, String reconciliationResultType,
        boolean canRegisterPayment, java.time.LocalDate deferrableFrom, java.util.Map<Long, Long> paymentJournalIds) {}
