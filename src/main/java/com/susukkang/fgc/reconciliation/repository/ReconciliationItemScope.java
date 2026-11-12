package com.susukkang.fgc.reconciliation.repository;

/** 스케줄 대사에서 제외하는 별도 배부 비용. 한도 산입과 원장 처리는 변경하지 않는다. */
final class ReconciliationItemScope {
    static final String EXCLUDED_ITEM_IDS = """
            (SELECT commission_item_id FROM fgc.commission_item
              WHERE item_code IN ('COMMON_COST', 'SETTLEMENT_SUPPORT'))
            """;

    private ReconciliationItemScope() {
    }
}
