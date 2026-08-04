package com.susukkang.fgc.cap.dto;

/**
 * CapCheckService 가 계산 결과를 저장한 뒤 돌려주는 값. capCheckId 는 저장 후에만 존재하므로
 * 순수 계산 결과인 CapCalculationResult 와는 분리된 타입으로 둔다.
 */
public record CapCheckSaveResult(
        Long capCheckId,
        CapCalculationResult result
) {
}
