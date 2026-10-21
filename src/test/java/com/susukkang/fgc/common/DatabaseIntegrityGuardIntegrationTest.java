package com.susukkang.fgc.common;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SRC-049·050: actual PostgreSQL rejection paths, isolated from the local application DB. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseIntegrityGuardIntegrationTest {
    private static final String URL = "jdbc:tc:postgresql:17:///fgc_integrity_guards";
    private Connection connection;

    @BeforeAll
    void migrate() throws Exception {
        connection = DriverManager.getConnection(URL, "fgc", "fgc_test_only");
        Flyway.configure().dataSource(URL, "fgc", "fgc_test_only").schemas("fgc")
                .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
    }

    @BeforeEach
    void begin() throws Exception {
        connection.setAutoCommit(false);
        execute("SET LOCAL search_path = public");
    }

    @AfterEach
    void rollback() throws Exception {
        connection.rollback();
        connection.setAutoCommit(true);
    }

    @AfterAll
    void close() throws Exception {
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "ACTIVE", "REVIEW", "RETIRED"})
    void policiesMustBeInsertedAsDraft(String status) throws Exception {
        rejected(policyInsert(status), "P0001", "must be inserted as DRAFT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"approved_by=1", "approved_by=NULL", "created_by=NULL"})
    void approvalRequiresDifferentNonNullAuthors(String invalid) throws Exception {
        execute(policyInsert("DRAFT"));
        rejected("UPDATE fgc.policy_version SET status='APPROVED', " + invalid
                + " WHERE policy_code='INTEGRITY-TEST'", "23514", "ck_policy_authorship");
    }

    @Test
    void policyAllowsApprovalButRejectsShortcutsAndMutation() throws Exception {
        execute(policyInsert("DRAFT"));
        rejected("UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_code='INTEGRITY-TEST'",
                "P0001", "Invalid policy status transition");
        execute("UPDATE fgc.policy_version SET status='APPROVED' WHERE policy_code='INTEGRITY-TEST'");
        execute("UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_code='INTEGRITY-TEST'");
        assertThat(scalar("SELECT status FROM fgc.policy_version WHERE policy_code='INTEGRITY-TEST'"))
                .isEqualTo("ACTIVE");
        rejected("UPDATE fgc.policy_version SET approved_by=1 WHERE policy_code='INTEGRITY-TEST'",
                "P0001", "immutable");
        rejected("UPDATE fgc.policy_version SET status='DRAFT' WHERE policy_code='INTEGRITY-TEST'",
                "P0001", "Invalid policy status transition");
        rejected("DELETE FROM fgc.policy_version WHERE policy_code='INTEGRITY-TEST'",
                "P0001", "Only DRAFT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"validation_run", "reconciliation_run"})
    void runsRejectSkippedStatesAndAllowRetryAndFinalization(String table) throws Exception {
        for (String status : new String[]{"RUNNING", "FAILED", "COMPLETED", "FINALIZED"}) {
            rejected(runInsert(table, status), "P0001", "must be inserted as CREATED");
        }
        execute(runInsert(table, "CREATED"));
        rejected(runUpdate(table, "COMPLETED"), "P0001", "Invalid " + table + " status transition");
        rejected(runUpdate(table, "FINALIZED"), "P0001", "Invalid " + table + " status transition");
        execute(runUpdate(table, "RUNNING"));
        execute(runUpdate(table, "FAILED"));
        rejected(runUpdate(table, "FINALIZED"), "P0001", "Invalid " + table + " status transition");
        execute(runUpdate(table, "RUNNING"));
        execute(runUpdate(table, "COMPLETED"));
        execute(runUpdate(table, "FINALIZED"));
        assertThat(scalar("SELECT status FROM fgc." + table + " WHERE " + monthColumn(table)
                + "=DATE '2099-01-01'" )).isEqualTo("FINALIZED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "pg_temp, public"})
    void correctionGuardsUseRealTablesEvenWithShadowTables(String searchPath) throws Exception {
        long original = journal("ORIGINAL");
        execute("INSERT INTO fgc.journal_line (journal_header_id,line_no,journal_account_id,debit_amount,credit_amount) "
                + "SELECT " + original + ",1,min(journal_account_id),100,0 FROM fgc.journal_account");
        execute("INSERT INTO fgc.journal_line (journal_header_id,line_no,journal_account_id,debit_amount,credit_amount) "
                + "SELECT " + original + ",2,min(journal_account_id),0,100 FROM fgc.journal_account");
        execute("UPDATE fgc.journal_header SET status='POSTED' WHERE journal_header_id=" + original);
        long draft = journal("DRAFT");
        execute("CREATE TEMP TABLE journal_header AS SELECT * FROM fgc.journal_header");
        execute("UPDATE pg_temp.journal_header SET status='POSTED' WHERE journal_header_id=" + draft);
        execute("UPDATE pg_temp.journal_header SET status='REVERSED' WHERE journal_header_id=" + original);
        execute("CREATE TEMP TABLE journal_correction_group AS SELECT * FROM fgc.journal_correction_group WITH NO DATA");
        execute("INSERT INTO pg_temp.journal_correction_group (correction_group_key,original_journal_header_id,reason) "
                + "VALUES ('SHADOW'," + original + ",'fake')");
        execute("SET LOCAL search_path = " + searchPath);
        rejected(groupInsert("DRAFT-GROUP", draft), "P0001", "Only a POSTED journal");
        execute(groupInsert("REAL-GROUP", original));
        rejected(reversalInsert("SHADOW", original), "P0001", "Unknown journal correction group");
        execute(reversalInsert("REAL-GROUP", original));
        assertThat(scalar("SELECT count(*) FROM fgc.journal_header WHERE correction_group_key='REAL-GROUP'"))
                .isEqualTo("1");
        for (String function : new String[]{"guard_journal_correction_group_write", "guard_journal_correction_membership"}) {
            assertThat(scalar("SELECT array_to_string(proconfig,',') FROM pg_proc WHERE oid='fgc."
                    + function + "()'::regprocedure")).contains("search_path=fgc, pg_temp");
        }
    }

    @Test
    void ordinaryRuntimeRoleCannotDisableTriggers() throws Exception {
        execute("CREATE ROLE integrity_runtime NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION");
        execute("SET LOCAL ROLE integrity_runtime");
        assertThat(scalar("SELECT has_parameter_privilege(current_user,'session_replication_role','SET')"))
                .isEqualTo("f");
        rejected("SET LOCAL session_replication_role=replica", "42501", "permission denied");
    }

    private String policyInsert(String status) {
        return "INSERT INTO fgc.policy_version (policy_code,policy_name,policy_type,source_class,version_no,"
                + "effective_from,status,created_by,approved_by,approved_at) VALUES "
                + "('INTEGRITY-TEST','Guard test','CAP_1200','PROJECT_ASSUMPTION',1,DATE '2099-01-01','"
                + status + "',1,2,now())";
    }

    private String runInsert(String table, String status) {
        String columns = table.equals("validation_run") ? "validation_month,run_no" : "settlement_month,payment_stage";
        String values = table.equals("validation_run") ? "DATE '2099-01-01',1" : "DATE '2099-01-01','GA_TO_FC'";
        return "INSERT INTO fgc." + table + " (" + columns + ",status) VALUES (" + values + ",'" + status + "')";
    }

    private String monthColumn(String table) {
        return table.equals("validation_run") ? "validation_month" : "settlement_month";
    }

    private String runUpdate(String table, String status) {
        String step = table.equals("validation_run") ? ", current_step="
                + (status.equals("FINALIZED") ? 10 : status.equals("COMPLETED") ? 8 : 0) : "";
        return "UPDATE fgc." + table + " SET status='" + status + "',finalized_at=now()" + step
                + " WHERE " + monthColumn(table) + "=DATE '2099-01-01'";
    }

    private long journal(String no) throws Exception {
        return Long.parseLong(scalar("INSERT INTO fgc.journal_header (journal_no,journal_date,journal_type,"
                + "source_entity_type,source_entity_id) VALUES ('GUARD-" + no
                + "',DATE '2099-01-01','ADJUSTMENT','INTEGRITY_TEST','" + no + "') RETURNING journal_header_id"));
    }

    private String groupInsert(String key, long original) {
        return "INSERT INTO fgc.journal_correction_group (correction_group_key,original_journal_header_id,reason) "
                + "VALUES ('" + key + "'," + original + ",'guard regression')";
    }

    private String reversalInsert(String key, long original) {
        return "INSERT INTO fgc.journal_header (journal_no,journal_date,journal_type,source_entity_type,"
                + "source_entity_id,reversal_of_id,correction_group_key) VALUES ('REV-" + key
                + "',DATE '2099-01-01','REVERSAL','JOURNAL_HEADER','" + original + "'," + original + ",'" + key + "')";
    }

    private void rejected(String sql, String state, String message) throws Exception {
        var savepoint = connection.setSavepoint();
        try {
            assertThatThrownBy(() -> execute(sql)).isInstanceOf(SQLException.class)
                    .hasMessageContaining(message).satisfies(error ->
                            assertThat(((SQLException) error).getSQLState()).isEqualTo(state));
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
