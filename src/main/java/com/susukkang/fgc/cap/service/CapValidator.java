package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;

/**
 * 설명 : 실제 지급 후보의 저장·확정 가능 여부 검증기
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
public interface CapValidator {
    CapValidationResult validate(CapValidationRequest request);
}
