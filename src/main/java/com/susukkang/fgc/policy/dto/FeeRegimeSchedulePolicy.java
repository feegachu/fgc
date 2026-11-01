package com.susukkang.fgc.policy.dto;

import java.util.List;

/**
 * FUN-014가 FUN-037·038에 제공하는 읽기 전용 계산 입력.
 * 정책 판·근거·선택 사유와 실제 지급 규칙을 함께 전달한다. 지급 확정 가능 여부를 뜻하지 않는다.
 */
public record FeeRegimeSchedulePolicy(
        FeeRegimeRulesetSelection selection,
        ResolvedCommissionPolicy commissionPolicy,
        Integer versionNo,
        List<String> sourceRefs
) {
    public boolean isSelected() {
        return selection.isSelected() && commissionPolicy != null;
    }
}
