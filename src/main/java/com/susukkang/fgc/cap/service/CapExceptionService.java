package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapExceptionCreateCommand;
import com.susukkang.fgc.cap.dto.CapExceptionResolveCommand;

/**
 * 설명 : 한도 검증 결과에 따라 주의·위반 예외 건을 등록하는 서비스
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public interface CapExceptionService {

    /**
     * 설명 : 1,200% 한도 계산 결과가 주의 또는 위반인 경우 예외 건을 생성한다
     *
     * @param command 한도 예외 생성 명령
     * @author hjKang
     * @since 2026-08-12
     */
    void createIfNecessary(CapExceptionCreateCommand command);

    /**
     * 설명 : 해결조치를 기록하고 한도 예외를 해결 완료 상태로 변경한다
     *
     * @param command 한도 예외 해결 명령
     * @author hjKang
     * @since 2026-08-12
     */
    void resolve(CapExceptionResolveCommand command);

    /**
     * 설명 : 지급 건에 미해결 한도 위반 예외가 존재하는지 확인한다
     *
     * @param paymentId 지급 건 ID
     * @return 미해결 한도 위반 예외 존재 여부
     * @author hjKang
     * @since 2026-08-12
     */
    boolean hasUnresolvedViolation(Long paymentId);
}
