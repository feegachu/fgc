package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationResult;

/**
 * 설명 : 한도 검증 결과에 따라 주의·위반 예외 건을 등록하는 서비스
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public interface CapExceptionService {
    /**
     * 설명 : 1200% 한도 계산 결과를 바탕으로 예외 건을 생성하는 함수
     * @author hjKang
     * @since 2026-08-12
     */
    void createIfNecessary(Long paymentId,Long agentId,CapCalculationResult result);
}