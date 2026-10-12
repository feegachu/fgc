package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : IF-API-31 게이지 주의 표시선 위치 (#490)
 */
class CapCheckBasisResponseTest {

    // 해약환급금 288,000원(한도 912,000) → 주의 시작 산입 792,000 = 사용률 86.842105%
    @Test
    void placesWarningMarkWhereRegulatoryBudgetReachesWarningPct() {
        // 저장 JSON을 Map으로 읽으면 90은 Integer로 들어온다
        CapCheckBasisResponse response = response("912000", "288000", Map.of("warningUsagePct", 90));

        assertThat(new BigDecimal(response.warningStartUsagePct())).isEqualByComparingTo("86.842105");
    }

    @Test
    void keepsWarningMarkAtWarningPctWithoutRefund() {
        CapCheckBasisResponse response = response("1200000", "0", Map.of("warningUsagePct", new BigDecimal("90.0000")));

        assertThat(new BigDecimal(response.warningStartUsagePct())).isEqualByComparingTo("90");
    }

    @Test
    void hidesWarningMarkWhenLimitIsZero() {
        CapCheckBasisResponse response = response("0", "1200000", Map.of("warningUsagePct", 90));

        assertThat(response.warningStartUsagePct()).isNull();
    }

    private CapCheckBasisResponse response(String limit, String refund, Map<String, Object> snapshot) {
        CapCalculationResult result = new CapCalculationResult(
                1L, PaymentStage.GA_TO_FC, CapCheckKind.REALTIME, LocalDate.of(2026, 7, 1), 31L, null,
                new BigDecimal("100000"), new BigDecimal(refund), BigDecimal.ZERO, new BigDecimal(limit),
                BigDecimal.ZERO, new BigDecimal(limit), BigDecimal.ZERO, CapResultStatus.NORMAL,
                List.of(), snapshot);
        return CapCheckBasisResponse.from(new CapCheckSaveResult(1L, result), "C-1");
    }
}
