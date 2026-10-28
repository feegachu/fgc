package com.susukkang.fgc.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AgentSeedMigrationIntegrationTest {
    private static final String OTHER_HASH = "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz012345";

    @Autowired JdbcTemplate jdbc;
    @Autowired AuthenticationConfiguration authentication;

    @Test
    void agentAccountIsSeededByV64AndActivatedByV66WithAHash() throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT version FROM fgc.flyway_schema_history
                WHERE script LIKE '%__seed_agent_role_and_account.sql'
                """, String.class)).isEqualTo("64");
        assertThat(jdbc.queryForObject("""
                SELECT version FROM fgc.flyway_schema_history
                WHERE script LIKE '%__activate_agent_demo_account.sql'
                """, String.class)).isEqualTo("66");
        var seed = jdbc.queryForMap("""
                SELECT u.password_hash, u.account_status, r.role_code, a.agent_code
                FROM fgc.app_user u JOIN fgc.app_role r USING (role_id)
                JOIN fgc.agent a ON a.agent_id = u.agent_id WHERE u.login_id = 'agent01'
                """);
        assertThat(seed).containsEntry("account_status", "ACTIVE")
                .containsEntry("role_code", "AGENT").containsEntry("agent_code", "A-FC-001");
        assertThat(seed.get("password_hash").toString()).startsWith("$2a$");
        var manager = authentication.getAuthenticationManager();
        var login = UsernamePasswordAuthenticationToken.unauthenticated("agent01", "unprovisioned-password");
        assertThatThrownBy(() -> manager.authenticate(login)).isInstanceOf(BadCredentialsException.class);
    }

    /** V64 가 남긴 상태('!'·DISABLED)에서는 V66 UPDATE 가 정확히 1건을 활성화한다. */
    @Test
    void v66ActivatesOnlyTheUnprovisionedAgentAccount() throws Exception {
        jdbc.update("UPDATE fgc.app_user SET password_hash = '!', account_status = 'DISABLED' WHERE login_id = 'agent01'");

        assertThat(jdbc.update(v66Update())).isEqualTo(1);

        var row = jdbc.queryForMap("SELECT password_hash, account_status FROM fgc.app_user WHERE login_id = 'agent01'");
        assertThat(row).containsEntry("account_status", "ACTIVE");
        assertThat(row.get("password_hash").toString()).startsWith("$2a$").isNotEqualTo("!");
    }

    /** 이미 다른 비밀번호가 설정된 계정은 V66 가 덮어쓰지 않는다(해시·상태 그대로, 갱신 0건). */
    @Test
    void v66DoesNotOverwriteAnAccountThatAlreadyHasAPassword() throws Exception {
        jdbc.update("UPDATE fgc.app_user SET password_hash = ?, account_status = 'DISABLED' WHERE login_id = 'agent01'",
                OTHER_HASH);

        assertThat(jdbc.update(v66Update())).isZero();

        var row = jdbc.queryForMap("SELECT password_hash, account_status FROM fgc.app_user WHERE login_id = 'agent01'");
        assertThat(row).containsEntry("password_hash", OTHER_HASH).containsEntry("account_status", "DISABLED");
    }

    /** 마이그레이션 파일의 UPDATE 문만 꺼내 그대로 실행한다. */
    private static String v66Update() throws Exception {
        String sql = new String(new ClassPathResource("db/demo-agent/V66__activate_agent_demo_account.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return sql.substring(sql.indexOf("UPDATE app_user")).replace("UPDATE app_user", "UPDATE fgc.app_user");
    }
}
