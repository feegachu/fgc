package com.susukkang.fgc.exceptioncase.service;

import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCapBasisResponse;
import com.susukkang.fgc.exceptioncase.repository.ExceptionPaymentRepository;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExceptionCapBasisService {
    private final ExceptionPaymentRepository repository;
    private final CapCheckService caps;
    private final CommissionPaymentService payments;

    @Transactional(readOnly = true)
    public ExceptionCapBasisResponse find(Long id) {
        var scope = repository.capScope(id);
        if (scope == null) throw new FgcBusinessException(FgcErrorCode.COMMON_004);
        var detected = caps.findDetail(scope.checkId()).orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004));
        var correctedId = repository.correctedCapCheckId(id, scope);
        var current = correctedId == null ? null : caps.findDetail(correctedId).orElse(null);
        return new ExceptionCapBasisResponse(detected, current,
                repository.deferredPaymentIds(id).stream().map(payments::get).toList());
    }
}
