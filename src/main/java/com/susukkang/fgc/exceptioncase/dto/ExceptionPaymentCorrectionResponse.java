package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;

public record ExceptionPaymentCorrectionResponse(CommissionPaymentResponse payment,
        boolean resolved, String message, ExceptionActionResponse action,
        java.util.List<ExceptionReconciliationIssue> remainingIssues, CommissionPaymentResponse deferredPayment) {}
