package com.susukkang.fgc.exceptioncase.controller;

import com.jayway.jsonpath.JsonPath;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IF-API-69 미처리 예외 집계(FUN-080). IF-API-43 요약·목록, IF-API-03 KPI 와 건수가 어긋나지 않는지 HTTP 로 대조한다.
 */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
@AutoConfigureMockMvc
@Transactional
class ExceptionCountsApiIntegrationTest {

    private static final String COUNTS = "/api/v1/exceptions/counts";
    /** 시드와 겹치지 않는 검증월. 이 달의 건수는 그대로 기대값으로 적는다. */
    private static final String MONTH = "2031-01-01";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    private void insert(String key, String type, String severity, String status, String month) {
        jdbc.update("""
                INSERT INTO fgc.exception_case(exception_key, exception_type, severity, status, source_entity_type,
                    source_entity_id, title, validation_month)
                VALUES (?, ?, ?, ?, 'IT', ?, '시험 예외', CAST(? AS date))
                """, "IT-COUNT:" + key, type, severity, status, key, month);
    }

    private void insertFixture() {
        insert("1", "DATA_QUALITY", "CRITICAL", "NEW", MONTH);
        insert("2", "DATA_QUALITY", "WARNING", "NEW", MONTH);
        insert("3", "CAP_VIOLATION", "WARNING", "IN_REVIEW", MONTH);
        insert("4", "RECONCILIATION_MISMATCH", "HIGH", "IN_REVIEW", MONTH);
        insert("5", "DATA_QUALITY", "CRITICAL", "RESOLVED", MONTH);
        insert("6", "DATA_QUALITY", "INFO", "REJECTED", MONTH);
        insert("7", "DATA_QUALITY", "CRITICAL", "NEW", "2031-02-01");
    }

    @BeforeEach
    void setUp() {
        insertFixture();
    }

    @Test
    void 미처리_건수를_심각도와_유형으로_나눈다() throws Exception {
        counts("OPEN", MONTH)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                // 심각한 순서로 준다.
                .andExpect(jsonPath("$.data.bySeverity[*].severity").value(contains("CRITICAL", "HIGH", "WARNING")))
                .andExpect(jsonPath("$.data.bySeverity[*].severityLabel").value(contains("긴급", "높음", "주의")))
                .andExpect(jsonPath("$.data.bySeverity[*].count").value(contains(1, 1, 2)))
                .andExpect(jsonPath("$.data.byType[*].type")
                        .value(contains("DATA_QUALITY", "RECONCILIATION_MISMATCH", "CAP_VIOLATION")))
                .andExpect(jsonPath("$.data.byType[*].count").value(contains(2, 1, 1)))
                .andExpect(jsonPath("$.data.byType[0].typeLabel").isNotEmpty());
    }

    @Test
    void 상태_해석은_예외함과_같다() throws Exception {
        // 상태를 보내지 않으면 미처리, 빈 값이면 전체다.
        counts(null, MONTH).andExpect(jsonPath("$.data.total").value(4));
        counts("", MONTH).andExpect(jsonPath("$.data.total").value(6));
        counts("RESOLVED", MONTH)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.bySeverity[*].severity").value(contains("CRITICAL")));
        counts("INVALID", MONTH)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("status"));
    }

    @Test
    void 조건에_맞는_예외가_없으면_0건이다() throws Exception {
        counts("OPEN", "2031-03-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.bySeverity").isEmpty())
                .andExpect(jsonPath("$.data.byType").isEmpty());
    }

    @Test
    void 검증월_조건의_건수는_같은_조건의_예외함_건수와_같다() throws Exception {
        assertThat(totalOf(COUNTS + "?status=OPEN&validationMonth=" + MONTH)).isEqualTo(4);
        assertThat(elements("/api/v1/exceptions?status=OPEN&validationMonth=" + MONTH)).isEqualTo(4);
    }

    /** 홈의 미처리 KPI 를 누르면 status=OPEN 예외함이 열린다. 네 숫자가 모두 같아야 한다(FUN-080 인수조건). */
    @Test
    void 홈_건수는_예외함_요약과_목록_건수와_같다() throws Exception {
        long total = totalOf(COUNTS + "?status=OPEN");
        assertThat(elements("/api/v1/exceptions?status=OPEN")).isEqualTo(total);
        assertThat(this.<Integer>read("/api/v1/dashboard/summary?month=2031-01", "$.data.openException").longValue())
                .isEqualTo(total);
        List<Integer> summary = read("/api/v1/exceptions?status=OPEN", "$.data.summary[*].count");
        assertThat(summary.stream().mapToLong(Integer::longValue).sum()).isEqualTo(total);

        // 시드 건수와 무관하게 이번 시험이 넣은 미처리 5건이 그대로 늘었는지도 본다.
        jdbc.update("DELETE FROM fgc.exception_case WHERE exception_key LIKE 'IT-COUNT:%'");
        assertThat(totalOf(COUNTS + "?status=OPEN")).isEqualTo(total - 5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void 내부_역할은_집계를_본다(String role) throws Exception {
        mvc.perform(get(COUNTS).param("validationMonth", MONTH).with(user(principal(role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4));
    }

    @Test
    void 설계사_역할과_미인증_요청은_막는다() throws Exception {
        mvc.perform(get(COUNTS).with(user(principal("AGENT"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));
        mvc.perform(get(COUNTS)).andExpect(status().isUnauthorized());
    }

    private ResultActions counts(String status, String month) throws Exception {
        var request = get(COUNTS).param("validationMonth", month).with(user(principal("SETTLEMENT")));
        return mvc.perform(status == null ? request : request.param("status", status));
    }

    private long totalOf(String url) throws Exception {
        return this.<Integer>read(url, "$.data.total").longValue();
    }

    private long elements(String url) throws Exception {
        return this.<Integer>read(url, "$.data.totalElements").longValue();
    }

    private <T> T read(String url, String path) throws Exception {
        String body = mvc.perform(get(url).with(user(principal("SETTLEMENT"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, path);
    }

    private FgcUserDetails principal(String role) {
        AppUserView view = new AppUserView();
        view.setUserId(jdbc.queryForObject("SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class));
        view.setLoginId("it-counts");
        view.setPasswordHash("{bcrypt}dummy");
        view.setUserName("it-counts");
        view.setRoleCode(role);
        return new FgcUserDetails(view, true, true);
    }
}
