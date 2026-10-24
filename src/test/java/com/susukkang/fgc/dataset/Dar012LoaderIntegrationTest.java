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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DAR-012 적재 경로를 실제 PostgreSQL(Flyway 전체 적용)에서 확인한다: 건수·합계 일치, 확정 가드, 중복 적재 거절. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Dar012LoaderIntegrationTest {
    private static final String PASSWORD = "fgc_test_only";
    private static final int CONTRACTS = 300;
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("fgc_dar012").withUsername("fgc").withPassword(PASSWORD);
    private JdbcTemplate jdbc;
    private Dar012Loader loader;
    private Dar012Properties properties;
    private Path metadataFile;

    @BeforeAll
    void setup(@TempDir Path tempDir) throws Exception {
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), "fgc", PASSWORD).schemas("fgc")
                .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl() + "&currentSchema=fgc", "fgc", PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        loader = new Dar012Loader(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        properties = new Dar012Properties("D12-IT", 20261118L, CONTRACTS, LocalDate.of(2026, 9, 10), tempDir);
        long goldenContractsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM insurance_contract", Long.class);
        goldenContracts = goldenContractsBefore;
        metadataFile = Dar012Runner.generate(loader, properties);
    }

    private long goldenContracts;

    @AfterAll
    void stop() {
        postgres.stop();
    }

    @Test
    void loadsExactlyTheCountsAndSumsTheMetadataReports() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM agent WHERE starts_with(agent_code, 'D12-A-')")).isEqualTo(600);
        assertThat(count("SELECT COUNT(*) FROM agent WHERE starts_with(agent_code, 'D12-A-') AND agent_status = 'ACTIVE'"))
                .isGreaterThanOrEqualTo(500);
        assertThat(count("SELECT COUNT(*) FROM organization WHERE organization_type = 'BRANCH' AND starts_with(organization_code, 'D12-')"))
                .isEqualTo(12);
        assertThat(count("SELECT COUNT(*) FROM insurance_contract")).isEqualTo(goldenContracts + CONTRACTS);
        assertThat(count("SELECT COUNT(DISTINCT insurer_id) FROM insurance_contract c WHERE c.contract_no LIKE '%-5___' "
                + "OR c.contract_no LIKE '%-6___'")).isGreaterThanOrEqualTo(4);
        String json = Files.readString(metadataFile);
        assertThat(json).contains("\"datasetId\" : \"D12-IT\"").contains("\"contracts\" : " + CONTRACTS)
                .contains("프로젝트 가정, OI-024");
        long payments = count("SELECT COUNT(*) FROM commission_transaction WHERE starts_with(source_business_key, 'SYNTHETIC:DAR012:D12-IT:')");
        assertThat(json).contains("\"insurerStatementPayments\" : " + payments);
        String amount = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM commission_transaction "
                + "WHERE starts_with(source_business_key, 'SYNTHETIC:DAR012:D12-IT:')", java.math.BigDecimal.class).toPlainString();
        assertThat(json).contains("\"insurerStatementAmount\" : \"" + amount + "\"");
        assertThat(Files.readString(metadataFile.resolveSibling("D12-IT-answers.csv")))
                .startsWith("contract_no,scenario,expected_result_type,expected_amount,note");
    }

    @Test
    void confirmsEveryPaymentWithMatchingAttributionTotals() {
        assertThat(count("SELECT COUNT(*) FROM commission_transaction WHERE starts_with(source_business_key, 'SYNTHETIC:DAR012:') "
                + "AND status <> 'CONFIRMED'")).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM commission_transaction t
                 WHERE starts_with(t.source_business_key, 'SYNTHETIC:DAR012:') AND t.amount <>
                   (SELECT COALESCE(SUM(a.attributed_amount), 0) FROM transaction_attribution a
                     WHERE a.commission_transaction_id = t.commission_transaction_id)""")).isZero();
        assertThat(count("SELECT COUNT(*) FROM commission_transaction WHERE starts_with(source_business_key, 'SYNTHETIC:DAR012:') "
                + "AND installment_no IS NULL")).isZero();
    }

    @Test
    void doesNotCreateDerivedDataAndLeavesGoldenRowsUntouched() {
        assertThat(count("SELECT COUNT(*) FROM schedule_line")).isZero();
        assertThat(count("SELECT COUNT(*) FROM cap_check")).isZero();
        assertThat(count("SELECT COUNT(*) FROM insurance_contract WHERE data_origin = 'SEED'")).isEqualTo(goldenContracts + CONTRACTS);
    }

    @Test
    void stopsWhenTheSameDatasetIsLoadedAgainAndLeavesCountsUnchanged() {
        long before = count("SELECT COUNT(*) FROM insurance_contract");
        assertThatThrownBy(() -> Dar012Runner.generate(loader, properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미 DAR-012 데이터셋");
        assertThat(count("SELECT COUNT(*) FROM insurance_contract")).isEqualTo(before);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
