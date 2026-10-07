package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IF-API-43: mock 서비스 없이 HTTP 바인딩부터 PostgreSQL 조회·조치까지 검증한다. */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@AutoConfigureMockMvc
@Transactional
class ExceptionCaseApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ExceptionCaseService service;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @ParameterizedTest
    @CsvSource({"page,0", "page,-1", "size,0", "size,-1", "size,101", "status,INVALID"})
    void rejectsInvalidSearchConditionsWithExistingErrorContract(String field, String value) throws Exception {
        mockMvc.perform(get("/api/v1/exceptions").with(user(principal("SETTLEMENT")))
                        .param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value(field))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void allowsAllExistingReadRolesAndPreservesNullsAndEmptyHistories(String role) throws Exception {
        String reason = "API-" + UUID.randomUUID();
        Long id = insertCase(reason, "CRITICAL");

        mockMvc.perform(get("/api/v1/exceptions").with(user(principal(role))).param("reasonCode", reason))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].exceptionCaseId").value(id))
                .andExpect(jsonPath("$.data.content[0].status").value("NEW"))
                .andExpect(jsonPath("$.data.content[0].contractId").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].contractNo").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].assignedTo").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].createdAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.data.content[0].actions").isEmpty())
                .andExpect(jsonPath("$.data.content[0].occurrences").isEmpty());
    }

    @Test
    void clampsPageAndKeepsGlobalSummaryForEmptySearch() throws Exception {
        String reason = "API-" + UUID.randomUUID();
        insertCase(reason, "CRITICAL");
        Long lastId = insertCase(reason, "WARNING");

        mockMvc.perform(get("/api/v1/exceptions").with(user(principal("SETTLEMENT")))
                        .param("reasonCode", reason).param("page", "2147483647").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.content[0].exceptionCaseId").value(lastId));

        mockMvc.perform(get("/api/v1/exceptions").with(user(principal("SETTLEMENT")))
                        .param("reasonCode", reason + "-missing").param("page", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.summary").isNotEmpty());
    }

    @Test
    void actionIsVisibleInNextSearchWithOrderedHistoryAndSeoulTime() throws Exception {
        String reason = "API-" + UUID.randomUUID();
        Long id = insertCase(reason, "WARNING");
        FgcUserDetails principal = principal("SETTLEMENT");
        mockMvc.perform(post("/api/v1/exceptions/{id}/actions", id).with(user(principal)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actionType":"START_REVIEW","reason":"검토 시작","evidenceRef":"DOC-API"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exceptionActionId").value(nullValue()))
                .andExpect(jsonPath("$.data.toStatus").value("IN_REVIEW"));

        mockMvc.perform(get("/api/v1/exceptions").with(user(principal)).param("reasonCode", reason))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.data.content[0].actions.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].actions[0].exceptionActionId").isNumber())
                .andExpect(jsonPath("$.data.content[0].actions[0].actionSeq").value(1))
                .andExpect(jsonPath("$.data.content[0].actions[0].fromStatus").value("NEW"))
                .andExpect(jsonPath("$.data.content[0].actions[0].actionAt").value(endsWith("+09:00")));
    }

    @Test
    void omittedStatusListsOnlyOpenCasesWhileEmptyStatusListsAll() throws Exception {
        String reason = "API-" + UUID.randomUUID();
        Long open = insertCase(reason, "WARNING");
        Long resolved = insertCase(reason, "WARNING");
        jdbcTemplate.update("UPDATE fgc.exception_case SET status = 'RESOLVED' WHERE exception_case_id = ?", resolved);

        mockMvc.perform(get("/api/v1/exceptions").with(user(principal("COMPLIANCE"))).param("reasonCode", reason))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].exceptionCaseId").value(open));

        mockMvc.perform(get("/api/v1/exceptions").with(user(principal("COMPLIANCE")))
                        .param("reasonCode", reason).param("status", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void optionsListsAllTypesAndSeveritiesWithLabelsAndUsedReasons() throws Exception {
        String reason = "API-" + UUID.randomUUID();
        insertCase(reason, "WARNING");

        mockMvc.perform(get("/api/v1/exceptions/options").with(user(principal("COMPLIANCE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.types[?(@.code == 'CAP_VIOLATION')].label").value("1,200% 위반"))
                .andExpect(jsonPath("$.data.severities.length()").value(4))
                .andExpect(jsonPath("$.data.reasons[?(@.code == '" + reason + "')].label").value(reason))
                .andExpect(jsonPath("$.data.assignees").isArray())
                .andExpect(jsonPath("$.data.validationMonths").isArray());
    }

    @Test
    void rejectsUnauthenticatedSearch() throws Exception {
        mockMvc.perform(get("/api/v1/exceptions")).andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional(readOnly = true)
    void readOnlySearchAndOptionsDoNotChangeDatabaseRows() {
        assertThat(jdbcTemplate.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
        List<String> before = snapshots();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        service.search(new ExceptionCaseSearchDTO(), 1, 100);
        service.reasonCodes();
        service.assignees();
        service.validationMonths();

        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(statistics.getEntityDeleteCount()).isZero();
        assertThat(snapshots()).isEqualTo(before);
    }

    private List<String> snapshots() {
        return List.of("exception_case", "exception_action", "exception_occurrence", "validation_run")
                .stream().map(table -> jdbcTemplate.queryForObject(
                        "SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text "
                                + "FROM fgc." + table + " t", String.class)).toList();
    }

    private Long insertCase(String reason, String severity) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.exception_case
                    (exception_key, exception_type, reason_code, severity, status,
                     source_entity_type, source_entity_id, title)
                VALUES (?, 'DATA_QUALITY', ?, ?, 'NEW', 'IT', ?, 'API 조회 검증')
                RETURNING exception_case_id
                """, Long.class, "API:" + UUID.randomUUID(), reason, severity, reason);
    }

    private FgcUserDetails principal(String role) {
        AppUserView view = jdbcTemplate.queryForObject("""
                SELECT user_id, login_id FROM fgc.app_user ORDER BY user_id LIMIT 1
                """, (rs, rowNum) -> {
            AppUserView result = new AppUserView();
            result.setUserId(rs.getLong("user_id"));
            result.setLoginId(rs.getString("login_id"));
            return result;
        });
        view.setPasswordHash("{bcrypt}dummy");
        view.setUserName(view.getLoginId());
        view.setRoleCode(role);
        return new FgcUserDetails(view, true, true);
    }
}
