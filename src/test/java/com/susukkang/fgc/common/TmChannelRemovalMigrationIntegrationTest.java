package com.susukkang.fgc.common;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 데모 V49·V50(SRC-036): 지운 값을 쓰는 행이 있으면 실패하고, 적용 뒤에는 지운 값을 거부한다. */
class TmChannelRemovalMigrationIntegrationTest {
    private static final String URL = "jdbc:tc:postgresql:17:///fgc_tm_removal_migration";

    @Test
    void v49FailsOnRowsUsingRemovedValuesAndRejectsThemAfterwards() throws Exception {
        try (var connection = DriverManager.getConnection(URL, "fgc", "fgc_test_only")) {
            migrate("48");
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

            jdbc.update("""
                    INSERT INTO fgc.product_offering (product_id, offering_version, sales_start_date,
                        basic_document_version, basic_document_date, channel_code, fee_regime_code)
                    SELECT product_id, 'TM-REMOVAL-TEST', DATE '2026-01-01', 'BD-TEST', DATE '2026-01-01', 'TM', 'CURRENT'
                      FROM fgc.product WHERE standard_product_code = 'STD-TERM-A'
                    """);
            assertThatThrownBy(() -> migrate("49")).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("시드 밖 TM 판매버전");
            jdbc.update("DELETE FROM fgc.product_offering WHERE offering_version = 'TM-REMOVAL-TEST'");
            migrate("49");

            Long headerId = insertScheduleHeader(jdbc, "TM_SPECIAL");
            assertThatThrownBy(() -> migrate(null)).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("schedule_header_schedule_regime_check");
            jdbc.update("DELETE FROM fgc.schedule_header WHERE schedule_header_id = ?", headerId);

            Long transactionId = insertDraftTransaction(jdbc);
            insertExcludedAttribution(jdbc, transactionId, "VOICE_RECORDING");
            assertThatThrownBy(() -> migrate(null)).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("ck_attribution_exclusion_type");
            jdbc.update("DELETE FROM fgc.transaction_attribution WHERE commission_transaction_id = ?", transactionId);

            migrate(null);
            assertThatThrownBy(() -> insertScheduleHeader(jdbc, "TM_SPECIAL"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertExcludedAttribution(jdbc, transactionId, "BROADCAST"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            insertExcludedAttribution(jdbc, transactionId, "NEW_AGENT_SUPPORT");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                     WHERE table_schema = 'fgc' AND table_name = 'product_offering'
                       AND column_name = 'channel_special_rule_yn'
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM fgc.product_offering WHERE channel_code = 'TM'", Integer.class)).isZero();
        }
    }

    private void migrate(String target) {
        var config = Flyway.configure().dataSource(URL, "fgc", "fgc_test_only")
                .schemas("fgc").locations("classpath:db/migration", "classpath:db/demo");
        if (target != null) config.target(target);
        config.load().migrate();
    }

    private Long insertScheduleHeader(JdbcTemplate jdbc, String regime) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header (contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, scenario_code, schedule_regime)
                SELECT (SELECT min(contract_id) FROM fgc.insurance_contract), 'GA_TO_FC',
                       (SELECT min(policy_version_id) FROM fgc.policy_version), 1, 'SIMULATION', 'TM-REMOVAL-TEST', ?
                RETURNING schedule_header_id
                """, Long.class, regime);
    }

    private Long insertDraftTransaction(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction (payment_stage, source_type, source_business_key,
                    recipient_agent_id, commission_item_id, settlement_month, due_date, amount, cashflow_type, status)
                SELECT 'GA_TO_FC', 'GA_MANUAL_PAYMENT', 'TM-REMOVAL-TEST', c.agent_id,
                       (SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'),
                       DATE '2026-07-01', DATE '2026-07-10', 100, 'PAYMENT', 'DRAFT'
                  FROM fgc.insurance_contract c
                 WHERE c.contract_id = (SELECT min(contract_id) FROM fgc.insurance_contract)
                RETURNING commission_transaction_id
                """, Long.class);
    }

    private void insertExcludedAttribution(JdbcTemplate jdbc, Long transactionId, String exclusionType) {
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution (commission_transaction_id, attribution_seq, attribution_scope,
                    contract_id, agent_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, exclusion_type_snapshot, attribution_method, evidence_ref)
                SELECT t.commission_transaction_id, 1, 'CONTRACT',
                       (SELECT min(contract_id) FROM fgc.insurance_contract), t.recipient_agent_id,
                       DATE '2026-07-10', DATE '2026-07-01', 100, 'EXCLUDED', ?, 'DIRECT', 'EVD-TM-REMOVAL'
                  FROM fgc.commission_transaction t
                 WHERE t.commission_transaction_id = ?
                """, exclusionType, transactionId);
    }
}
