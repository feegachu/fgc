package com.susukkang.fgc.audit.controller;

import com.susukkang.fgc.audit.dto.AuditLogResponse;
import com.susukkang.fgc.audit.dto.AuditUserRow;
import com.susukkang.fgc.audit.service.AuditLogQueryService;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.web.ShellAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FGC-UI-AUDT-W01 감사로그 조회 화면 테스트 (FUN-061).
 * 역할 제한 스모크(ScreenViewControllerTest 에서 이관)와 서버 렌더링(목록·BATCH 표기·diff)을 본다.
 */
@WebMvcTest(AuditLogViewController.class)
@Import({ShellAdvice.class, SecurityConfig.class, MessageSourceAutoConfiguration.class,
        com.susukkang.fgc.common.exception.FgcMessageResolver.class,
        com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class AuditLogViewControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuditLogQueryService auditLogQueryService;

    private static FgcUserDetails userWithRole(Long userId, String loginId, String roleCode) {
        var view = new AppUserView();
        view.setUserId(userId);
        view.setLoginId(loginId);
        view.setPasswordHash("x");
        view.setUserName(loginId);
        view.setRoleCode(roleCode);
        return new FgcUserDetails(view, true, true);
    }

    private static FgcUserDetails complianceUser() {
        return userWithRole(2L, "audit01", "COMPLIANCE");
    }

    private static AuditLogResponse log(Long id, String loginId, String beforeJson, String afterJson) {
        return new AuditLogResponse(
                id,
                OffsetDateTime.parse("2026-08-16T10:00:00+09:00"),
                loginId == null ? null : 12L,
                loginId,
                "PAYMENT_CONFIRMED",
                "COMMISSION_PAYMENT",
                "42",
                beforeJson,
                afterJson,
                "확정 처리",
                "20260816-1a2b3c",
                3L
        );
    }

    private void stubSearch(List<AuditLogResponse> rows) {
        given(auditLogQueryService.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(PageResponse.of(rows, 1, 20, rows.size(), "occurredAt,desc"));
        given(auditLogQueryService.actionCodes()).willReturn(List.of("PAYMENT_CONFIRMED"));
        given(auditLogQueryService.entityTypes()).willReturn(List.of("COMMISSION_PAYMENT"));
        given(auditLogQueryService.auditUsers()).willReturn(List.of(new AuditUserRow(12L, "settle01")));
    }

    /** AUDT-W01(FUN-061)은 COMPLIANCE·SYSTEM_ADMIN 전용 — 화면정의서 :1530. */
    @Test
    void audit_log_screen_renders_for_compliance() throws Exception {
        stubSearch(List.of());
        mvc.perform(get("/audit-logs").with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FGC-UI-AUDT-W01")));
    }

    @Test
    void audit_log_screen_renders_for_system_admin() throws Exception {
        stubSearch(List.of());
        mvc.perform(get("/audit-logs").with(user(userWithRole(4L, "admin", "SYSTEM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FGC-UI-AUDT-W01")));
    }

    /** FUN-002 인수조건: 권한 없는 역할의 직접 URL 호출은 403 으로 차단된다. */
    @Test
    void audit_log_screen_forbidden_for_settlement() throws Exception {
        mvc.perform(get("/audit-logs").with(user(userWithRole(1L, "settle01", "SETTLEMENT"))))
                .andExpect(status().isForbidden());
    }

    /**
     * MPA 는 GlobalExceptionHandler(@RestController 한정) 밖 — 사용자가 폼으로 만들 수 있는
     * 시작일 > 종료일 입력이 업무 예외로 500 화면에 떨어지지 않고 조용히 정상화되어야 한다.
     */
    @Test
    void swaps_inverted_date_range_instead_of_error_page() throws Exception {
        stubSearch(List.of());
        mvc.perform(get("/audit-logs")
                        .param("from", "2026-08-16")
                        .param("to", "2026-08-01")
                        .with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FGC-UI-AUDT-W01")));
        verify(auditLogQueryService).search(isNull(), isNull(), isNull(), isNull(),
                eq(LocalDate.parse("2026-08-01")), eq(LocalDate.parse("2026-08-16")), eq(1), eq(20));
    }

    @Test
    void clamps_invalid_page_to_first_page() throws Exception {
        stubSearch(List.of());
        mvc.perform(get("/audit-logs").param("page", "0").with(user(complianceUser())))
                .andExpect(status().isOk());
        verify(auditLogQueryService).search(isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(1), eq(20));
    }

    /** 검색조건이 서비스로 그대로 전달되고, 20행을 넘으면 페이지 이동이 렌더링된다 (§4-1 공통 규칙 7). */
    @Test
    void passes_filters_to_service_and_renders_pagination() throws Exception {
        stubSearch(List.of());
        given(auditLogQueryService.search(
                eq("COMMISSION_PAYMENT"), eq("42"), eq(12L), eq("PAYMENT_CONFIRMED"),
                eq(LocalDate.parse("2026-08-01")), eq(LocalDate.parse("2026-08-16")), eq(2), eq(20)))
                .willReturn(PageResponse.of(
                        List.of(log(21L, "settle01", null, null)), 2, 20, 50, "occurredAt,desc"));

        mvc.perform(get("/audit-logs")
                        .param("entityType", "COMMISSION_PAYMENT")
                        .param("entityId", "42")
                        .param("userId", "12")
                        .param("action", "PAYMENT_CONFIRMED")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-16")
                        .param("page", "2")
                        .with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2 / 3")))
                .andExpect(content().string(containsString("이전")))
                .andExpect(content().string(containsString("다음")));
    }

    @Test
    void renders_rows_and_filter_options_from_server() throws Exception {
        stubSearch(List.of(log(1L, "settle01", null, null)));
        mvc.perform(get("/audit-logs").with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PAYMENT_CONFIRMED")))
                .andExpect(content().string(containsString("settle01")))
                .andExpect(content().string(containsString("20260816-1a2b3c")))
                .andExpect(content().string(containsString("확정 처리")));
    }

    /** 배치 발 감사행(user_id NULL)은 행위자를 "BATCH" 로 표기한다 — 화면정의서 :1517. */
    @Test
    void renders_batch_for_rows_without_user() throws Exception {
        stubSearch(List.of(log(1L, null, null, null)));
        mvc.perform(get("/audit-logs").with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BATCH")));
    }

    /** 선택한 행의 before/after 를 서버가 diff 계산해 바뀐 칸만 노랗게 칠한다. */
    @Test
    void renders_diff_panel_highlighting_only_changed_fields() throws Exception {
        stubSearch(List.of(log(1L, "settle01",
                "{\"status\":\"DRAFT\",\"amount\":500000}",
                "{\"status\":\"CONFIRMED\",\"amount\":500000}")));
        mvc.perform(get("/audit-logs").param("selected", "1").with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("DRAFT")))
                .andExpect(content().string(containsString("CONFIRMED")))
                .andExpect(content().string(containsString("audit-diff-cell-changed")));
    }

    /** 중첩 객체는 리프 경로 단위로 비교한다 — payment/attributions 가 통째로 한 칸이 되면 "바뀐 칸만 노랗게"가 무의미하다. */
    @Test
    void renders_field_level_diff_for_nested_payment_values() throws Exception {
        stubSearch(List.of(log(1L, "settle01",
                "{\"payment\":{\"amount\":500000,\"status\":\"DRAFT\"},\"attributions\":[{\"contractId\":3}]}",
                "{\"payment\":{\"amount\":700000,\"status\":\"DRAFT\"},\"attributions\":[{\"contractId\":3}]}")));
        mvc.perform(get("/audit-logs").param("selected", "1").with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("payment.amount")))
                .andExpect(content().string(containsString("attributions[0].contractId")))
                .andExpect(content().string(containsString("500000")))
                .andExpect(content().string(containsString("700000")))
                // 변경된 payment.amount 의 이전·이후 두 칸만 노랗다 —
                // 변경 없는 payment.status·attributions[0].contractId 행은 칠하지 않는다
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    long highlightedCells = Pattern.compile("audit-diff-cell-changed")
                            .matcher(html).results().count();
                    assertThat(highlightedCells).isEqualTo(2);
                });
    }

    /** 한쪽이 JSON 객체가 아니면(파싱 실패 포함) 필드 비교 대신 원문 한 줄 비교 — 원문이 diff 에서 사라지면 안 된다. */
    @Test
    void falls_back_to_raw_comparison_when_before_is_not_a_json_object() throws Exception {
        stubSearch(List.of(log(1L, "settle01",
                "not-a-json-object",
                "{\"status\":\"CONFIRMED\"}")));
        mvc.perform(get("/audit-logs").param("selected", "1").with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not-a-json-object")))
                .andExpect(content().string(containsString("{&quot;status&quot;:&quot;CONFIRMED&quot;}")));
    }

    @Test
    void falls_back_to_raw_comparison_when_after_is_not_a_json_object() throws Exception {
        stubSearch(List.of(log(1L, "settle01",
                "{\"status\":\"DRAFT\"}",
                "[1,2,3]")));
        mvc.perform(get("/audit-logs").param("selected", "1").with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("{&quot;status&quot;:&quot;DRAFT&quot;}")))
                .andExpect(content().string(containsString("[1,2,3]")));
    }

    /** 화면정의서 "막아야 할 것": 수정·삭제 버튼 자체를 만들지 않는다 — 안내 배너만 있고 버튼은 없다. */
    @Test
    void never_renders_mutation_buttons() throws Exception {
        stubSearch(List.of(log(1L, "settle01", null, null)));
        mvc.perform(get("/audit-logs").with(user(complianceUser())))
                .andExpect(content().string(not(containsString("삭제</button>"))))
                .andExpect(content().string(not(containsString("수정</button>"))))
                .andExpect(content().string(containsString("수정·삭제 버튼이 없습니다")));
    }
}
