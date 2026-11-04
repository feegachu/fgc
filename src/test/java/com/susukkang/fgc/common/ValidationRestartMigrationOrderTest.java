package com.susukkang.fgc.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.ArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** #844: 병합된 정식 마이그레이션 V78→V79→V80의 순서와 재실행을 검증한다. */
class ValidationRestartMigrationOrderTest {
  @Test
  void pendingVersionsApplyBeforeRestartAndSecondMigrationIsNoOp() throws Exception {
    try (var db =
        new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("migration_order")
            .withUsername("fgc")
            .withPassword("fgc_test_only")) {
      db.start();
      Flyway.configure()
          .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
          .schemas("fgc")
          .locations("classpath:db/migration", "classpath:db/demo", "classpath:db/demo-agent")
          .target("77")
          .outOfOrder(false)
          .load()
          .migrate();
      var all =
          Flyway.configure()
              .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
              .schemas("fgc")
              .locations("classpath:db/migration", "classpath:db/demo", "classpath:db/demo-agent")
              .outOfOrder(false)
              .load();
      assertThat(all.migrate().migrationsExecuted).isEqualTo(3);
      try (var c =
              DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
          var stmt = c.createStatement();
          var rs =
              stmt.executeQuery(
                  "SELECT version FROM fgc.flyway_schema_history WHERE version IN ('78','79','80')"
                      + " AND success ORDER BY installed_rank")) {
        var versions = new ArrayList<String>();
        while (rs.next()) versions.add(rs.getString(1));
        assertThat(versions).containsExactly("78", "79", "80");
      }
      all.validate();
      assertThat(all.migrate().migrationsExecuted).isZero();
    }
  }
}
