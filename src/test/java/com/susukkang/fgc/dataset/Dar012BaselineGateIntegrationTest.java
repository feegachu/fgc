package com.susukkang.fgc.dataset;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적재 전 게이트(시드명세 §50). 사용 중인 DB, 목록과 다른 계약, 바뀐 agent01 연결이면 아무것도 쓰기 전에 멈춘다.
 * 새 DB를 통과시키는 시험이 함께 있어서 db/demo에 계약을 더하고 목록을 안 고치면 여기서 실패한다.
 * 각 사례는 트랜잭션 안에서 DB를 더럽히고 끝나면 되돌린다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Dar012BaselineGateIntegrationTest {
    private static final String PASSWORD = "fgc_test_only";
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("fgc_dar012_gate").withUsername("fgc").withPassword(PASSWORD);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private Dar012Loader loader;
    private Dar012Properties properties;

    @BeforeAll
    void setup(@TempDir Path tempDir) {
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), "fgc", PASSWORD).schemas("fgc")
                .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl() + "&currentSchema=fgc", "fgc", PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        loader = new Dar012Loader(jdbc, tx);
        properties = new Dar012Properties("D12-GATE", 20261118L, Dar012Planner.MIN_CONTRACTS, LocalDate.of(2026, 9, 10), tempDir, false, null, null);
    }

    @AfterAll
    void stop() {
        postgres.stop();
    }

    @Test
    void passesOnAFreshDatabase() {
        assertThatCode(() -> loader.assertFreshBaseline()).doesNotThrowAnyException();
    }

    @Test
    void stopsWhenAValidationRunExists() {
        stopsBeforeWriting("""
                INSERT INTO validation_run (validation_month, run_no, run_type, status)
                VALUES (DATE '2026-07-01', 1, 'MONTHLY', 'CREATED')
                """, "validation_run 1건");
    }

    @Test
    void stopsWhenAnAuditedActionExists() {
        stopsBeforeWriting("""
                INSERT INTO audit_log (action_code, entity_type, entity_id) VALUES ('LOGIN_FAIL', 'APP_USER', 'gate')
                """, "audit_log 1건");
    }

    @Test
    void stopsWhenAContractIsNotInTheBaseList() {
        stopsBeforeWriting("""
                INSERT INTO insurance_contract (insurer_id, product_offering_id, contract_no, contract_date, agent_id,
                    organization_id, premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code, payment_term_months, current_status, data_origin, created_by)
                SELECT insurer_id, product_offering_id, 'FGC-FGL01-202607-0999', contract_date, agent_id,
                    organization_id, premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code, payment_term_months, current_status, data_origin, created_by
                  FROM insurance_contract WHERE contract_no = 'FGC-FGL01-202607-0001'
                """, "FGC-FGL01-202607-0999");
    }

    @Test
    void stopsWhenAgent01IsNoLongerLinkedToTheBaseAgent() {
        stopsBeforeWriting("""
                UPDATE app_user SET agent_id = (SELECT agent_id FROM agent WHERE agent_code = 'A-FC-002')
                 WHERE login_id = 'agent01'
                """, "agent01 연결 [A-FC-002]");
    }

    private void stopsBeforeWriting(String dirtySql, String expectedProblem) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.update(dirtySql);
            assertThatThrownBy(() -> Dar012Runner.generate(loader, properties))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(expectedProblem);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent WHERE starts_with(agent_code, 'D12-A-')", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization WHERE starts_with(organization_code, 'D12-')", Long.class)).isZero();
        });
    }
}
