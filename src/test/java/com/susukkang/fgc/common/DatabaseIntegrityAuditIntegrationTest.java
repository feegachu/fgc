package com.susukkang.fgc.common;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FGC-FUN-047 / SRC-050: audit actual role privileges, not just execution as a superuser. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseIntegrityAuditIntegrationTest {
    private static final String PASSWORD = "fgc_test_only";
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("fgc_audit").withUsername("fgc").withPassword(PASSWORD);
    private String auditSql;

    @BeforeAll
    void setup() throws Exception {
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), "fgc", PASSWORD).schemas("fgc")
                .locations("classpath:db/migration", "classpath:db/demo").load().migrate();
        auditSql = Files.readString(Path.of("scripts/verification/db-integrity-audit.sql"));
        try (var admin = connect("fgc")) {
            execute(admin, "CREATE ROLE audit_owner NOLOGIN NOSUPERUSER NOCREATEROLE");
            execute(admin, "CREATE TABLE fgc.audit_owner_probe (id integer)");
            execute(admin, "ALTER TABLE fgc.audit_owner_probe OWNER TO audit_owner");
            execute(admin, "CREATE FUNCTION fgc.audit_owner_probe() RETURNS integer LANGUAGE SQL AS 'SELECT 1'");
            execute(admin, "ALTER FUNCTION fgc.audit_owner_probe() OWNER TO audit_owner");
            execute(admin, "CREATE TABLE fgc.audit_acl_probe (id integer)");
            execute(admin, "INSERT INTO fgc.audit_acl_probe VALUES (1)");
            for (String role : List.of("audit_inherit", "audit_set", "audit_member", "audit_acl", "audit_plain")) {
                execute(admin, "CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION "
                        + "NOBYPASSRLS PASSWORD '" + PASSWORD + "'");
                execute(admin, "GRANT USAGE ON SCHEMA fgc TO " + role);
                execute(admin, "GRANT SELECT ON fgc.flyway_schema_history TO " + role);
            }
            execute(admin, "GRANT audit_owner TO audit_inherit WITH INHERIT TRUE, SET FALSE");
            execute(admin, "GRANT audit_owner TO audit_set WITH INHERIT FALSE, SET TRUE");
            execute(admin, "GRANT audit_owner TO audit_member WITH INHERIT FALSE, SET FALSE");
            execute(admin, "GRANT TRUNCATE, TRIGGER ON fgc.audit_acl_probe TO audit_acl");
        }
    }

    @AfterAll
    void stop() {
        postgres.stop();
    }

    @ParameterizedTest
    @CsvSource({"audit_inherit,true,false", "audit_set,false,true", "audit_member,false,false"})
    void reportsOwnerMembershipAndItsUsablePaths(String role, boolean inherits, boolean canSet) throws Exception {
        try (var runtime = connect(role)) {
            var result = audit(runtime);
            assertThat(result.warnings()).isEmpty();
            assertThat(result.rows()).anySatisfy(row -> {
                assertThat(row).containsEntry("table_name", "audit_owner_probe").containsEntry("owner", "audit_owner")
                        .containsEntry("inherits_owner", inherits).containsEntry("can_set_owner", canSet);
            }).anySatisfy(row -> {
                assertThat(row).containsEntry("owner_function", "audit_owner_probe").containsEntry("owner", "audit_owner")
                        .containsEntry("inherits_owner", inherits).containsEntry("can_set_owner", canSet);
            });
            if (inherits) {
                // INHERIT-only ownership permits ALTER without SET ROLE; roll back every probe.
                runtime.setAutoCommit(false);
                try {
                    execute(runtime, "ALTER TABLE fgc.audit_owner_probe DISABLE TRIGGER USER");
                    execute(runtime, "ALTER FUNCTION fgc.audit_owner_probe() SET search_path = fgc");
                } finally {
                    runtime.rollback();
                    runtime.setAutoCommit(true);
                }
                assertThatThrownBy(() -> execute(runtime, "SET ROLE audit_owner"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @Test
    void reportsExplicitTruncateAndTriggerGrantsWithoutOwnership() throws Exception {
        try (var runtime = connect("audit_acl")) {
            var result = audit(runtime);
            assertThat(result.rows()).anySatisfy(row -> assertThat(row)
                    .containsEntry("table_name", "audit_acl_probe")
                    .containsEntry("can_truncate", true).containsEntry("can_create_trigger", true));
            assertThat(result.rows()).noneMatch(row -> row.containsKey("owner")
                    && "audit_acl_probe".equals(row.get("table_name")));
            runtime.setAutoCommit(false);
            try {
                execute(runtime, "TRUNCATE fgc.audit_acl_probe");
            } finally {
                runtime.rollback();
            }
        }
        try (var admin = connect("fgc"); var statement = admin.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM fgc.audit_acl_probe")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void plainRuntimeHasNoReportedOwnershipOrDestructiveTablePrivileges() throws Exception {
        try (var runtime = connect("audit_plain")) {
            var result = audit(runtime);
            assertThat(result.warnings()).isEmpty();
            assertThat(result.rows()).noneMatch(row -> row.containsKey("inherits_owner")
                    || row.containsKey("can_truncate"));
            assertThat(result.rows()).anySatisfy(row -> assertThat(row)
                    .containsEntry("rolname", "audit_plain").containsEntry("rolsuper", false)
                    .containsEntry("can_set_replication_role", false));
        }
    }

    @Test
    void warnsWhenRunAsSuperuser() throws Exception {
        try (var admin = connect("fgc")) {
            assertThat(audit(admin).warnings()).anyMatch(message -> message.contains("Superuser session/current role detected"));
        }
    }

    private AuditResult audit(Connection connection) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        var warnings = new ArrayList<String>();
        try (var statement = connection.createStatement()) {
            boolean resultSet = statement.execute(auditSql);
            while (resultSet || statement.getUpdateCount() != -1) {
                if (resultSet) {
                    try (var result = statement.getResultSet()) {
                        var metadata = result.getMetaData();
                        while (result.next()) {
                            var row = new LinkedHashMap<String, Object>();
                            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                                row.put(metadata.getColumnLabel(i), result.getObject(i));
                            }
                            rows.add(row);
                        }
                    }
                }
                for (var warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                    warnings.add(warning.getMessage());
                }
                statement.clearWarnings();
                resultSet = statement.getMoreResults();
            }
        }
        return new AuditResult(rows, warnings);
    }

    private Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), role, PASSWORD);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record AuditResult(List<Map<String, Object>> rows, List<String> warnings) { }
}
