package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** IF-API-20 수수료 지급 건 목록 검색 조건. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommissionPaymentSearchCondition {
    /** yyyy-MM 형식. */
    private String settlementMonth;
    private PaymentStage paymentStage;
    private CommissionPaymentStatus status;
    private String sourceType;
    private Long insurerId;
    private String contractNo;
    private Long agentId;
    private Long commissionItemId;
    private Boolean noAttributionOnly;

    // 2026-08-19 yslee - FUN-044 귀속합계 오류 바로가기 조회 조건 추가
    // 기존 코드: 귀속행이 전혀 없는 지급 건만 별도 조회 가능
    // 문제: 지급액과 귀속합계가 다른 전체 건을 체크리스트에서 직접 확인할 수 없음
    // 개선: 정본 뷰와 같은 기준의 귀속 불균형 지급 건 선택 조회 지원
    private Boolean attributionImbalanceOnly;
}
