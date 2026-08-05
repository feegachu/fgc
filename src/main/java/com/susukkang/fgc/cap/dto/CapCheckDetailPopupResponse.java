package com.susukkang.fgc.cap.dto;

import java.util.List;
import java.util.Map;

/**
 * IF-API-31 응답. calculation_snapshot 은 검증 당시 저장된 스냅샷을 그대로 내려준다 — 팝업에서
 * 다시 계산하지 않는다(CAP-W02 "막아야 할 것").
 */
public record CapCheckDetailPopupResponse(
        CapCheckItemResponse capCheck,
        List<CapCheckDetailItemResponse> details,
        Map<String, Object> calculationSnapshot
) {
    public static CapCheckDetailPopupResponse from(CapCheckSaveResult saved) {
        List<CapCheckDetailItemResponse> details = saved.result().details().stream()
                .map(CapCheckDetailItemResponse::from)
                .toList();
        return new CapCheckDetailPopupResponse(
                CapCheckItemResponse.from(saved), details, saved.result().calculationSnapshot());
    }
}
