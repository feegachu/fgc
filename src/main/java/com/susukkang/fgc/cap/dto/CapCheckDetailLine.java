package com.susukkang.fgc.cap.dto;

import java.math.BigDecimal;

/**
 * cap_check_detail 1건에 대응하는 산입·제외 근거 라인
 * classification 은 cap_rule_item.inclusion_status 와 같은 값(INCLUDED/EXCLUDED/REVIEW_REQUIRED)을 사용
 * IF-API-31(계산근거 팝업)이 항목명·근거자료를 그대로 노출해야 해서 itemName/evidenceRef 도 담는다.
 */
public record CapCheckDetailLine(
        int detailSeq,
        Long commissionItemId,
        String itemCode,
        String itemName,
        Long scheduleLineId,
        int contractMonthNo,
        String classification,
        BigDecimal amount,
        String decisionReason,
        String evidenceRef
) {
}
