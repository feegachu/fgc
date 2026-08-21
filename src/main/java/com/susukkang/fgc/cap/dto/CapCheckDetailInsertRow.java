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
 * itemCode/itemName/contractMonthNo는 계산 당시 값을 그대로 스냅샷한다(V5 마이그레이션으로 추가된
 * 컬럼) — commission_item/schedule_line이 나중에 바뀌어도 과거 판정의 계산근거는 그때 값 그대로
 * 남아야 하기 때문이다(CAP-W02 요구사항, PR #2 코드리뷰 지적).
 *
 * evidenceRef는 증빙 필수 제외항목에 연결된 transaction_attribution의 증빙 참조를 스냅샷한다.
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
    private String itemCode;
    private String itemName;
    private Long scheduleLineId;
    /** 실제 지급 건 귀속행 출처(PRE_CONFIRM)일 때만 채운다 — scheduleLineId 와 동시 설정 금지(ck_cap_detail_source). */
    private Long transactionAttributionId;
    private Integer contractMonthNo;
    private String classificationSnapshot;
    private BigDecimal amount;
    private String decisionReason;
    private String evidenceRef;
}
