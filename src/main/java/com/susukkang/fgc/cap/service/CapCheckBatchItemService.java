package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : CapCheckBatchItemService
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-14
 */
@Service
@RequiredArgsConstructor
public class CapCheckBatchItemService {
    private final CapCheckService capCheckService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CapCheckSaveResult process(
            CapCalculationCommand command
    ) {
        return capCheckService.calculateAndSave(command);
    }
}