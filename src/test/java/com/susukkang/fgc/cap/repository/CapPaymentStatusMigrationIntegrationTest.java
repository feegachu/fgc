package com.susukkang.fgc.cap.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class CapPaymentStatusMigrationIntegrationTest {
    @Test
    void preservesFinalizedAndHistoricalRowsAndSnapshotsOnlyNewDetails() throws Exception {
        String url = "jdbc:tc:postgresql:17:///fgc_cap_payment_status_migration";
        try (var connection = DriverManager.getConnection(url, "fgc", "fgc_test_only")) {
            flyway(url, "91").migrate();
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SET search_path TO fgc,public");
            Long attribution = jdbc.queryForObject("SELECT min(transaction_attribution_id) FROM fgc.transaction_attribution WHERE contract_id IS NOT NULL", Long.class);
            assertThat(attribution).isNotNull();
            Long run = jdbc.queryForObject("""
                    INSERT INTO fgc.validation_run(validation_month,run_no,run_type)
                    VALUES ('2096-01-01',9373,'MANUAL_CONTRACT') RETURNING validation_run_id
                    """, Long.class);
            Long finalized = check(jdbc, run, attribution);
            Long historical = check(jdbc, null, attribution);
            detail(jdbc, finalized, attribution, 1);
            detail(jdbc, historical, attribution, 1);
            jdbc.update("UPDATE fgc.validation_run SET status='RUNNING',started_at=now() WHERE validation_run_id=?", run);
            jdbc.update("UPDATE fgc.validation_run SET status='COMPLETED',current_step=8,completed_at=now() WHERE validation_run_id=?", run);
            jdbc.update("UPDATE fgc.validation_run SET status='FINALIZED',current_step=10,finalized_at=now() WHERE validation_run_id=?", run);
            String before = jdbc.queryForObject("SELECT to_jsonb(d)::text FROM fgc.cap_check_detail d WHERE cap_check_id=?", String.class, finalized);

            flyway(url, "92").migrate();

            assertThat(jdbc.queryForObject("SELECT (to_jsonb(d)-'payment_status_snapshot')::text FROM fgc.cap_check_detail d WHERE cap_check_id=?", String.class, finalized)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check_detail WHERE cap_check_id IN (?,?) AND payment_status_snapshot IS NOT NULL", Long.class, finalized, historical)).isZero();
            detail(jdbc, historical, attribution, 2);
            String actual = jdbc.queryForObject("SELECT p.status FROM fgc.transaction_attribution a JOIN fgc.commission_transaction p USING(commission_transaction_id) WHERE a.transaction_attribution_id=?", String.class, attribution);
            assertThat(jdbc.queryForObject("SELECT payment_status_snapshot FROM fgc.cap_check_detail WHERE cap_check_id=? AND detail_seq=2", String.class, historical)).isEqualTo(actual);
        }
    }

    private Long check(JdbcTemplate jdbc, Long run, Long attribution) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.cap_check(validation_run_id,contract_id,payment_stage,cap_rule_set_id,check_kind,
                    as_of_date,base_premium_amount,refund_12m_amount,compliance_deduction_amount,
                    limit_amount,included_amount,remaining_amount,usage_pct,result_status,calculation_snapshot)
                SELECT ?,a.contract_id,'GA_TO_FC',r.cap_rule_set_id,?,'2096-01-31',100,0,0,1200,100,1100,8.33,'NORMAL','{}'::jsonb
                FROM fgc.transaction_attribution a CROSS JOIN fgc.cap_rule_set r
                WHERE a.transaction_attribution_id=? AND r.payment_stage='GA_TO_FC'
                ORDER BY r.cap_rule_set_id LIMIT 1 RETURNING cap_check_id
                """, Long.class, run, run == null ? "REALTIME" : "MONTHLY", attribution);
    }

    private void detail(JdbcTemplate jdbc, Long check, Long attribution, int seq) {
        jdbc.update("""
                INSERT INTO fgc.cap_check_detail(cap_check_id,detail_seq,commission_item_id,transaction_attribution_id,
                    item_code,item_name,classification_snapshot,amount,decision_reason)
                SELECT ?,?,p.commission_item_id,a.transaction_attribution_id,'STATUS-TEST','지급 상태 보존','INCLUDED',100,'검출 근거'
                FROM fgc.transaction_attribution a JOIN fgc.commission_transaction p USING(commission_transaction_id)
                WHERE a.transaction_attribution_id=?
                """, check, seq, attribution);
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, "fgc", "fgc_test_only").schemas("fgc")
                .locations("classpath:db/migration", "classpath:db/demo").target(target).load();
    }
}
