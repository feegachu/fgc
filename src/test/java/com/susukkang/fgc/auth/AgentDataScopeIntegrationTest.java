package com.susukkang.fgc.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.util.DateUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 PostgreSQL·Repository·JWT를 사용한다. 사용자·업무 서비스를 목으로 대체하지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AgentDataScopeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired FgcUserDetailsService users;
    @Autowired AuthTokenService tokens;
    @Autowired JwtDecoder decoder;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @Autowired AgentDataScopeService scope;
    private Long ownAgent;
    private Long ownContract;
    private Long otherContract;
    private String access;
    private String loginPassword;

    @BeforeEach
    void prepare() {
        ownAgent = id("SELECT agent_id FROM fgc.agent WHERE agent_code = 'A-FC-001'");
        ownContract = id("SELECT min(contract_id) FROM fgc.insurance_contract WHERE agent_id = ?", ownAgent);
        otherContract = id("SELECT min(contract_id) FROM fgc.insurance_contract WHERE agent_id <> ?", ownAgent);
        loginPassword = UUID.randomUUID().toString();
        jdbc.update("UPDATE fgc.app_user SET password_hash = ? WHERE login_id = 'agent01'", passwords.encode(loginPassword));
        access = tokens.login((FgcUserDetails) users.loadUserByUsername("agent01")).accessToken();
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void seedLoginAndMeCarryAgentIdentityWithoutInternalPermissions() throws Exception {
        var login = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "http://localhost").header("X-FGC-Client", "web")
                .contentType("application/json").content(json.writeValueAsString(java.util.Map.of(
                        "loginId", "agent01", "password", loginPassword)))).andExpect(status().isOk());
        access = data(login).path("accessToken").asText();
        assertThat(decoder.decode(access).getClaimAsString("agentId")).isEqualTo(ownAgent.toString());
        read("/api/v1/auth/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCode").value("AGENT"))
                .andExpect(jsonPath("$.data.agentId").value(ownAgent))
                .andExpect(jsonPath("$.data.isAgent").value(true))
                .andExpect(jsonPath("$.data.canUseAgentWorkspace").value(true))
                .andExpect(jsonPath("$.data.canProcess").value(false))
                .andExpect(jsonPath("$.data.canViewAuditLog").value(false))
                .andExpect(jsonPath("$.data.canFinalizeValidation").value(false));
    }

    @Test
    void listsCountsAndCsvUseTheSameScope() throws Exception {
        long expected = id("SELECT count(*) FROM fgc.insurance_contract WHERE agent_id = ?", ownAgent);
        JsonNode data = data(read("/api/v1/contracts?size=100").andExpect(status().isOk()));
        assertThat(data.path("totalElements").asLong()).isEqualTo(expected);
        assertThat(data.path("content").size()).isEqualTo(expected);
        String otherNo = jdbc.queryForObject("SELECT contract_no FROM fgc.insurance_contract WHERE contract_id = ?",
                String.class, otherContract);
        read("/api/v1/contracts/export.csv").andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(otherNo))));
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        read("/api/v1/contracts?agentId=" + otherAgent).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void directOtherContractAndHistoryAreForbidden() throws Exception {
        read("/api/v1/contracts/" + ownContract).andExpect(status().isOk());
        read("/api/v1/contracts/" + otherContract).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"))
                .andExpect(jsonPath("$.error.message").value("본인 담당 자료만 조회·처리할 수 있습니다."));
        read("/api/v1/contracts/" + otherContract + "/status-events").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
    }

    @Test
    void currentMaintenanceAssignmentAllowsContractButNotAnotherRecipientsPayment() throws Exception {
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        jdbc.update("""
                INSERT INTO fgc.contract_manager_assignment(contract_id, agent_id, assignment_type, effective_from, effective_to)
                VALUES (?, ?, 'MAINTENANCE_MANAGER', ?, ?)
                """, otherContract, ownAgent, today, today);
        read("/api/v1/contracts/" + otherContract).andExpect(status().isOk());
        long expected = id("SELECT count(*) FROM fgc.insurance_contract WHERE agent_id = ?", ownAgent) + 1;
        read("/api/v1/contracts?size=100").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(expected));
        Long otherPayment = id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction"
                + " WHERE recipient_agent_id IS NOT NULL AND recipient_agent_id <> ?", ownAgent);
        read("/api/v1/transactions/" + otherPayment).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
        jdbc.update("UPDATE fgc.contract_manager_assignment SET effective_from = ?, effective_to = ?"
                + " WHERE contract_id = ?", today.plusDays(1), today.plusDays(2), otherContract);
        read("/api/v1/contracts/" + otherContract).andExpect(status().isForbidden());
    }

    @Test
    void paymentListAndCountUseRecipientRatherThanSolicitor() throws Exception {
        long expected = id("SELECT count(*) FROM fgc.commission_transaction WHERE recipient_agent_id = ?", ownAgent);
        read("/api/v1/transactions?size=100").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(expected));
        Long payment = id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction WHERE recipient_agent_id = ?", ownAgent);
        read("/api/v1/transactions/" + payment).andExpect(status().isOk());
    }

    @Test
    void mixedInternalDataPdfAndAiCannotBeReadByAgent() throws Exception {
        for (String path : new String[]{"/api/v1/intake/documents", "/api/v1/intake/documents/1/source",
                "/api/v1/documents/OTHER.pdf?version=1", "/api/v1/base/agents", "/api/v1/dashboard",
                "/api/v1/contracts/" + ownContract + "/journals",
                "/api/v1/contracts/" + ownContract + "/transactions"}) {
            read(path).andExpect(status().isForbidden());
        }
        mvc.perform(get("/contracts/" + ownContract).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
                (FgcUserDetails) users.loadUserByUsername("agent01")))).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ai/pico/ask")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .contentType("application/json").content("{\"context\":{\"entityType\":\"CONTRACT\",\"entityId\":" + otherContract + "}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sharedPdfAndAiGuardsCheckActualOwnerAndRequestedVersion() {
        FgcUserDetails agent = (FgcUserDetails) users.loadUserByUsername("agent01");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                agent, null, agent.getAuthorities()));
        String no = "SCOPE-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fgc.generated_document(document_no, version_no, document_kind,
                    source_entity_type, source_entity_id, storage_key, content_hash, created_by)
                VALUES (?, 1, 'COMPARE_CONFIRMATION', 'CONTRACT', ?, 'synthetic/scope.pdf', ?, ?)
                """, no, ownContract, "0".repeat(64), agent.getUserId());
        scope.requireDocument(no, 1);
        assertScopeDenied(() -> scope.requireDocument(no, 2));
        assertScopeDenied(() -> scope.requireDocument("OTHER", 1));
        scope.requireTarget("CONTRACT", ownContract);
        assertScopeDenied(() -> scope.requireTarget("CONTRACT", otherContract));
        assertScopeDenied(() -> scope.requireTarget("INTAKE", 1L));
    }

    @Test
    void changedAgentLinkIsReadFromDbAndMissingLinkIsRejected() throws Exception {
        Long newAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        jdbc.update("UPDATE fgc.app_user SET agent_id = ? WHERE login_id = 'agent01'", newAgent);
        read("/api/v1/contracts/" + ownContract).andExpect(status().isForbidden());
        read("/api/v1/contracts/" + otherContract).andExpect(status().isOk());
        jdbc.update("UPDATE fgc.app_user SET agent_id = NULL WHERE login_id = 'agent01'");
        read("/api/v1/contracts").andExpect(status().isUnauthorized());
    }

    @Test
    void existingFourRolesKeepContractReadAccess() throws Exception {
        for (String login : new String[]{"admin", "gaadmin", "settle01", "audit01"}) {
            String token = tokens.login((FgcUserDetails) users.loadUserByUsername(login)).accessToken();
            mvc.perform(get("/api/v1/contracts/" + otherContract).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void expiredMaintenanceAndMissingSessionLinkDoNotKeepAccess() throws Exception {
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        jdbc.update("""
                INSERT INTO fgc.contract_manager_assignment(contract_id, agent_id, assignment_type, effective_from, effective_to)
                VALUES (?, ?, 'MAINTENANCE_MANAGER', ?, ?)
                """, otherContract, ownAgent, today.minusDays(2), today.minusDays(1));
        read("/api/v1/contracts/" + otherContract).andExpect(status().isForbidden());
        FgcUserDetails oldSession = (FgcUserDetails) users.loadUserByUsername("agent01");
        jdbc.update("UPDATE fgc.app_user SET agent_id = NULL WHERE login_id = 'agent01'");
        mvc.perform(get("/api/v1/contracts").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(oldSession)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
        mvc.perform(get("/api/v1/auth/me").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(oldSession)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentIdAndRoleClaimsCannotExpandAgentScopeEvenWhenSigned() throws Exception {
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .claims(values -> values.putAll(decoder.decode(access).getClaims()))
                .claim("agentId", id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract))
                .claim("roles", java.util.List.of("SYSTEM_ADMIN")).build();
        String modified = encoder.encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                        org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        mvc.perform(get("/api/v1/contracts/" + otherContract).header(HttpHeaders.AUTHORIZATION, "Bearer " + modified))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
    }

    private ResultActions read(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + access));
    }

    @Test
    void aiAndDocumentGuardsRejectMissingAuthenticationContext() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> scope.requireTarget("CONTRACT", otherContract))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.AUTH_002));
        assertThatThrownBy(() -> scope.requireDocument("ANY", 1))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.AUTH_002));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "/api/v1/base/agents?asOf=2026-07-01,false",
            "/api/v1/transactions?size=5,false",
            "/contracts,false",
            "/contracts,true"})
    void sessionMustReauthenticateWhenInternalRoleChangesToAgent(String path, boolean forgedBearer) throws Exception {
        jdbc.update("UPDATE fgc.app_user SET password_hash = ? WHERE login_id = 'settle01'", passwords.encode(loginPassword));
        var login = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("username", "settle01").param("password", loginPassword)).andExpect(status().is3xxRedirection());
        var session = (org.springframework.mock.web.MockHttpSession) login.andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        jdbc.update("UPDATE fgc.app_user SET role_id = (SELECT role_id FROM fgc.app_role WHERE role_code = 'AGENT'),"
                + " agent_id = ? WHERE login_id = 'settle01'", ownAgent);
        var request = get(path).session(session);
        if (forgedBearer) request.header(HttpHeaders.AUTHORIZATION, "Bearer forged");
        mvc.perform(request)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"));
        assertThat(session.isInvalid()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"LOCKED", "DISABLED"})
    void sessionMustReauthenticateWhenAccountStateChanges(String state) throws Exception {
        var login = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("username", "agent01").param("password", loginPassword)).andExpect(status().is3xxRedirection());
        var session = (org.springframework.mock.web.MockHttpSession) login.andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        jdbc.update("UPDATE fgc.app_user SET account_status = ? WHERE login_id = 'agent01'", state);
        mvc.perform(get("/api/v1/contracts").session(session))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"));
        assertThat(session.isInvalid()).isTrue();
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private Long id(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    private void assertScopeDenied(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(FgcBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.AUTH_005));
    }
}
