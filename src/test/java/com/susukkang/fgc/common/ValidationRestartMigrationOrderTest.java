package com.susukkang.fgc.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/** #844: 병합된 정식 마이그레이션 V78→V79→V80의 순서와 재실행을 검증한다. */
class ValidationRestartMigrationOrderTest {
  @Test
  void versionsThrough80StayBoundedWhenFutureMigrationExists(@TempDir Path futureMigrations)
      throws Exception {
    // 실제 V81 등과 번호가 겹치지 않도록 테스트 전용의 높은 버전을 임시 디렉터리에 둔다.
    Files.writeString(
        futureMigrations.resolve("V999999__future_migration_probe.sql"),
        "CREATE TABLE fgc.it_future_migration_probe (id bigint)");
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
              .locations(
                  "classpath:db/migration",
                  "classpath:db/demo",
                  "classpath:db/demo-agent",
                  "filesystem:" + futureMigrations.toAbsolutePath())
              .target("80")
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
