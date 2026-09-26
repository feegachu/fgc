package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.ProductRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 상품 판매버전 및 납입기간 조회 결과를 검증하는 통합 테스트
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsOnlyActiveOfferingsInInclusiveSalesPeriodForRequestedInsurer() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        String suffix = suffix();
        long insurerId = insertInsurer("I-A-" + suffix);
        long otherInsurerId = insertInsurer("I-B-" + suffix);
        long productId = insertProduct(insurerId, "P-100-" + suffix,
                "STD-100-" + suffix, true);
        long inactiveProductId = insertProduct(insurerId, "P-200-" + suffix,
                "STD-200-" + suffix, false);
        long otherProductId = insertProduct(otherInsurerId, "P-300-" + suffix,
                "STD-300-" + suffix, true);

        long startsTodayId = insertOffering(productId, "A-START", asOf, null,
                "TM", true, "TM_SPECIAL", true, true);
        long endsTodayId = insertOffering(productId, "B-END", asOf.minusMonths(1), asOf,
                "FACE_TO_FACE", false, "CURRENT", false, true);
        long openEndedId = insertOffering(productId, "C-OPEN", asOf.minusYears(1), null,
                "FACE_TO_FACE", false, "CURRENT", true, true);
        insertOffering(productId, "D-FUTURE", asOf.plusDays(1), null,
                "FACE_TO_FACE", false, "CURRENT", false, true);
        insertOffering(productId, "E-EXPIRED", asOf.minusMonths(1), asOf.minusDays(1),
                "FACE_TO_FACE", false, "CURRENT", false, true);
        insertOffering(productId, "F-INACTIVE", asOf.minusMonths(1), null,
                "FACE_TO_FACE", false, "CURRENT", false, false);
        insertOffering(inactiveProductId, "A-INACTIVE-PRODUCT", asOf.minusMonths(1), null,
                "FACE_TO_FACE", false, "CURRENT", false, true);
        insertOffering(otherProductId, "A-OTHER-INSURER", asOf.minusMonths(1), null,
                "FACE_TO_FACE", false, "CURRENT", false, true);

        Page<ProductRow> result = productRepository.search(insurerId, asOf, PageRequest.of(0, 3));
        List<ProductRow> rows = result.getContent();

        assertThat(rows).extracting(ProductRow::productOfferingId)
                .containsExactly(startsTodayId, endsTodayId, openEndedId);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(rows.get(1)).satisfies(row -> {
            assertThat(row.salesEndDate()).isEqualTo(asOf);
            assertThat(row.channelSpecialRuleYn()).isFalse();
            assertThat(row.standardDeduction80Yn()).isFalse();
        });
        assertThat(rows.get(0)).satisfies(row -> {
            assertThat(row.insurerProductCode()).isEqualTo("P-100-" + suffix);
            assertThat(row.standardProductCode()).isEqualTo("STD-100-" + suffix);
            assertThat(row.productName()).isEqualTo("테스트 보험상품");
            assertThat(row.productGroupCode()).isEqualTo("HEALTH_PROTECTION");
            assertThat(row.salesStartDate()).isEqualTo(asOf);
            assertThat(row.salesEndDate()).isNull();
            assertThat(row.basicDocumentVersion()).isEqualTo("BD-A-START");
            assertThat(row.basicDocumentDate()).isEqualTo(asOf.minusYears(1));
            assertThat(row.channelCode()).isEqualTo("TM");
            assertThat(row.channelSpecialRuleYn()).isTrue();
            assertThat(row.feeRegimeCode()).isEqualTo("TM_SPECIAL");
            assertThat(row.standardDeduction80Yn()).isTrue();
            assertThat(row.paymentTermMonths()).isNull();
        });
    }

    @Test
    void ordersByProductCodeVersionChannelAndIdAndAppliesPaging() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        String suffix = suffix();
        long insurerId = insertInsurer("I-P-" + suffix);
        long productB = insertProduct(insurerId, "B-" + suffix,
                "STD-B-" + suffix, true);
        long productA = insertProduct(insurerId, "A-" + suffix,
                "STD-A-" + suffix, true);

        long aV1Face = insertOffering(productA, "V1", asOf.minusDays(1), null,
                "FACE_TO_FACE", false, "CURRENT", false, true);
        long aV1Tm = insertOffering(productA, "V1", asOf.minusDays(1), null,
                "TM", true, "TM_SPECIAL", false, true);
        long aV2 = insertOffering(productA, "V2", asOf.minusDays(1), null,
                "FACE_TO_FACE", false, "CURRENT", false, true);
        long bV1 = insertOffering(productB, "V1", asOf.minusDays(1), null,
                "FACE_TO_FACE", false, "CURRENT", false, true);

        Page<ProductRow> first = productRepository.search(insurerId, asOf, PageRequest.of(0, 2));
        Page<ProductRow> second = productRepository.search(insurerId, asOf, PageRequest.of(1, 2));
        Page<ProductRow> beyondLast = productRepository.search(insurerId, asOf, PageRequest.of(2, 2));

        assertThat(first.getContent())
                .extracting(ProductRow::productOfferingId)
                .containsExactly(aV1Face, aV1Tm);
        assertThat(second.getContent())
                .extracting(ProductRow::productOfferingId)
                .containsExactly(aV2, bV1);
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(second.getTotalElements()).isEqualTo(4);
        assertThat(beyondLast.getContent()).isEmpty();
        assertThat(beyondLast.getTotalElements()).isEqualTo(4);
    }

    @Test
    void returnsEmptyForUnknownPositiveInsurer() {
        Page<ProductRow> result = productRepository.search(
                Long.MAX_VALUE, LocalDate.of(2026, 8, 11), PageRequest.of(0, 20));
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void findsRefundRatePaymentTermByProductAndChannelAcrossOfferingVersions() {
        Long insurerId = jdbcTemplate.queryForObject("""
                SELECT insurer_id
                  FROM fgc.insurer
                 WHERE insurer_code = 'FGL02'
                """, Long.class);

        List<ProductRow> rows = productRepository.search(
                insurerId, LocalDate.of(2026, 5, 10),
                PageRequest.of(0, 20)
        ).getContent();

        assertThat(rows)
                .filteredOn(row -> "STD-LIFE-B".equals(row.standardProductCode()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.offeringVersion()).isEqualTo("2026-H1-B");
                    assertThat(row.paymentTermMonths()).isEqualTo(240);
                });
    }

    @Test
    void choosesLatestEligiblePaymentTermPerProductAndChannelWithoutDuplicatingOfferings() {
        LocalDate asOf = LocalDate.of(2026, 8, 11);
        String suffix = suffix();
        long insurerId = insertInsurer("I-R-" + suffix);
        long otherInsurerId = insertInsurer("I-O-" + suffix);
        long productA = insertProduct(insurerId, "A-" + suffix, "STD-A-" + suffix, true);
        long productB = insertProduct(insurerId, "B-" + suffix, "STD-B-" + suffix, true);
        long aFaceV1 = insertOffering(productA, "V1", asOf, null,
                "FACE_TO_FACE", false, "CURRENT", false, true);
        long aTm = insertOffering(productA, "V1", asOf, null,
                "TM", true, "TM_SPECIAL", false, true);
        long aFaceV2 = insertOffering(productA, "V2", asOf, null,
                "FACE_TO_FACE", false, "CURRENT", false, true);
        long bFace = insertOffering(productB, "V1", asOf, null,
                "FACE_TO_FACE", false, "CURRENT", false, true);
        long activePolicyId = insertDraftRefundPolicy("TEST-ACTIVE-" + suffix, asOf.minusDays(2));
        long draftPolicyId = insertDraftRefundPolicy("TEST-DRAFT-" + suffix, asOf);

        insertRefundTable(activePolicyId, insurerId, productA, "FACE_TO_FACE", 240, asOf.minusDays(1), null);
        insertRefundTable(activePolicyId, insurerId, productA, "FACE_TO_FACE", 360, asOf, asOf);
        // 적용일이 같으면 ID가 큰 표를 선택하며, 납입기간의 크기로 선택하지 않는다.
        insertRefundTable(activePolicyId, insurerId, productA, "FACE_TO_FACE", 180, asOf, asOf);
        insertRefundTable(activePolicyId, insurerId, productA, "FACE_TO_FACE", 480, asOf.minusDays(2), null);
        insertRefundTable(activePolicyId, insurerId, productA, "FACE_TO_FACE", 600, asOf.plusDays(1), null);
        insertRefundTable(activePolicyId, insurerId, productA, "FACE_TO_FACE", 720,
                asOf.minusDays(2), asOf.minusDays(1));
        insertRefundTable(draftPolicyId, insurerId, productA, "FACE_TO_FACE", 960, asOf, null);
        insertRefundTable(activePolicyId, otherInsurerId, productA, "FACE_TO_FACE", 60, asOf, null);
        insertRefundTable(activePolicyId, insurerId, productA, "TM", 120, asOf, null);
        insertRefundTable(activePolicyId, insurerId, productB, "FACE_TO_FACE", 84, asOf, null);
        // 환수율표를 모두 작성한 다음 승인·활성화하여 정책 불변성 제약을 지킨다.
        activatePolicy(activePolicyId);

        Page<ProductRow> result = productRepository.search(insurerId, asOf, PageRequest.of(0, 4));

        assertThat(result.getTotalElements()).isEqualTo(4);
        assertThat(result.getContent()).extracting(ProductRow::productOfferingId)
                .containsExactly(aFaceV1, aTm, aFaceV2, bFace);
        assertThat(result.getContent()).extracting(ProductRow::paymentTermMonths)
                .containsExactly(180, 120, 180, 84);

        // 기준일 다음 날에는 만료된 표를 제외하고 새로 유효해진 표를 선택한다.
        List<ProductRow> nextDay = productRepository.search(
                insurerId, asOf.plusDays(1), PageRequest.of(0, 4)).getContent();
        assertThat(nextDay).extracting(ProductRow::paymentTermMonths)
                .containsExactly(600, 120, 600, 84);
    }

    private long insertDraftRefundPolicy(String code, LocalDate effectiveFrom) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.policy_version (
                    policy_code, policy_name, policy_type, source_class,
                    version_no, effective_from, status
                ) VALUES (?, '테스트 환수율 정책', 'REFUND_RATE', 'PROJECT_ASSUMPTION', 1, ?, 'DRAFT')
                RETURNING policy_version_id
                """, Long.class, code, effectiveFrom);
    }

    private void activatePolicy(long policyId) {
        long writerId = insertUser("writer-" + suffix());
        long approverId = insertUser("approver-" + suffix());
        jdbcTemplate.update("""
                UPDATE fgc.policy_version
                SET status = 'APPROVED', approved_at = clock_timestamp(), created_by = ?, approved_by = ?
                WHERE policy_version_id = ?
                """, writerId, approverId, policyId);
        jdbcTemplate.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?",
                policyId);
    }

    private long insertUser(String loginId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.app_user (login_id, password_hash, user_name, role_id)
                VALUES (?, 'test-only', '상품 조회 테스트', (SELECT min(role_id) FROM fgc.app_role))
                RETURNING user_id
                """, Long.class, loginId);
    }

    private void insertRefundTable(long policyId, long insurerId, long productId, String channel,
                                   int paymentTerm, LocalDate effectiveFrom, LocalDate effectiveTo) {
        jdbcTemplate.update("""
                INSERT INTO fgc.refund_rate_table (
                    policy_version_id, insurer_id, product_id, payment_term_months,
                    channel_code, effective_from, effective_to, source_product_code, source_document_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST-PRODUCT', 'TEST-REFUND-DOCUMENT')
                """, policyId, insurerId, productId, paymentTerm, channel, effectiveFrom, effectiveTo);
    }

    private long insertInsurer(String code) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.insurer (insurer_code, insurer_name, insurer_type)
                VALUES (?, '테스트 보험사', 'LIFE')
                RETURNING insurer_id
                """, Long.class, code);
    }

    private long insertProduct(long insurerId, String insurerProductCode,
                               String standardProductCode, boolean activeYn) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.product (
                    insurer_id, insurer_product_code, standard_product_code,
                    product_name, product_group_code, active_yn
                ) VALUES (?, ?, ?, '테스트 보험상품', 'HEALTH_PROTECTION', ?)
                RETURNING product_id
                """, Long.class, insurerId, insurerProductCode, standardProductCode, activeYn);
    }

    private long insertOffering(long productId, String version,
                                LocalDate salesStartDate, LocalDate salesEndDate,
                                String channelCode, boolean channelSpecialRuleYn,
                                String feeRegimeCode, boolean standardDeduction80Yn,
                                boolean activeYn) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.product_offering (
                    product_id, offering_version, sales_start_date, sales_end_date,
                    basic_document_version, basic_document_date, channel_code,
                    channel_special_rule_yn, fee_regime_code,
                    standard_deduction_80_yn, active_yn
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING product_offering_id
                """,
                Long.class,
                productId,
                version,
                salesStartDate,
                salesEndDate,
                "BD-" + version,
                LocalDate.of(2025, 8, 11),
                channelCode,
                channelSpecialRuleYn,
                feeRegimeCode,
                standardDeduction80Yn,
                activeYn
        );
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
