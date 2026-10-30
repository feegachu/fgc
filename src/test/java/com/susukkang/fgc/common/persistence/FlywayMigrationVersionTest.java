package com.susukkang.fgc.common.persistence;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import java.util.TreeMap;
import static org.assertj.core.api.Assertions.assertThat;

/** Validate the combined locations used by dev/test, without requiring a database to start. */
class FlywayMigrationVersionTest {
    @Test void migrationVersionsAreUniqueAcrossAllActiveLocations() throws Exception {
        var versions = new TreeMap<MigrationVersion, String>();
        var resources = new PathMatchingResourcePatternResolver();
        for (var location : new String[] { "migration", "demo", "demo-agent" }) {
            for (var resource : resources.getResources("classpath*:db/" + location + "/V*__*.sql")) {
                var filename = resource.getFilename();
                assertThat(filename).isNotNull();
                var version = MigrationVersion.fromVersion(filename.substring(1, filename.indexOf("__")));
                var source = location + "/" + filename;
                var previous = versions.putIfAbsent(version, source);
                assertThat(previous).as("Flyway version %s duplicated by %s and %s", version, previous, source).isNull();
            }
        }
        assertThat(versions).isNotEmpty();
    }
}
