package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PendingActionButtonStructureTest {

    /** FGC-FUN-036, FGC-FUN-039, FGC-FUN-040 / REG-01, REG-19 */
    @Test
    void scheduleExportStaysDisabledUntilApiIntegration() throws IOException {
        String template = resource("templates/schedule/detail.html");

        assertPendingButton(template, "btn-export", "schedule-export-pending");
        assertThat(template).contains("엑셀 다운로드는 API 화면 연동 대기입니다.");
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
                        // 공백 선행을 요구해 aria-disabled 의 부분 문자열 "disabled" 오탐을 막는다
                        + "(?=[^>]*\\sdisabled\\b)"
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
