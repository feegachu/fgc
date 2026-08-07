package com.susukkang.fgc.cap.dto;

import java.math.BigDecimal;

/**
 * cap_check_detail 1건에 대응하는 산입·제외 근거 라인
 * classification 은 cap_rule_item.inclusion_status 와 같은 값(INCLUDED/EXCLUDED/REVIEW_REQUIRED)을 사용
 * IF-API-31(계산근거 팝업)이 항목명·근거자료를 그대로 노출해야 해서 itemName/evidenceRef 도 담는다.
 *
 * contractMonthNo 가 Integer 인 이유 — 예상 스케줄에서 온 상세행에만 회차가 있다.
 * 귀속행(실제 지급건) 출처의 상세행에는 회차 개념이 없어 NULL 이 정당하다
 * (ck_cap_detail_source · ck_cap_detail_month_snapshot). primitive 로 두면 언박싱에서 터진다.
 */
public record CapCheckDetailLine(
        int detailSeq,
        Long commissionItemId,
        String itemCode,
        String itemName,
        Long scheduleLineId,
        Integer contractMonthNo,
        String classification,
        BigDecimal amount,
        String decisionReason,
        String evidenceRef
) {
}
