package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapContractView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : PostgreSQL에서 계약·판매버전·상품의 cap 계산용 JPQL projection을 검증한다.
 *
 * @author hjKang
 * @since 2026-10-02
 * @version 1.0
 */
@SpringBootTest
@Transactional
class CapContractQueryRepositoryIntegrationTest {

    @Autowired CapContractQueryRepository capContractQueryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesSqlProjectionAndUsesContractOfferingWhenProductHasMultipleOfferings(
            boolean standardDeduction80Yn
    ) {
        Long sourceOfferingId = jdbcTemplate.queryForObject("""
                SELECT product_offering_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                """, Long.class);
        insertOffering(sourceOfferingId, "FACE_TO_FACE", !standardDeduction80Yn);
        Long selectedOfferingId = insertOffering(sourceOfferingId, "TM", standardDeduction80Yn);
        BigDecimal surrenderDeduction = standardDeduction80Yn ? new BigDecimal("4321.09") : null;
        Long contractId = insertContract(sourceOfferingId, selectedOfferingId, surrenderDeduction);

        CapContractView actual = capContractQueryRepository.findCapViewByContractId(contractId);
        CapContractView sqlBaseline = findSqlBaseline(contractId);

        assertThat(actual).isNotNull();
        assertThat(actual).usingRecursiveComparison().isEqualTo(sqlBaseline);
        assertThat(actual.getProductOfferingId()).isEqualTo(selectedOfferingId);
        assertThat(actual.getChannelCode()).isEqualTo("TM");
        assertThat(actual.getStandardDeduction80Yn()).isEqualTo(standardDeduction80Yn);
        assertThat(actual.getStandardSurrenderDeductionAmount()).isEqualTo(surrenderDeduction);
    }

    @Test
    void returnsNullWhenContractDoesNotExist() {
        assertThat(capContractQueryRepository.findCapViewByContractId(-1L)).isNull();
    }

    private Long insertOffering(Long sourceOfferingId, String channelCode, boolean standardDeduction80Yn) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.product_offering (
                    product_id, offering_version, sales_start_date,
                    basic_document_version, basic_document_date, channel_code,
                    channel_special_rule_yn, fee_regime_code, standard_deduction_80_yn
                )
                SELECT product_id, ?, DATE '2035-01-01',
                       basic_document_version, basic_document_date, ?,
                       channel_special_rule_yn, fee_regime_code, ?
                  FROM fgc.product_offering
                 WHERE product_offering_id = ?
                RETURNING product_offering_id
                """, Long.class, UUID.randomUUID().toString(), channelCode,
                standardDeduction80Yn, sourceOfferingId);
    }

    private Long insertContract(Long sourceOfferingId, Long selectedOfferingId, BigDecimal surrenderDeduction) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date,
                    agent_id, organization_id, premium_per_cycle_amount,
                    first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code,
                    payment_term_months, standard_surrender_deduction_amount, current_status, data_origin
                )
                SELECT insurer_id, ?, ?, DATE '2035-01-15',
                       agent_id, organization_id, 1481481.36,
                       1481481.36, 123456.78, 'DIRECT_INPUT', 'ANNUAL',
                       180, ?, 'ACTIVE', 'MANUAL'
                  FROM fgc.insurance_contract
                 WHERE product_offering_id = ?
                 ORDER BY contract_id
                 LIMIT 1
                RETURNING contract_id
                """, Long.class, selectedOfferingId, "IT-CAP-CONTRACT-" + UUID.randomUUID(),
                surrenderDeduction, sourceOfferingId);
    }

    // 전환 전 계약 조회 SQL과 모든 필드의 값·자료형·null 처리가 같은지 비교한다.
    private CapContractView findSqlBaseline(Long contractId) {
        return jdbcTemplate.queryForObject("""
                SELECT c.contract_id,
                       c.contract_date,
                       c.monthly_equivalent_first_premium,
                       c.payment_term_months,
                       c.standard_surrender_deduction_amount,
                       c.insurer_id,
                       po.product_offering_id,
                       po.channel_code,
                       po.standard_deduction_80_yn,
                       p.product_id,
                       p.product_group_code
                  FROM fgc.insurance_contract c
                  JOIN fgc.product_offering po ON po.product_offering_id = c.product_offering_id
                  JOIN fgc.product p ON p.product_id = po.product_id
                 WHERE c.contract_id = ?
                """, (rs, rowNum) -> new CapContractView(
                rs.getLong("contract_id"),
                rs.getObject("contract_date", LocalDate.class),
                rs.getBigDecimal("monthly_equivalent_first_premium"),
                rs.getInt("payment_term_months"),
                rs.getBigDecimal("standard_surrender_deduction_amount"),
                rs.getLong("insurer_id"),
                rs.getLong("product_offering_id"),
                rs.getString("channel_code"),
                rs.getBoolean("standard_deduction_80_yn"),
                rs.getLong("product_id"),
                rs.getString("product_group_code")), contractId);
    }
}
