package com.susukkang.fgc.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.compare.dto.CompareRankingFilters;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.repository.CompareRankingRepository;
import com.susukkang.fgc.compare.repository.CompareRankingRepository.Offering;
import com.susukkang.fgc.compare.service.ClawbackRuleResolver;
import com.susukkang.fgc.compare.service.CompareRankResultStore;
import com.susukkang.fgc.compare.service.CompareRankingCalculator;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUN-079: 실제 PostgreSQL·JWT·보안 설정으로 순위 API 의 네이티브 SQL, 환수율 출처(D-01), 본인 범위(CT-A01)를 확인한다.
 * 환수율은 시연 시드(V72)의 보험사 전용 정책과 #766 V70_1 의 전체 정책(병합 후)에서 읽는다. 병합 전에는 전용 정책이 없는
 * 보험사 상품이 '환수 규칙 없음'으로 빠지므로, 단언은 두 상태에서 모두 성립하는 것만 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "fgc.compare.rule-version=" + CompareRankingCalculator.RULE_VERSION)
class CompareRankingIntegrationTest {
    private static final String RANK = "/api/v1/ai/compare-chat/rank";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FgcUserDetailsService users;
    @Autowired AuthTokenService tokens;
    @Autowired CompareRankResultStore store;
    @Autowired CompareRankingRepository repository;

    private final LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
    private Long ownAgent;
    private String agentToken;

    @BeforeEach
    void prepare() {
        ownAgent = jdbc.queryForObject("SELECT agent_id FROM fgc.agent WHERE agent_code = 'A-FC-001'", Long.class);
        jdbc.update("UPDATE fgc.app_user SET account_status = 'ACTIVE' WHERE login_id = 'agent01'");
        agentToken = token("agent01");
    }

    @Test
    void demoInsurersAreRankedByTheirOwnClawbackPolicy() throws Exception {
        JsonNode items = data(post(RANK, agentToken, "{\"categoryCode\":\"HEALTH_PROTECTION\"}").andExpect(status().isOk()))
                .path("items");

        // 시연 가정(V72): 첫해 환수율 다온 40% < 푸른 60% < 하늘 80%. 수수료 조건이 같아도 환수 부담이 작은 보험사가 앞선다.
        assertThat(items.size()).isGreaterThanOrEqualTo(3);
        List<String> expected = List.of("다온가상손해보험:CLAW-FGN03-DEMO-2026 v1", "푸른가상생명:CLAW-FGL06-DEMO-2026 v1",
                "하늘가상생명:CLAW-FGL05-DEMO-2026 v1");
        for (int i = 0; i < expected.size(); i++) {
            JsonNode item = items.get(i);
            String policy = "";
            for (JsonNode evidence : item.path("evidence")) {
                if (evidence.path("label").asText().equals("환수 노출액")) {
                    policy = evidence.path("policyVersion").asText();
                }
            }
            assertThat(item.path("insurer").asText() + ":" + policy).isEqualTo(expected.get(i));
            if (i > 0) {
                assertThat(item.path("conservativeNet").asLong()).isLessThan(items.get(i - 1).path("conservativeNet").asLong());
            }
        }
    }

    @Test
    void rankReadsTheClawbackPolicyNotTheRefundTable() throws Exception {
        JsonNode data = data(post(RANK, agentToken, "{\"categoryCode\":\"HEALTH_PROTECTION\"}").andExpect(status().isOk()));

        assertThat(data.path("status").asText()).isEqualTo("READY");
        assertThat(data.path("ruleVersion").asText()).isEqualTo(CompareRankingCalculator.RULE_VERSION);
        assertThat(data.path("items").size()).isPositive();
        for (JsonNode item : data.path("items")) {
            long clawbackLines = 0;
            for (JsonNode evidence : item.path("evidence")) {
                assertThat(evidence.path("source").asText() + evidence.path("rule").asText()).doesNotContain("해약환급률");
                if (evidence.path("label").asText().equals("환수 노출액")) {
                    assertThat(evidence.path("source").asText()).isEqualTo("환수 규칙");
                    clawbackLines += evidence.path("value").asLong(0);
                }
            }
            // 환수 노출액 = 항목별 환수 근거의 합(대상이 아닌 항목은 금액 없음)
            assertThat(item.path("clawbackExposure").asLong()).isEqualTo(clawbackLines);
            assertThat(item.path("conservativeNet").asLong())
                    .isEqualTo(item.path("cappedReceipt").asLong() - item.path("clawbackExposure").asLong());
            assertThat(item.has("clawbackRatePct")).isFalse();
        }
    }

    @Test
    void otherAgentsResultIsForbiddenBeforeAnyAiCall() throws Exception {
        Long otherAgent = jdbc.queryForObject("SELECT min(agent_id) FROM fgc.agent WHERE agent_id <> ?", Long.class, ownAgent);
        String otherResult = store.save(otherAgent, sampleRanking());
        long aiCalls = jdbc.queryForObject("SELECT count(*) FROM fgc.ai_call_log", Long.class);

        post("/api/v1/ai/compare-chat/explain", agentToken, "{\"rankResultId\":\"" + otherResult + "\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));
        post("/api/v1/ai/compare-chat/ask", agentToken, "{\"rankResultId\":\"" + otherResult + "\",\"question\":\"2순위 이유는?\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));

        // 권한 검사 전에 다른 설계사의 결과를 AI 에 보내지 않는다(FUN-085)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.ai_call_log", Long.class)).isEqualTo(aiCalls);
    }

    @Test
    void onlyAgentsCanUseTheCompareChat() throws Exception {
        post(RANK, token("gaadmin"), "{\"categoryCode\":\"HEALTH_PROTECTION\"}").andExpect(status().isForbidden());
    }

    @Test
    void offeringScopedClawbackRuleIsReadOnlyForThatOffering() {
        List<Offering> offerings = repository.findSellableOfferings(today).stream()
                .filter(o -> o.groupCode().equals("HEALTH_PROTECTION")).limit(2).toList();
        assertThat(offerings).hasSize(2);
        offeringClawbackPolicy("CLAW-COMPARE-IT-OFFERING", offerings.get(0).offeringId());

        assertThat(repository.findClawbackRules(offerings.get(0), today)).extracting(ClawbackRuleResolver.RuleRow::policyVersion)
                .contains("CLAW-COMPARE-IT-OFFERING v1");
        assertThat(repository.findClawbackRules(offerings.get(1), today)).extracting(ClawbackRuleResolver.RuleRow::policyVersion)
                .doesNotContain("CLAW-COMPARE-IT-OFFERING v1");
    }

    /** 판매버전 한 개에만 맞는 승인 환수 정책을 만든다(D-02 가상 환수율표와 같은 구간·비율). */
    private void offeringClawbackPolicy(String code, long offeringId) {
        jdbc.update("""
                INSERT INTO fgc.policy_version(policy_code, policy_name, policy_type, source_class, version_no, effective_from,
                                               effective_to, status, regulation_refs, source_refs, created_by)
                VALUES (?, '상품 비교 통합시험 환수율', 'CLAWBACK', 'GA_POLICY', 1, DATE '2026-01-01', NULL, 'DRAFT',
                        ARRAY['REG-14'], ARRAY['D-02'], (SELECT user_id FROM fgc.app_user WHERE login_id = 'settle01'))
                """, code);
        jdbc.update("""
                INSERT INTO fgc.clawback_rule(policy_version_id, insurer_id, product_offering_id, commission_item_id,
                                              elapsed_month_from, elapsed_month_to, clawback_basis_code, clawback_rate_pct)
                SELECT pv.policy_version_id, NULL, ?, item.commission_item_id, band.month_from, band.month_to,
                       'PAID_ATTRIBUTED_AMOUNT', band.rate_pct
                  FROM fgc.policy_version pv
                  JOIN fgc.commission_item item ON item.item_code = 'BASE_COMMISSION'
                 CROSS JOIN (VALUES (0, 11, 100.000000), (12, 23, 50.000000), (24, 1199, 0.000000)) AS band(month_from, month_to, rate_pct)
                 WHERE pv.policy_code = ? AND pv.version_no = 1
                """, offeringId, code);
        jdbc.update("""
                UPDATE fgc.policy_version SET status = 'APPROVED', approved_at = clock_timestamp(),
                       approved_by = (SELECT user_id FROM fgc.app_user WHERE login_id = 'gaadmin'),
                       approval_evidence_ref = '상품 비교 통합시험'
                 WHERE policy_code = ? AND version_no = 1 AND status = 'DRAFT'
                """, code);
        jdbc.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_code = ? AND version_no = 1 AND status = 'APPROVED'",
                code);
    }

    private CompareRankingResponse sampleRanking() {
        CompareRankingResponse.Item item = new CompareRankingResponse.Item(1, 1L, "가보험", "든든건강", 805_000, 805_000, 0, 750_000,
                55_000, 67.083333, null, List.of());
        return new CompareRankingResponse(null, CompareRankingResponse.READY, "HEALTH_PROTECTION", "건강보험", today,
                CompareRankingCalculator.RULE_VERSION, null, 100_000L, 12, List.of(), null, List.of(item), List.of(),
                CompareRankingFilters.NONE);
    }

    private String token(String loginId) {
        return tokens.login((FgcUserDetails) users.loadUserByUsername(loginId)).accessToken();
    }

    private ResultActions post(String path, String token, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType("application/json").content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }
}
