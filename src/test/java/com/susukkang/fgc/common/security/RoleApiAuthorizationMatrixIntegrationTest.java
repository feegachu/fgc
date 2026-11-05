package com.susukkang.fgc.common.security;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : TER-009, SER-002, FUN-002 역할 × API 행렬(#677). 실제 로그인 토큰과 PostgreSQL 로 URL 규칙과 @PreAuthorize 를 함께 지난다.
 *
 * <p>기대 역할은 Roles 상수를 쓰지 않고 인터페이스정의서 v3.0 §4-2 역할 열을 옮겨 적었다. 상수가 넓어지면 여기서 깨져야 한다.
 * 역할 열은 SYSTEM_ADMIN 을 생략하는 표기 규칙이라(§2-1 역할 표 아래) 아래 묶음에 더해 두었다.
 *
 * <p>본문은 Bean Validation 은 통과하되 없는 ID 를 가리킨다. 인자 검증이 @PreAuthorize 보다 먼저 돌아서,
 * 형식이 틀린 본문은 거부 역할에게도 400 이 되어 권한을 시험하지 못한다. 허용 역할이 만든 초안은 @Transactional 로 되돌린다.
 * AGENT 의 본인 데이터 범위는 AgentDataScopeIntegrationTest 가 맡고, 여기서는 역할 대 경로 차단만 본다.
 *
 * @author yslee
 * @since 2026-11-04
 * @version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleApiAuthorizationMatrixIntegrationTest {

    private enum Actor {
        ANONYMOUS(null), SYSTEM_ADMIN("admin"), GA_ADMIN("gaadmin"), SETTLEMENT("settle01"), COMPLIANCE("audit01"), AGENT("agent01");

        final String loginId;

        Actor(String loginId) {
            this.loginId = loginId;
        }
    }

    private static final Set<Actor> PROCESS = Set.of(Actor.SYSTEM_ADMIN, Actor.SETTLEMENT);
    private static final Set<Actor> PROCESS_OR_GA = Set.of(Actor.SYSTEM_ADMIN, Actor.SETTLEMENT, Actor.GA_ADMIN);
    private static final Set<Actor> APPROVER = Set.of(Actor.SYSTEM_ADMIN, Actor.GA_ADMIN);
    private static final Set<Actor> AUDIT = Set.of(Actor.SYSTEM_ADMIN, Actor.COMPLIANCE);
    private static final Set<Actor> INTERNAL = Set.of(Actor.SYSTEM_ADMIN, Actor.GA_ADMIN, Actor.SETTLEMENT, Actor.COMPLIANCE);
    private static final Set<Actor> AGENT_ONLY = Set.of(Actor.AGENT);

    /** 로그인 전에 부르는 토큰 API. 역할이 아니라 Origin·X-FGC-Client 로 막으며 AuthTokenApiIntegrationTest 가 지킨다. */
    private static final Set<String> TOKEN_ENDPOINTS = Set.of(
            "POST /api/v1/auth/login", "POST /api/v1/auth/refresh", "POST /api/v1/auth/logout");

    private static final String NO_SUCH_ID = "999999999";
    private static final String MANUAL_RUN_MONTH = "2096-12-01";

    private static final String CONTRACT = """
            "insurerId":999999999,"contractNo":"AUTHZ-MATRIX","productOfferingId":999999999,"contractDate":"2026-01-01",
            "contractStatus":"ACTIVE","agentId":999999999,"organizationId":999999999,"paymentCycleCode":"MONTHLY",
            "premiumPerCycleAmount":1000,"firstPremiumAmount":1000,"monthlyEquivalentFirstPremium":1000,"paymentTermMonths":12""";
    private static final String PAYMENT = """
            {"sourceType":"GA_MANUAL_PAYMENT","sourceBusinessKey":"AUTHZ-MATRIX","commissionItemId":999999999,"amount":1000,
             "settlementMonth":"2026-01-01","cashflowType":"PAYMENT","scheduledPaymentDate":"2026-01-25",
             "paymentStage":"GA_TO_FC","attributions":[]}""";
    /** 수정은 expectedUpdatedAt이 필수다(#857). 본문 검증이 @PreAuthorize 보다 먼저 돌아 형식이 맞아야 역할 판정까지 닿는다. */
    private static final String PAYMENT_UPDATE = """
            {"expectedUpdatedAt":"2026-01-01T00:00:00Z","sourceType":"GA_MANUAL_PAYMENT","sourceBusinessKey":"AUTHZ-MATRIX",
             "commissionItemId":999999999,"amount":1000,"settlementMonth":"2026-01-01","cashflowType":"PAYMENT",
             "scheduledPaymentDate":"2026-01-25","paymentStage":"GA_TO_FC","attributions":[]}""";
    private static final String POLICY = """
            {"policyCode":"AUTHZ-MATRIX","policyName":"권한 시험","policyType":"ALLOCATION","sourceClass":"PROJECT_ASSUMPTION",
             "effectiveFrom":"2099-01-01","regulationRefs":[],"sourceRefs":[],"commissionRules":[],"feeRegimeRules":[],
             "parameters":[],"clawbackRules":[]}""";
    private static final String REASON = """
            {"reason":"권한 시험"}""";

    private record Row(String id, HttpMethod method, String pattern, String body, Set<Actor> allowed) {

        /** RequestMappingInfo 와 대조하는 키. 경로 변수 이름은 컨트롤러마다 달라 {} 로 맞춘다. */
        String mappingKey() {
            return method + " " + pattern.split("\\?")[0];
        }

        String url() {
            return pattern.replace("{}", NO_SUCH_ID);
        }

        @Override
        public String toString() {
            return id + " " + mappingKey();
        }
    }

    private static Row row(String id, HttpMethod method, String pattern, String body, Set<Actor> allowed) {
        return new Row(id, method, pattern, body, allowed);
    }

    private static String policyAction(String action, String expectedStatus) {
        return """
                {"actionType":"%s","reason":"권한 시험","expectedStatus":"%s","expectedUpdatedAt":"2026-01-01T00:00:00+09:00"}"""
                .formatted(action, expectedStatus);
    }

    private static String calculation(String kind) {
        return """
                {"contractVersion":"assistant.v1","kind":"%s","mode":"EXISTING","clientRevision":1}""".formatted(kind);
    }

    private static String revalidation(String kind) {
        return """
                {"calculationId":"authorization-matrix","stateToken":"unknown-token","input":%s}"""
                .formatted(calculation(kind));
    }

    private static String guide(String workflowId) {
        return """
                {"workflowId":"%s","guideVersion":"1.0","paymentId":"999999999","clientContextRevision":"1"}""".formatted(workflowId);
    }

    private static List<Row> rows() {
        return List.of(
                // 계약 — §4-2 IF-18·19·19A·19B·33
                row("IF-18", POST, "/api/v1/contracts", "{" + CONTRACT + "}", PROCESS),
                row("IF-19", PUT, "/api/v1/contracts/{}",
                        "{" + CONTRACT + ",\"expectedUpdatedAt\":\"2026-01-01T00:00:00+09:00\"}", PROCESS),
                row("IF-19A", POST, "/api/v1/contracts/{}/schedules/regenerate?reason=CONTRACT_DETAIL_MANUAL", null, PROCESS),
                row("IF-19B", POST, "/api/v1/contracts/{}/cap-check", null, PROCESS),
                row("FUN-062", POST, "/api/v1/acquisition-cost-checks",
                        "{\"contractId\":999999999,\"paymentStage\":\"GA_TO_FC\"}", PROCESS),
                row("IF-33", POST, "/api/v1/contracts/{}/arbitrage-check",
                        "{\"asOfDate\":\"" + MANUAL_RUN_MONTH + "\",\"reason\":\"권한 시험\"}", PROCESS),
                // 계약 상태 미리보기 IF-64. 저장은 없지만 POST 라 전수 대조 대상이다
                row("IF-64", POST, "/api/v1/contracts/{}/status-events/preview",
                        "{\"eventType\":\"LAPSED\",\"effectiveAt\":\"2026-01-01T00:00:00+09:00\"}", PROCESS_OR_GA),
                // 지급 — IF-22·23·23D·24·25
                row("IF-22", POST, "/api/v1/transactions", PAYMENT, PROCESS),
                row("IF-23", PUT, "/api/v1/transactions/{}", PAYMENT_UPDATE, PROCESS),
                row("IF-23D", DELETE, "/api/v1/transactions/{}", null, PROCESS),
                row("IF-24", POST, "/api/v1/transactions/{}/precheck", null, PROCESS),
                row("IF-25", POST, "/api/v1/transactions/{}/confirm", null, PROCESS),
                // 스케줄 — IF-29·29A
                row("IF-29", POST, "/api/v1/schedules/{}/regenerate", REASON, PROCESS),
                row("IF-29A", POST, "/api/v1/schedules/{}/confirm", null, PROCESS),
                // 원장 정정 — IF-36·36A, 예외 처리 — IF-44·44A
                row("IF-36", POST, "/api/v1/journals/{}/reverse", REASON, PROCESS_OR_GA),
                row("IF-36A", POST, "/api/v1/journals/{}/correction-exceptions", REASON, PROCESS_OR_GA),
                row("IF-44", POST, "/api/v1/exceptions/{}/actions",
                        "{\"actionType\":\"COMMENT\",\"reason\":\"권한 시험\"}", PROCESS_OR_GA),
                row("IF-44A", POST, "/api/v1/exceptions/{}/journal-correction", """
                        {"reason":"권한 시험","journalDate":"2026-01-01","description":"권한 시험",
                         "lines":[{"accountCode":"X","debitAmount":1,"creditAmount":0}]}""", PROCESS_OR_GA),
                // 대사 — IF-38·42, 검증 실행 — IF-45·48·51
                row("IF-38", POST, "/api/v1/reconciliations", """
                        {"settlementMonth":"2026-01","paymentStage":"GA_TO_FC","insurerId":999999999,"validationRunId":999999999}""", PROCESS),
                row("IF-42", POST, "/api/v1/reconciliations/{}/exceptions", null, PROCESS),
                row("IF-45", POST, "/api/v1/validation-runs", "{\"validationMonth\":\"없는달\",\"runType\":\"없는유형\"}", PROCESS),
                row("IF-48", POST, "/api/v1/validation-runs/{}/execute", null, PROCESS),
                // 본문 검증이 @PreAuthorize 보다 먼저 돌아 형식이 맞는 본문이 있어야 역할 판정까지 닿는다.
                row("IF-82", POST, "/api/v1/validation-runs/{}/restart", """
                        {"expectedBatchExecutionId":999999999,"expectedStateVersion":"role-matrix","reason":"역할 행렬 시험"}""", PROCESS),
                row("IF-51", POST, "/api/v1/validation-runs/{}/finalize", null, APPROVER),
                // 정책 #747 — IF-79·80·81·75. 동작별 역할은 §4-2 IF-75 와 화면정의서 정책 절이 나눈다
                row("IF-79", POST, "/api/v1/policies", POLICY, PROCESS_OR_GA),
                row("IF-80", PUT, "/api/v1/policies/{}", POLICY, PROCESS_OR_GA),
                row("IF-81", POST, "/api/v1/policies/{}/versions",
                        "{\"effectiveFrom\":\"2099-01-01\",\"reason\":\"권한 시험\"}", PROCESS_OR_GA),
                row("IF-75 SUBMIT", POST, "/api/v1/policies/{}/actions", policyAction("SUBMIT", "DRAFT"), PROCESS_OR_GA),
                row("IF-75 APPROVE", POST, "/api/v1/policies/{}/actions", policyAction("APPROVE", "REVIEW"), APPROVER),
                row("IF-75 REJECT", POST, "/api/v1/policies/{}/actions", policyAction("REJECT", "REVIEW"), APPROVER),
                row("IF-75 ACTIVATE", POST, "/api/v1/policies/{}/actions", policyAction("ACTIVATE", "APPROVED"), APPROVER),
                // 환수 054·055·084 — IF-85·73
                row("IF-85", POST, "/api/v1/clawbacks/candidates/{}/calculations", """
                        {"expectedSourceVersion":"v","policyVersionId":999999999,"reason":"권한 시험"}""", PROCESS_OR_GA),
                row("IF-73", POST, "/api/v1/clawbacks/{}/notice-documents",
                        "{\"calculationVersion\":1,\"expectedSourceVersion\":\"v\"}", PROCESS_OR_GA),
                // 상품 비교 #739 — IF-77·78. 본인 ID 를 세션에서 얻는 설계사 전용이라 SYSTEM_ADMIN 도 쓰지 않는다
                row("IF-77", POST, "/api/v1/ai/compare-chat/rank", "{\"categoryCode\":\"없는분류\"}", AGENT_ONLY),
                row("IF-78", POST, "/api/v1/ai/compare-chat/explain", "{\"rankResultId\":\"없는결과\"}", AGENT_ONLY),
                row("IF-78", POST, "/api/v1/ai/compare-chat/ask",
                        "{\"rankResultId\":\"없는결과\",\"question\":\"권한 시험\"}", AGENT_ONLY),
                // 설계사 계약 확인 기록 FUN-086 — IF-74A. 본인 계약 범위는 AgentDataScopeIntegrationTest 쪽이다
                row("IF-74A", POST, "/api/v1/agent/me/contract-checks",
                        "{\"contractId\":999999999,\"result\":\"CLOSED\",\"expectedContextVersion\":\"v\"}", AGENT_ONLY),
                // 피코 #745·#751 — IF-60·60A·60B·60C. 조회 역할은 안내만, 처리 업무는 정산/시스템 관리자
                row("IF-60", POST, "/api/v1/ai/pico/ask", """
                        {"question":"권한 시험","screenId":"TRAN-W02","entityRefs":[],"clientContextRevision":"1","guideVersion":"1.0"}""", INTERNAL),
                row("IF-60A 잔액", POST, "/api/v1/assistant/calculations", calculation("ATTRIBUTION_BALANCE"), INTERNAL),
                row("IF-60A 후보", POST, "/api/v1/assistant/calculations", calculation("PAYMENT_CANDIDATE"), PROCESS),
                row("IF-60A 잔액 재검증", POST, "/api/v1/assistant/calculations/revalidate", revalidation("ATTRIBUTION_BALANCE"), INTERNAL),
                row("IF-60A 후보 재검증", POST, "/api/v1/assistant/calculations/revalidate", revalidation("PAYMENT_CANDIDATE"), PROCESS),
                row("IF-60A 실제 사전검증 증표", POST, "/api/v1/assistant/prechecks/revalidate",
                        "{\"paymentId\":\"999999999\",\"precheckToken\":\"unknown-token\"}", PROCESS),
                row("IF-60B 조회", POST, "/api/v1/ai/pico/guides/prepare", guide("payment.amount.review"), INTERNAL),
                row("IF-60B 처리", POST, "/api/v1/ai/pico/guides/prepare", guide("payment.confirm"), PROCESS),
                row("IF-60C 조회", POST, "/api/v1/ai/pico/guides/start", guide("payment.amount.review"), INTERNAL),
                row("IF-60C 처리", POST, "/api/v1/ai/pico/guides/start", guide("payment.confirm"), PROCESS),
                // 역할을 가르는 읽기. 나머지 읽기의 역할 대 경로 차단은 아래 internalReadsStayClosedForAgent
                row("AUDT-W01", GET, "/api/v1/audit-logs", null, AUDIT),
                row("IF-67A", GET, "/api/v1/documents/{}.pdf?version=1", null, INTERNAL),
                row("IF-86", GET, "/api/v1/clawbacks/{}/notice-documents", null, INTERNAL),
                row("IF-83", GET, "/api/v1/clawbacks/candidates", null, INTERNAL),
                row("IF-87 본인", GET, "/api/v1/agent/me/statements?month=2026-07", null, AGENT_ONLY));
    }

    static Stream<Arguments> matrix() {
        return rows().stream().flatMap(row -> Arrays.stream(Actor.values()).map(actor -> Arguments.of(row, actor)));
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthTokenService tokens;
    @Autowired
    private FgcUserDetailsService users;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlers;

    private final Map<Actor, String> accessTokens = new EnumMap<>(Actor.class);

    /**
     * 시험 트랜잭션 밖에서 로그인을 커밋한다. login() 의 app_user FOR UPDATE 를 시험 트랜잭션이 쥐고 있으면
     * 요청 안의 REQUIRES_NEW 쓰기(IF-33 수동 실행 생성)가 FK 검사에서 그 잠금을 기다려 시험이 멈춘다.
     */
    @BeforeAll
    void loginEveryRole() {
        for (Actor actor : Actor.values()) {
            if (actor != Actor.ANONYMOUS) {
                accessTokens.put(actor, tokens.login((FgcUserDetails) users.loadUserByUsername(actor.loginId)).accessToken());
            }
        }
    }

    @AfterAll
    void leaveNoTrace() {
        jdbc.update("""
                DELETE FROM fgc.auth_refresh_token WHERE user_id IN
                    (SELECT user_id FROM fgc.app_user WHERE login_id IN ('admin', 'gaadmin', 'settle01', 'audit01', 'agent01'))
                """);
        // IF-33 은 계약을 찾기 전에 수동 검증 실행을 별도 트랜잭션으로 커밋한다. 다른 시험의 월별 집계에 끼지 않게 치운다.
        jdbc.update("DELETE FROM fgc.validation_run WHERE validation_month = DATE '" + MANUAL_RUN_MONTH + "'");
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("matrix")
    void eachRoleGetsTheResponseTheInterfaceSpecPromises(Row row, Actor actor) throws Exception {
        ResultActions result = mvc.perform(asActor(actor, send(row)));
        if (actor == Actor.ANONYMOUS) {
            result.andExpect(status().isUnauthorized());
        } else if (row.allowed().contains(actor)) {
            // 없는 ID 라 4xx 가 정상이다. 대상은 역할 검사를 지나 업무 로직까지 갔다는 사실이다(FGC-AUTH-005 는 역할 다음의 본인 범위 검사).
            MockHttpServletResponse response = result.andReturn().getResponse();
            assertThat(response.getStatus()).as(row + " / " + actor).isNotEqualTo(401).isLessThan(500);
            assertThat(response.getContentAsString()).as(row + " / " + actor).doesNotContain("FGC-AUTH-003");
            // 허용된 역할의 403 은 회귀다. 예외는 AGENT 의 본인 범위 검사(FGC-AUTH-005)뿐이다.
            if (response.getStatus() == 403) {
                assertThat(actor).as(row + " / " + actor).isEqualTo(Actor.AGENT);
                assertThat(response.getContentAsString()).as(row + " / " + actor).contains("FGC-AUTH-005");
            }
        } else {
            result.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));
        }
    }

    @Test
    void everyWriteMappingHasAMatrixRow() {
        Set<String> mapped = applicationMappings()
                .flatMap(info -> keys(info).stream())
                .filter(key -> !key.startsWith("GET "))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> covered = rows().stream().map(Row::mappingKey).filter(key -> !key.startsWith("GET "))
                .collect(Collectors.toCollection(TreeSet::new));
        covered.addAll(TOKEN_ENDPOINTS);
        assertThat(mapped).as("새 쓰기 API 는 인터페이스정의서 역할 열을 보고 rows() 에 행을 더한다").isEqualTo(covered);
    }

    /** 허용 역할은 없는 ID 의 404 도 통과로 보므로, 행이 가리키는 API 가 실제로 있는지 따로 본다. */
    @Test
    void everyMatrixRowPointsAtARealMapping() {
        Set<String> mapped = applicationMappings().flatMap(info -> keys(info).stream()).collect(Collectors.toSet());
        assertThat(mapped).containsAll(rows().stream().map(Row::mappingKey).toList());
    }

    /** 굵은 URL 규칙(anyRequest authenticated)이 빠지면 @PreAuthorize 없는 읽기가 그대로 열린다. */
    @Test
    void anonymousIsRejectedOnEveryReadMapping() throws Exception {
        List<String> opened = new ArrayList<>();
        for (RequestMappingInfo info : applicationMappings().toList()) {
            for (String key : keys(info)) {
                if (key.startsWith("GET ")) {
                    String url = key.substring(4).replace("{}", NO_SUCH_ID);
                    if (mvc.perform(request(GET, url)).andReturn().getResponse().getStatus() != 401) {
                        opened.add(key);
                    }
                }
            }
        }
        assertThat(opened).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/audit-logs/options", "/api/v1/base/agents", "/api/v1/base/organizations", "/api/v1/cap-checks",
            "/api/v1/clawbacks/candidates/1", "/api/v1/contracts/1/journals", "/api/v1/contracts/1/schedules",
            "/api/v1/contracts/1/transactions", "/api/v1/dashboard/summary", "/api/v1/exceptions", "/api/v1/journals",
            "/api/v1/policies", "/api/v1/reconciliations", "/api/v1/reports/agents/1/statements", "/api/v1/schedules",
            "/api/v1/validation-runs", "/api/v1/arbitrage-checks", "/api/v1/acquisition-cost-checks",
            "/api/v1/home/summary", "/api/v1/exceptions/counts"})
    void internalReadsStayClosedForAgent(String url) throws Exception {
        mvc.perform(asActor(Actor.AGENT, request(GET, url)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));
    }

    /** SIR-001 버전 정책: 앱이 여는 경로는 모두 /api/v1 아래다. */
    @Test
    void everyApplicationMappingIsVersionedUnderApiV1() {
        List<String> outside = applicationMappings()
                .flatMap(info -> info.getPatternValues().stream())
                .filter(pattern -> !pattern.startsWith("/api/v1/"))
                .toList();
        assertThat(outside).isEmpty();
    }

    private static MockHttpServletRequestBuilder send(Row row) {
        MockHttpServletRequestBuilder builder = request(row.method(), row.url())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        return row.body() == null ? builder : builder.contentType(MediaType.APPLICATION_JSON).content(row.body());
    }

    private MockHttpServletRequestBuilder asActor(Actor actor, MockHttpServletRequestBuilder builder) {
        return actor == Actor.ANONYMOUS ? builder : builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokens.get(actor));
    }

    private Stream<RequestMappingInfo> applicationMappings() {
        return handlers.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().getPackageName().startsWith("com.susukkang.fgc"))
                .map(Map.Entry::getKey);
    }

    /** 메서드 조건 없는 매핑은 모든 메서드를 받는 쓰기 경로이므로 ANY 로 드러내 행렬에서 걸리게 한다. */
    private static List<String> keys(RequestMappingInfo info) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        List<String> verbs = methods.isEmpty() ? List.of("ANY") : methods.stream().map(Enum::name).toList();
        return info.getPatternValues().stream()
                .map(pattern -> pattern.replaceAll("\\{[^}]+}", "{}"))
                .flatMap(pattern -> verbs.stream().map(verb -> verb + " " + pattern))
                .toList();
    }
}
