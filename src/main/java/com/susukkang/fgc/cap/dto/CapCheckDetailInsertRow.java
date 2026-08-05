package com.susukkang.fgc.cap.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * cap_check_detail 1행 INSERT 파라미터.
 *
 * evidenceRef가 없는 이유: 이 엔진(REALTIME/MONTHLY)이 만드는 detail 행은 schedule_line 기반
 * 산입 후보라 계산 시점엔 증빙이 존재 X(증빙은 실제 지급 건 확정 때 transaction_attribution
 * 경로로 붙는다)
 *
 * item_code/item_name/contract_month_no를 스냅샷하지 않는 이유: cap_check_detail 테이블 자체에
 * 그 컬럼 X(commission_item_id로만 참조)
 * 이 이력 불변성 문제는 스키마 변경(컬럼 추가)이 필요해서 IF-API-31을 구현할 담당자가 필요하면 마이그레이션과 함께 처리해야 함
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapCheckDetailInsertRow {
    private Long capCheckId;
    private int detailSeq;
    private Long commissionItemId;
    private Long scheduleLineId;
    private String classificationSnapshot;
    private BigDecimal amount;
    private String decisionReason;
}
