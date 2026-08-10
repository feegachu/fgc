package com.susukkang.fgc.common.code;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SIR-008: run_type 값 3개 전부 한글 라벨이 있어야 한다(#41 목록 응답이 runTypeLabel로 노출) */
class ValidationRunTypeTest {

    @Test
    void everyTypeHasALabel() {
        assertThat(ValidationRunType.MONTHLY.label()).isEqualTo("월정기검증");
        assertThat(ValidationRunType.MANUAL_CONTRACT.label()).isEqualTo("계약별 수동검증");
        assertThat(ValidationRunType.PRE_CONFIRM.label()).isEqualTo("지급확정 게이트검증");
    }
}
