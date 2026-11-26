package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.exceptioncase.event.ExceptionCaseClosed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FGC-FUN-089, IF-EVT-08: 정산담당자가 예외 조치 API(IF-API-44)로 예외를 종결하면 종결 사건이 나가는지 본다.
 * 마감 알림은 이 사건을 커밋 뒤에 받아 확정 조건을 바로 다시 본다(ClosingNotificationListenerIntegrationTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class ExceptionCaseClosedEventIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEvents events;

    @Test
    @DisplayName("FGC-FUN-089 검토를 시작한 것만으로는 알리지 않고, 예외를 종결하면 종결 사건을 한 번 낸다")
    void announcesTheExceptionOnlyWhenItIsClosed() throws Exception {
        Long id = insertOpenCase();
        FgcUserDetails clerk = principal("SETTLEMENT");

        act(id, clerk, """
                {"actionType":"START_REVIEW","reason":"검토 시작"}
                """).andExpect(jsonPath("$.data.toStatus").value("IN_REVIEW"));
        assertThat(events.stream(ExceptionCaseClosed.class)).isEmpty();

        act(id, clerk, """
                {"actionType":"REJECT","reason":"오탐으로 확인","evidenceRef":"DOC-FUN089"}
                """).andExpect(jsonPath("$.data.toStatus").value("REJECTED"));

        assertThat(events.stream(ExceptionCaseClosed.class)).containsExactly(new ExceptionCaseClosed(id));
    }

    private ResultActions act(Long id, FgcUserDetails actor, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/exceptions/{id}/actions", id).with(user(actor)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private Long insertOpenCase() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.exception_case
                    (exception_key, exception_type, reason_code, severity, status,
                     source_entity_type, source_entity_id, title)
                VALUES (?, 'DATA_QUALITY', 'FUN089-EVT08', 'HIGH', 'NEW', 'IT', ?, '예외 종결 사건 검증')
                RETURNING exception_case_id
                """, Long.class, "FUN089:" + UUID.randomUUID(), UUID.randomUUID().toString());
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
