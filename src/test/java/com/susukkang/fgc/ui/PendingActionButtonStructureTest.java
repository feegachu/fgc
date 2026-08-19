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

    // 2026-08-19 yslee - #238 병합 후에도 IF-API-50·51 연동 완료 기준을 단일 테스트로 유지
    // 기존 코드: IF-API-50 전용 테스트와 IF-API-50·51 통합 테스트가 병합 과정에서 중복됨
    // 문제: 메서드 닫는 괄호가 유실되어 테스트 소스가 컴파일되지 않음
    // 개선: 확정 버튼의 초기 비활성·권한 게이트·IF-API-50·51 연동을 하나의 테스트로 검증
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
                .contains("검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.")
                .containsOnlyOnce("id=\"finalize-action-guide\"")
                .containsOnlyOnce("id=\"btn-finalize\"")
                .doesNotContain("id=\"finalize-actions-pending\"")
                .doesNotContain("확정 조건 확인과 확정 작업은 API 연동 대기입니다.");
        assertThat(detailScript)
                .contains("/finalize-checklist")
                .contains("/finalize\"")
                .contains("error.code === \"FGC-VRUN-006\"")
                .contains("finalizeIdempotencyKey = null")
                .contains("idempotencyKey: finalizeIdempotencyKey");
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
