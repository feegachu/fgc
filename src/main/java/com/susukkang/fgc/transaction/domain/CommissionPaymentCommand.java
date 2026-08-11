package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.PaymentStage;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 수수료 지급 건 등록·수정 명령
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
// 2026-08-11 yslee - 지급 건 증빙 참조를 저장 명령에 포함
// 기존 코드: 요청 evidenceRef를 MyBatis INSERT·UPDATE까지 전달할 도메인 필드가 없음
// 문제: DB에 evidence_ref 컬럼이 있어도 지급 건 증빙이 항상 NULL로 저장
// 개선: 부모 증빙과 귀속행 증빙을 분리해 CommissionPaymentCommand에 보존
@Getter
@Builder
public class CommissionPaymentCommand {

    @Setter
    private Long paymentId;
    private final String sourceType;
    private final String sourceBusinessKey;
    private final Long sourceContractId;
    private final Long agentId;
    private final Long commissionItemId;
    private final PaymentStage paymentStage;
    private final Long policyVersionId;
    private final LocalDate settlementMonth;
    private final LocalDate dueDate;
    private final BigDecimal amount;
    private final String cashflowType;
    private final String evidenceRef;
    private final String note;
    private final Long naturalContractId;
}
