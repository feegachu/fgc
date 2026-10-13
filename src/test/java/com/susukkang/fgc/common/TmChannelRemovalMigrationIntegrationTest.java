package com.susukkang.fgc.common;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.insurance_contract", Integer.class)).isEqualTo(21);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL03-202608-0002'
                    """, Integer.class)).isZero();

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

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL", "SEED"})
    void v49PreservesOtherContractsUsingTheSeedOffering(String origin) throws Exception {
        try (var connection = DriverManager.getConnection(URL, "fgc", "fgc_test_only")) {
            migrate("48");
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.update("""
                    INSERT INTO fgc.insurance_contract (insurer_id, product_offering_id, contract_no, contract_date,
                        agent_id, organization_id, premium_per_cycle_amount, first_premium_amount,
                        monthly_equivalent_first_premium, premium_conversion_rule_code, payment_cycle_code,
                        payment_term_months, standard_surrender_deduction_amount, current_status, data_origin)
                    SELECT insurer_id, product_offering_id, 'TM-REMOVAL-EXTRA', contract_date,
                           agent_id, organization_id, premium_per_cycle_amount, first_premium_amount,
                           monthly_equivalent_first_premium, premium_conversion_rule_code, payment_cycle_code,
                           payment_term_months, standard_surrender_deduction_amount, current_status, ?
                      FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL03-202608-0002'
                    """, origin);
            jdbc.update("""
                    INSERT INTO fgc.contract_status_event (contract_id, event_seq, new_status, effective_at,
                        received_at, source_system, source_event_key, data_origin)
                    SELECT contract_id, 1, 'ACTIVE', now(), now(), 'LOCAL_TEST', 'TM-REMOVAL-EXTRA', ?
                      FROM fgc.insurance_contract WHERE contract_no = 'TM-REMOVAL-EXTRA'
                    """, origin);

            assertThatThrownBy(() -> migrate("49")).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("시드 밖 계약");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.insurance_contract", Integer.class)).isEqualTo(23);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fgc.contract_status_event e JOIN fgc.insurance_contract c USING (contract_id)
                     WHERE c.contract_no = 'TM-REMOVAL-EXTRA'
                    """, Integer.class)).isEqualTo(1);
            assertG3Preserved(jdbc);
        }
    }

    @Test
    void v49PreservesG3WhenItIsNotSeedData() throws Exception {
        try (var connection = DriverManager.getConnection(URL, "fgc", "fgc_test_only")) {
            migrate("48");
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.update("""
                    UPDATE fgc.insurance_contract SET data_origin = 'MANUAL' WHERE contract_no = 'FGC-FGL03-202608-0002'
                    """);
            assertThatThrownBy(() -> migrate("49")).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("시드 밖 계약");
            assertG3Preserved(jdbc);
        }
    }

    @Test
    void v49RollsBackSeedDeletionAndTriggerChangeWhenG3HasASchedule() throws Exception {
        try (var connection = DriverManager.getConnection(URL, "fgc", "fgc_test_only")) {
            migrate("48");
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.update("""
                    INSERT INTO fgc.schedule_header (contract_id, payment_stage, policy_version_id, schedule_version_no,
                        schedule_purpose, scenario_code, schedule_regime)
                    SELECT contract_id, 'GA_TO_FC', (SELECT min(policy_version_id) FROM fgc.policy_version),
                           1, 'SIMULATION', 'TM-REMOVAL-TEST', 'TM_SPECIAL'
                      FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL03-202608-0002'
                    """);
            assertThatThrownBy(() -> migrate("49")).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("foreign key constraint");
            assertG3Preserved(jdbc);
            assertThatThrownBy(() -> jdbc.update("""
                    DELETE FROM fgc.contract_status_event WHERE contract_id = (
                        SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL03-202608-0002')
                    """)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .hasMessageContaining("append-only");
        }
    }

    private void assertG3Preserved(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fgc.insurance_contract c
                JOIN fgc.contract_status_event e USING (contract_id)
                JOIN fgc.contract_financial_snapshot s USING (contract_id)
                JOIN fgc.product_offering po USING (product_offering_id)
                 WHERE c.contract_no = 'FGC-FGL03-202608-0002' AND po.channel_code = 'TM'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT tgenabled::text FROM pg_trigger WHERE tgname = 'trg_contract_status_event_append_only'
                """, String.class)).isEqualTo("O");
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
