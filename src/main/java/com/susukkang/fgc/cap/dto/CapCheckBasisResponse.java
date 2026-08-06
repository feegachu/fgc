package com.susukkang.fgc.cap.dto;

import java.util.List;
import java.util.Map;

/**
 * IF-API-31(계산근거 팝업) 응답. api-spec.md 명세: capCheck + details[] + calculationSnapshot.
 * cap_check 저장 당시의 계산 스냅샷을 재계산 없이 그대로 보여준다.
 *
 *   capCheck            - IF-API-30과 동일한 표시 형식(CapCheckItemResponse, SIR-008)의 판정 요약.
 *                          화면 ①입력값·②계산식·④합계/판정에 필요한 값이 전부 여기 들어있다.
 *   details[]           - cap_check_detail 스냅샷 (③항목별 산입 내역). commissionItemName/
 *                          classificationSnapshot 필드명은 명세 그대로.
 *   calculationSnapshot - premiumMultiplier/refundAdditionCondition/complianceDeductionPct 등
 *                          계산 시점 정책값 스냅샷. 화면이 ②계산식 문자열을 조립할 때 쓴다.
 *
 * 항목별 산입 합계는 details[]에서 classificationSnapshot='INCLUDED'인 것만 더해 화면에서
 * capCheck.includedAmount와 비교한다(서버가 별도 필드로 내려주지 않는다 — 화면정의서 CAP-W02
 * 목업도 이 합계를 클라이언트에서 계산한다).
 */
public record CapCheckBasisResponse(
        CapCheckItemResponse capCheck,
        List<CapCheckDetailResponse> details,
        Map<String, Object> calculationSnapshot
) {
    public static CapCheckBasisResponse from(CapCheckSaveResult saved, String contractNo) {
        CapCalculationResult r = saved.result();

        List<CapCheckDetailResponse> details = r.details().stream()
                .map(CapCheckDetailResponse::from)
                .toList();

        return new CapCheckBasisResponse(
                CapCheckItemResponse.from(saved, contractNo),
                details,
                r.calculationSnapshot()
        );
    }
}
