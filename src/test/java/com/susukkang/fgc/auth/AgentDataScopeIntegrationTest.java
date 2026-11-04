package com.susukkang.fgc.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.agenthome.service.AgentHomeService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.compare.service.CompareRankingService;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.report.service.AgentStatementService;
import com.susukkang.fgc.transaction.dto.CommissionPaymentSearchCondition;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
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
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    @Autowired ContractService contractService;
    @Autowired CommissionPaymentService paymentService;
    @Autowired AgentStatementService statementService;
    @Autowired AgentHomeService homeService;
    @Autowired CompareRankingService compareService;
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
        jdbc.update("UPDATE fgc.app_user SET password_hash = ?, account_status = 'ACTIVE' WHERE login_id = 'agent01'",
                passwords.encode(loginPassword));
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
        // 다른 설계사를 지정한 요청은 빈 목록으로 답하지 않고 조회 전에 거절한다(FUN-085 세부 처리규칙).
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        read("/api/v1/contracts?agentId=" + otherAgent).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
    }

    @Test
    void directOtherContractAndHistoryAreForbidden() throws Exception {
        read("/api/v1/contracts/" + ownContract).andExpect(status().isOk());
        read("/api/v1/contracts/" + otherContract).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"))
                .andExpect(jsonPath("$.error.message").value("본인 데이터만 조회하거나 허용된 확인 기록을 저장할 수 있습니다."));
        read("/api/v1/contracts/" + otherContract + "/status-events").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
        // IF-API-89 영향 조회도 남의 계약이면 객체 범위 검사(FGC-AUTH-005)로 막힌다. 경로 자체가 막힌 403 과 구별한다.
        read("/api/v1/contracts/" + otherContract + "/status-events/1/effects").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
    }

    @Test
    void ownContractHistoryAndEffectsAreReadableButAnotherContractsEventIsNotFound() throws Exception {
        read("/api/v1/contracts/" + ownContract + "/status-events").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].followUp.status").exists());
        long ownEvent = id("SELECT min(contract_status_event_id) FROM fgc.contract_status_event WHERE contract_id = ?", ownContract);
        long otherEvent = id("SELECT min(contract_status_event_id) FROM fgc.contract_status_event WHERE contract_id = ?", otherContract);
        read("/api/v1/contracts/" + ownContract + "/status-events/" + ownEvent + "/effects").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.content").isArray());
        // 본인 계약 ID에 남의 사건 ID를 붙여도 그 사건의 영향을 읽을 수 없다.
        read("/api/v1/contracts/" + ownContract + "/status-events/" + otherEvent + "/effects").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
        // 남의 계약 ID에 남의 사건 ID를 맞춰 붙여도 객체 범위 검사가 먼저 막는다.
        read("/api/v1/contracts/" + otherContract + "/status-events/" + otherEvent + "/effects").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
    }

    @Test
    void currentMaintenanceAssignmentDoesNotExpandContractsOrPayments() throws Exception {
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        long expected = id("SELECT count(*) FROM fgc.insurance_contract WHERE agent_id = ?", ownAgent);
        jdbc.update("""
                INSERT INTO fgc.contract_manager_assignment(contract_id, agent_id, assignment_type, effective_from, effective_to)
                VALUES (?, ?, 'MAINTENANCE_MANAGER', ?, ?)
                """, otherContract, ownAgent, today, today);
        read("/api/v1/contracts/" + otherContract).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
        read("/api/v1/contracts/" + otherContract + "/status-events").andExpect(status().isForbidden());
        read("/api/v1/contracts?size=100").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(expected));
        read("/api/v1/contracts/export.csv").andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(contractNo(otherContract)))));
        Long otherPayment = id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction"
                + " WHERE recipient_agent_id IS NOT NULL AND recipient_agent_id <> ?", ownAgent);
        read("/api/v1/transactions/" + otherPayment).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
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
        Long comparison = createComparison(ownAgent);
        jdbc.update("""
                INSERT INTO fgc.generated_document(document_no, version_no, document_kind,
                    source_entity_type, source_entity_id, storage_key, content_hash, created_by)
                VALUES (?, 1, 'COMPARE_CONFIRMATION', 'COMPARE_EXPLANATION', ?, 'synthetic/scope.pdf', ?, ?)
                """, no, comparison, "0".repeat(64), agent.getUserId());
        scope.requireComparison(comparison);
        scope.requireDocument(no, 1);
        assertScopeDenied(() -> scope.requireDocument(no, 2));
        assertScopeDenied(() -> scope.requireDocument("OTHER", 1));
        scope.requireTarget("CONTRACT", ownContract);
        assertScopeDenied(() -> scope.requireTarget("CONTRACT", otherContract));
        assertScopeDenied(() -> scope.requireTarget("INTAKE", 1L));
    }

    @Test
    void comparisonAndPdfOwnershipBothFollowCurrentAgentLinkRegardlessOfPdfGenerator() {
        FgcUserDetails agent = (FgcUserDetails) users.loadUserByUsername("agent01");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                agent, null, agent.getAuthorities()));
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        Long ownComparison = createComparison(ownAgent);
        Long otherComparison = createComparison(otherAgent);
        String ownNo = "OWN-" + UUID.randomUUID();
        String otherNo = "OTHER-" + UUID.randomUUID();
        for (var entry : java.util.Map.of(ownNo, ownComparison, otherNo, otherComparison).entrySet()) {
            jdbc.update("""
                    INSERT INTO fgc.generated_document(document_no, version_no, document_kind,
                        source_entity_type, source_entity_id, storage_key, content_hash, created_by)
                    VALUES (?, 1, 'COMPARE_CONFIRMATION', 'COMPARE_EXPLANATION', ?, 'synthetic/scope.pdf', ?, ?)
                    """, entry.getKey(), entry.getValue(), "0".repeat(64),
                    entry.getKey().equals(ownNo) ? agent.getUserId() : id("SELECT user_id FROM fgc.app_user WHERE login_id = 'admin'"));
        }
        scope.requireComparison(ownComparison);
        scope.requireDocument(ownNo, 1);
        assertScopeDenied(() -> scope.requireComparison(otherComparison));
        assertScopeDenied(() -> scope.requireDocument(otherNo, 1));
        jdbc.update("UPDATE fgc.app_user SET agent_id = ? WHERE login_id = 'agent01'", otherAgent);
        assertScopeDenied(() -> scope.requireComparison(ownComparison));
        assertScopeDenied(() -> scope.requireDocument(ownNo, 1));
        scope.requireComparison(otherComparison);
        scope.requireDocument(otherNo, 1);
        String invalidSource = "INVALID-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fgc.generated_document(document_no, version_no, document_kind,
                    source_entity_type, source_entity_id, storage_key, content_hash, created_by)
                VALUES (?, 1, 'COMPARE_CONFIRMATION', 'CONTRACT', ?, 'synthetic/scope.pdf', ?, ?)
                """, invalidSource, otherComparison, "0".repeat(64), agent.getUserId());
        assertScopeDenied(() -> scope.requireDocument(invalidSource, 1));
    }

    private Long createComparison(Long agentId) {
        Long group = id("""
                INSERT INTO fgc.compare_product_group(group_code, insurance_sector, major_class, minor_class_name)
                VALUES (?, 'LIFE', 'PROTECTION', 'scope fixture') RETURNING compare_product_group_id
                """, UUID.randomUUID().toString());
        return id("""
                INSERT INTO fgc.compare_explanation(grade_data_as_of, agent_id, compare_product_group_id,
                    recommended_offering_id, compared_offering_ids, selection_criteria, recommendation_reason, fee_level_note)
                VALUES (CURRENT_DATE, ?, ?, (SELECT min(product_offering_id) FROM fgc.product_offering),
                    '[]'::jsonb, 'scope fixture', 'scope fixture', 'scope fixture') RETURNING compare_explanation_id
                """, agentId, group);
    }

    @Test
    void sharedInquiryClawbackAndPaymentGuardsExecuteRealOwnerQueries() {
        FgcUserDetails agent = (FgcUserDetails) users.loadUserByUsername("agent01");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                agent, null, agent.getAuthorities()));
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        Long ownInquiry = createInquiry(ownAgent, agent.getUserId());
        Long otherInquiry = createInquiry(otherAgent, agent.getUserId());
        Long otherWriter = createInquiry(ownAgent, id("SELECT user_id FROM fgc.app_user WHERE login_id = 'admin'"));
        scope.requireInquiry(ownInquiry);
        assertScopeDenied(() -> scope.requireInquiry(otherInquiry));
        assertScopeDenied(() -> scope.requireInquiry(otherWriter));
        Long rule = id("""
                WITH fixture_policy AS (
                  INSERT INTO fgc.policy_version(policy_code,policy_name,policy_type,source_class,version_no,effective_from,status)
                  VALUES (?, '소유권 검증 환수 정책', 'CLAWBACK', 'PROJECT_ASSUMPTION', 1, DATE '2026-01-01', 'DRAFT')
                  RETURNING policy_version_id
                )
                INSERT INTO fgc.clawback_rule(policy_version_id, elapsed_month_from, elapsed_month_to,
                    clawback_basis_code, clawback_rate_pct)
                SELECT policy_version_id, 9999, 9999, 'SCOPE_FIXTURE', 10 FROM fixture_policy
                RETURNING clawback_rule_id
                """, "SCOPE-CLAW-" + UUID.randomUUID());
        Long ownCase = createClawback(ownAgent, rule);
        Long otherCase = createClawback(otherAgent, rule);
        scope.requireClawback(ownCase);
        scope.requireTarget("CLAWBACK", ownCase);
        assertScopeDenied(() -> scope.requireClawback(otherCase));
        assertScopeDenied(() -> scope.requireTarget("CLAWBACK", otherCase));
        // 수취인이 본인이어도 남의 계약에 걸린 행의 환수는 본인 환수가 아니다.
        Long foreignRowPayment = payment("GA_MANUAL_PAYMENT", ownAgent, new Long[]{otherContract, ownAgent, 100L});
        Long foreignRowCase = createClawbackOn(id("SELECT transaction_attribution_id FROM fgc.transaction_attribution"
                + " WHERE commission_transaction_id = ?", foreignRowPayment), rule);
        assertScopeDenied(() -> scope.requireClawback(foreignRowCase));
        Long ownPayment = id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction WHERE recipient_agent_id = ?", ownAgent);
        Long otherPayment = id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction WHERE recipient_agent_id = ?", otherAgent);
        scope.requireTarget("TRANSACTION", ownPayment);
        assertScopeDenied(() -> scope.requireTarget("TRANSACTION", otherPayment));
    }

    private Long createInquiry(Long ownerAgent, Long writer) {
        return id("""
                INSERT INTO fgc.inquiry(inquiry_kind, target_type, target_id, agent_id, content, due_date, created_by)
                VALUES ('INQUIRY', 'CONTRACT', ?, ?, 'scope fixture', CURRENT_DATE, ?) RETURNING inquiry_id
                """, ownContract, ownerAgent, writer);
    }

    private Long createClawback(Long recipient, Long rule) {
        Long attribution = id("""
                SELECT min(a.transaction_attribution_id) FROM fgc.transaction_attribution a
                JOIN fgc.commission_transaction t USING (commission_transaction_id)
                WHERE t.recipient_agent_id = ? AND a.contract_id IS NOT NULL
                """, recipient);
        assertThat(attribution).isNotNull();
        return createClawbackOn(attribution, rule);
    }

    private Long createClawbackOn(Long attribution, Long rule) {
        Long caseId = id("""
                INSERT INTO fgc.clawback_case(contract_id, policy_version_id, case_no)
                SELECT a.contract_id, r.policy_version_id, ? FROM fgc.transaction_attribution a
                CROSS JOIN fgc.clawback_rule r WHERE a.transaction_attribution_id = ? AND r.clawback_rule_id = ?
                RETURNING clawback_case_id
                """, "SCOPE-" + UUID.randomUUID(), attribution, rule);
        jdbc.update("""
                INSERT INTO fgc.clawback_line(clawback_case_id, line_no, transaction_attribution_id,
                    clawback_rule_id, originally_paid_amount, applied_rate_pct, clawback_amount)
                VALUES (?, 1, ?, ?, 100, 10, 10)
                """, caseId, attribution, rule);
        return caseId;
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
        Long otherPayment = id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction"
                + " WHERE recipient_agent_id IS NOT NULL AND recipient_agent_id <> ?", ownAgent);
        for (String login : new String[]{"admin", "gaadmin", "settle01", "audit01"}) {
            String token = tokens.login((FgcUserDetails) users.loadUserByUsername(login)).accessToken();
            for (String path : new String[]{"/api/v1/contracts/" + otherContract, "/api/v1/contracts", "/api/v1/transactions",
                    "/api/v1/transactions/" + otherPayment, "/api/v1/reports/agents/" + ownAgent + "/statements?month=2026-07"}) {
                as(token, path).andExpect(status().isOk());
            }
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
        read("/api/v1/contracts?size=100").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements")
                .value(id("SELECT count(*) FROM fgc.insurance_contract WHERE agent_id = ?", ownAgent)));
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

    // 2026-10-27 yslee - 세션 대신 Bearer 로 검증한다(#419 STATELESS). 역할·계정 상태는 요청마다 DB 에서 다시 읽으므로
    // 이미 받은 토큰도 다음 요청부터 바뀐 역할로만 동작하고, 잠기거나 비활성화된 계정의 토큰은 거절된다.
    @Test
    void bearerFollowsRoleChangeToAgentOnNextRequest() throws Exception {
        String internal = tokens.login((FgcUserDetails) users.loadUserByUsername("settle01")).accessToken();
        jdbc.update("UPDATE fgc.app_user SET role_id = (SELECT role_id FROM fgc.app_role WHERE role_code = 'AGENT'),"
                + " agent_id = ? WHERE login_id = 'settle01'", ownAgent);
        mvc.perform(get("/api/v1/base/agents?asOf=2026-07-01").header(HttpHeaders.AUTHORIZATION, "Bearer " + internal))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + internal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roleCode").value("AGENT"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"LOCKED", "DISABLED"})
    void bearerIsRejectedWhenAccountStateChanges(String state) throws Exception {
        jdbc.update("UPDATE fgc.app_user SET account_status = ? WHERE login_id = 'agent01'", state);
        mvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"));
    }

    /** 라우트 전수 시험의 예외 목록. 새 API는 막힌 상태로 시작하고, 열 때는 이 목록과 객체 범위 검사를 함께 고친다. */
    private static final java.util.Set<String> AGENT_ROUTES = java.util.Set.of(
            "POST /api/v1/auth/login", "POST /api/v1/auth/refresh", "POST /api/v1/auth/logout", "GET /api/v1/auth/me",
            "GET /api/v1/base/insurers", "GET /api/v1/base/products", "GET /api/v1/base/commission-items",
            "GET /api/v1/news/headlines", "GET /api/v1/ai/compare-chat/categories",
            "POST /api/v1/ai/compare-chat/rank", "POST /api/v1/ai/compare-chat/explain", "POST /api/v1/ai/compare-chat/ask",
            "GET /api/v1/contracts", "GET /api/v1/contracts/export.csv", "GET /api/v1/contracts/{id}",
            "GET /api/v1/contracts/{id}/status-events",
            "GET /api/v1/contracts/{id}/status-events/{eventId}/effects",
            "GET /api/v1/transactions", "GET /api/v1/transactions/{paymentId}",
            "GET /api/v1/agent/me/home", "POST /api/v1/agent/me/contract-checks",
            "GET /api/v1/agent/me/statements", "GET /api/v1/agent/me/statements/{entryKey}");

    @Test
    void everyApiRouteOutsideTheAgentListIsForbidden() throws Exception {
        List<String> checked = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        for (var info : handlerMapping.getHandlerMethods().keySet()) {
            var methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/")) continue;
                for (var method : methods.isEmpty() ? java.util.Set.of(RequestMethod.GET) : methods) {
                    String route = method + " " + pattern;
                    if (AGENT_ROUTES.contains(route)) continue;
                    checked.add(route);
                    var response = mvc.perform(request(HttpMethod.valueOf(method.name()), pattern.replaceAll("\\{[^}]+}", "1"))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)).andReturn().getResponse();
                    if (response.getStatus() != 403 || !response.getContentAsString().contains("FGC-AUTH-003")) {
                        opened.add(route + " -> " + response.getStatus());
                    }
                }
            }
        }
        assertThat(opened).isEmpty();
        // 073 내부 AI, 084 환수 PDF, 정책/지급/환수 쓰기가 실제로 검사 대상이었는지 확인한다.
        assertThat(checked).hasSizeGreaterThan(50).contains(
                "POST /api/v1/ai/pico/ask", "POST /api/v1/ai/pico/guides/prepare", "POST /api/v1/ai/pico/guides/start",
                "POST /api/v1/assistant/calculations", "GET /api/v1/documents/{documentNo}.pdf",
                "GET /api/v1/clawbacks/{id}/notice-documents", "POST /api/v1/clawbacks/{id}/notice-documents",
                "POST /api/v1/transactions", "PUT /api/v1/transactions/{paymentId}", "DELETE /api/v1/transactions/{paymentId}",
                "POST /api/v1/transactions/{paymentId}/confirm", "POST /api/v1/contracts", "PUT /api/v1/contracts/{id}",
                "POST /api/v1/contracts/{id}/status-events/preview",
                "POST /api/v1/policies", "PUT /api/v1/policies/{policyVersionId}", "POST /api/v1/policies/{policyVersionId}/actions",
                "GET /api/v1/reports/agents/{agentId}/statements", "GET /api/v1/contracts/{id}/transactions");
    }

    @Test
    @DisplayName("CT-A01 A/B 설계사는 서로의 계약, 지급, 명세, 합계, 확인 기록에 어떤 ID로도 닿지 못한다")
    void ctA01TwoAgentsCannotReachEachOthersData() throws Exception {
        Long agentB = id("SELECT agent_id FROM fgc.agent WHERE agent_code = 'A-FC-002'");
        Long contractB = id("SELECT min(contract_id) FROM fgc.insurance_contract WHERE agent_id = ?", agentB);
        jdbc.update("""
                INSERT INTO fgc.app_user(login_id, password_hash, user_name, role_id, agent_id, account_status)
                VALUES ('agent-b-scope', 'unused', '교차 검증 설계사', (SELECT role_id FROM fgc.app_role WHERE role_code = 'AGENT'), ?, 'ACTIVE')
                """, agentB);
        String tokenB = tokens.login((FgcUserDetails) users.loadUserByUsername("agent-b-scope")).accessToken();

        assertOnlyOwnDataIsReachable(access, ownAgent, ownContract, agentB, contractB);
        assertOnlyOwnDataIsReachable(tokenB, agentB, contractB, ownAgent, ownContract);
    }

    private void assertOnlyOwnDataIsReachable(String token, Long agent, Long contract, Long otherAgent, Long foreignContract)
            throws Exception {
        long ownContracts = id("SELECT count(*) FROM fgc.insurance_contract WHERE agent_id = ?", agent);
        String foreignNo = contractNo(foreignContract);
        JsonNode contracts = data(as(token, "/api/v1/contracts?size=100").andExpect(status().isOk()));
        assertThat(contracts.path("totalElements").asLong()).isEqualTo(ownContracts);
        assertThat(contracts.path("content").findValuesAsText("contractNo")).isNotEmpty().doesNotContain(foreignNo);
        as(token, "/api/v1/contracts/export.csv").andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(foreignNo))));
        as(token, "/api/v1/contracts/" + contract).andExpect(status().isOk());
        for (String path : new String[]{"/api/v1/contracts/" + foreignContract, "/api/v1/contracts/" + foreignContract + "/status-events",
                "/api/v1/contracts/" + foreignContract + "/status-events/1/effects",
                "/api/v1/contracts?agentId=" + otherAgent, "/api/v1/contracts/export.csv?agentId=" + otherAgent,
                "/api/v1/transactions?agentId=" + otherAgent,
                "/api/v1/transactions/" + id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction WHERE recipient_agent_id = ?", otherAgent)}) {
            as(token, path).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
        }
        long ownPayments = id("SELECT count(*) FROM fgc.commission_transaction WHERE recipient_agent_id = ?", agent);
        JsonNode payments = data(as(token, "/api/v1/transactions?size=100").andExpect(status().isOk()));
        assertThat(payments.path("totalElements").asLong()).isEqualTo(ownPayments);
        assertThat(payments.path("content").findValuesAsText("recipientAgentId")).containsOnly(agent.toString());
        as(token, "/api/v1/transactions/" + payments.path("content").get(0).path("commissionTransactionId").asLong())
                .andExpect(status().isOk());

        // 명세 목록과 합계는 같은 본인 범위이고, 남의 행 키는 없는 키와 같은 404다(인터페이스 §4-4-6 060).
        JsonNode statements = data(as(token, "/api/v1/agent/me/statements?month=2026-07&entryType=PAYMENT&size=100")
                .andExpect(status().isOk()));
        BigDecimal expected = jdbc.queryForObject("""
                SELECT COALESCE(sum(ta.attributed_amount), 0) FROM fgc.commission_transaction ct
                JOIN fgc.transaction_attribution ta USING (commission_transaction_id)
                WHERE ct.recipient_agent_id = ? AND ta.agent_id = ? AND ct.payment_stage = 'GA_TO_FC' AND ct.status = 'CONFIRMED'
                  AND ct.cashflow_type = 'PAYMENT' AND ct.source_type NOT IN ('ADJUSTMENT', 'CLAWBACK', 'RECOVERY')
                  AND ta.attribution_month = DATE '2026-07-01'
                """, BigDecimal.class, agent, agent);
        assertThat(statements.path("summary").path("payment").path("amount").decimalValue()).isEqualByComparingTo(expected);
        assertThat(statements.path("items").path("content").findValuesAsText("contractId")).doesNotContain(foreignContract.toString());
        Long foreignRow = id("""
                SELECT min(transaction_attribution_id) FROM fgc.transaction_attribution ta
                JOIN fgc.commission_transaction ct USING (commission_transaction_id)
                WHERE ct.recipient_agent_id = ? AND ct.payment_stage = 'GA_TO_FC' AND ct.status = 'CONFIRMED'
                """, otherAgent);
        as(token, "/api/v1/agent/me/statements/" + entryKey(foreignRow)).andExpect(status().isNotFound());

        // 홈 합계와 확인 기록도 본인 계약만 다룬다.
        JsonNode home = data(as(token, "/api/v1/agent/me/home?period=ALL").andExpect(status().isOk()));
        assertThat(home.path("portfolio").path("contractCount").asLong()).isEqualTo(id(
                "SELECT count(*) FROM fgc.insurance_contract WHERE agent_id = ? AND contract_date <= ?",
                agent, LocalDate.now(DateUtil.SEOUL_ZONE)));
        assertThat(home.path("priorityContracts").findValuesAsText("contractId")).doesNotContain(foreignContract.toString());
        long checks = id("SELECT count(*) FROM fgc.agent_contract_check");
        mvc.perform(post("/api/v1/agent/me/contract-checks").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("contractId", foreignContract, "result", "CLOSED",
                                "expectedContextVersion", "contract" + foreignContract + "-event1"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
        assertThat(id("SELECT count(*) FROM fgc.agent_contract_check")).isEqualTo(checks);
    }

    /** 남의 행은 귀속행 설계사가 다른 경우와, 귀속행 설계사는 본인이지만 남의 계약에 걸린 경우 두 형태로 온다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mixedPaymentShowsOnlyOwnRowsAndAmountOnEverySurface(boolean rowCarriesOtherAgent) throws Exception {
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        String statements = "/api/v1/agent/me/statements?month=2026-07&entryType=PAYMENT&size=100";
        JsonNode before = data(read(statements)).path("summary").path("payment");
        long listed = data(read("/api/v1/transactions?size=1")).path("totalElements").asLong();

        Long mixed = payment("GA_MANUAL_PAYMENT", ownAgent, new Long[]{ownContract, ownAgent, 1000L},
                new Long[]{otherContract, rowCarriesOtherAgent ? otherAgent : ownAgent, 9000L});

        JsonNode page = data(read("/api/v1/transactions?size=100"));
        assertThat(page.path("totalElements").asLong()).isEqualTo(listed + 1);
        JsonNode row = paymentRow(page, mixed);
        assertThat(row.path("amount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(row.path("attributionCount").asInt()).isEqualTo(1);
        assertThat(row.path("differenceAmount").decimalValue()).isEqualByComparingTo("0");
        // 남의 계약번호로 지급 건을 떠볼 수 없다.
        assertThat(paymentRow(data(read("/api/v1/transactions?size=100&contractNo=" + contractNo(otherContract))), mixed)).isNull();
        assertThat(paymentRow(data(read("/api/v1/transactions?size=100&contractNo=" + contractNo(ownContract))), mixed)).isNotNull();

        var detail = read("/api/v1/transactions/" + mixed).andExpect(status().isOk());
        JsonNode payment = data(detail);
        assertThat(payment.path("amount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(payment.path("attributions")).hasSize(1);
        assertThat(payment.path("attributions").get(0).path("contractId").asLong()).isEqualTo(ownContract);
        assertThat(payment.path("contractId").isNull()).isTrue();
        assertThat(payment.path("capCheckIds")).isEmpty();
        assertThat(payment.findValues("amount")).allSatisfy(amount -> assertThat(amount.decimalValue()).isEqualByComparingTo("1000"));
        assertThat(detail.andReturn().getResponse().getContentAsString()).doesNotContain("HEADER-NOTE-SECRET", "EVD-HEADER-SECRET");

        JsonNode after = data(read(statements));
        assertThat(after.path("summary").path("payment").path("count").asLong()).isEqualTo(before.path("count").asLong() + 1);
        assertThat(after.path("summary").path("payment").path("amount").decimalValue())
                .isEqualByComparingTo(before.path("amount").decimalValue().add(new BigDecimal("1000")));
        assertThat(after.path("items").path("content").findValuesAsText("contractId")).doesNotContain(otherContract.toString());
        Long foreignRow = id("SELECT transaction_attribution_id FROM fgc.transaction_attribution WHERE commission_transaction_id = ? AND contract_id = ?",
                mixed, otherContract);
        read("/api/v1/agent/me/statements/" + entryKey(foreignRow)).andExpect(status().isNotFound());

        // 내부 역할의 기존 조회는 그대로다.
        String settlement = tokens.login((FgcUserDetails) users.loadUserByUsername("settle01")).accessToken();
        JsonNode internal = data(as(settlement, "/api/v1/transactions/" + mixed).andExpect(status().isOk()));
        assertThat(internal.path("amount").decimalValue()).isEqualByComparingTo("10000");
        assertThat(internal.path("attributions")).hasSize(2);
        assertThat(internal.path("contractId").asLong()).isEqualTo(otherContract);
        assertThat(internal.path("note").asText()).isEqualTo("HEADER-NOTE-SECRET");
    }

    /** IF-API-89: 본인 계약의 사건이어도 영향 행에는 다른 수혜자 회차와 남의 지급 건, 헤더 총액이 함께 들어 있다. AGENT에게는 본인 몫만 나간다. */
    @Test
    void statusEventEffectsShowOnlyOwnLinesAndPaymentsEvenOnOwnContract() throws Exception {
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        Long header = id("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no, schedule_regime)
                SELECT ?, 'GA_TO_FC', min(pv.policy_version_id), 1, 'CURRENT' FROM fgc.policy_version pv
                RETURNING schedule_header_id
                """, ownContract);
        Long ownLine = scheduleLine(header, 1, ownAgent);
        Long managerLine = scheduleLine(header, 2, otherAgent);
        Long ownPayment = payment("GA_MANUAL_PAYMENT", ownAgent, new Long[]{ownContract, ownAgent, 300L});
        Long mixed = payment("GA_MANUAL_PAYMENT", ownAgent, new Long[]{ownContract, ownAgent, 1000L}, new Long[]{otherContract, otherAgent, 9000L});
        Long managerPayment = id("SELECT commission_transaction_id FROM fgc.commission_transaction WHERE source_business_key = 'GA-2026-07-0102'");
        Long insurerPayment = id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction WHERE payment_stage = 'INSURER_TO_GA'");
        Long event = statusEvent(ownContract);
        effect(event, "SCHEDULE_LINE", ownLine, 100);
        effect(event, "SCHEDULE_LINE", managerLine, 200);
        effect(event, "PAYMENT", ownPayment, 300);
        effect(event, "PAYMENT", mixed, 10000);
        effect(event, "PAYMENT", managerPayment, 500);
        effect(event, "PAYMENT", insurerPayment, 700);
        String path = "/api/v1/contracts/" + ownContract + "/status-events/" + event + "/effects?size=100";

        JsonNode page = data(read(path).andExpect(status().isOk()));
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page.path("content").findValuesAsText("entityId"))
                .containsExactlyInAnyOrder(ownLine.toString(), ownPayment.toString(), mixed.toString());
        for (JsonNode row : page.path("content")) {
            if (row.path("entityId").asLong() == mixed && "PAYMENT".equals(row.path("entityType").asText())) {
                assertThat(row.path("amount").decimalValue()).isEqualByComparingTo("1000");
            }
        }

        // 내부 역할의 기존 조회는 그대로다.
        String settlement = tokens.login((FgcUserDetails) users.loadUserByUsername("settle01")).accessToken();
        JsonNode internal = data(as(settlement, path).andExpect(status().isOk()));
        assertThat(internal.path("totalElements").asLong()).isEqualTo(6);
        assertThat(internal.path("content").findValues("amount")).extracting(JsonNode::intValue)
                .containsExactlyInAnyOrder(100, 200, 300, 10000, 500, 700);

        // 본인 계약 경로에 남의 계약 사건을 넣어도 읽을 수 없다.
        read("/api/v1/contracts/" + ownContract + "/status-events/" + statusEvent(otherContract) + "/effects")
                .andExpect(status().isNotFound());
    }

    private Long scheduleLine(Long header, int lineNo, Long beneficiary) {
        return id("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount, calculation_type, rate_pct,
                    expected_amount, line_status)
                SELECT ?, ?, ?, ?, DATE '2026-07-25', min(commission_item_id), ?, 'FIRST_YEAR_PREMIUM', 1000000, 'RATE', 10, 100, 'PLANNED'
                FROM fgc.commission_item RETURNING schedule_line_id
                """, header, lineNo, lineNo, lineNo, beneficiary);
    }

    private Long statusEvent(Long contractId) {
        return id("""
                INSERT INTO fgc.contract_status_event(contract_id, event_seq, previous_status, new_status, effective_at,
                    received_at, reason_code, source_system, source_event_key)
                SELECT ?, COALESCE(max(event_seq), 0) + 1, NULL, 'UNPAID', clock_timestamp(), clock_timestamp(), 'TEST', 'IT-SCOPE', ?
                FROM fgc.contract_status_event WHERE contract_id = ?
                RETURNING contract_status_event_id
                """, contractId, UUID.randomUUID().toString(), contractId);
    }

    private void effect(Long event, String entityType, Long entityId, int amount) {
        jdbc.update("""
                INSERT INTO fgc.contract_status_event_effect(contract_status_event_id, processing_job, entity_type, entity_id,
                    previous_state, next_state, amount, reason_code, reason)
                VALUES (?, 'PaymentHoldJob', ?, ?, 'DRAFT', 'HELD', ?, 'TEST', 'scope fixture')
                """, event, entityType, entityId, amount);
    }

    @Test
    void mixedAdjustmentStatementCarriesOnlyOwnAmountAndNoHeaderNote() throws Exception {
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        String adjustments = "/api/v1/agent/me/statements?month=2026-07&entryType=ADJUSTMENT&size=100";
        BigDecimal before = data(read(adjustments)).path("summary").path("adjustment").path("amount").decimalValue();
        Long mixed = payment("ADJUSTMENT", ownAgent, new Long[]{ownContract, ownAgent, 400L}, new Long[]{otherContract, otherAgent, 600L});

        JsonNode after = data(read(adjustments));
        assertThat(after.path("summary").path("adjustment").path("amount").decimalValue())
                .isEqualByComparingTo(before.add(new BigDecimal("400")));
        for (JsonNode entry : after.path("items").path("content")) {
            String key = entry.path("entryKey").asText();
            JsonNode evidence = data(read("/api/v1/agent/me/statements/" + key).andExpect(status().isOk())).path("evidence");
            if (evidence.path("commissionTransactionId").asLong() == mixed) {
                assertThat(entry.path("amount").decimalValue()).isEqualByComparingTo("400");
                assertThat(evidence.path("note").isNull()).isTrue();
            }
        }
    }

    @Test
    void headerBasedFilterAndSortDoNotRevealTheMixedTotal() throws Exception {
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        Long mixed = draftPayment("GA_MANUAL_PAYMENT", ownAgent, new Long[]{ownContract, ownAgent, 1L}, new Long[]{otherContract, otherAgent, 90_000_000L});
        Long plain = draftPayment("GA_MANUAL_PAYMENT", ownAgent, new Long[]{ownContract, ownAgent, 5L});
        jdbc.update("UPDATE fgc.commission_transaction SET amount = amount + 500 WHERE commission_transaction_id IN (?, ?)", mixed, plain);

        JsonNode imbalanced = data(read("/api/v1/transactions?size=100&attributionImbalanceOnly=true"));
        assertThat(paymentRow(imbalanced, plain)).isNotNull();
        assertThat(paymentRow(imbalanced, mixed)).isNull();

        // 헤더 총액 순서면 혼합 건이 맨 앞에 온다. 보이는 금액(본인 몫 1원) 기준으로 맨 뒤쪽에 있어야 한다.
        JsonNode sorted = data(read("/api/v1/transactions?size=100&sort=amount,desc")).path("content");
        assertThat(sorted.get(0).path("commissionTransactionId").asLong()).isNotEqualTo(mixed);
        List<BigDecimal> amounts = sorted.findValues("amount").stream().map(JsonNode::decimalValue).toList();
        assertThat(amounts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void paymentWithoutRowsStaysVisibleButOnlyOthersRowsIsHidden() throws Exception {
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        long listed = data(read("/api/v1/transactions?size=1")).path("totalElements").asLong();
        Long noRows = draftPayment("GA_MANUAL_PAYMENT", ownAgent);
        jdbc.update("UPDATE fgc.commission_transaction SET amount = 700 WHERE commission_transaction_id = ?", noRows);
        Long foreign = payment("GA_MANUAL_PAYMENT", ownAgent, new Long[]{otherContract, otherAgent, 300L});

        JsonNode page = data(read("/api/v1/transactions?size=100"));
        assertThat(page.path("totalElements").asLong()).isEqualTo(listed + 1);
        assertThat(paymentRow(page, foreign)).isNull();
        assertThat(paymentRow(page, noRows).path("amount").decimalValue()).isEqualByComparingTo("700");
        assertThat(paymentRow(page, noRows).path("attributionCount").asInt()).isZero();
        assertThat(data(read("/api/v1/transactions/" + noRows).andExpect(status().isOk())).path("amount").decimalValue())
                .isEqualByComparingTo("700");
        read("/api/v1/transactions/" + foreign).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-005"));
    }

    /** 관리자수수료는 모집하지 않은 계약에 귀속된 본인 수령이다(IF-API-22). 혼합 건이 아니므로 그대로 보이고, 계약 자체는 열리지 않는다. */
    @Test
    void managerCommissionStaysWithTheManagerAndNotWithTheSolicitor() throws Exception {
        Long payment = id("SELECT commission_transaction_id FROM fgc.commission_transaction WHERE source_business_key = 'GA-2026-07-0102'");
        Long manager = id("SELECT agent_id FROM fgc.agent WHERE agent_code = 'A-TL-001'");
        Long contract = id("SELECT min(contract_id) FROM fgc.transaction_attribution WHERE commission_transaction_id = ?", payment);
        assertThat(id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contract)).isEqualTo(ownAgent);
        read("/api/v1/transactions/" + payment).andExpect(status().isForbidden());

        jdbc.update("UPDATE fgc.app_user SET agent_id = ? WHERE login_id = 'agent01'", manager);
        JsonNode detail = data(read("/api/v1/transactions/" + payment).andExpect(status().isOk()));
        BigDecimal header = jdbc.queryForObject("SELECT amount FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                BigDecimal.class, payment);
        assertThat(detail.path("amount").decimalValue()).isEqualByComparingTo(header);
        assertThat(detail.path("attributions").get(0).path("contractId").asLong()).isEqualTo(contract);
        assertThat(paymentRow(data(read("/api/v1/transactions?size=100")), payment).path("amount").decimalValue())
                .isEqualByComparingTo(header);
        read("/api/v1/contracts/" + contract).andExpect(status().isForbidden());
    }

    @Test
    void requestedAgentIdEqualToTheOwnerIsAcceptedAndOtherRequestFieldsAreIgnored() throws Exception {
        read("/api/v1/contracts?agentId=" + ownAgent).andExpect(status().isOk());
        read("/api/v1/transactions?agentId=" + ownAgent).andExpect(status().isOk());
        Long otherAgent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", otherContract);
        // 본인 경로에는 agentId 입력이 없다. 넣어도 인증 연결로만 범위를 정한다.
        String month = "/api/v1/agent/me/statements?month=2026-07";
        assertThat(data(read(month + "&agentId=" + otherAgent)).path("summary")).isEqualTo(data(read(month)).path("summary"));
        assertThat(data(read("/api/v1/agent/me/home?period=ALL&agentId=" + otherAgent)).path("portfolio"))
                .isEqualTo(data(read("/api/v1/agent/me/home?period=ALL")).path("portfolio"));
    }

    private List<MockHttpServletRequestBuilder> scopedRoutes() {
        return List.of(get("/api/v1/auth/me"), get("/api/v1/contracts"), get("/api/v1/contracts/export.csv"),
                get("/api/v1/contracts/" + ownContract), get("/api/v1/contracts/" + ownContract + "/status-events"),
                get("/api/v1/contracts/" + ownContract + "/status-events/1/effects"),
                get("/api/v1/transactions"), get("/api/v1/transactions/1"), get("/api/v1/agent/me/home"),
                get("/api/v1/agent/me/statements?month=2026-07"), get("/api/v1/agent/me/statements/x"),
                get("/api/v1/base/products"), get("/api/v1/ai/compare-chat/categories"), get("/api/v1/news/headlines"),
                post("/api/v1/ai/compare-chat/rank"), post("/api/v1/ai/compare-chat/explain"),
                post("/api/v1/ai/compare-chat/ask"), post("/api/v1/agent/me/contract-checks"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCKED", "DISABLED", "UNLINKED"})
    void everyAgentRouteRejectsAnIssuedTokenAfterTheAccountChanges(String change) throws Exception {
        if ("UNLINKED".equals(change)) {
            jdbc.update("UPDATE fgc.app_user SET agent_id = NULL WHERE login_id = 'agent01'");
        } else {
            jdbc.update("UPDATE fgc.app_user SET account_status = ? WHERE login_id = 'agent01'", change);
        }
        for (var route : scopedRoutes()) {
            mvc.perform(route.header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"));
        }
    }

    @Test
    void everyAgentRouteRejectsAnonymousRequests() throws Exception {
        for (var route : scopedRoutes()) {
            mvc.perform(route).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void lockedAccountCannotReadThroughAnAlreadyBuiltSecurityContext() throws Exception {
        FgcUserDetails oldSession = (FgcUserDetails) users.loadUserByUsername("agent01");
        jdbc.update("UPDATE fgc.app_user SET account_status = 'LOCKED' WHERE login_id = 'agent01'");
        for (String path : new String[]{"/api/v1/contracts", "/api/v1/transactions", "/api/v1/agent/me/statements?month=2026-07",
                "/api/v1/agent/me/home", "/api/v1/ai/compare-chat/categories"}) {
            mvc.perform(get(path).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(oldSession)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"));
        }
    }

    /** 비동기·LLM 스레드에서 인증 문맥이 사라져도 내부 전체 범위로 풀리지 않는다(이슈 #621 처리 내용 7). */
    @Test
    void scopedReadsFailClosedWithoutAuthenticationContext() {
        Long payment = id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction");
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> reads = List.of(
                () -> contractService.selectByCondition(new ContractSearchCondition(), 1, 20),
                () -> contractService.selectAllByCondition(new ContractSearchCondition()),
                () -> contractService.selectContractDetailById(otherContract),
                () -> contractService.selectStatusEventsByContractId(otherContract),
                () -> paymentService.search(new CommissionPaymentSearchCondition(), 1, 20),
                () -> paymentService.get(payment),
                () -> statementService.listMine("2026-07", null, null, null, null, 1, 20),
                () -> statementService.listForAgent(ownAgent, "2026-07", null, null, null, null, 1, 20),
                () -> homeService.home(null, null, null, null),
                () -> compareService.categories(),
                () -> scope.requireContract(otherContract),
                () -> scope.requirePayment(payment));
        SecurityContextHolder.clearContext();
        reads.forEach(this::assertLoginRequired);
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "scope", "anonymous", org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        reads.forEach(this::assertLoginRequired);

        // 요청 스레드의 설계사 문맥은 작업 스레드로 넘어가지 않는다. 작업 스레드는 거절되어야 한다.
        FgcUserDetails agent = (FgcUserDetails) users.loadUserByUsername("agent01");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                agent, null, agent.getAuthorities()));
        for (var read : reads) {
            assertThatThrownBy(() -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    read.call();
                } catch (Throwable failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }).join()).hasCauseInstanceOf(FgcBusinessException.class).cause()
                    .satisfies(cause -> assertThat(((FgcBusinessException) cause).getErrorCode()).isEqualTo(FgcErrorCode.AUTH_002));
        }
    }

    private void assertLoginRequired(org.assertj.core.api.ThrowableAssert.ThrowingCallable read) {
        assertThatThrownBy(read).isInstanceOfSatisfying(FgcBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.AUTH_002));
    }

    private ResultActions as(String token, String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String contractNo(Long contractId) {
        return jdbc.queryForObject("SELECT contract_no FROM fgc.insurance_contract WHERE contract_id = ?", String.class, contractId);
    }

    private static String entryKey(Long attributionId) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("PAYMENT:" + attributionId + ":0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static JsonNode paymentRow(JsonNode page, Long paymentId) {
        for (JsonNode row : page.path("content")) {
            if (row.path("commissionTransactionId").asLong() == paymentId) return row;
        }
        return null;
    }

    private Long payment(String sourceType, Long recipient, Long[]... rows) {
        Long payment = draftPayment(sourceType, recipient, rows);
        jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?", payment);
        return payment;
    }

    /** 헤더에 남의 계약과 알아볼 수 있는 근거를 넣어 응답 노출을 확인한다. rows = {귀속 계약, 귀속행 설계사, 금액}, 합이 헤더 총액이다. */
    private Long draftPayment(String sourceType, Long recipient, Long[]... rows) {
        long total = java.util.Arrays.stream(rows).mapToLong(row -> row[2]).sum();
        Long payment = id("""
                INSERT INTO fgc.commission_transaction(payment_stage, source_type, source_business_key, source_contract_id,
                    recipient_agent_id, commission_item_id, settlement_month, due_date, amount, cashflow_type, status, evidence_ref, note)
                SELECT 'GA_TO_FC', ?, ?, ?, ?, min(commission_item_id), DATE '2026-07-01', DATE '2026-07-25', ?, 'PAYMENT', 'DRAFT',
                       'EVD-HEADER-SECRET', 'HEADER-NOTE-SECRET'
                FROM fgc.commission_transaction WHERE recipient_agent_id = ?
                RETURNING commission_transaction_id
                """, sourceType, "SCOPE-MIX-" + UUID.randomUUID(), otherContract, recipient, total, ownAgent);
        int seq = 1;
        for (Long[] row : rows) {
            jdbc.update("""
                    INSERT INTO fgc.transaction_attribution(commission_transaction_id, attribution_seq, attribution_scope,
                        contract_id, agent_id, attribution_date, attribution_month, attributed_amount,
                        inclusion_status_snapshot, exclusion_type_snapshot, attribution_method, allocation_basis_snapshot, evidence_ref)
                    VALUES (?, ?, 'CONTRACT', ?, ?, DATE '2026-07-25', DATE '2026-07-01', ?, 'EXCLUDED', 'NEW_AGENT_SUPPORT', 'DIRECT',
                        '{}'::jsonb, 'EVD-ROW')
                    """, payment, seq++, row[0], row[1], row[2]);
        }
        return payment;
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
