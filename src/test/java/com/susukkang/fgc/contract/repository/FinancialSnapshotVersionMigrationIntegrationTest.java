package com.susukkang.fgc.contract.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V43의 실제 시드 DB를 V44로 올려 데이터 보존과 버전 제약을 검증한다. */
class FinancialSnapshotVersionMigrationIntegrationTest {
    @Test
    void migratesExistingRowsWithoutChangingEvidenceAndRejectsInvalidVersionChains() throws Exception {
        String url = "jdbc:tc:postgresql:17:///fgc_snapshot_migration";
        try (var connection = DriverManager.getConnection(url, "fgc", "fgc_test_only")) {
            Flyway.configure().dataSource(url, "fgc", "fgc_test_only")
                    .schemas("fgc").locations("classpath:db/migration", "classpath:db/demo")
                    .target("43").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var previousRows = jdbc.queryForList("SELECT to_jsonb(fs)::text FROM fgc.contract_financial_snapshot fs ORDER BY contract_financial_snapshot_id", String.class);
            assertThat(previousRows).isNotEmpty();
            Flyway.configure().dataSource(url, "fgc", "fgc_test_only")
                    .schemas("fgc").locations("classpath:db/migration", "classpath:db/demo")
                    .load().migrate();
            assertThat(jdbc.queryForList("SELECT (to_jsonb(fs)-'snapshot_version'-'previous_snapshot_id')::text FROM fgc.contract_financial_snapshot fs ORDER BY contract_financial_snapshot_id", String.class))
                    .containsExactlyElementsOf(previousRows);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.contract_financial_snapshot WHERE snapshot_version<>1 OR previous_snapshot_id IS NOT NULL", Integer.class)).isZero();
            Long root = jdbc.queryForObject("SELECT MIN(contract_financial_snapshot_id) FROM fgc.contract_financial_snapshot", Long.class);
            String copy = """
                    INSERT INTO fgc.contract_financial_snapshot(contract_id,as_of_date,contract_month_no,
                        cumulative_paid_premium,surrender_value_type,snapshot_version,previous_snapshot_id)
                    SELECT contract_id,as_of_date,contract_month_no,cumulative_paid_premium,surrender_value_type,?,?
                    FROM fgc.contract_financial_snapshot WHERE contract_financial_snapshot_id=?
                    """;
            // 기존 INSERT는 기본 버전 1의 동일 업무키에서 계속 중복으로 거절된다.
            assertThatThrownBy(() -> jdbc.update(copy, 1, null, root)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(copy, 3, root, root)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(copy, 2, null, root)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.update(copy, 2, root, root)).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("UPDATE fgc.contract_financial_snapshot SET cumulative_paid_premium=1 WHERE contract_financial_snapshot_id=?", root))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE fgc.contract_financial_snapshot SET cumulative_paid_premium=1 WHERE previous_snapshot_id=?", root))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM fgc.contract_financial_snapshot WHERE previous_snapshot_id=?", root))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(copy, 2, root, root)).isInstanceOf(DataIntegrityViolationException.class);
            Long otherContractRoot = jdbc.queryForObject("SELECT MIN(contract_financial_snapshot_id) FROM fgc.contract_financial_snapshot WHERE contract_id<>(SELECT contract_id FROM fgc.contract_financial_snapshot WHERE contract_financial_snapshot_id=?)", Long.class, root);
            assertThatThrownBy(() -> jdbc.update(copy, 2, otherContractRoot, root)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
