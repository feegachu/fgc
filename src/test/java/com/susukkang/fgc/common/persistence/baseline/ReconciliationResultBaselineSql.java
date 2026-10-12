package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class ReconciliationResultBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "insertResult" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                parameters.addValue("p1", parameter(value(input, "matchGroupKey")));
                parameters.addValue("p2", parameter(value(input, "contractId")));
                parameters.addValue("p3", parameter(value(input, "expectedAgentId")));
                parameters.addValue("p4", parameter(value(input, "actualAgentId")));
                parameters.addValue("p5", parameter(value(input, "actualSourceAgentCode")));
                parameters.addValue("p6", parameter(value(input, "commissionItemId")));
                parameters.addValue("p7", parameter(value(input, "installmentNo")));
                parameters.addValue("p8", parameter(value(input, "resultType")));
                parameters.addValue("p9", parameter(value(input, "expectedTotalAmount")));
                parameters.addValue("p10", parameter(value(input, "actualTotalAmount")));
                parameters.addValue("p11", parameter(value(input, "differenceAmount")));
                parameters.addValue("p12", parameter(value(input, "primaryReasonCode")));
                sql.append("""
                    INSERT INTO fgc.reconciliation_result (
                        reconciliation_run_id,
                        match_group_key,
                        contract_id,
                        expected_agent_id,
                        actual_agent_id,
                        actual_source_agent_code,
                        commission_item_id,
                        installment_no,
                        result_type,
                        expected_total_amount,
                        actual_total_amount,
                        difference_amount,
                        primary_reason_code,
                        secondary_reason_codes,
                        detail_snapshot
                    ) VALUES (
                        :p0,
                        :p1,
                        :p2,
                        :p3,
                        :p4,
                        :p5,
                        :p6,
                        :p7,
                        :p8,
                        :p9,
                        :p10,
                        :p11,
                        :p12,
                    """);
                sql.append(" ");
                if (value(input, "secondaryReasonCodes") != null && !((java.util.Collection<?>) value(input, "secondaryReasonCodes")).isEmpty()) {
                    sql.append("""
                        ARRAY[
                        """);
                    sql.append(" ");
                    sql.append("");
                    int indexitem13 = 0;
                    for (Object item13 : (java.util.Collection<?>) value(input, "secondaryReasonCodes")) {
                        if (indexitem13 > 0) sql.append(",");
                        parameters.addValue("p14_" + indexitem13, parameter(item13));
                        sql.append(":p14_").append(indexitem13);
                        sql.append(" ");
                        indexitem13++;
                    }
                    sql.append("");
                    sql.append("""
                        ]::text[]
                        """);
                    sql.append(" ");
                }
                else {
                    sql.append("""
                        ARRAY[]::text[]
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p15", parameter(value(input, "detailSnapshotJson")));
                sql.append("""
                    ,
                                CAST(:p15 AS jsonb)
                            )
                            ON CONFLICT ON CONSTRAINT uq_reconciliation_result DO NOTHING
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "reconciliationResultId", "reconciliation_result_id");
            }
            case "findResultId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                parameters.addValue("p1", parameter(value(input, "matchGroupKey")));
                sql.append("""
                    SELECT reconciliation_result_id
                      FROM fgc.reconciliation_result
                     WHERE reconciliation_run_id = :p0
                       AND match_group_key = :p1
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "insertMatch" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationResultId")));
                parameters.addValue("p1", parameter(value(input, "matchSeq")));
                parameters.addValue("p2", parameter(value(input, "scheduleLineId")));
                parameters.addValue("p3", parameter(value(input, "transactionAttributionId")));
                parameters.addValue("p4", parameter(value(input, "matchedAmount")));
                parameters.addValue("p5", parameter(value(input, "matchRole")));
                sql.append("""
                    INSERT INTO fgc.reconciliation_match (
                        reconciliation_result_id,
                        match_seq,
                        schedule_line_id,
                        transaction_attribution_id,
                        matched_amount,
                        match_role
                    ) VALUES (
                        :p0,
                        :p1,
                        :p2,
                        :p3,
                        :p4,
                        :p5
                    )
                    ON CONFLICT ON CONSTRAINT uq_reconciliation_match DO NOTHING
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "countByRunId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                sql.append("""
                    SELECT COUNT(*)
                      FROM fgc.reconciliation_result
                     WHERE reconciliation_run_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "findClassificationContext" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "expectedAgentId")));
                parameters.addValue("p2", parameter(value(input, "actualAgentId")));
                parameters.addValue("p3", parameter(value(input, "expectedAgentId")));
                parameters.addValue("p4", parameter(value(input, "actualAgentId")));
                sql.append("""
                    SELECT EXISTS (
                               SELECT 1
                                 FROM fgc.insurance_contract contract
                                WHERE contract.contract_id = :p0
                                  AND contract.current_status IN ('CANCELLED', 'TERMINATED', 'MATURED', 'LAPSED')
                           ) AS invalidContractPayment,
                           CASE
                               WHEN CAST(:p1 AS bigint) IS NULL
                                 OR CAST(:p2 AS bigint) IS NULL THEN FALSE
                               ELSE COALESCE((
                                   SELECT expected.organization_id <> actual.organization_id
                                     FROM fgc.agent expected, fgc.agent actual
                                    WHERE expected.agent_id = :p3
                                      AND actual.agent_id = :p4
                               ), FALSE)
                           END AS organizationMismatch,
                    """);
                sql.append(" ");
                if (value(input, "journalHeaderIds") != null && !((java.util.Collection<?>) value(input, "journalHeaderIds")).isEmpty()) {
                    sql.append("""
                        (SELECT COUNT(DISTINCT header.policy_version_id) > 1
                           FROM fgc.journal_header header
                          WHERE header.journal_header_id IN
                        """);
                    sql.append(" ");
                    sql.append("(");
                    int indexitem5 = 0;
                    for (Object item5 : (java.util.Collection<?>) value(input, "journalHeaderIds")) {
                        if (indexitem5 > 0) sql.append(",");
                        parameters.addValue("p6_" + indexitem5, parameter(item5));
                        sql.append(":p6_").append(indexitem5);
                        sql.append(" ");
                        indexitem5++;
                    }
                    sql.append(")");
                    sql.append("""
                        AND header.policy_version_id IS NOT NULL)
                        """);
                    sql.append(" ");
                }
                else {
                    sql.append("""
                        FALSE
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    AS policyVersionError,
                    """);
                sql.append(" ");
                if (value(input, "journalHeaderIds") != null && !((java.util.Collection<?>) value(input, "journalHeaderIds")).isEmpty()) {
                    sql.append("""
                        EXISTS (
                            SELECT 1
                              FROM fgc.vw_journal_imbalance imbalance
                             WHERE imbalance.journal_header_id IN
                        """);
                    sql.append(" ");
                    sql.append("(");
                    int indexitem7 = 0;
                    for (Object item7 : (java.util.Collection<?>) value(input, "journalHeaderIds")) {
                        if (indexitem7 > 0) sql.append(",");
                        parameters.addValue("p8_" + indexitem7, parameter(item7));
                        sql.append(":p8_").append(indexitem7);
                        sql.append(" ");
                        indexitem7++;
                    }
                    sql.append(")");
                    sql.append("""
                        )
                        """);
                    sql.append(" ");
                }
                else {
                    sql.append("""
                        FALSE
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    AS journalImbalance
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.ReconciliationClassificationContext.class), "", "");
            }
            case "findResults" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT result.reconciliation_result_id AS reconciliationResultId,
                           contract.contract_no AS contractNo,
                           item.item_code AS commissionItemCode,
                           item.item_name AS commissionItemName,
                           result.installment_no AS installmentNo,
                           expected_agent.agent_id AS expectedAgentId,
                           expected_agent.agent_code AS expectedAgentCode,
                           expected_agent.agent_name AS expectedAgentName,
                           expected_org.organization_name AS expectedOrganizationName,
                           actual_agent.agent_id AS actualAgentId,
                           actual_agent.agent_code AS actualAgentCode,
                           actual_agent.agent_name AS actualAgentName,
                           actual_org.organization_name AS actualOrganizationName,
                           result.actual_source_agent_code AS actualSourceAgentCode,
                           result.expected_total_amount AS expectedTotalAmount,
                           result.actual_total_amount AS actualTotalAmount,
                           result.difference_amount AS differenceAmount,
                           result.result_type AS resultType,
                           result.primary_reason_code AS primaryReasonCode,
                           array_to_string(result.secondary_reason_codes, ',') AS secondaryReasonCodesCsv,
                           result.created_at AS createdAt
                    """);
                sql.append(" ");
                sql.append("""
                    FROM fgc.reconciliation_result result
                    LEFT JOIN fgc.insurance_contract contract ON contract.contract_id = result.contract_id
                    LEFT JOIN fgc.commission_item item ON item.commission_item_id = result.commission_item_id
                    LEFT JOIN fgc.agent expected_agent ON expected_agent.agent_id = result.expected_agent_id
                    LEFT JOIN fgc.organization expected_org
                      ON expected_org.organization_id = expected_agent.organization_id
                    LEFT JOIN fgc.agent actual_agent ON actual_agent.agent_id = result.actual_agent_id
                    LEFT JOIN fgc.organization actual_org
                      ON actual_org.organization_id = actual_agent.organization_id
                    """);
                sql.append(" ");
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                sql.append("""
                    WHERE result.reconciliation_run_id = :p0
                    """);
                sql.append(" ");
                if (value(input, "resultType") != null) {
                    parameters.addValue("p1", parameter(value(input, "resultType")));
                    sql.append("""
                        AND result.result_type = :p1
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ORDER BY result.created_at
                    """);
                sql.append(" ");
                if ("asc".equals(value(input, "sortDirection"))) {
                    sql.append("""
                        ASC
                        """);
                    sql.append(" ");
                }
                else {
                    sql.append("""
                        DESC
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ,
                            result.reconciliation_result_id
                    """);
                sql.append(" ");
                if ("asc".equals(value(input, "sortDirection"))) {
                    sql.append("""
                        ASC
                        """);
                    sql.append(" ");
                }
                else {
                    sql.append("""
                        DESC
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p2", parameter(value(input, "limit")));
                parameters.addValue("p3", parameter(value(input, "offset")));
                sql.append("""
                    LIMIT :p2 OFFSET :p3
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.ReconciliationResultListRow.class), "", "");
            }
            case "countResults" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                sql.append("""
                    SELECT COUNT(*)
                      FROM fgc.reconciliation_result result
                     WHERE result.reconciliation_run_id = :p0
                    """);
                sql.append(" ");
                if (value(input, "resultType") != null) {
                    parameters.addValue("p1", parameter(value(input, "resultType")));
                    sql.append("""
                        AND result.result_type = :p1
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "findSummary" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                sql.append("""
                    SELECT result_count AS resultCount,
                           matched_count AS matchedCount,
                           exception_count AS exceptionCount,
                           expected_total AS expectedTotal,
                           actual_total AS actualTotal,
                           difference_total AS differenceTotal
                      FROM fgc.vw_reconciliation_summary
                     WHERE reconciliation_run_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.ReconciliationSummaryRow.class), "", "");
            }
            case "findDetail" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT result.reconciliation_result_id AS reconciliationResultId,
                           result.reconciliation_run_id AS reconciliationRunId,
                           result.match_group_key AS matchGroupKey,
                           result.contract_id AS contractId,
                           contract.contract_no AS contractNo,
                           result.commission_item_id AS commissionItemId,
                           item.item_code AS commissionItemCode,
                           item.item_name AS commissionItemName,
                           result.installment_no AS installmentNo,
                           expected_agent.agent_id AS expectedAgentId,
                           expected_agent.agent_code AS expectedAgentCode,
                           expected_agent.agent_name AS expectedAgentName,
                           expected_org.organization_name AS expectedOrganizationName,
                           actual_agent.agent_id AS actualAgentId,
                           actual_agent.agent_code AS actualAgentCode,
                           actual_agent.agent_name AS actualAgentName,
                           actual_org.organization_name AS actualOrganizationName,
                           result.actual_source_agent_code AS actualSourceAgentCode,
                           result.expected_total_amount AS expectedTotalAmount,
                           result.actual_total_amount AS actualTotalAmount,
                           result.difference_amount AS differenceAmount,
                           result.result_type AS resultType,
                           result.primary_reason_code AS primaryReasonCode,
                           array_to_string(result.secondary_reason_codes, ',') AS secondaryReasonCodesCsv,
                           result.detail_snapshot::text AS detailSnapshotJson,
                           result.created_at AS createdAt
                    """);
                sql.append(" ");
                sql.append("""
                    FROM fgc.reconciliation_result result
                    LEFT JOIN fgc.insurance_contract contract ON contract.contract_id = result.contract_id
                    LEFT JOIN fgc.commission_item item ON item.commission_item_id = result.commission_item_id
                    LEFT JOIN fgc.agent expected_agent ON expected_agent.agent_id = result.expected_agent_id
                    LEFT JOIN fgc.organization expected_org
                      ON expected_org.organization_id = expected_agent.organization_id
                    LEFT JOIN fgc.agent actual_agent ON actual_agent.agent_id = result.actual_agent_id
                    LEFT JOIN fgc.organization actual_org
                      ON actual_org.organization_id = actual_agent.organization_id
                    """);
                sql.append(" ");
                parameters.addValue("p0", parameter(value(input, "reconciliationResultId")));
                sql.append("""
                    WHERE result.reconciliation_result_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.ReconciliationResultDetailRow.class), "", "");
            }
            case "findMatches" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationResultId")));
                sql.append("""
                    SELECT match.match_seq AS matchSeq,
                           match.match_role AS matchRole,
                           match.schedule_line_id AS scheduleLineId,
                           line.schedule_header_id AS scheduleHeaderId,
                           match.transaction_attribution_id AS transactionAttributionId,
                           transaction.commission_transaction_id AS commissionTransactionId,
                           COALESCE(expected_journal.journal_header_id, actual_journal.journal_header_id) AS journalHeaderId,
                           COALESCE(schedule.contract_id, attribution.contract_id) AS contractId,
                           COALESCE(line.beneficiary_agent_id, attribution.agent_id, transaction.recipient_agent_id) AS agentId,
                           COALESCE(line.commission_item_id, transaction.commission_item_id) AS commissionItemId,
                           COALESCE(line.installment_no, transaction.installment_no) AS installmentNo,
                           line.due_date AS dueDate,
                           line.basis_amount AS basisAmount,
                           line.rate_pct AS ratePct,
                           attribution.attribution_date AS attributionDate,
                           transaction.settlement_month AS settlementMonth,
                           COALESCE(line.due_date, attribution.attribution_date) AS referenceDate,
                           match.matched_amount AS matchedAmount
                      FROM fgc.reconciliation_match match
                      JOIN fgc.reconciliation_result result
                        ON result.reconciliation_result_id = match.reconciliation_result_id
                      JOIN fgc.reconciliation_run run
                        ON run.reconciliation_run_id = result.reconciliation_run_id
                      LEFT JOIN fgc.schedule_line line ON line.schedule_line_id = match.schedule_line_id
                      LEFT JOIN fgc.schedule_header schedule ON schedule.schedule_header_id = line.schedule_header_id
                      LEFT JOIN fgc.journal_header expected_journal
                       ON expected_journal.source_entity_type = 'SCHEDULE_LINE'
                       AND expected_journal.source_entity_id = CAST(line.schedule_line_id AS varchar)
                       AND expected_journal.status = 'POSTED'
                       AND expected_journal.journal_type = CASE run.payment_stage
                               WHEN 'INSURER_TO_GA' THEN 'EXPECTED_INSURER_INCOME'
                               ELSE 'EXPECTED_FC_PAYOUT'
                           END
                      LEFT JOIN fgc.transaction_attribution attribution
                        ON attribution.transaction_attribution_id = match.transaction_attribution_id
                      LEFT JOIN fgc.commission_transaction transaction
                        ON transaction.commission_transaction_id = attribution.commission_transaction_id
                      LEFT JOIN fgc.journal_header actual_journal
                       ON actual_journal.source_entity_type = 'COMMISSION_TRANSACTION'
                       AND actual_journal.source_entity_id = CAST(transaction.commission_transaction_id AS varchar)
                       AND actual_journal.status = 'POSTED'
                       AND actual_journal.journal_type = CASE run.payment_stage
                               WHEN 'INSURER_TO_GA' THEN 'ACTUAL_INSURER_STATEMENT'
                               ELSE 'CONFIRMED_FC_PAYOUT'
                           END
                     WHERE match.reconciliation_result_id = :p0
                     ORDER BY match.match_seq
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.ReconciliationMatchDetailRow.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
