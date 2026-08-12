package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;

/**
 * 설명 : 한도 초과 예외 처리 기능을 제공하는 인터페이스
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public interface CapExceptionService {
    // 한도초과 예외 발생 시 처리
    void createIfNecessary(CapCalculationCommand command, CapCalculationResult result);
}