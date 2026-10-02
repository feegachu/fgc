package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.RefundRateTableView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : PostgreSQL에서 상품 범위 환급률표 선택과 월별 환급률의 JPQL 조회를 검증한다.
 *
 * @author hjKang
 * @since 2026-10-02
 * @version 1.0
 */
@SpringBootTest
@Transactional
class RefundRateQueryRepositoryIntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2000, 1, 1);
    private static final String CHANNEL = "TEST_REFUND_CHANNEL";
    private static final int PAYMENT_TERM_MONTHS = 240;

    @Autowired RefundRateQueryRepository refundRateQueryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long policyId;
    private ProductScope scope;

    @BeforeEach
    void insertDraftPolicyAndFindProductScope() {
        scope = jdbcTemplate.queryForObject("""
                SELECT p.insurer_id, p.product_id, po.product_offering_id
                  FROM fgc.product p
                  JOIN fgc.product_offering po ON po.product_id = p.product_id
                 ORDER BY po.product_offering_id LIMIT 1
                """, (rs, rowNum) -> new ProductScope(rs.getLong("insurer_id"),
                rs.getLong("product_id"), rs.getLong("product_offering_id")));
        policyId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.policy_version (
                    policy_code, policy_name, policy_type, source_class, version_no,
                    effective_from, status, created_by, approved_by, approved_at
                )
                SELECT ?, '환급률 조회 테스트', 'REFUND_RATE', 'PROJECT_ASSUMPTION', 7,
                       DATE '2000-01-01', 'DRAFT', created_by, approved_by, approved_at
                  FROM fgc.policy_version
                 WHERE status = 'ACTIVE' AND created_by IS NOT NULL AND approved_by IS NOT NULL
                 ORDER BY policy_version_id LIMIT 1
                RETURNING policy_version_id
                """, Long.class, "TEST-REFUND-" + UUID.randomUUID());
    }

    @Test
    void returnsTableOnlyWhileItsPolicyIsActive() {
        Long tableId = insertTable(FROM, null, true);
        assertThat(findTable(FROM)).isNull();

        for (String status : List.of("REVIEW", "APPROVED", "ACTIVE", "RETIRED")) {
            jdbcTemplate.update("UPDATE fgc.policy_version SET status = ? WHERE policy_version_id = ?", status, policyId);
            RefundRateTableView actual = findTable(FROM);
            if (status.equals("ACTIVE")) {
                assertThat(actual).isNotNull();
                assertThat(actual.getRefundRateTableId()).isEqualTo(tableId);
            } else {
                assertThat(actual).as("정책 상태 %s", status).isNull();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSURER", "PRODUCT", "PAYMENT_TERM", "CHANNEL"})
    void requiresExactMatchForEveryScopeField(String mismatch) {
        insertTable(FROM, null, true);
        activatePolicy();

        assertThat(refundRateQueryRepository.findApplicableTable(
                mismatch.equals("INSURER") ? -1L : scope.insurerId(),
                mismatch.equals("PRODUCT") ? -1L : scope.productId(),
                mismatch.equals("PAYMENT_TERM") ? 120 : PAYMENT_TERM_MONTHS,
                mismatch.equals("CHANNEL") ? "OTHER_CHANNEL" : CHANNEL,
                FROM)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "2000-01-01, 2000-01-31, true",
            "2000-01-31, 2000-01-31, true",
            "1999-12-31, 2000-01-31, false",
            "2000-02-01, 2000-01-31, false",
            "2001-01-01, , true"
    })
    void usesInclusiveEffectiveDatesAndAllowsOpenEnd(String asOfDate, String effectiveTo, boolean applicable) {
        Long tableId = insertTable(FROM, effectiveTo == null ? null : LocalDate.parse(effectiveTo), true);
        activatePolicy();

        RefundRateTableView actual = findTable(LocalDate.parse(asOfDate));

        if (applicable) {
            assertThat(actual).isNotNull();
            assertThat(actual.getRefundRateTableId()).isEqualTo(tableId);
        } else {
            assertThat(actual).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectsLatestEffectiveDateAndMapsAllFieldsForProductWithMultipleOfferings(boolean standardDeduction80) {
        jdbcTemplate.update("""
                INSERT INTO fgc.product_offering (
                    product_id, offering_version, sales_start_date,
                    basic_document_version, basic_document_date, channel_code,
                    channel_special_rule_yn, fee_regime_code, standard_deduction_80_yn
                )
                SELECT product_id, ?, DATE '2035-01-01',
                       basic_document_version, basic_document_date, channel_code,
                       channel_special_rule_yn, fee_regime_code, standard_deduction_80_yn
                  FROM fgc.product_offering WHERE product_offering_id = ?
                """, UUID.randomUUID().toString(), scope.offeringId());
        LocalDate newestFrom = FROM.plusMonths(1);
        // 표는 판매버전을 지정하지 않는다. 높은 ID보다 최신 적용일이 우선한다.
        Long newestId = insertTable(newestFrom, null, standardDeduction80);
        Long olderId = insertTable(FROM, null, !standardDeduction80);
        activatePolicy();

        RefundRateTableView expected = new RefundRateTableView(newestId, policyId, 7, standardDeduction80, newestFrom);

        assertThat(findTable(newestFrom.plusDays(1))).usingRecursiveComparison().isEqualTo(expected);
        assertThat(refundRateQueryRepository.findApplicableTables(scope.insurerId(), scope.productId(),
                PAYMENT_TERM_MONTHS, CHANNEL, newestFrom.plusDays(1), PageRequest.of(0, 10)))
                .extracting(RefundRateTableView::getRefundRateTableId).containsExactly(newestId, olderId);
    }

    @Test
    void readsExactTableAndMonthPreservingZeroAndSixDecimalPlacesAndReturnsNullForMissingRate() {
        Long tableId = insertTable(FROM, null, true);
        Long otherTableId = insertTable(FROM.plusMonths(1), null, false);
        insertRate(tableId, 11, "1.234567");
        insertRate(tableId, 12, "0.000000");
        insertRate(otherTableId, 11, "99.654321");

        assertThat(refundRateQueryRepository.findRateAtMonth(tableId, 11)).isEqualTo(new BigDecimal("1.234567"));
        assertThat(refundRateQueryRepository.findRateAtMonth(tableId, 12)).isEqualTo(new BigDecimal("0.000000"));
        assertThat(refundRateQueryRepository.findRateAtMonth(otherTableId, 11)).isEqualTo(new BigDecimal("99.654321"));
        assertThat(refundRateQueryRepository.findRateAtMonth(tableId, 13)).isNull();
        assertThat(refundRateQueryRepository.findRateAtMonth(-1L, 11)).isNull();
    }

    private RefundRateTableView findTable(LocalDate asOfDate) {
        return refundRateQueryRepository.findApplicableTable(
                scope.insurerId(), scope.productId(), PAYMENT_TERM_MONTHS, CHANNEL, asOfDate);
    }

    private Long insertTable(LocalDate from, LocalDate to, boolean standardDeduction80) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.refund_rate_table (
                    policy_version_id, insurer_id, product_id, payment_term_months,
                    channel_code, standard_deduction_80_yn, effective_from, effective_to,
                    source_product_code, source_document_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'TEST-PRODUCT', 'TEST-REFUND-DOCUMENT')
                RETURNING refund_rate_table_id
                """, Long.class, policyId, scope.insurerId(), scope.productId(), PAYMENT_TERM_MONTHS,
                CHANNEL, standardDeduction80, from, to);
    }

    private void insertRate(Long tableId, int month, String rate) {
        jdbcTemplate.update("""
                INSERT INTO fgc.refund_rate_line (refund_rate_table_id, contract_month_no, refund_rate_pct)
                VALUES (?, ?, ?)
                """, tableId, month, new BigDecimal(rate));
    }

    private void activatePolicy() {
        jdbcTemplate.update("UPDATE fgc.policy_version SET status = 'APPROVED' WHERE policy_version_id = ?", policyId);
        jdbcTemplate.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", policyId);
    }

    private record ProductScope(Long insurerId, Long productId, Long offeringId) {
    }
}
