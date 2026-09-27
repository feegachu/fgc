package com.susukkang.fgc.common.code;

import lombok.Getter;

/**
 * 설명 : 계약 재무 스냅샷의 해약환급금 유형
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Getter
public enum SurrenderValueType {

    ACTUAL("실제 해약환급금"),
    EXPECTED_TABLE("예상 환급률표"),
    ESTIMATED("추정 해약환급금");

    private final String label;

    SurrenderValueType(String label) {
        this.label = label;
    }

}