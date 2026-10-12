package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class ContractReadBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "selectByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT
                        ic.contract_id AS contract_id,
                        ic.contract_no AS contract_no,
                        i.insurer_name AS insurer_name,
                        p.product_name AS product_name,
                        ic.contract_date AS contract_date,
                        ic.monthly_equivalent_first_premium AS monthly_equivalent_first_premium,
                        CONCAT('FC-', LPAD(a.agent_id::text, 4, '0'), ' ', a.agent_name) AS agent_id_name,
                        ic.current_status AS contract_status,
                        cc.result_status AS cap_result_status,
                        ic.data_origin AS data_origin
                    FROM insurance_contract ic --계약
                        JOIN insurer i         --보험회사 이름 가져와야함
                            ON i.insurer_id = ic.insurer_id
                        JOIN product_offering po --상품판매 버전을 가져와서 상품 테이블 JOIN
                            ON po.product_offering_id = ic.product_offering_id
                        JOIN product p           --상품판매 버전 id로
                            ON p.product_id = po.product_id
                        JOIN agent a              -- 설계사 번호 , 이름
                            ON a.agent_id = ic.agent_id
                        LEFT JOIN fgc.vw_latest_cap_check cc --1200% 한도 가장 최근껄 가져옴
                            ON cc.contract_id = ic.contract_id
                        AND cc.payment_stage = 'GA_TO_FC'
                        -- mapper로 받는 매개변수가 2개 이상일 경우 condition. 을 붙여 명시
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p0", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND ic.contract_no
                        LIKE CONCAT('%', :p0, '%')
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.insurerId") != null) {
                    parameters.addValue("p1", parameter(value(input, "condition.insurerId")));
                    sql.append("""
                        AND ic.insurer_id = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.productOfferingId") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.productOfferingId")));
                    sql.append("""
                        AND ic.product_offering_id = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.agentId") != null) {
                    parameters.addValue("p3", parameter(value(input, "condition.agentId")));
                    sql.append("""
                        AND ic.agent_id = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.orgId") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.orgId")));
                    sql.append("""
                        AND ic.organization_id = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.capResultStatus") != null) {
                    parameters.addValue("p5", parameter(value(input, "condition.capResultStatus")));
                    sql.append("""
                        AND cc.result_status = :p5
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.currentStatus") != null) {
                    parameters.addValue("p6", parameter(value(input, "condition.currentStatus")));
                    sql.append("""
                        AND ic.current_status = :p6
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.contractDateFrom") != null) {
                    parameters.addValue("p7", parameter(value(input, "condition.contractDateFrom")));
                    sql.append("""
                        AND ic.contract_date
                         >=
                        :p7
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.contractDateTo") != null) {
                    parameters.addValue("p8", parameter(value(input, "condition.contractDateTo")));
                    sql.append("""
                        AND ic.contract_date
                         <=
                        :p8
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p9", parameter(value(input, "size")));
                parameters.addValue("p10", parameter(value(input, "offset")));
                sql.append("""
                    ORDER BY ic.contract_id DESC
                    LIMIT :p9
                    OFFSET :p10
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.contract.dto.ContractView.class), "", "");
            }
            case "countByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT COUNT(*)
                    FROM fgc.insurance_contract ic
                    JOIN fgc.insurer i
                    ON i.insurer_id = ic.insurer_id
                    JOIN fgc.product_offering po
                    ON po.product_offering_id = ic.product_offering_id
                    JOIN fgc.product p
                    ON p.product_id = po.product_id
                    JOIN fgc.agent a
                    ON a.agent_id = ic.agent_id
                    LEFT JOIN fgc.vw_latest_cap_check cc
                    ON cc.contract_id = ic.contract_id
                    AND cc.payment_stage = 'GA_TO_FC'
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "contractNo") != null && !"".equals(value(input, "contractNo"))) {
                    parameters.addValue("p0", parameter(value(input, "contractNo")));
                    sql.append("""
                        AND ic.contract_no
                        LIKE CONCAT('%', :p0, '%')
                        """);
                    sql.append(" ");
                }
                if (value(input, "insurerId") != null) {
                    parameters.addValue("p1", parameter(value(input, "insurerId")));
                    sql.append("""
                        AND ic.insurer_id = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "productOfferingId") != null) {
                    parameters.addValue("p2", parameter(value(input, "productOfferingId")));
                    sql.append("""
                        AND ic.product_offering_id =
                        :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "agentId") != null) {
                    parameters.addValue("p3", parameter(value(input, "agentId")));
                    sql.append("""
                        AND ic.agent_id = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "orgId") != null) {
                    parameters.addValue("p4", parameter(value(input, "orgId")));
                    sql.append("""
                        AND ic.organization_id = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "capResultStatus") != null) {
                    parameters.addValue("p5", parameter(value(input, "capResultStatus")));
                    sql.append("""
                        AND cc.result_status =
                        :p5
                        """);
                    sql.append(" ");
                }
                if (value(input, "currentStatus") != null) {
                    parameters.addValue("p6", parameter(value(input, "currentStatus")));
                    sql.append("""
                        AND ic.current_status =
                        :p6
                        """);
                    sql.append(" ");
                }
                if (value(input, "contractDateFrom") != null) {
                    parameters.addValue("p7", parameter(value(input, "contractDateFrom")));
                    sql.append("""
                        AND ic.contract_date
                         >=
                        :p7
                        """);
                    sql.append(" ");
                }
                if (value(input, "contractDateTo") != null) {
                    parameters.addValue("p8", parameter(value(input, "contractDateTo")));
                    sql.append("""
                        AND ic.contract_date
                         <=
                        :p8
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "selectAllByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT ic.contract_id AS contract_id,
                           ic.contract_no AS contract_no,
                           i.insurer_name AS insurer_name,
                           p.product_name AS product_name,
                           ic.contract_date AS contract_date,
                           ic.monthly_equivalent_first_premium AS monthly_equivalent_first_premium,
                           CONCAT('FC-', LPAD(a.agent_id::text, 4, '0'), ' ', a.agent_name) AS agent_id_name,
                           ic.current_status AS contract_status,
                           cc.result_status AS cap_result_status,
                           ic.data_origin AS data_origin
                      FROM insurance_contract ic
                      JOIN insurer i ON i.insurer_id = ic.insurer_id
                      JOIN product_offering po ON po.product_offering_id = ic.product_offering_id
                      JOIN product p ON p.product_id = po.product_id
                      JOIN agent a ON a.agent_id = ic.agent_id
                      LEFT JOIN fgc.vw_latest_cap_check cc
                        ON cc.contract_id = ic.contract_id
                       AND cc.payment_stage = 'GA_TO_FC'
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p0", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND ic.contract_no LIKE CONCAT('%', :p0, '%')
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.insurerId") != null) {
                    parameters.addValue("p1", parameter(value(input, "condition.insurerId")));
                    sql.append("""
                        AND ic.insurer_id = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.productOfferingId") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.productOfferingId")));
                    sql.append("""
                        AND ic.product_offering_id = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.agentId") != null) {
                    parameters.addValue("p3", parameter(value(input, "condition.agentId")));
                    sql.append("""
                        AND ic.agent_id = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.orgId") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.orgId")));
                    sql.append("""
                        AND ic.organization_id = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.capResultStatus") != null) {
                    parameters.addValue("p5", parameter(value(input, "condition.capResultStatus")));
                    sql.append("""
                        AND cc.result_status = :p5
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.currentStatus") != null) {
                    parameters.addValue("p6", parameter(value(input, "condition.currentStatus")));
                    sql.append("""
                        AND ic.current_status = :p6
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.contractDateFrom") != null) {
                    parameters.addValue("p7", parameter(value(input, "condition.contractDateFrom")));
                    sql.append("""
                        AND ic.contract_date  >=  :p7
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.contractDateTo") != null) {
                    parameters.addValue("p8", parameter(value(input, "condition.contractDateTo")));
                    sql.append("""
                        AND ic.contract_date  <=  :p8
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ORDER BY ic.contract_id DESC
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.contract.dto.ContractView.class), "", "");
            }
            case "selectContractDetailById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT
                        ic.contract_id,
                        ic.insurer_id,
                        i.insurer_name,
                        ic.product_offering_id,
                        ic.contract_no,
                        p.product_name,
                        po.offering_version,
                        ic.contract_date,
                        ic.agent_id,
                        a.agent_name,
                        ic.organization_id,
                        o.organization_name,
                        ic.premium_per_cycle_amount,
                        ic.payment_cycle_code,
                        ic.first_premium_amount,
                        ic.monthly_equivalent_first_premium,
                        ic.payment_term_months,
                        ic.standard_surrender_deduction_amount,
                        ic.current_status AS contract_status,
                        ic.data_origin, ic.updated_at
                    FROM insurance_contract ic
                             JOIN insurer i
                                  ON i.insurer_id = ic.insurer_id
                             JOIN product_offering po
                                  ON ic.product_offering_id = po.product_offering_id
                             JOIN product p
                                  ON p.product_id = po.product_id
                             JOIN agent a
                                  ON a.agent_id = ic.agent_id
                             JOIN organization o
                                  ON o.organization_id = ic.organization_id
                    WHERE ic.contract_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.contract.dto.ContractDetailResponse.class), "", "");
            }
            case "selectContractById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "id")));
                sql.append("""
                    SELECT
                        contract_id,
                        insurer_id,
                        product_offering_id,
                        contract_no,
                        contract_date,
                        agent_id,
                        organization_id,
                        premium_per_cycle_amount,
                        first_premium_amount,
                        monthly_equivalent_first_premium,
                        premium_conversion_rule_code,
                        payment_cycle_code,
                        payment_term_months,
                        standard_surrender_deduction_amount,
                        current_status,
                        data_origin,
                        updated_at
                    FROM insurance_contract
                    WHERE contract_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.contract.repository.BaselineContractRow.class), "", "");
            }
            case "findTransactionAttributionsByContractId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT ct.commission_transaction_id, ct.settlement_month, ct.payment_stage,
                           ct.amount, ct.status, ct.source_type,
                           ta.transaction_attribution_id, ta.attributed_amount, ta.attribution_date,
                           ta.inclusion_status_snapshot AS inclusion_status,
                           ag.agent_id, ag.agent_name, ag.agent_code,
                           (SELECT COALESCE(SUM(ta2.attributed_amount), 0)
                              FROM fgc.transaction_attribution ta2
                             WHERE ta2.commission_transaction_id = ct.commission_transaction_id
                           ) AS transaction_attributed_total
                    FROM fgc.transaction_attribution ta
                    JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = ta.commission_transaction_id
                    LEFT JOIN fgc.agent ag ON ag.agent_id = ta.agent_id
                    WHERE ta.contract_id = :p0
                    ORDER BY ct.commission_transaction_id, ta.attribution_seq
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.contract.dto.ContractTransactionAttributionRow.class), "", "");
            }
            case "findReconciliationResultsByContractId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT reconciliation_result_id, match_group_key, result_type,
                           expected_total_amount, actual_total_amount, difference_amount,
                           primary_reason_code, installment_no, commission_item_id, created_at
                    FROM fgc.reconciliation_result
                    WHERE contract_id = :p0
                    ORDER BY created_at DESC, reconciliation_result_id DESC
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.contract.dto.ContractReconciliationRow.class), "", "");
            }
            case "findJournalLinesByContractId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT h.journal_header_id, h.journal_no, h.journal_date, h.journal_type,
                           h.source_entity_type, h.source_entity_id, h.revision_no,
                           h.policy_version_id, h.validation_run_id, h.status, h.description,
                           l.line_no, a.account_code, a.account_name,
                           l.debit_amount, l.credit_amount, l.agent_id, l.payment_stage,
                           l.commission_item_id, l.memo
                    FROM fgc.journal_header h
                    JOIN fgc.journal_line l ON l.journal_header_id = h.journal_header_id
                    JOIN fgc.journal_account a ON a.journal_account_id = l.journal_account_id
                    WHERE h.contract_id = :p0
                    ORDER BY h.journal_header_id, l.line_no
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.contract.dto.ContractJournalLineRow.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
