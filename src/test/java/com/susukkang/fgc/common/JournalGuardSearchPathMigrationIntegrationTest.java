package com.susukkang.fgc.common;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FGC-FUN-047 / SRC-050: V54 -> V55 upgrade keeps the existing guard bodies and repairs function settings. */
class JournalGuardSearchPathMigrationIntegrationTest {
    private static final String URL = "jdbc:tc:postgresql:17:///fgc_journal_guard_upgrade";

    @Test
    void upgradesExistingFunctionsWithoutReplacingTheirBusinessRules() throws Exception {
        try (var connection = DriverManager.getConnection(URL, "fgc", "fgc_test_only")) {
            migrate("54");
            String groupBody = definition(connection, "guard_journal_correction_group_write", "prosrc");
            String membershipBody = definition(connection, "guard_journal_correction_membership", "prosrc");
            assertThat(definition(connection, "guard_journal_correction_group_write", "proconfig::text")).isNull();
            assertThat(definition(connection, "guard_journal_correction_membership", "proconfig::text"))
                    .contains("search_path=fgc, pg_temp");
            execute(connection, "SET search_path = public");
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO fgc.journal_correction_group
                        (correction_group_key,original_journal_header_id,reason)
                    VALUES ('UPGRADE-CHECK',-1,'test')
                    """)).isInstanceOf(SQLException.class).hasMessageContaining("relation \"journal_header\" does not exist");

            // Also cover a deployed membership function whose setting was removed by a redefinition.
            execute(connection, "ALTER FUNCTION fgc.guard_journal_correction_membership() RESET search_path");
            migrate(null);
            assertThat(definition(connection, "guard_journal_correction_group_write", "prosrc")).isEqualTo(groupBody);
            assertThat(definition(connection, "guard_journal_correction_membership", "prosrc")).isEqualTo(membershipBody);
            for (String function : new String[]{"guard_journal_correction_group_write", "guard_journal_correction_membership"}) {
                assertThat(definition(connection, function, "proconfig::text")).contains("search_path=fgc, pg_temp");
            }
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO fgc.journal_correction_group
                        (correction_group_key,original_journal_header_id,reason)
                    VALUES ('UPGRADE-CHECK',-1,'test')
                    """)).isInstanceOf(SQLException.class).hasMessageContaining("Original journal -1 does not exist");
            // Execute the delivered read-only audit against this disposable DB only.
            execute(connection, Files.readString(Path.of("scripts/verification/db-integrity-audit.sql")));
        }
    }

    private void migrate(String target) {
        var config = Flyway.configure().dataSource(URL, "fgc", "fgc_test_only").schemas("fgc")
                .locations("classpath:db/migration", "classpath:db/demo");
        if (target != null) config.target(target);
        config.load().migrate();
    }

    private String definition(Connection connection, String function, String field) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT " + field + " FROM pg_proc WHERE oid='fgc."
                     + function + "()'::regprocedure")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
