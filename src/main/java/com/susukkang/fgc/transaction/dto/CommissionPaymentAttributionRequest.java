package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 수수료 지급 건 계약별 귀속 요청
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
// 2026-08-11 yslee - 규제 산입기간 판정을 위한 실제 귀속일 입력 추가
// 기존 코드: 월의 첫날로 변환된 attributionMonth만 받아 실제 지급 귀속일을 잃음
// 문제: 초년도 12개월 경계일과 정착지원금 지급월 귀속을 일 단위로 재현할 수 없음
// 개선: attributionDate를 필수로 받고 attributionMonth는 저장 시 해당 날짜의 월 첫날로 파생
public record CommissionPaymentAttributionRequest(
        Long contractId,
        @NotNull LocalDate attributionDate,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull InclusionDecisionStatus inclusionDecisionStatus,
        ExclusionType exclusionType,
        @NotBlank @Size(max = 1000) String inclusionDecisionReason,
        @Size(max = 200) String allocationBasis,
        @Size(max = 500) String evidenceRef,
        @NotNull AttributionMethod attributionMethod
) {
}
