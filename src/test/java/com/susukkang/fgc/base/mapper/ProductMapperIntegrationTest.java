package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.base.dto.ProductRow;
import com.susukkang.fgc.base.dto.ProductSearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProductMapperIntegrationTest {

    @Autowired
    private ProductMapper productMapper;

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

        ProductSearchCriteria criteria = new ProductSearchCriteria(insurerId, asOf);
        List<ProductRow> rows = productMapper.selectProducts(criteria, 0, 20);

        assertThat(rows).extracting(ProductRow::productOfferingId)
                .containsExactly(startsTodayId, endsTodayId, openEndedId);
        assertThat(productMapper.countProducts(criteria)).isEqualTo(3);
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

        ProductSearchCriteria criteria = new ProductSearchCriteria(insurerId, asOf);

        assertThat(productMapper.selectProducts(criteria, 0, 2))
                .extracting(ProductRow::productOfferingId)
                .containsExactly(aV1Face, aV1Tm);
        assertThat(productMapper.selectProducts(criteria, 2, 2))
                .extracting(ProductRow::productOfferingId)
                .containsExactly(aV2, bV1);
        assertThat(productMapper.countProducts(criteria)).isEqualTo(4);
    }

    @Test
    void returnsEmptyForUnknownPositiveInsurer() {
        ProductSearchCriteria criteria = new ProductSearchCriteria(
                Long.MAX_VALUE, LocalDate.of(2026, 8, 11));

        assertThat(productMapper.selectProducts(criteria, 0, 20)).isEmpty();
        assertThat(productMapper.countProducts(criteria)).isZero();
    }

    @Test
    void findsRefundRatePaymentTermByProductAndChannelAcrossOfferingVersions() {
        Long insurerId = jdbcTemplate.queryForObject("""
                SELECT insurer_id
                  FROM fgc.insurer
                 WHERE insurer_code = 'FGL02'
                """, Long.class);

        List<ProductRow> rows = productMapper.selectProducts(
                new ProductSearchCriteria(insurerId, LocalDate.of(2026, 5, 10)),
                0,
                20
        );

        assertThat(rows)
                .filteredOn(row -> "STD-LIFE-B".equals(row.standardProductCode()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.offeringVersion()).isEqualTo("2026-H1-B");
                    assertThat(row.paymentTermMonths()).isEqualTo(240);
                });
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
