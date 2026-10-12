package com.susukkang.fgc.reconciliation.repository;

/**
 * 설명 : 지급예정일 시점 계약 상태를 읽는 SQL 조각. 대사 분류(비유효 계약 지급)와
 * 예상 원천(지급 대상 아닌 회차 제외)이 같은 날짜 경계를 쓰도록 한 곳에 둔다.
 * 효력일(effective_at)이 지급예정일(Asia/Seoul 날짜) 끝 이전인 상태 사건 중 최신 것의 new_status다.
 * 같은 효력시각이면 event_seq가 큰 사건이 이긴다. 사건이 없으면 NULL이다.
 *
 * @author yslee
 * @since 2026-10-12
 * @version 1.0
 */
final class ContractStatusOnDueDateSql {

    /** STATUS_MONTH_RULE = PAY_IF_ACTIVE_ON_DUE_DATE에서 그 회차를 지급하지 않는 상태(인터페이스정의서 §9-5). */
    static final String NON_PAYABLE_STATUSES = "('CANCELLED','TERMINATED','MATURED','LAPSED')";

    private ContractStatusOnDueDateSql() {
    }

    static String statusOn(String contractIdExpr, String dueDateExpr) {
        return """
                (SELECT e.new_status FROM fgc.contract_status_event e
                  WHERE e.contract_id = %s
                    AND e.effective_at < (CAST(%s AS timestamp) + INTERVAL '1 day') AT TIME ZONE 'Asia/Seoul'
                  ORDER BY e.effective_at DESC, e.event_seq DESC
                  LIMIT 1)""".formatted(contractIdExpr, dueDateExpr);
    }
}
