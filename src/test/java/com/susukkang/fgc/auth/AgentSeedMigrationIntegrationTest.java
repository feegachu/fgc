package com.susukkang.fgc.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.DisabledException;
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
    void newAgentAccountRequiresAnExplicitPasswordAndActivation() throws Exception {
        assertThat(jdbc.queryForObject("""
                SELECT version FROM fgc.flyway_schema_history
                WHERE script LIKE '%__seed_agent_role_and_account.sql'
                """, String.class)).isEqualTo("64");
        var seed = jdbc.queryForMap("""
                SELECT u.password_hash, u.account_status, r.role_code, a.agent_code
                FROM fgc.app_user u JOIN fgc.app_role r USING (role_id)
                JOIN fgc.agent a ON a.agent_id = u.agent_id WHERE u.login_id = 'agent01'
                """);
        assertThat(seed).containsEntry("password_hash", "!").containsEntry("account_status", "DISABLED")
                .containsEntry("role_code", "AGENT").containsEntry("agent_code", "A-FC-001");
        var manager = authentication.getAuthenticationManager();
        var login = UsernamePasswordAuthenticationToken.unauthenticated("agent01", "unprovisioned-password");
        assertThatThrownBy(() -> manager.authenticate(login)).isInstanceOf(DisabledException.class);
    }
}
