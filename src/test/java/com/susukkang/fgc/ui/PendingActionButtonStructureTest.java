package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PendingActionButtonStructureTest {

    @Test
    void transactionActionsStayDisabledUntilFrontendApiIntegration() throws IOException {
        String template = resource("templates/transaction/form.html");

        assertPendingButton(template, "btn-add-attr", "transaction-actions-pending");
        assertPendingButton(template, "btn-save", "transaction-actions-pending");
        assertPendingButton(template, "btn-precheck", "transaction-actions-pending");
        assertPendingButton(template, "btn-confirm", "transaction-actions-pending");
        assertThat(template).contains("지급 등록 작업은 API 화면 연동 대기입니다.");
    }

    @Test
    void scheduleActionsStayDisabledUntilFrontendApiIntegration() throws IOException {
        String template = resource("templates/schedule/detail.html");

        assertPendingButton(template, "btn-export", "schedule-actions-pending");
        assertPendingButton(template, "btn-regenerate", "schedule-actions-pending");
        assertPendingButton(template, "btn-confirm", "schedule-actions-pending");
        assertThat(template).contains("다운로드·새 버전 생성·확정 작업은 API 화면 연동 대기입니다.");
    }

    @Test
    void reconciliationActionsStayDisabledUntilApiIntegration() throws IOException {
        String template = resource("templates/reco/list.html");

        assertPendingButton(template, "btn-run", "reconciliation-actions-pending");
        assertPendingButton(template, "btn-bulk-exception", "reconciliation-actions-pending");
        assertThat(template).contains("대사 실행과 불일치 예외 일괄 생성은 API 연동 대기입니다.");
    }

    @Test
    void validationRunActionsStayDisabledUntilFrontendApiIntegration() throws IOException {
        String listTemplate = resource("templates/vrun/list.html");
        String detailTemplate = resource("templates/vrun/detail.html");

        assertPendingButton(listTemplate, "btn-create", "create-hint");
        assertPendingButton(detailTemplate, "btn-refresh", "validation-actions-pending");
        assertPendingButton(detailTemplate, "btn-execute", "validation-actions-pending");
        assertPendingButton(detailTemplate, "btn-finalize", "finalize-actions-pending");
        assertThat(listTemplate).contains("실행 생성은 API 화면 연동 대기입니다.");
        assertThat(detailTemplate)
                .contains("진행률 새로고침과 실행은 API 연동 대기입니다.")
                .contains("확정 조건 확인과 확정 작업은 API 연동 대기입니다.");
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
