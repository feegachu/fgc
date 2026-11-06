package com.susukkang.fgc.reconciliation.repository;

/**
 * 설명 : 지급예정일 시점 계약 상태를 읽는 SQL 조각. 대사 분류(비유효 계약 지급)와
 * 예상 원천(지급 대상 아닌 회차 제외)이 같은 날짜 경계를 쓰도록 한 곳에 둔다.
 * 조각은 별칭 {@code c}(insurance_contract)와 {@code sl.due_date}(지급예정일)를 참조한다.
 * 모두 컴파일 시점 상수라 쿼리에 사용자 입력이 섞이지 않는다.
 *
 * @author yslee
 * @since 2026-10-12
 * @version 1.1
 */
final class ContractStatusOnDueDateSql {

    /** STATUS_MONTH_RULE = PAY_IF_ACTIVE_ON_DUE_DATE에서 그 회차를 지급하지 않는 상태(인터페이스정의서 §9-5). */
    static final String NON_PAYABLE_STATUSES = "('CANCELLED','TERMINATED','MATURED','LAPSED')";

    /** 효력일 기준 최신 상태 사건. 같은 효력시각이면 event_seq가 큰 사건이 이긴다. 사건이 없으면 NULL. */
    static final String LATEST_EVENT_STATUS = """
            (SELECT l.new_status FROM fgc.contract_status_event l
              WHERE l.contract_id = c.contract_id
              ORDER BY l.effective_at DESC, l.event_seq DESC
              LIMIT 1)""";

    /** 효력일이 지급예정일(Asia/Seoul 날짜) 끝 이전인 상태 사건 중 최신 것. 사건이 없으면 NULL. */
    static final String STATUS_ON_DUE_DATE = """
            (SELECT e.new_status FROM fgc.contract_status_event e
              WHERE e.contract_id = c.contract_id
                AND e.effective_at < (CAST(sl.due_date AS timestamp) + INTERVAL '1 day') AT TIME ZONE 'Asia/Seoul'
              ORDER BY e.effective_at DESC, e.event_seq DESC
              LIMIT 1)""";

    /**
     * 상태 사건으로 확인된 "그 회차는 지급 대상이 아님". 청약철회는 처음부터 무효라 현재 상태로 본다.
     * 나머지는 계약의 현재 상태와 무관하게 지급예정일 시점 상태로 본다 — 실효 뒤 부활(REVIVED)한 계약의
     * 실효 기간 회차도 지급 대상이 아니다(REVIVAL_BACKFILL_RULE = NO_RETROACTIVE). 단 최신 상태 사건이
     * 현재 상태와 같을 때만 이력을 믿는다. FUN-026 이전의 계약 수정 화면 상태 변경은 사건을 남기지 않았다.
     */
    static final String NON_PAYABLE_ON_DUE_DATE = "(c.current_status = 'CANCELLED' OR ("
            + LATEST_EVENT_STATUS + " IS NOT DISTINCT FROM c.current_status AND COALESCE("
            + STATUS_ON_DUE_DATE + ", '') IN " + NON_PAYABLE_STATUSES + "))";

    private ContractStatusOnDueDateSql() {
    }
}
