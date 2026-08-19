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

    /**
     * FGC-FUN-048~052 — RECO-W01 실행·예외생성 버튼은 #205에서 IF-API-38·42로 연동됐다.
     * 버튼의 활성/비활성 규칙(SETTLEMENT만 활성)은 ReconciliationViewControllerTest가 검증한다.
     */

    /** FGC-FUN-044 — IF-API-50/51 연결 후 초기 disabled를 권한·상태·체크리스트가 모두 통과할 때만 해제한다. */
    @Test
    void validationRunFinalizeUsesFrontendChecklistAndRoleGate() throws IOException {
        String detailTemplate = resource("templates/vrun/detail.html");
        String detailScript = resource("static/js/features/vrun/vrun-detail.js");

        assertThat(detailTemplate)
                .containsPattern("(?s)<button(?=[^>]*\\bid=\"btn-finalize\")"
                        + "(?=[^>]*\\btype=\"button\")(?=[^>]*\\sdisabled\\b)"
                        + "(?=[^>]*data-checklist-passed=\"false\")"
                        + "(?=[^>]*data-can-finalize=)[^>]*>")
                .contains("id=\"finalize-action-guide\"")
                .doesNotContain("확정 조건 확인과 확정 작업은 API 연동 대기입니다.");
        assertThat(detailScript)
                .contains("/finalize-checklist")
                .contains("/finalize\"")
                .contains("idempotencyKey: finalizeIdempotencyKey");
    /**
     * FGC-FUN-044 — IF-API-50은 연동되었고 확정(IF-API-51)만 아직 미연동이다.
     * 생성·새로고침·실행(IF-API-45·48·49)은 #188 에서 연동돼 pending 단언에서 뺐다 —
     * 해당 버튼들의 활성/비활성 규칙은 ValidationRunViewControllerTest 가 검증한다.
     */
    // 2026-08-19 yslee - IF-API-50 체크리스트 연동 후 남은 IF-API-51 대기 상태 검증 적용
    // 기존 코드: IF-API-50·51이 모두 미연동인 예전 안내 문구를 검사
    // 문제: IF-API-50 연결로 문구가 제거되어 정상 기능이 CI 실패로 판정됨
    // 개선: 체크리스트 API 호출과 확정 버튼의 초기 비활성 상태를 독립적으로 검증
    @Test
    void validationRunChecklistLoadsWhileFinalizeStaysDisabled() throws IOException {
        String detailTemplate = resource("templates/vrun/detail.html");
        String detailScript = resource("static/js/features/vrun/vrun-detail.js");

        assertPendingButton(detailTemplate, "btn-finalize", "finalize-actions-pending");
        assertThat(detailTemplate)
                .contains("data-checklist-passed=\"false\"")
                .doesNotContain("확정 조건 확인과 확정 작업은 API 연동 대기입니다.");
        assertThat(detailScript).contains("/finalize-checklist");
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
