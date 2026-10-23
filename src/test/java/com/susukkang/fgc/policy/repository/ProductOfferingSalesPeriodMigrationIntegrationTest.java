package com.susukkang.fgc.policy.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V60(demo)·V61(#534): 판매버전 판매기간 겹침 배제와 마이그레이션 전 겹침 점검. */
class ProductOfferingSalesPeriodMigrationIntegrationTest {
    private static final String URL = "jdbc:tc:postgresql:17:///fgc_offering_sales_period";

    @Test
    void closesOldOfferingAndRejectsOverlap() throws Exception {
        try (var connection = DriverManager.getConnection(URL, "fgc", "fgc_test_only")) {
            Flyway.configure().dataSource(URL, "fgc", "fgc_test_only").schemas("fgc")
                    .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

            // V60: 2026-CURRENT-B 는 2027-FOUR-YEAR 시작 전날까지로 닫혀 있다
            assertThat(jdbc.queryForObject("""
                    SELECT sales_end_date::text FROM fgc.product_offering po JOIN fgc.product p USING (product_id)
                     WHERE p.standard_product_code = 'STD-LIFE-B' AND po.offering_version = '2026-CURRENT-B'
                    """, String.class)).isEqualTo("2026-12-31");

            // 같은 상품·채널에서 판매기간이 겹치는 판매버전은 거부된다
            assertThatThrownBy(() -> insertOffering(jdbc, "2026-OVERLAP", "2026-12-01"))
                    .hasMessageContaining("ex_product_offering_sales_period");
            // 판매종료일이 빈 2027-FOUR-YEAR 가 이미 열려 있으므로 그 뒤에 시작하는 판매버전도 겹쳐서 거부
            assertThatThrownBy(() -> insertOffering(jdbc, "2028-NEXT", "2028-01-01"))
                    .hasMessageContaining("ex_product_offering_sales_period");
            // 겹치지 않는 기간(기존 판매버전 이전)은 등록할 수 있다
            jdbc.update("""
                    INSERT INTO fgc.product_offering (product_id, offering_version, sales_start_date, sales_end_date,
                        basic_document_version, basic_document_date, channel_code, fee_regime_code,
                        standard_deduction_80_yn, governance_status, committee_approval_date)
                    SELECT product_id, '2025-PAST', DATE '2025-01-01', DATE '2025-12-31', 'BD-X', DATE '2025-01-01',
                           'FACE_TO_FACE', 'CURRENT', true, 'APPROVED', DATE '2025-01-01'
                      FROM fgc.product WHERE standard_product_code = 'STD-LIFE-B'
                    """);
        }
    }

    @Test
    void migrationFailsWithOfferingIdsWhenPeriodsOverlap() throws Exception {
        String url = "jdbc:tc:postgresql:17:///fgc_offering_sales_period_fail";
        try (var ignored = DriverManager.getConnection(url, "fgc", "fgc_test_only")) {
            // V59 까지(데모 시드 포함)에서는 2026-CURRENT-B 가 열려 있어 2027-FOUR-YEAR 와 겹친다
            Flyway.configure().dataSource(url, "fgc", "fgc_test_only").schemas("fgc")
                    .locations("classpath:db/migration", "classpath:db/demo").target("59").load().migrate();

            // 데모(V60)를 빼고 구조 마이그레이션만 적용하면 V61 점검이 실패하고 판매버전 ID 를 알려 준다
            assertThatThrownBy(() -> Flyway.configure().dataSource(url, "fgc", "fgc_test_only").schemas("fgc")
                    .locations("classpath:db/migration").ignoreMigrationPatterns("*:missing").load().migrate())
                    .hasMessageContaining("Overlapping product_offering sales periods")
                    .hasMessageContaining("2026-CURRENT-B");
        }
    }

    private void insertOffering(JdbcTemplate jdbc, String version, String salesStart) {
        jdbc.update("""
                INSERT INTO fgc.product_offering (product_id, offering_version, sales_start_date, sales_end_date,
                    basic_document_version, basic_document_date, channel_code, fee_regime_code,
                    standard_deduction_80_yn, governance_status, committee_approval_date)
                SELECT product_id, ?, ?::date, NULL, 'BD-X', ?::date,
                       'FACE_TO_FACE', 'CURRENT', true, 'APPROVED', ?::date
                  FROM fgc.product WHERE standard_product_code = 'STD-LIFE-B'
                """, version, salesStart, salesStart, salesStart);
    }
}
