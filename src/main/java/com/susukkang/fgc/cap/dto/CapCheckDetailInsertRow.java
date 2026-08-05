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
 * 산입 후보라 계산 시점엔 증빙이 존재하지 않는다(증빙은 실제 지급 건 확정 때 transaction_attribution
 * 경로로 붙는다 — 아직 미구현). 그래서 evidence_ref 컬럼은 항상 NULL로 INSERT된다.
 *
 * item_code/item_name/contract_month_no를 스냅샷하지 않는 이유: cap_check_detail 테이블 자체에
 * 그 컬럼이 없다(commission_item_id로만 참조). 그래서 조회(findDetailsByCapCheckId)는 지금도
 * commission_item/schedule_line을 다시 조인해서 읽는다 — 나중에 상품명이 바뀌면 과거 판정의
 * 계산근거 팝업(CAP-W02, FUN-035)에 그 시점 이름이 아니라 최신 이름이 보일 수 있다. 이 이력 불변성
 * 문제는 스키마 변경(컬럼 추가)이 필요해서 이 이슈(FUN-030) 범위 밖으로 남겨두고, IF-API-31을
 * 구현할 담당자(강현준)가 필요하면 마이그레이션과 함께 처리해야 한다.
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
