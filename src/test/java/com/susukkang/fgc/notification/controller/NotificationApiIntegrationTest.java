package com.susukkang.fgc.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** FUN-089 IF-API-91~94: 본인 알림 설정과 발송 이력·다시 보내기를 실제 보안 규칙·PostgreSQL 과 함께 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationApiIntegrationTest {

    private static final String SETTING = "/api/v1/notification-settings/me";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FgcUserDetailsService users;

    @Test
    @DisplayName("번호를 등록하면 숫자만 저장하고, 감사로그에는 가운데를 가린 번호만 남긴다")
    void savesOwnNumberAndAuditsOnlyTheMaskedValue() throws Exception {
        mvc.perform(get(SETTING).with(user(actor("gaadmin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobilePhone").isEmpty())
                .andExpect(jsonPath("$.data.consentedAt").isEmpty())
                .andExpect(jsonPath("$.data.smsConfigured").value(false));

        mvc.perform(save("gaadmin", "010-0000-0102", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobilePhone").value("01000000102"))
                .andExpect(jsonPath("$.data.consentedAt").isString());

        assertThat(jdbc.queryForObject("SELECT mobile_phone FROM fgc.user_notification_setting WHERE user_id = ?",
                String.class, userId("gaadmin"))).isEqualTo("01000000102");
        String audited = jdbc.queryForObject("""
                SELECT after_value::text FROM fgc.audit_log
                 WHERE action_code = 'NOTIFICATION_SETTING_CHANGED' AND entity_id = ?
                 ORDER BY audit_log_id DESC LIMIT 1
                """, String.class, String.valueOf(userId("gaadmin")));
        assertThat(audited).contains("010-****-0102").doesNotContain("01000000102");
    }

    @Test
    @DisplayName("번호를 바꾸면 같은 사람의 설정 한 줄만 갱신한다")
    void updatesTheSameRowWhenTheNumberChanges() throws Exception {
        mvc.perform(save("gaadmin", "01000000102", true)).andExpect(status().isOk());
        mvc.perform(save("gaadmin", "010 0000 0199", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobilePhone").value("01000000199"));

        assertThat(jdbc.queryForList("SELECT mobile_phone FROM fgc.user_notification_setting WHERE user_id = ?",
                String.class, userId("gaadmin"))).containsExactly("01000000199");
    }

    @ParameterizedTest
    @ValueSource(strings = {"02-123-4567", "0101234", "010-0000-01020", "abc", ""})
    @DisplayName("휴대폰 번호 형식이 아니면 저장하지 않는다")
    void rejectsValuesThatAreNotMobileNumbers(String phone) throws Exception {
        mvc.perform(save("gaadmin", phone, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("mobilePhone"));

        assertThat(settingCount("gaadmin")).isZero();
    }

    @Test
    @DisplayName("수신 동의를 철회하면 번호를 지운다")
    void deletesTheNumberWhenConsentIsWithdrawn() throws Exception {
        mvc.perform(save("gaadmin", "01000000102", true)).andExpect(status().isOk());

        mvc.perform(save("gaadmin", null, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobilePhone").isEmpty())
                .andExpect(jsonPath("$.data.consentedAt").isEmpty());

        assertThat(settingCount("gaadmin")).isZero();
    }

    @Test
    @DisplayName("설계사도 본인 번호를 등록하고 본인 것만 본다")
    void letsAnAgentManageOnlyTheirOwnSetting() throws Exception {
        mvc.perform(save("gaadmin", "01000000102", true)).andExpect(status().isOk());

        mvc.perform(get(SETTING).with(user(actor("agent01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobilePhone").isEmpty());
        mvc.perform(save("agent01", "01000000201", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobilePhone").value("01000000201"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"settle01", "audit01"})
    @DisplayName("알림을 받지 않는 역할은 알림 설정을 쓸 수 없다")
    void deniesRolesThatReceiveNoNotifications(String loginId) throws Exception {
        mvc.perform(get(SETTING).with(user(actor(loginId)))).andExpect(status().isForbidden());
        mvc.perform(save(loginId, "01000000103", true)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications").with(user(actor(loginId)))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("로그인하지 않으면 401이다")
    void requiresLogin() throws Exception {
        mvc.perform(get(SETTING)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("발송 이력은 확정 권한자만 보고, 설계사에게는 닫혀 있다")
    void listsHistoryForFinalizersOnly() throws Exception {
        Long id = createNotification("SKIPPED");

        mvc.perform(get("/api/v1/notifications").param("size", "1").with(user(actor("gaadmin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].closingNotificationId").value(id))
                .andExpect(jsonPath("$.data.content[0].eventType").value("FINALIZE_READY"))
                .andExpect(jsonPath("$.data.content[0].validationMonth").value("2088-09-01"))
                .andExpect(jsonPath("$.data.content[0].recipientLoginId").value("gaadmin"))
                .andExpect(jsonPath("$.data.content[0].recipientPhone").value("010-****-0102"))
                .andExpect(jsonPath("$.data.content[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.content[0].errorCode").value("NO_API_KEY"))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.sort").value("createdAt,desc"));
        mvc.perform(get("/api/v1/notifications").param("size", "101").with(user(actor("gaadmin"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
        mvc.perform(get("/api/v1/notifications").with(user(actor("agent01")))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("건너뛴 알림은 다시 보내기로 대기 상태가 되고, 대기 중인 알림은 거절한다")
    void resendsASkippedNotification() throws Exception {
        Long id = createNotification("SKIPPED");

        mvc.perform(post("/api/v1/notifications/" + id + "/resend").with(user(actor("agent01"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notifications/" + id + "/resend").with(user(actor("gaadmin"))))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM fgc.closing_notification WHERE closing_notification_id = ?",
                String.class, id)).isEqualTo("PENDING");
        mvc.perform(post("/api/v1/notifications/" + id + "/resend").with(user(actor("gaadmin"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-NOTI-001"));
        mvc.perform(post("/api/v1/notifications/999999999/resend").with(user(actor("gaadmin"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
    }

    private MockHttpServletRequestBuilder save(String loginId, String phone, boolean consent) {
        String body = phone == null ? "{\"consent\":" + consent + "}"
                : "{\"mobilePhone\":\"" + phone + "\",\"consent\":" + consent + "}";
        return put(SETTING).contentType(MediaType.APPLICATION_JSON).content(body).with(user(actor(loginId)));
    }

    private UserDetails actor(String loginId) {
        return users.loadUserByUsername(loginId);
    }

    private Long userId(String loginId) {
        return jdbc.queryForObject("SELECT user_id FROM fgc.app_user WHERE login_id = ?", Long.class, loginId);
    }

    private Integer settingCount(String loginId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fgc.user_notification_setting WHERE user_id = ?",
                Integer.class, userId(loginId));
    }

    private Long createNotification(String status) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM')
                RETURNING validation_run_id
                """, Long.class, LocalDate.of(2088, 9, 1), ThreadLocalRandom.current().nextInt(3_000_000, 4_000_000));
        jdbc.update("INSERT INTO fgc.user_notification_setting (user_id, mobile_phone) VALUES (?, '01000000102')",
                userId("gaadmin"));
        return jdbc.queryForObject("""
                INSERT INTO fgc.closing_notification
                    (event_type, validation_run_id, recipient_user_id, message_text, status, attempt_count, error_code)
                VALUES ('FINALIZE_READY', ?, ?, '[FGC] 시험 문자', ?, 1, 'NO_API_KEY')
                RETURNING closing_notification_id
                """, Long.class, runId, userId("gaadmin"), status);
    }
}
