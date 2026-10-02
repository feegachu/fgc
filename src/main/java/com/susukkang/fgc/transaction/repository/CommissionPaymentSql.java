package com.susukkang.fgc.transaction.repository;

/**
 * 설명 : 지급 검색·집계 및 선택적 잠금에 필요한 PostgreSQL SQL.
 * JSON 스냅샷, 월 구간, LATERAL 배열 순서, FOR UPDATE OF와 기존 조건을 보존한다.
 *
 * @author hjKang
 * @since 2026-09-30
 * @version 1.0
 */
final class CommissionPaymentSql {
    private CommissionPaymentSql() { }

    static final String FIND_CONFIRMATION_DATA = """
            SELECT ct.commission_transaction_id AS payment_id,
                  ct.status,
                  ct.amount,
                  ta.attributed_amount,
                  (
                      SELECT SUM(total_ta.attributed_amount)
                        FROM fgc.transaction_attribution total_ta
                       WHERE total_ta.commission_transaction_id = ct.commission_transaction_id
                  ) AS total_attributed_amount,
                  ct.confirm_idempotency_key,
                  ta.attribution_date,
                  ta.attribution_month,
                  ta.transaction_attribution_id,
                  ta.contract_id,
                  ct.recipient_agent_id AS agent_id,
                  ct.payment_stage,
                  ct.commission_item_id,
                  ci.item_code,
                  ci.item_name,
                  ct.policy_version_id,
                  ta.inclusion_status_snapshot AS inclusion_decision_status,
                  COALESCE(ta.exclusion_type_snapshot, 'NONE') AS exclusion_type,
                  ta.allocation_basis_snapshot ->> 'inclusionDecisionReason' AS inclusion_decision_reason,
                  ta.allocation_basis_snapshot ->> 'allocationBasis' AS allocation_basis,
                  COALESCE(ta.evidence_ref, ct.evidence_ref) AS evidence_ref,
                  ta.attribution_method,
                  c.contract_date
             FROM fgc.commission_transaction ct
             JOIN fgc.commission_item ci
               ON ci.commission_item_id = ct.commission_item_id
             LEFT JOIN fgc.transaction_attribution ta
               ON ta.commission_transaction_id = ct.commission_transaction_id
             LEFT JOIN fgc.insurance_contract c
               ON c.contract_id = ta.contract_id
            WHERE ct.commission_transaction_id = :paymentId
            ORDER BY ta.attribution_seq
            """;

    static final String SELECT_BY_CONDITION = """
            SELECT ct.commission_transaction_id,
                           ct.payment_stage,
                           CASE ct.payment_stage
                               WHEN 'INSURER_TO_GA' THEN '원수사→GA'
                               WHEN 'GA_TO_FC' THEN 'GA→설계사'
                           END AS payment_stage_label,
                           ct.source_type,
                           CASE ct.source_type
                               WHEN 'INSURER_STATEMENT' THEN '원수사 명세'
                               WHEN 'GA_MANUAL_PAYMENT' THEN 'GA 수기지급'
                               WHEN 'GA_CONFIRMED_PAYMENT' THEN 'GA 확정지급'
                               WHEN 'ADJUSTMENT' THEN '조정'
                               WHEN 'ALLOCATION_POOL' THEN '공통비 풀'
                               WHEN 'CLAWBACK' THEN '환수'
                               WHEN 'RECOVERY' THEN '회수'
                           END AS source_type_label,
                           ct.source_business_key,
                           ct.settlement_month,
                           ct.recipient_agent_id,
                           agent.agent_name AS recipient_agent_name,
                           ct.commission_item_id,
                           item.item_code AS commission_item_code,
                           item.item_name AS commission_item_name,
                           ct.amount,
                           ct.cashflow_type,
                           CASE ct.cashflow_type WHEN 'PAYMENT' THEN '지급' WHEN 'DEDUCTION' THEN '차감' END
                               AS cashflow_type_label,
                           ct.status,
                           CASE ct.status WHEN 'DRAFT' THEN '작성중' WHEN 'CONFIRMED' THEN '확정' WHEN 'CANCELLED' THEN '취소' END
                               AS status_label,
                           COUNT(attribution.transaction_attribution_id)::integer AS attribution_count,
                           ct.amount - COALESCE(SUM(attribution.attributed_amount), 0) AS difference_amount
                      FROM fgc.commission_transaction ct
                      JOIN fgc.commission_item item
                        ON item.commission_item_id = ct.commission_item_id
                      LEFT JOIN fgc.agent agent
                        ON agent.agent_id = ct.recipient_agent_id
                      LEFT JOIN fgc.insurance_contract source_contract
                        ON source_contract.contract_id = ct.source_contract_id
                      LEFT JOIN fgc.transaction_attribution attribution
                        ON attribution.commission_transaction_id = ct.commission_transaction_id
            __FILTER__
                     GROUP BY ct.commission_transaction_id, agent.agent_name, item.item_code, item.item_name
                     ORDER BY ct.commission_transaction_id DESC
                     LIMIT :size
                    OFFSET :offset
            """;

    static final String COUNT_BY_CONDITION = """
            SELECT COUNT(*)
                      FROM fgc.commission_transaction ct
                      LEFT JOIN fgc.insurance_contract source_contract
                        ON source_contract.contract_id = ct.source_contract_id
            __FILTER__
            """;

    static final String FIND_ALLOCATION_POLICY_ID = """
            SELECT allocation_policy_id
              FROM fgc.allocation_policy
             WHERE policy_version_id = :policyVersionId
               AND allocation_basis_code = :allocationBasis
             ORDER BY allocation_policy_id
             LIMIT 1
            """;

    private static final String OPERATIONAL_SCHEDULE_LINE_CONDITIONS = """
             FROM fgc.schedule_line sl
             JOIN fgc.schedule_header sh
               ON sh.schedule_header_id = sl.schedule_header_id
            WHERE sh.contract_id = :contractId
              AND sh.payment_stage = :paymentStage
              AND sh.schedule_purpose = 'OPERATIONAL'
              AND sh.active_yn = true
              AND sl.commission_item_id = :commissionItemId
              AND sl.beneficiary_agent_id IS NOT DISTINCT FROM :beneficiaryAgentId
              AND sl.due_date  >=  :attributionMonthStart
              AND sl.due_date  <  :nextAttributionMonthStart
              AND sl.line_status <> 'CANCELLED'
            ORDER BY sl.schedule_line_id
            """;

    static final String FIND_OPERATIONAL_SCHEDULE_LINE_IDS =
            "SELECT sl.schedule_line_id\n" + OPERATIONAL_SCHEDULE_LINE_CONDITIONS;

    static final String FIND_OPERATIONAL_SCHEDULE_INSTALLMENT_NOS =
            "SELECT sl.installment_no\n" + OPERATIONAL_SCHEDULE_LINE_CONDITIONS;

    static final String FIND_BY_ID = """
            SELECT ct.commission_transaction_id AS payment_id,
                   ct.source_type,
                   ct.source_business_key,
                   ct.source_contract_id AS contract_id,
                   ct.recipient_agent_id AS agent_id,
                   ct.commission_item_id,
                   ci.item_code AS commission_item_code,
                   ci.item_name AS commission_item_name,
                   ct.amount,
                   ct.settlement_month,
                   ct.cashflow_type,
                   ct.due_date AS scheduled_payment_date,
                   ct.payment_stage,
                   ct.status,
                   ct.policy_version_id AS allocation_policy_version,
                   ct.evidence_ref,
                   ct.note,
                   ct.created_at,
                   ct.updated_at
              FROM fgc.commission_transaction ct
              JOIN fgc.commission_item ci
                ON ci.commission_item_id = ct.commission_item_id
             WHERE ct.commission_transaction_id = :paymentId
            """;

    static final String FIND_ATTRIBUTIONS = """
            SELECT ta.attribution_seq AS attribution_sequence,
                   ta.contract_id,
                   ta.attribution_date,
                   ta.attribution_month,
                   ta.attributed_amount AS amount,
                   ta.inclusion_status_snapshot AS inclusion_decision_status,
                   COALESCE(ta.exclusion_type_snapshot, 'NONE') AS exclusion_type,
                   ta.allocation_basis_snapshot ->> 'inclusionDecisionReason' AS inclusion_decision_reason,
                   ta.allocation_basis_snapshot ->> 'allocationBasis' AS allocation_basis,
                   ta.evidence_ref,
                   ta.attribution_method
              FROM fgc.transaction_attribution ta
             WHERE ta.commission_transaction_id = :paymentId
             ORDER BY ta.attribution_seq
            """;

    static final String FIND_CONFIRMATION_DATA_FOR_UPDATE = FIND_CONFIRMATION_DATA + "\nFOR UPDATE OF ct";

    static final String FIND_ATTRIBUTED_CONTRACT_NUMBERS = """
            SELECT DISTINCT ta.contract_id,
                   c.contract_no
              FROM fgc.transaction_attribution ta
              JOIN fgc.insurance_contract c
                ON c.contract_id = ta.contract_id
             WHERE ta.commission_transaction_id = :paymentId
            """;

    static final String LOCK_ATTRIBUTED_CONTRACTS = """
            SELECT c.contract_id
              FROM fgc.insurance_contract c
             WHERE c.contract_id IN (
                   SELECT ta.contract_id
                     FROM fgc.transaction_attribution ta
                    WHERE ta.commission_transaction_id = :paymentId
                      AND ta.contract_id IS NOT NULL
             )
             ORDER BY c.contract_id
             FOR UPDATE
            """;

    static final String FIND_CAP_RULE_SNAPSHOT = """
            SELECT crs.cap_rule_set_id,
                   cri.cap_rule_item_id,
                   cri.inclusion_status AS rule_inclusion_status,
                   cri.decision_reason,
                   c.monthly_equivalent_first_premium AS base_premium_amount,
                   crs.premium_multiplier,
                   crs.warning_usage_pct,
                   COALESCE((
                       SELECT SUM(
                                  CASE
                                      WHEN other_ct.cashflow_type = 'DEDUCTION'
                                          THEN -ROUND(other_ta.attributed_amount, 0)
                                      ELSE ROUND(other_ta.attributed_amount, 0)
                                  END
                              )
                         FROM fgc.transaction_attribution other_ta
                         JOIN fgc.commission_transaction other_ct
                           ON other_ct.commission_transaction_id = other_ta.commission_transaction_id
                        WHERE other_ta.contract_id = ta.contract_id
                          AND other_ta.inclusion_status_snapshot = 'INCLUDED'
                          AND other_ct.payment_stage = ct.payment_stage
                          AND other_ct.status = 'CONFIRMED'
                          AND other_ct.commission_transaction_id <> ct.commission_transaction_id
                          AND other_ta.attribution_date >= c.contract_date
                          AND other_ta.attribution_date < c.contract_date + INTERVAL '12 months'
                   ), 0) AS existing_included_amount,
                   (
                        SELECT SUM(
                                   CASE
                                       WHEN compliance_ct.cashflow_type = 'DEDUCTION'
                                           THEN -ROUND(compliance_ta.attributed_amount, 0)
                                       ELSE ROUND(compliance_ta.attributed_amount, 0)
                                   END
                               )
                         FROM fgc.transaction_attribution compliance_ta
                         JOIN fgc.commission_transaction compliance_ct
                           ON compliance_ct.commission_transaction_id = compliance_ta.commission_transaction_id
                        WHERE compliance_ta.contract_id = ta.contract_id
                          AND compliance_ta.exclusion_type_snapshot = 'COMPLIANCE_3PCT'
                          AND NULLIF(BTRIM(compliance_ta.evidence_ref), '') IS NOT NULL
                          AND compliance_ct.payment_stage = ct.payment_stage
                          AND (
                              compliance_ct.status = 'CONFIRMED'
                              OR compliance_ct.commission_transaction_id = ct.commission_transaction_id
                          )
                          AND compliance_ta.attribution_date >= c.contract_date
                          AND compliance_ta.attribution_date < c.contract_date + INTERVAL '12 months'
                   ) AS compliance_evidence_amount
              FROM fgc.commission_transaction ct
              JOIN fgc.transaction_attribution ta
                ON ta.commission_transaction_id = ct.commission_transaction_id
               AND ta.transaction_attribution_id = :transactionAttributionId
              JOIN fgc.insurance_contract c
                ON c.contract_id = ta.contract_id
              JOIN fgc.product_offering po
                ON po.product_offering_id = c.product_offering_id
              JOIN fgc.product p
                ON p.product_id = po.product_id
              JOIN fgc.cap_rule_set crs
                ON crs.payment_stage = ct.payment_stage
               AND c.contract_date >= crs.contract_date_from
               AND (crs.contract_date_to IS NULL OR c.contract_date <= crs.contract_date_to)
               AND (crs.insurer_id IS NULL OR crs.insurer_id = c.insurer_id)
               AND (crs.product_group_code IS NULL OR crs.product_group_code = p.product_group_code)
               AND (crs.channel_code IS NULL OR crs.channel_code = po.channel_code)
              JOIN fgc.policy_version cap_pv
                ON cap_pv.policy_version_id = crs.policy_version_id
               AND cap_pv.status = 'ACTIVE'
              JOIN fgc.cap_rule_item cri
                ON cri.cap_rule_set_id = crs.cap_rule_set_id
               AND cri.commission_item_id = ct.commission_item_id
             WHERE ct.commission_transaction_id = :paymentId
             ORDER BY
                   (CASE WHEN crs.insurer_id IS NOT NULL THEN 1 ELSE 0 END
                  + CASE WHEN crs.product_group_code IS NOT NULL THEN 1 ELSE 0 END
                  + CASE WHEN crs.channel_code IS NOT NULL THEN 1 ELSE 0 END) DESC,
                      crs.contract_date_from DESC,
                      crs.cap_rule_set_id DESC
             LIMIT 1
            """;

    static final String EXISTS_APPLICABLE_CAP_RULE_SET = """
            SELECT EXISTS (
                SELECT 1
                  FROM fgc.commission_transaction ct
                  JOIN fgc.transaction_attribution ta
                    ON ta.commission_transaction_id = ct.commission_transaction_id
                   AND ta.transaction_attribution_id = :transactionAttributionId
                  JOIN fgc.insurance_contract c
                    ON c.contract_id = ta.contract_id
                  JOIN fgc.product_offering po
                    ON po.product_offering_id = c.product_offering_id
                  JOIN fgc.product p
                    ON p.product_id = po.product_id
                  JOIN fgc.cap_rule_set crs
                    ON crs.payment_stage = ct.payment_stage
                   AND c.contract_date >= crs.contract_date_from
                   AND (crs.contract_date_to IS NULL OR c.contract_date <= crs.contract_date_to)
                   AND (crs.insurer_id IS NULL OR crs.insurer_id = c.insurer_id)
                   AND (crs.product_group_code IS NULL OR crs.product_group_code = p.product_group_code)
                   AND (crs.channel_code IS NULL OR crs.channel_code = po.channel_code)
                  JOIN fgc.policy_version cap_pv
                    ON cap_pv.policy_version_id = crs.policy_version_id
                   AND cap_pv.status = 'ACTIVE'
                 WHERE ct.commission_transaction_id = :paymentId
            )
            """;

    static final String FIND_EXISTING_INCLUDED_DETAILS = """
            SELECT other_ta.transaction_attribution_id AS transaction_attribution_id,
                   other_ct.commission_item_id         AS commission_item_id,
                   ci.item_code                        AS item_code,
                   ci.item_name                        AS item_name,
                   other_ta.inclusion_status_snapshot  AS classification_snapshot,
                   CASE
                       WHEN other_ct.cashflow_type = 'DEDUCTION'
                           THEN -ROUND(other_ta.attributed_amount, 0)
                       ELSE ROUND(other_ta.attributed_amount, 0)
                   END                                 AS amount,
                   COALESCE(NULLIF(BTRIM(cri.decision_reason), ''),
                            '확정 당시 산입 판정 스냅샷') AS decision_reason,
                   other_ta.evidence_ref               AS evidence_ref
              FROM fgc.commission_transaction ct
              JOIN fgc.transaction_attribution ta
                ON ta.commission_transaction_id = ct.commission_transaction_id
               AND ta.transaction_attribution_id = :transactionAttributionId
              JOIN fgc.insurance_contract c
                ON c.contract_id = ta.contract_id
              JOIN fgc.transaction_attribution other_ta
                ON other_ta.contract_id = ta.contract_id
              JOIN fgc.commission_transaction other_ct
                ON other_ct.commission_transaction_id = other_ta.commission_transaction_id
              JOIN fgc.commission_item ci
                ON ci.commission_item_id = other_ct.commission_item_id
              LEFT JOIN fgc.cap_rule_item cri
                ON cri.cap_rule_item_id = other_ta.cap_rule_item_id
             WHERE ct.commission_transaction_id = :paymentId
               AND other_ta.inclusion_status_snapshot = 'INCLUDED'
               AND other_ct.payment_stage = ct.payment_stage
               AND other_ct.status = 'CONFIRMED'
               AND other_ct.commission_transaction_id <> ct.commission_transaction_id
               AND other_ta.attribution_date >= c.contract_date
               AND other_ta.attribution_date < c.contract_date + INTERVAL '12 months'
             ORDER BY other_ta.attribution_date, other_ta.transaction_attribution_id
            """;

    static final String FIND_CAP_CHECK_IDS = """
            SELECT response.cap_check_id
              FROM fgc.commission_transaction ct
              CROSS JOIN LATERAL unnest(ct.confirm_cap_check_ids)
                   WITH ORDINALITY AS response(cap_check_id, response_order)
             WHERE ct.commission_transaction_id = :paymentId
             ORDER BY response.response_order
            """;
}
