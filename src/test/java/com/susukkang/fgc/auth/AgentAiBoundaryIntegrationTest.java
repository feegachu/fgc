package com.susukkang.fgc.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.compare.service.CompareRankResultStore;
import com.susukkang.fgc.compare.service.CompareRankingCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUN-085 AI 경계(CT-G05, CT-A01의 AI 부분). 실제 PostgreSQL과 키 없는 LlmGateway로 확인한다.
 *
 * <p>게이트웨이는 ai_call_log를 별도 트랜잭션으로 남기고 그 FK가 요청자의 app_user 행을 본다. 로그인은 그 행을 FOR UPDATE로
 * 잠그므로 시험 트랜잭션 안에서 로그인하면 서로를 기다려 멈춘다. 그래서 @Transactional을 걸지 않고 끝나면 만든 행을 지운다.
 * DB 연결 대기는 같은 스레드 인터럽트로 풀리지 않아 시간 제한은 별도 스레드로 건다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fgc.compare.rule-version=" + CompareRankingCalculator.RULE_VERSION)
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AgentAiBoundaryIntegrationTest {
    private static final String RANK_BODY = "{\"categoryCode\":\"HEALTH_PROTECTION\"}";
    private static final String AGENT_B = "agent-b-ai-boundary";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FgcUserDetailsService users;
    @Autowired AuthTokenService tokens;
    @Autowired CompareRankResultStore store;

    private long lastAiCall;

    @BeforeEach
    void rememberAiCalls() {
        lastAiCall = id("SELECT COALESCE(max(ai_call_log_id), 0) FROM fgc.ai_call_log");
    }

    @AfterEach
    void cleanOwnedRows() {
        jdbc.update("DELETE FROM fgc.ai_call_log WHERE ai_call_log_id > ?", lastAiCall);
        jdbc.update("DELETE FROM fgc.auth_refresh_token WHERE user_id IN (SELECT user_id FROM fgc.app_user"
                + " WHERE login_id IN ('agent01', 'audit01', 'settle01'))");
        jdbc.update("DELETE FROM fgc.app_user WHERE login_id = ?", AGENT_B);
    }

    @Test
    @DisplayName("CT-G05 COMPLIANCE는 조회 질문만 가능하고 업무 쓰기는 막히며 AGENT의 073 요청은 403이다")
    void ctG05ComplianceAsksReadOnlyAndAgentIsBlockedFromInternalAi() throws Exception {
        String question = """
                {"question":"초안 저장 방법","screenId":"TRAN-W02","entityRefs":[],"clientContextRevision":"r1","guideVersion":"1.0","conversation":[]}
                """;
        Long payment = id("SELECT min(commission_transaction_id) FROM fgc.commission_transaction WHERE status = 'CONFIRMED'");
        Long contract = id("SELECT min(contract_id) FROM fgc.insurance_contract");
        String calculation = """
                {"contractVersion":"assistant.v1","kind":"ATTRIBUTION_BALANCE","mode":"EXISTING","scope":{"paymentId":"%d"},"clientRevision":0}
                """.formatted(payment);

        // AGENT: 073의 네 경로 모두 403이고, 권한 검사 전에 내용이 AI로 넘어가지 않는다.
        RequestPostProcessor agent = bearer(login("agent01"));
        for (String path : new String[]{"/api/v1/ai/pico/ask", "/api/v1/ai/pico/guides/prepare", "/api/v1/ai/pico/guides/start",
                "/api/v1/assistant/calculations"}) {
            send(path, path.endsWith("calculations") ? calculation : question, agent)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));
        }
        assertThat(aiCallsSinceStart()).isZero();

        // COMPLIANCE: 조회 질문과 계산 조회는 그대로 쓰고, 게이트웨이까지 실제로 간다(키가 없어 건너뜀으로 기록).
        RequestPostProcessor compliance = bearer(login("audit01"));
        send("/api/v1/ai/pico/ask", question, compliance).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").isNotEmpty());
        assertThat(aiCallsSinceStart()).isPositive();
        send("/api/v1/assistant/calculations", calculation, compliance).andExpect(status().isOk());
        // COMPLIANCE의 업무 쓰기는 막힌다.
        for (String path : new String[]{"/api/v1/transactions", "/api/v1/transactions/" + payment + "/confirm", "/api/v1/contracts",
                "/api/v1/policies", "/api/v1/clawbacks/1/notice-documents"}) {
            send(path, "{}", compliance).andExpect(status().isForbidden());
        }
        mvc.perform(put("/api/v1/contracts/" + contract).with(compliance).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());

        // 내부 처리 역할의 기존 권한도 그대로다.
        send("/api/v1/ai/pico/ask", question, bearer(login("settle01"))).andExpect(status().isOk());
    }

    /** 두 설계사가 API로 만든 실제 순위 결과. 본인 결과는 게이트웨이까지 가고, 남의 결과는 어느 방향이든 그 전에 막힌다. */
    @Test
    @DisplayName("CT-A01 A/B 설계사는 서로의 평가 결과로 설명과 질문을 부를 수 없고 내용이 AI로 넘어가지 않는다")
    void ctA01OtherAgentsRankResultNeverReachesTheGatewayButTheOwnResultDoes() throws Exception {
        Long agentA = id("SELECT agent_id FROM fgc.agent WHERE agent_code = 'A-FC-001'");
        Long agentB = id("SELECT agent_id FROM fgc.agent WHERE agent_code = 'A-FC-002'");
        jdbc.update("""
                INSERT INTO fgc.app_user(login_id, password_hash, user_name, role_id, agent_id, account_status)
                VALUES (?, 'unused', '교차 검증 설계사', (SELECT role_id FROM fgc.app_role WHERE role_code = 'AGENT'), ?, 'ACTIVE')
                """, AGENT_B, agentB);
        // B는 시험이 끝나면 지우는 계정이라 감사 기록을 남기는 로그인 대신 같은 사용자 정보로 호출한다. A는 JWT로 호출한다.
        RequestPostProcessor b = user((FgcUserDetails) users.loadUserByUsername(AGENT_B));
        RequestPostProcessor a = bearer(login("agent01"));
        String resultA = data(send("/api/v1/ai/compare-chat/rank", RANK_BODY, a).andExpect(status().isOk())).path("rankResultId").asText();
        String resultB = data(send("/api/v1/ai/compare-chat/rank", RANK_BODY, b).andExpect(status().isOk())).path("rankResultId").asText();
        assertThat(resultA).isNotBlank().isNotEqualTo(resultB);
        assertThat(store.find(agentA, resultA)).isPresent();
        assertThat(store.find(agentB, resultA)).isEmpty();
        assertThat(store.find(agentA, resultB)).isEmpty();

        assertForbiddenBeforeAi(a, resultB);
        assertForbiddenBeforeAi(b, resultA);
        assertThat(aiCallsSinceStart()).isZero();

        // 대조군: 본인 결과는 게이트웨이까지 간다. 위의 0건이 헛돌지 않는다는 증거다.
        send("/api/v1/ai/compare-chat/explain", explain(resultA), a).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summarySource").value("RULE"));
        assertThat(aiCallsSinceStart()).isEqualTo(1);
    }

    private void assertForbiddenBeforeAi(RequestPostProcessor caller, String foreignResult) throws Exception {
        send("/api/v1/ai/compare-chat/explain", explain(foreignResult), caller)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));
        send("/api/v1/ai/compare-chat/ask", "{\"rankResultId\":\"" + foreignResult + "\",\"question\":\"1순위 이유는?\"}", caller)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));
    }

    private static String explain(String rankResultId) {
        return "{\"rankResultId\":\"" + rankResultId + "\"}";
    }

    private String login(String loginId) {
        return tokens.login((FgcUserDetails) users.loadUserByUsername(loginId)).accessToken();
    }

    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private ResultActions send(String path, String body, RequestPostProcessor caller) throws Exception {
        return mvc.perform(post(path).with(caller).contentType("application/json").content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private long aiCallsSinceStart() {
        return id("SELECT count(*) FROM fgc.ai_call_log WHERE ai_call_log_id > ?", lastAiCall);
    }

    private Long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
