package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class CapContractBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT c.contract_id                          AS contractId,
                           c.contract_date                         AS contractDate,
                           c.monthly_equivalent_first_premium       AS monthlyEquivalentFirstPremium,
                           c.payment_term_months                    AS paymentTermMonths,
                           c.standard_surrender_deduction_amount    AS standardSurrenderDeductionAmount,
                           c.insurer_id                             AS insurerId,
                           po.product_offering_id                   AS productOfferingId,
                           po.channel_code                          AS channelCode,
                           po.standard_deduction_80_yn              AS standardDeduction80Yn,
                           p.product_id                             AS productId,
                           p.product_group_code                     AS productGroupCode
                      FROM fgc.insurance_contract c
                      JOIN fgc.product_offering po ON po.product_offering_id = c.product_offering_id
                      JOIN fgc.product p           ON p.product_id = po.product_id
                     WHERE c.contract_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapContractView.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
