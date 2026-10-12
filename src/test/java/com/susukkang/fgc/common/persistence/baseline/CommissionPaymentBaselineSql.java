package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class CommissionPaymentBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "selectByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
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
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.settlementMonth") != null && !"".equals(value(input, "condition.settlementMonth"))) {
                    parameters.addValue("p0", parameter(value(input, "condition.settlementMonth")));
                    sql.append("""
                        AND ct.settlement_month = CAST(CONCAT(:p0, '-01') AS date)
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.paymentStage") != null) {
                    parameters.addValue("p1", parameter(value(input, "condition.paymentStage")));
                    sql.append("""
                        AND ct.payment_stage = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.status") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.status")));
                    sql.append("""
                        AND ct.status = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.sourceType") != null && !"".equals(value(input, "condition.sourceType"))) {
                    parameters.addValue("p3", parameter(value(input, "condition.sourceType")));
                    sql.append("""
                        AND ct.source_type = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.insurerId") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.insurerId")));
                    sql.append("""
                        AND COALESCE(ct.insurer_id, source_contract.insurer_id) = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p5", parameter(value(input, "condition.contractNo")));
                    parameters.addValue("p6", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND (
                            source_contract.contract_no ILIKE CONCAT('%', :p5, '%')
                            OR EXISTS (
                                SELECT 1
                                  FROM fgc.transaction_attribution filter_ta
                                  JOIN fgc.insurance_contract filter_contract
                                    ON filter_contract.contract_id = filter_ta.contract_id
                                 WHERE filter_ta.commission_transaction_id = ct.commission_transaction_id
                                   AND filter_contract.contract_no ILIKE CONCAT('%', :p6, '%')
                            )
                        )
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.agentId") != null) {
                    parameters.addValue("p7", parameter(value(input, "condition.agentId")));
                    sql.append("""
                        AND ct.recipient_agent_id = :p7
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.commissionItemId") != null) {
                    parameters.addValue("p8", parameter(value(input, "condition.commissionItemId")));
                    sql.append("""
                        AND ct.commission_item_id = :p8
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.noAttributionOnly") != null && Boolean.TRUE.equals(value(input, "condition.noAttributionOnly"))) {
                    sql.append("""
                        AND NOT EXISTS (
                            SELECT 1
                              FROM fgc.transaction_attribution noattr_ta
                             WHERE noattr_ta.commission_transaction_id = ct.commission_transaction_id
                        )
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.attributionImbalanceOnly") != null && Boolean.TRUE.equals(value(input, "condition.attributionImbalanceOnly"))) {
                    sql.append("""
                        AND EXISTS (
                            SELECT 1
                              FROM fgc.vw_transaction_attribution_balance imbalance
                             WHERE imbalance.commission_transaction_id = ct.commission_transaction_id
                        )
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p9", parameter(value(input, "size")));
                parameters.addValue("p10", parameter(value(input, "offset")));
                sql.append("""
                    GROUP BY ct.commission_transaction_id, agent.agent_name, item.item_code, item.item_name
                     ORDER BY ct.commission_transaction_id DESC
                     LIMIT :p9
                    OFFSET :p10
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.dto.CommissionPaymentListResponse.class), "", "");
            }
            case "countByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT COUNT(*)
                      FROM fgc.commission_transaction ct
                      LEFT JOIN fgc.insurance_contract source_contract
                        ON source_contract.contract_id = ct.source_contract_id
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.settlementMonth") != null && !"".equals(value(input, "condition.settlementMonth"))) {
                    parameters.addValue("p0", parameter(value(input, "condition.settlementMonth")));
                    sql.append("""
                        AND ct.settlement_month = CAST(CONCAT(:p0, '-01') AS date)
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.paymentStage") != null) {
                    parameters.addValue("p1", parameter(value(input, "condition.paymentStage")));
                    sql.append("""
                        AND ct.payment_stage = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.status") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.status")));
                    sql.append("""
                        AND ct.status = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.sourceType") != null && !"".equals(value(input, "condition.sourceType"))) {
                    parameters.addValue("p3", parameter(value(input, "condition.sourceType")));
                    sql.append("""
                        AND ct.source_type = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.insurerId") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.insurerId")));
                    sql.append("""
                        AND COALESCE(ct.insurer_id, source_contract.insurer_id) = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p5", parameter(value(input, "condition.contractNo")));
                    parameters.addValue("p6", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND (
                            source_contract.contract_no ILIKE CONCAT('%', :p5, '%')
                            OR EXISTS (
                                SELECT 1
                                  FROM fgc.transaction_attribution filter_ta
                                  JOIN fgc.insurance_contract filter_contract
                                    ON filter_contract.contract_id = filter_ta.contract_id
                                 WHERE filter_ta.commission_transaction_id = ct.commission_transaction_id
                                   AND filter_contract.contract_no ILIKE CONCAT('%', :p6, '%')
                            )
                        )
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.agentId") != null) {
                    parameters.addValue("p7", parameter(value(input, "condition.agentId")));
                    sql.append("""
                        AND ct.recipient_agent_id = :p7
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.commissionItemId") != null) {
                    parameters.addValue("p8", parameter(value(input, "condition.commissionItemId")));
                    sql.append("""
                        AND ct.commission_item_id = :p8
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.noAttributionOnly") != null && Boolean.TRUE.equals(value(input, "condition.noAttributionOnly"))) {
                    sql.append("""
                        AND NOT EXISTS (
                            SELECT 1
                              FROM fgc.transaction_attribution noattr_ta
                             WHERE noattr_ta.commission_transaction_id = ct.commission_transaction_id
                        )
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.attributionImbalanceOnly") != null && Boolean.TRUE.equals(value(input, "condition.attributionImbalanceOnly"))) {
                    sql.append("""
                        AND EXISTS (
                            SELECT 1
                              FROM fgc.vw_transaction_attribution_balance imbalance
                             WHERE imbalance.commission_transaction_id = ct.commission_transaction_id
                        )
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "existsAgent" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "agentId")));
                sql.append("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM fgc.agent
                         WHERE agent_id = :p0
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Boolean.class), "", "");
            }
            case "existsEligibleNewcomerSupportAgent" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "agentId")));
                parameters.addValue("p1", parameter(value(input, "asOfDate")));
                sql.append("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM fgc.agent
                         WHERE agent_id = :p0
                           AND active_yn = TRUE
                           AND newcomer_support_eligible_yn = TRUE
                           AND prior_three_year_experience_yn = FALSE
                           AND newcomer_support_end_date >= :p1
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Boolean.class), "", "");
            }
            case "findAgentAppointmentDate" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "agentId")));
                sql.append("""
                    SELECT appointment_date
                      FROM fgc.agent
                     WHERE agent_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(java.time.LocalDate.class), "", "");
            }
            case "findContract" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT contract_id,
                           agent_id,
                           organization_id,
                           contract_date
                      FROM fgc.insurance_contract
                     WHERE contract_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.domain.ContractReference.class), "", "");
            }
            case "findAgentRankCode" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "agentId")));
                sql.append("""
                    SELECT rank_code
                      FROM fgc.agent
                     WHERE agent_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.common.code.AgentRankCode.class), "", "");
            }
            case "countContractsBeforeMonth" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "agentId")));
                parameters.addValue("p1", parameter(value(input, "monthStart")));
                sql.append("""
                    SELECT COUNT(*)
                      FROM fgc.insurance_contract
                     WHERE agent_id = :p0
                       AND contract_date < :p1
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Integer.class), "", "");
            }
            case "findCommissionItem" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "commissionItemId")));
                parameters.addValue("p1", parameter(value(input, "asOf")));
                parameters.addValue("p2", parameter(value(input, "asOf")));
                sql.append("""
                    SELECT commission_item_id,
                           item_code,
                           cashflow_type
                     FROM fgc.commission_item
                     WHERE commission_item_id = :p0
                       AND active_yn = TRUE
                       AND effective_from <= :p1
                       AND (effective_to IS NULL OR effective_to >= :p2)
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.domain.CommissionItemReference.class), "", "");
            }
            case "findAllocationPolicyId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "policyVersionId")));
                parameters.addValue("p1", parameter(value(input, "allocationBasis")));
                sql.append("""
                    SELECT allocation_policy_id
                      FROM fgc.allocation_policy
                     WHERE policy_version_id = :p0
                       AND allocation_basis_code = :p1
                     ORDER BY allocation_policy_id
                     LIMIT 1
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "findOperationalScheduleLineIds" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT sl.schedule_line_id
                    """);
                sql.append(" ");
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                parameters.addValue("p2", parameter(value(input, "commissionItemId")));
                parameters.addValue("p3", parameter(value(input, "beneficiaryAgentId")));
                parameters.addValue("p4", parameter(value(input, "attributionMonthStart")));
                parameters.addValue("p5", parameter(value(input, "nextAttributionMonthStart")));
                sql.append("""
                    FROM fgc.schedule_line sl
                     JOIN fgc.schedule_header sh
                       ON sh.schedule_header_id = sl.schedule_header_id
                    WHERE sh.contract_id = :p0
                      AND sh.payment_stage = :p1
                      AND sh.schedule_purpose = 'OPERATIONAL'
                      AND sh.active_yn = true
                      AND sl.commission_item_id = :p2

                      AND sl.beneficiary_agent_id IS NOT DISTINCT FROM :p3
                      AND sl.due_date  >=  :p4
                      AND sl.due_date  <  :p5
                      AND sl.line_status <> 'CANCELLED'
                    ORDER BY sl.schedule_line_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "findOperationalScheduleInstallmentNos" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT sl.installment_no
                    """);
                sql.append(" ");
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                parameters.addValue("p2", parameter(value(input, "commissionItemId")));
                parameters.addValue("p3", parameter(value(input, "beneficiaryAgentId")));
                parameters.addValue("p4", parameter(value(input, "attributionMonthStart")));
                parameters.addValue("p5", parameter(value(input, "nextAttributionMonthStart")));
                sql.append("""
                    FROM fgc.schedule_line sl
                     JOIN fgc.schedule_header sh
                       ON sh.schedule_header_id = sl.schedule_header_id
                    WHERE sh.contract_id = :p0
                      AND sh.payment_stage = :p1
                      AND sh.schedule_purpose = 'OPERATIONAL'
                      AND sh.active_yn = true
                      AND sl.commission_item_id = :p2

                      AND sl.beneficiary_agent_id IS NOT DISTINCT FROM :p3
                      AND sl.due_date  >=  :p4
                      AND sl.due_date  <  :p5
                      AND sl.line_status <> 'CANCELLED'
                    ORDER BY sl.schedule_line_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Integer.class), "", "");
            }
            case "existsPolicyVersion" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "policyVersionId")));
                sql.append("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM fgc.policy_version
                         WHERE policy_version_id = :p0
                           AND status IN ('APPROVED', 'ACTIVE')
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Boolean.class), "", "");
            }
            case "insertTransaction" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentStage")));
                parameters.addValue("p1", parameter(value(input, "sourceType")));
                parameters.addValue("p2", parameter(value(input, "sourceBusinessKey")));
                parameters.addValue("p3", parameter(value(input, "naturalContractId")));
                parameters.addValue("p4", parameter(value(input, "naturalContractId")));
                parameters.addValue("p5", parameter(value(input, "agentId")));
                parameters.addValue("p6", parameter(value(input, "commissionItemId")));
                parameters.addValue("p7", parameter(value(input, "policyVersionId")));
                parameters.addValue("p8", parameter(value(input, "settlementMonth")));
                parameters.addValue("p9", parameter(value(input, "dueDate")));
                parameters.addValue("p10", parameter(value(input, "installmentNo")));
                parameters.addValue("p11", parameter(value(input, "amount")));
                parameters.addValue("p12", parameter(value(input, "cashflowType")));
                parameters.addValue("p13", parameter(value(input, "evidenceRef")));
                parameters.addValue("p14", parameter(value(input, "note")));
                sql.append("""
                    INSERT INTO fgc.commission_transaction (
                        payment_stage,
                        source_type,
                        source_business_key,
                        source_contract_id,
                        insurer_id,
                        recipient_agent_id,
                        commission_item_id,
                        policy_version_id,
                        settlement_month,
                        due_date,
                        installment_no,
                        amount,
                        cashflow_type,
                        status,
                        evidence_ref,
                        note
                    ) VALUES (
                        :p0,
                        :p1,
                        :p2,
                        :p3,
                        (SELECT c.insurer_id FROM fgc.insurance_contract c WHERE c.contract_id = :p4),
                        :p5,
                        :p6,
                        :p7,
                        :p8,
                        :p9,
                        :p10,
                        :p11,
                        :p12,
                        'DRAFT',
                        :p13,
                        :p14
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "paymentId", "commission_transaction_id");
            }
            case "updateTransaction" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentStage")));
                parameters.addValue("p1", parameter(value(input, "sourceType")));
                parameters.addValue("p2", parameter(value(input, "sourceBusinessKey")));
                parameters.addValue("p3", parameter(value(input, "naturalContractId")));
                parameters.addValue("p4", parameter(value(input, "naturalContractId")));
                parameters.addValue("p5", parameter(value(input, "agentId")));
                parameters.addValue("p6", parameter(value(input, "commissionItemId")));
                parameters.addValue("p7", parameter(value(input, "policyVersionId")));
                parameters.addValue("p8", parameter(value(input, "settlementMonth")));
                parameters.addValue("p9", parameter(value(input, "dueDate")));
                parameters.addValue("p10", parameter(value(input, "installmentNo")));
                parameters.addValue("p11", parameter(value(input, "amount")));
                parameters.addValue("p12", parameter(value(input, "cashflowType")));
                parameters.addValue("p13", parameter(value(input, "evidenceRef")));
                parameters.addValue("p14", parameter(value(input, "note")));
                parameters.addValue("p15", parameter(value(input, "paymentId")));
                sql.append("""
                    UPDATE fgc.commission_transaction
                       SET payment_stage = :p0,
                           source_type = :p1,
                           source_business_key = :p2,
                           source_contract_id = :p3,
                           -- 2026-08-19 hjKang - 수기 INSURER_TO_GA 지급 건도 원수사 대사 대상이 되도록 보험회사를 채운다.
                           -- InsurerGaReconciliationMapper.findActualSources 가 ct.insurer_id 로 대상을 좁히는데
                           -- 기존에는 이 컬럼이 항상 NULL 이라 수기 건이 전부 탈락했다.
                           insurer_id = (SELECT c.insurer_id FROM fgc.insurance_contract c WHERE c.contract_id = :p4),
                           recipient_agent_id = :p5,
                           commission_item_id = :p6,
                           policy_version_id = :p7,
                           settlement_month = :p8,
                           due_date = :p9,
                           installment_no = :p10,
                           amount = :p11,
                           cashflow_type = :p12,
                           evidence_ref = :p13,
                           note = :p14
                     WHERE commission_transaction_id = :p15
                       AND status = 'DRAFT'
                       AND source_type = 'GA_MANUAL_PAYMENT'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "detachPreConfirmDetails" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
                    UPDATE fgc.cap_check_detail ccd
                       SET transaction_attribution_id = NULL
                      FROM fgc.cap_check cc
                     WHERE ccd.cap_check_id = cc.cap_check_id
                       AND cc.candidate_transaction_id = :p0
                       AND cc.check_kind = 'PRE_CONFIRM'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "deleteAttributions" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
                    DELETE FROM fgc.transaction_attribution
                     WHERE commission_transaction_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "insertAttributions" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    INSERT INTO fgc.transaction_attribution (
                        commission_transaction_id,
                        attribution_seq,
                        attribution_scope,
                        contract_id,
                        schedule_line_id,
                        agent_id,
                        attribution_date,
                        attribution_month,
                        attributed_amount,
                        inclusion_status_snapshot,
                        exclusion_type_snapshot,
                        attribution_method,
                        allocation_policy_id,
                        allocation_basis_snapshot,
                        evidence_ref
                    ) VALUES
                    """);
                sql.append(" ");
                sql.append("");
                int indexitem0 = 0;
                for (Object item0 : (java.util.Collection<?>) value(input, "attributions")) {
                    if (indexitem0 > 0) sql.append(",");
                    parameters.addValue("p1_" + indexitem0, parameter(value(item0, "paymentId")));
                    parameters.addValue("p2_" + indexitem0, parameter(value(item0, "attributionSequence")));
                    parameters.addValue("p3_" + indexitem0, parameter(value(item0, "attributionScope")));
                    parameters.addValue("p4_" + indexitem0, parameter(value(item0, "contractId")));
                    parameters.addValue("p5_" + indexitem0, parameter(value(item0, "scheduleLineId")));
                    parameters.addValue("p6_" + indexitem0, parameter(value(item0, "agentId")));
                    parameters.addValue("p7_" + indexitem0, parameter(value(item0, "attributionDate")));
                    parameters.addValue("p8_" + indexitem0, parameter(value(item0, "attributionMonth")));
                    parameters.addValue("p9_" + indexitem0, parameter(value(item0, "amount")));
                    parameters.addValue("p10_" + indexitem0, parameter(value(item0, "inclusionDecisionStatus")));
                    parameters.addValue("p11_" + indexitem0, parameter(value(item0, "exclusionType")));
                    parameters.addValue("p12_" + indexitem0, parameter(value(item0, "attributionMethod")));
                    parameters.addValue("p13_" + indexitem0, parameter(value(item0, "allocationPolicyId")));
                    parameters.addValue("p14_" + indexitem0, parameter(value(item0, "allocationBasisJson")));
                    parameters.addValue("p15_" + indexitem0, parameter(value(item0, "evidenceRef")));
                    sql.append("""
                        (

                        """);
                    sql.append(":p1_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p2_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p3_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p4_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p5_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p6_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p7_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p8_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p9_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p10_").append(indexitem0);
                    sql.append("""
                        ,
                                        NULLIF(
                        """);
                    sql.append(":p11_").append(indexitem0);
                    sql.append("""
                        , 'NONE'),

                        """);
                    sql.append(":p12_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p13_").append(indexitem0);
                    sql.append("""
                        ,
                                        CAST(
                        """);
                    sql.append(":p14_").append(indexitem0);
                    sql.append("""
                         AS jsonb),

                        """);
                    sql.append(":p15_").append(indexitem0);
                    sql.append("""

                                    )
                        """);
                    sql.append(" ");
                    indexitem0++;
                }
                sql.append("");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "findById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
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
                     WHERE ct.commission_transaction_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.domain.CommissionPaymentRow.class), "", "");
            }
            case "findAttributions" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
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
                     WHERE ta.commission_transaction_id = :p0
                     ORDER BY ta.attribution_seq
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionRow.class), "", "");
            }
            case "findConfirmationDataForUpdate" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
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
                           c.contract_date,
                           ct.cashflow_type
                      FROM fgc.commission_transaction ct
                      JOIN fgc.commission_item ci
                        ON ci.commission_item_id = ct.commission_item_id




                      LEFT JOIN fgc.transaction_attribution ta
                        ON ta.commission_transaction_id = ct.commission_transaction_id
                      LEFT JOIN fgc.insurance_contract c
                        ON c.contract_id = ta.contract_id
                     WHERE ct.commission_transaction_id = :p0
                     ORDER BY ta.attribution_seq
                    """);
                sql.append(" ");
                sql.append("""
                    FOR UPDATE OF ct
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.domain.ConfirmationData.class), "", "");
            }
            case "findConfirmationData" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
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
                           c.contract_date,
                           ct.cashflow_type
                      FROM fgc.commission_transaction ct
                      JOIN fgc.commission_item ci
                        ON ci.commission_item_id = ct.commission_item_id




                      LEFT JOIN fgc.transaction_attribution ta
                        ON ta.commission_transaction_id = ct.commission_transaction_id
                      LEFT JOIN fgc.insurance_contract c
                        ON c.contract_id = ta.contract_id
                     WHERE ct.commission_transaction_id = :p0
                     ORDER BY ta.attribution_seq
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.domain.ConfirmationData.class), "", "");
            }
            case "findAttributedContractNumbers" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
                    SELECT DISTINCT ta.contract_id,
                           c.contract_no
                      FROM fgc.transaction_attribution ta
                      JOIN fgc.insurance_contract c
                        ON c.contract_id = ta.contract_id
                     WHERE ta.commission_transaction_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.domain.AttributedContractNo.class), "", "");
            }
            case "lockAttributedContracts" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
                    SELECT c.contract_id
                      FROM fgc.insurance_contract c
                     WHERE c.contract_id IN (
                           SELECT ta.contract_id
                             FROM fgc.transaction_attribution ta
                            WHERE ta.commission_transaction_id = :p0
                              AND ta.contract_id IS NOT NULL
                     )
                     ORDER BY c.contract_id
                     FOR UPDATE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "findCapRuleSnapshot" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "transactionAttributionId")));
                parameters.addValue("p1", parameter(value(input, "paymentId")));
                sql.append("""
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
                       AND ta.transaction_attribution_id = :p0
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
                     WHERE ct.commission_transaction_id = :p1
                     ORDER BY
                           (CASE WHEN crs.insurer_id IS NOT NULL THEN 1 ELSE 0 END
                          + CASE WHEN crs.product_group_code IS NOT NULL THEN 1 ELSE 0 END
                          + CASE WHEN crs.channel_code IS NOT NULL THEN 1 ELSE 0 END) DESC,
                              crs.contract_date_from DESC,
                              crs.cap_rule_set_id DESC
                     LIMIT 1
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.domain.CapRuleSnapshot.class), "", "");
            }
            case "existsApplicableCapRuleSet" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "transactionAttributionId")));
                parameters.addValue("p1", parameter(value(input, "paymentId")));
                sql.append("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM fgc.commission_transaction ct
                          JOIN fgc.transaction_attribution ta
                            ON ta.commission_transaction_id = ct.commission_transaction_id
                           AND ta.transaction_attribution_id = :p0
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
                         WHERE ct.commission_transaction_id = :p1
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Boolean.class), "", "");
            }
            case "insertCapCheck" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                parameters.addValue("p2", parameter(value(input, "capRuleSetId")));
                parameters.addValue("p3", parameter(value(input, "refundRateTableId")));
                parameters.addValue("p4", parameter(value(input, "paymentId")));
                parameters.addValue("p5", parameter(value(input, "asOfDate")));
                parameters.addValue("p6", parameter(value(input, "basePremiumAmount")));
                parameters.addValue("p7", parameter(value(input, "refund12mAmount")));
                parameters.addValue("p8", parameter(value(input, "complianceDeductionAmount")));
                parameters.addValue("p9", parameter(value(input, "limitAmount")));
                parameters.addValue("p10", parameter(value(input, "includedAmount")));
                parameters.addValue("p11", parameter(value(input, "remainingAmount")));
                parameters.addValue("p12", parameter(value(input, "usagePct")));
                parameters.addValue("p13", parameter(value(input, "resultStatus")));
                parameters.addValue("p14", parameter(value(input, "calculationSnapshotJson")));
                sql.append("""
                    INSERT INTO fgc.cap_check (
                        contract_id,
                        payment_stage,
                        cap_rule_set_id,
                        refund_rate_table_id,
                        candidate_transaction_id,
                        check_kind,
                        as_of_date,
                        base_premium_amount,
                        refund_12m_amount,
                        compliance_deduction_amount,
                        limit_amount,
                        included_amount,
                        remaining_amount,
                        usage_pct,
                        result_status,
                        calculation_snapshot
                    ) VALUES (
                        :p0,
                        :p1,
                        :p2,
                        :p3,
                        :p4,
                        'PRE_CONFIRM',
                        :p5,
                        :p6,
                        :p7,
                        :p8,
                        :p9,
                        :p10,
                        :p11,
                        :p12,
                        :p13,
                        CAST(:p14 AS jsonb)
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "capCheckId", "cap_check_id");
            }
            case "findExistingIncludedDetails" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "transactionAttributionId")));
                parameters.addValue("p1", parameter(value(input, "paymentId")));
                sql.append("""
                    SELECT other_ta.transaction_attribution_id AS transactionAttributionId,
                           other_ct.commission_item_id         AS commissionItemId,
                           ci.item_code                        AS itemCode,
                           ci.item_name                        AS itemName,
                           other_ta.inclusion_status_snapshot  AS classificationSnapshot,
                           CASE
                               WHEN other_ct.cashflow_type = 'DEDUCTION'
                                   THEN -ROUND(other_ta.attributed_amount, 0)
                               ELSE ROUND(other_ta.attributed_amount, 0)
                           END                                 AS amount,
                           COALESCE(NULLIF(BTRIM(cri.decision_reason), ''),
                                    '확정 당시 산입 판정 스냅샷') AS decisionReason,
                           other_ta.evidence_ref               AS evidenceRef
                      FROM fgc.commission_transaction ct
                      JOIN fgc.transaction_attribution ta
                        ON ta.commission_transaction_id = ct.commission_transaction_id
                       AND ta.transaction_attribution_id = :p0
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
                     WHERE ct.commission_transaction_id = :p1
                       AND other_ta.inclusion_status_snapshot = 'INCLUDED'
                       AND other_ct.payment_stage = ct.payment_stage
                       AND other_ct.status = 'CONFIRMED'
                       AND other_ct.commission_transaction_id <> ct.commission_transaction_id
                       AND other_ta.attribution_date >= c.contract_date
                       AND other_ta.attribution_date < c.contract_date + INTERVAL '12 months'
                     ORDER BY other_ta.attribution_date, other_ta.transaction_attribution_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.domain.ExistingIncludedDetail.class), "", "");
            }
            case "insertExceptionCase" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "exceptionKey")));
                parameters.addValue("p1", parameter(value(input, "exceptionType")));
                parameters.addValue("p2", parameter(value(input, "reasonCode")));
                parameters.addValue("p3", parameter(value(input, "severity")));
                parameters.addValue("p4", parameter(value(input, "contractId")));
                parameters.addValue("p5", parameter(value(input, "agentId")));
                parameters.addValue("p6", parameter(value(input, "policyVersionId")));
                parameters.addValue("p7", parameter(value(input, "paymentId")));
                parameters.addValue("p8", parameter(value(input, "sourceEntityId")));
                parameters.addValue("p9", parameter(value(input, "paymentId")));
                parameters.addValue("p10", parameter(value(input, "title")));
                parameters.addValue("p11", parameter(value(input, "description")));
                sql.append("""
                    INSERT INTO fgc.exception_case AS existing (
                        exception_key,
                        exception_type,
                        reason_code,
                        severity,
                        status,
                        contract_id,
                        agent_id,
                        policy_version_id,
                        source_entity_type,
                        source_entity_id,
                        validation_month,
                        title,
                        description
                    ) VALUES (
                        :p0,
                        :p1,
                        :p2,
                        :p3,
                        'NEW',
                        :p4,
                        :p5,
                        :p6,
                        'COMMISSION_TRANSACTION',
                        COALESCE(CAST(:p7 AS varchar), :p8),

                        (SELECT ct.settlement_month
                           FROM fgc.commission_transaction ct
                          WHERE ct.commission_transaction_id = :p9),
                        :p10,
                        :p11
                    )
                    ON CONFLICT (exception_key) DO UPDATE
                       SET status = 'NEW',
                           reason_code = EXCLUDED.reason_code,
                           validation_month = COALESCE(EXCLUDED.validation_month, existing.validation_month),
                           title = EXCLUDED.title,
                           description = EXCLUDED.description,
                           updated_at = clock_timestamp()
                     WHERE existing.status NOT IN ('RESOLVED', 'REJECTED')
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "findCapCheckIds" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
                    SELECT response.cap_check_id
                      FROM fgc.commission_transaction ct
                      CROSS JOIN LATERAL unnest(ct.confirm_cap_check_ids)
                           WITH ORDINALITY AS response(cap_check_id, response_order)
                     WHERE ct.commission_transaction_id = :p0
                     ORDER BY response.response_order
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "confirm" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "idempotencyKey")));
                parameters.addValue("p1", parameter(value(input, "capCheckIdsCsv")));
                parameters.addValue("p2", parameter(value(input, "paymentId")));
                sql.append("""
                    UPDATE fgc.commission_transaction
                       SET status = 'CONFIRMED',
                           confirm_idempotency_key = :p0,
                           confirm_cap_check_ids = string_to_array(:p1, ',')::bigint[]
                     WHERE commission_transaction_id = :p2
                       AND status = 'DRAFT'
                       AND source_type = 'GA_MANUAL_PAYMENT'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
