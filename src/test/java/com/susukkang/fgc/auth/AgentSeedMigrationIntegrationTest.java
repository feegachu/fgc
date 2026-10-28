package com.susukkang.fgc.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AgentSeedMigrationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthenticationConfiguration authentication;

    @Test
    void agentAccountIsSeededDisabledThenActivatedByV66WithAHash() throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT version FROM fgc.flyway_schema_history
                WHERE script LIKE '%__seed_agent_role_and_account.sql'
                """, String.class)).isEqualTo("64");
        var seed = jdbc.queryForMap("""
                SELECT u.password_hash, u.account_status, r.role_code, a.agent_code
                FROM fgc.app_user u JOIN fgc.app_role r USING (role_id)
                JOIN fgc.agent a ON a.agent_id = u.agent_id WHERE u.login_id = 'agent01'
                """);
        assertThat(jdbc.queryForObject("SELECT version FROM fgc.flyway_schema_history WHERE script LIKE '%__activate_agent_demo_account.sql'", String.class)).isEqualTo("66");
        assertThat(seed).containsEntry("account_status", "ACTIVE")
                .containsEntry("role_code", "AGENT").containsEntry("agent_code", "A-FC-001");
        var manager = authentication.getAuthenticationManager();
        var login = UsernamePasswordAuthenticationToken.unauthenticated("agent01", "unprovisioned-password");
        assertThat(seed.get("password_hash").toString()).startsWith("$2a$");
        assertThatThrownBy(() -> manager.authenticate(login)).isInstanceOf(BadCredentialsException.class);
    }
}
