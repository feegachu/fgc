package com.susukkang.fgc.common.code;

/**
 * 설명 : 차익거래 검증 결과의 해약환급금이 어디로 부터 왔는지 유형을 보여주는 enum
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public enum SurrenderValueSourceType {
    ACTUAL("실제 해약환급금"),
    EXPECTED_TABLE("예상 환급률표"),
    NOT_APPLICABLE("적용 대상 아님");

    private final String label;

    SurrenderValueSourceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
