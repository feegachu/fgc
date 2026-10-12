package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class CapRuleBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findApplicableRuleSet" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentStage")));
                parameters.addValue("p1", parameter(value(input, "contractDate")));
                parameters.addValue("p2", parameter(value(input, "contractDate")));
                parameters.addValue("p3", parameter(value(input, "insurerId")));
                parameters.addValue("p4", parameter(value(input, "productGroupCode")));
                parameters.addValue("p5", parameter(value(input, "channelCode")));
                sql.append("""
                    SELECT crs.cap_rule_set_id             AS capRuleSetId,
                           crs.policy_version_id            AS policyVersionId,
                           crs.payment_stage                AS paymentStage,
                           crs.contract_date_from           AS contractDateFrom,
                           crs.contract_date_to             AS contractDateTo,
                           crs.insurer_id                   AS insurerId,
                           crs.product_group_code           AS productGroupCode,
                           crs.channel_code                 AS channelCode,
                           crs.first_year_months            AS firstYearMonths,
                           crs.premium_multiplier           AS premiumMultiplier,
                           crs.compliance_deduction_pct     AS complianceDeductionPct,
                           crs.refund_addition_condition    AS refundAdditionCondition,
                           crs.warning_usage_pct            AS warningUsagePct
                      FROM fgc.cap_rule_set crs
                      JOIN fgc.policy_version pv ON pv.policy_version_id = crs.policy_version_id
                     WHERE pv.status = 'ACTIVE'
                       AND crs.payment_stage = :p0
                       AND crs.contract_date_from <= :p1
                       AND (crs.contract_date_to IS NULL OR crs.contract_date_to >= :p2)
                       AND (crs.insurer_id IS NULL OR crs.insurer_id = :p3)
                       AND (crs.product_group_code IS NULL OR crs.product_group_code = :p4)
                       AND (crs.channel_code IS NULL OR crs.channel_code = :p5)
                     ORDER BY
                           (CASE WHEN crs.insurer_id IS NOT NULL THEN 1 ELSE 0 END
                          + CASE WHEN crs.product_group_code IS NOT NULL THEN 1 ELSE 0 END
                          + CASE WHEN crs.channel_code IS NOT NULL THEN 1 ELSE 0 END) DESC,
                           crs.contract_date_from DESC,
                           crs.cap_rule_set_id DESC
                     LIMIT 1
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapRuleSetView.class), "", "");
            }
            case "findRuleItems" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "capRuleSetId")));
                sql.append("""
                    SELECT cri.cap_rule_item_id      AS capRuleItemId,
                           cri.commission_item_id     AS commissionItemId,
                           ci.item_code               AS itemCode,
                           ci.item_name               AS itemName,
                           cri.inclusion_status       AS inclusionStatus,
                           cri.exclusion_type         AS exclusionType,
                           cri.evidence_required_yn   AS evidenceRequiredYn,
                           cri.attribution_method     AS attributionMethod,
                           cri.decision_reason        AS decisionReason
                      FROM fgc.cap_rule_item cri
                      JOIN fgc.commission_item ci ON ci.commission_item_id = cri.commission_item_id
                     WHERE cri.cap_rule_set_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapRuleItemView.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
