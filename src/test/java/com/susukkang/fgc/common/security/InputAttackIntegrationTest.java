package com.susukkang.fgc.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : TER-009·SER-004 대표 공격 입력(#677). "대표 SQLi/XSS/경로조작 입력이 저장·실행되지 않는다"를 실제 PostgreSQL 로 확인한다.
 *
 * <p>자유 입력 칸은 입력을 막지 않고 글자 그대로 보관한다(OWASP XSS Cheat Sheet, SRC-028: 출력 인코딩이 1차 방어).
 * 그래서 여기서는 "실행될 수 없는 형태로 응답되는가"를 보고, 화면의 텍스트 출력은 frontend exception.test.tsx 가 본다.
 * 저장소 키의 경로 조작은 PrivateVolumeFileStorageTest 가, CSV 수식 무력화는 CsvExportWriterTest 가 이미 지킨다.
 *
 * @author yslee
 * @since 2026-11-04
 * @version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InputAttackIntegrationTest {

    private static final List<String> SQL_INJECTIONS = List.of(
            "' OR '1'='1",
            "%' OR 1=1 --",
            "'; DROP TABLE fgc.insurance_contract; --",
            "' UNION SELECT password_hash FROM fgc.app_user --");

    /** 값 자리에 SQL 을 넣어도 0건이어야 하는 자유 입력 검색. 저장소 방식(QueryDSL·JPQL·네이티브)이 고루 들어가게 골랐다. */
    private static final List<String> SEARCHES = List.of(
            "/api/v1/contracts?contractNo=",
            "/api/v1/cap-checks?contractNo=",
            "/api/v1/arbitrage-checks?contractNo=",
            "/api/v1/clawbacks/candidates?contractNo=",
            "/api/v1/reports/agents/1/statements?month=2026-07&contractNo=",
            "/api/v1/exceptions?reasonCode=",
            "/api/v1/base/agents?asOf=2026-07-01&keyword=",
            "/api/v1/base/organizations?asOf=2026-07-01&keyword=",
            "/api/v1/base/insurers?keyword=",
            "/api/v1/base/products?insurerId=1&asOf=2026-07-01&keyword=",
            "/api/v1/base/commission-items/search?asOf=2026-07-01&keyword=");

    /** ListSort 허용 목록을 거치는 목록 API. */
    private static final List<String> SORTABLE_LISTS = List.of(
            "/api/v1/contracts",
            "/api/v1/transactions",
            "/api/v1/schedules",
            "/api/v1/cap-checks",
            "/api/v1/arbitrage-checks",
            "/api/v1/audit-logs",
            "/api/v1/exceptions",
            "/api/v1/journals",
            "/api/v1/validation-runs",
            "/api/v1/reconciliations",
            "/api/v1/clawbacks/candidates",
            "/api/v1/reports/agents/1/statements?month=2026-07",
            "/api/v1/base/agents?asOf=2026-07-01",
            "/api/v1/base/organizations?asOf=2026-07-01",
            "/api/v1/base/insurers",
            "/api/v1/base/products?insurerId=1&asOf=2026-07-01");

    private static final List<String> SORT_INJECTIONS = List.of(
            "contractNo;DROP TABLE fgc.insurance_contract--,asc",
            "(SELECT 1),desc",
            "contract_id,asc NULLS FIRST",
            "1,asc",
            "<script>alert(1)</script>,asc");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private AuthTokenService tokens;
    @Autowired
    private FgcUserDetailsService users;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlers;

    private String bearer;

    /** 시험 트랜잭션 밖에서 커밋한다. login() 의 app_user 잠금을 쥔 채 요청하면 요청 안 REQUIRES_NEW 쓰기가 그 잠금을 기다린다. */
    @BeforeAll
    void loginAsSystemAdmin() {
        bearer = "Bearer " + tokens.login((FgcUserDetails) users.loadUserByUsername("admin")).accessToken();
    }

    @AfterAll
    void clearRefreshToken() {
        jdbc.update("DELETE FROM fgc.auth_refresh_token WHERE user_id = (SELECT user_id FROM fgc.app_user WHERE login_id = 'admin')");
    }

    static Stream<Arguments> searchInjections() {
        return SEARCHES.stream().flatMap(url -> SQL_INJECTIONS.stream().map(payload -> Arguments.of(url, payload)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("searchInjections")
    void sqlInSearchValueMatchesNothingAndChangesNothing(String url, String payload) throws Exception {
        Long contractsBefore = countContracts();

        MockHttpServletResponse response = mvc.perform(get(URI.create(url + encode(payload)))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn().getResponse();

        assertThat(response.getStatus()).as(url).isEqualTo(200);
        JsonNode data = json.readTree(response.getContentAsByteArray()).path("data");
        JsonNode rows = data.isArray() ? data : firstArray(data);
        assertThat(rows).as(url + " 는 값을 글자로 비교해 0건이어야 한다").isEmpty();
        assertThat(response.getContentAsString()).doesNotContain("$2a$");
        assertThat(countContracts()).isEqualTo(contractsBefore);
    }

    static Stream<Arguments> sortInjections() {
        return SORTABLE_LISTS.stream().flatMap(url -> SORT_INJECTIONS.stream().map(sort -> Arguments.of(url, sort)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("sortInjections")
    void sortOutsideTheAllowListIsRejected(String url, String sort) throws Exception {
        mvc.perform(get(URI.create(url + (url.contains("?") ? "&" : "?") + "sort=" + encode(sort)))
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("sort"))
                .andExpect(content().string(not(containsString("<script"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/contracts?insurerId=1%20OR%201%3D1",
            "/api/v1/contracts/1%20OR%201%3D1",
            "/api/v1/transactions/1%20DROP",
            "/api/v1/contracts?contractDateFrom=%3Cscript%3Ealert(1)%3C%2Fscript%3E",
            // ZAP API scan 이 500 으로 찾은 입력. 월 형식과 NUL 문자는 입력 오류여야 한다.
            "/api/v1/cap-checks?month=http%3A%2F%2Fexample.com%2F",
            "/api/v1/contracts?contractNo=%00"})
    void typedParametersRejectAttackTextWithoutEchoingIt(String url) throws Exception {
        mvc.perform(get(URI.create(url)).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(content().string(not(containsString("<script"))));
    }

    /** 화면은 CSV 를 Accept: text/csv 로 부른다(client.ts). 그래도 오류는 표준 봉투여야 한다(SIR-001). ZAP API scan 이 본문 없는 500 으로 찾았다. */
    @Test
    void csvDownloadErrorStillReturnsTheJsonEnvelope() throws Exception {
        mvc.perform(get("/api/v1/schedules/999999999/export.csv").accept("text/csv")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    /** 응답이 JSON + nosniff 이면 브라우저가 HTML 로 해석하지 않는다. 글자 그대로 돌려주는지까지 봐서 입력 변조도 없음을 확인한다. */
    @Test
    void storedScriptComesBackAsInertJsonText() throws Exception {
        String payload = "<script>alert(1)</script><img src=x onerror=alert(2)>";
        String reasonCode = "XSS-" + UUID.randomUUID();
        Long caseId = jdbc.queryForObject("""
                INSERT INTO fgc.exception_case
                    (exception_key, exception_type, reason_code, severity, status, source_entity_type, source_entity_id, title)
                VALUES (?, 'DATA_QUALITY', ?, 'WARNING', 'NEW', 'IT', ?, ?)
                RETURNING exception_case_id
                """, Long.class, "XSS:" + UUID.randomUUID(), reasonCode, reasonCode, payload);

        mvc.perform(post("/api/v1/exceptions/{id}/actions", caseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("actionType", "START_REVIEW", "reason", payload))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/exceptions").param("reasonCode", reasonCode)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.data.content[0].title").value(payload))
                .andExpect(jsonPath("$.data.content[0].actions[0].reason").value(payload));
    }

    /** documentNo 는 파일 경로가 아니라 DB 조회 키다. 파이어월이나 서비스 허용 문자 검사에서 끊겨 어떤 파일도 내려가지 않아야 한다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/documents/..%2F..%2Fetc%2Fpasswd.pdf?version=1",
            "/api/v1/documents/%2e%2e%2fsecret.pdf?version=1",
            "/api/v1/documents/..%5Csecret.pdf?version=1",
            "/api/v1/documents/..;/secret.pdf?version=1",
            "/api/v1/documents/NO%0D%0AX-Injected:1.pdf?version=1",
            "/api/v1/documents/not.allowed.pdf?version=1"})
    void documentDownloadRejectsPathManipulation(String url) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(URI.create(url)).header(HttpHeaders.AUTHORIZATION, bearer))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeaderNames()).doesNotContain("X-Injected", HttpHeaders.CONTENT_DISPOSITION);
    }

    /** 계약번호는 자유 입력(@Size 만)이고 스케줄 내려받기 파일명에 들어간다. 헤더 분리·경로 문자가 그대로 나가면 안 된다. */
    @Test
    void downloadFilenameEncodesHostileContractNumber() throws Exception {
        Long contractId = jdbc.queryForObject("SELECT min(contract_id) FROM fgc.insurance_contract", Long.class);
        jdbc.update("UPDATE fgc.insurance_contract SET contract_no = ? WHERE contract_id = ?",
                "../../evil\"\r\nX-Injected: 1", contractId);
        Long scheduleHeaderId = jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, scenario_code, schedule_regime)
                VALUES (?, 'GA_TO_FC', (SELECT min(policy_version_id) FROM fgc.policy_version), 999, 'OPERATIONAL', NULL, 'CURRENT')
                RETURNING schedule_header_id
                """, Long.class, contractId);

        MockHttpServletResponse response = mvc.perform(get("/api/v1/schedules/{id}/export.csv", scheduleHeaderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Injected"))
                .andReturn().getResponse();

        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).doesNotContain("\r", "\n", "\"evil").contains("filename*=UTF-8''", "..%2F..%2Fevil");
    }

    /** SER-005: 업로드 기능 없음(#637 not planned, #707 닫힘). 업로드가 돌아오면 여기서 깨져 확장자·MIME·크기 시험을 함께 넣게 한다. */
    @Test
    void noHandlerAcceptsFileUpload() {
        assertThat(handlers.getHandlerMethods().values())
                .noneMatch(handler -> Arrays.stream(handler.getMethodParameters())
                        .anyMatch(parameter -> MultipartFile.class.isAssignableFrom(parameter.nestedIfOptional().getNestedParameterType())
                                || parameter.getGenericParameterType().getTypeName().contains(MultipartFile.class.getName())));
        assertThat(handlers.getHandlerMethods().keySet())
                .noneMatch(info -> info.getConsumesCondition().getConsumableMediaTypes().contains(MediaType.MULTIPART_FORM_DATA));
    }

    private Long countContracts() {
        return jdbc.queryForObject("SELECT count(*) FROM fgc.insurance_contract", Long.class);
    }

    /** 페이지 응답은 content, 명세 응답은 items.content 처럼 목록 위치가 조금씩 달라 첫 번째 배열을 찾는다. */
    private static JsonNode firstArray(JsonNode data) {
        if (data.has("content")) {
            return data.get("content");
        }
        for (JsonNode child : data) {
            if (child.isArray()) {
                return child;
            }
            if (child.isObject() && child.has("content")) {
                return child.get("content");
            }
        }
        throw new AssertionError("목록을 찾지 못했습니다: " + data);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
