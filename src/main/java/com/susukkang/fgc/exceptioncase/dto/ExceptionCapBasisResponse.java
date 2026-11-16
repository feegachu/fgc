package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.cap.dto.CapCheckBasisResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import java.util.List;

/** 검출 당시 근거와 해당 예외의 지급 정정으로 생성된 근거를 구분한다. */
public record ExceptionCapBasisResponse(CapCheckBasisResponse detectedBasis,
                                       CapCheckBasisResponse currentBasis,
                                       List<CommissionPaymentResponse> deferredPayments) {}
