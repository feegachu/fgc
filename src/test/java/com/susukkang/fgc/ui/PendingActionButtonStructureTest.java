package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PendingActionButtonStructureTest {

    /**
     * FGC-FUN-065, FGC-FUN-031, FGC-FUN-033, FGC-FUN-034 /
     * REG-08, REG-09, REG-11, REG-20, REG-21
     */
    @Test
    void transactionActionsStayDisabledUntilFrontendApiIntegration() throws IOException {
        String template = resource("templates/transaction/form.html");

        assertPendingButton(template, "btn-add-attr", "transaction-actions-pending");
        assertPendingButton(template, "btn-save", "transaction-actions-pending");
        assertPendingButton(template, "btn-precheck", "transaction-actions-pending");
        assertPendingButton(template, "btn-confirm", "transaction-actions-pending");
        assertThat(template).contains("지급 등록 작업은 API 화면 연동 대기입니다.");
    }

    /** FGC-FUN-036, FGC-FUN-039, FGC-FUN-040 / REG-01, REG-19 */
    @Test
    void scheduleActionsStayDisabledUntilFrontendApiIntegration() throws IOException {
        String template = resource("templates/schedule/detail.html");

        assertPendingButton(template, "btn-export", "schedule-actions-pending");
        assertPendingButton(template, "btn-regenerate", "schedule-actions-pending");
        assertPendingButton(template, "btn-confirm", "schedule-actions-pending");
        assertThat(template).contains("다운로드·새 버전 생성·확정 작업은 API 화면 연동 대기입니다.");
    }

    /** FGC-FUN-048, FGC-FUN-049, FGC-FUN-050, FGC-FUN-051, FGC-FUN-052 */
    @Test
    void reconciliationActionsStayDisabledUntilApiIntegration() throws IOException {
        String template = resource("templates/reco/list.html");

        assertPendingButton(template, "btn-run", "reconciliation-actions-pending");
        assertPendingButton(template, "btn-bulk-exception", "reconciliation-actions-pending");
        assertThat(template).contains("대사 실행과 불일치 예외 일괄 생성은 API 연동 대기입니다.");
    }

    /**
     * FGC-FUN-044 — 확정(IF-API-51)만 아직 미연동이라 pending 을 유지한다.
     * 생성·새로고침·실행(IF-API-45·48·49)은 #188 에서 연동돼 pending 단언에서 뺐다 —
     * 해당 버튼들의 활성/비활성 규칙은 ValidationRunViewControllerTest 가 검증한다.
     */
    @Test
    void validationRunFinalizeStaysDisabledUntilFrontendApiIntegration() throws IOException {
        String detailTemplate = resource("templates/vrun/detail.html");

        assertPendingButton(detailTemplate, "btn-finalize", "finalize-actions-pending");
        assertThat(detailTemplate).contains("확정 조건 확인과 확정 작업은 API 연동 대기입니다.");
    }

    private static void assertPendingButton(String template, String id, String descriptionId) {
        assertThat(template)
                .containsPattern("(?s)<button(?=[^>]*\\bid=\"" + id + "\")"
                        + "(?=[^>]*\\btype=\"button\")"
                        + "(?=[^>]*\\bdisabled\\b)"
                        + "(?=[^>]*\\baria-describedby=\"" + descriptionId + "\")[^>]*>")
                .contains("id=\"" + descriptionId + "\"");
    }

    private static String resource(String path) throws IOException {
        try (var input = PendingActionButtonStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
