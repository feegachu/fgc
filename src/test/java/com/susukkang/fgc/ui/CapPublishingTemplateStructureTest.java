package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CapPublishingTemplateStructureTest {

    @Test
    void capListUsesCommonComponentsAndServerAggregates() throws IOException {
        assertThat(resource("templates/cap/list.html"))
                .contains("class=\"surface cap-filter-panel\"")
                .contains("class=\"kpi-card cap-kpi-card")
                .contains("class=\"data-table cap-contract-table\"")
                .contains("class=\"data-table cap-agent-table\"")
                .contains("지급단계별 한도 게이지")
                .contains("모니터링 지표 · 규제 판정 아님")
                .contains("data-modal=\"cap-detail\"")
                .contains("class=\"modal-footer cap-detail-modal-footer\"")
                .contains("class=\"button button-primary\"")
                .doesNotContain("기준월과 계약 조건으로 저장된 판정 결과를 조회합니다.")
                .doesNotContain("서로 다른 규제이므로 두 단계를 합산하지 않습니다.")
                .doesNotContain("규제 판정 단위는 계약 1건입니다.")
                .doesNotContain("GA → 설계사 지급단계만 집계합니다.")
                .doesNotContain("검증 당시 저장된 값과 항목별 판단을 확인합니다.")
                .doesNotContain("판정과 상세는 읽기 전용이며 이 팝업에서 수정할 수 없습니다.")
                .doesNotContain("page-description")
                .doesNotContain("cap-guidance")
                .doesNotContain("fgc-banner")
                .doesNotContain("style=");

        assertThat(resource("static/js/features/cap/cap-list.js"))
                .contains("/api/v1/cap-checks?")
                .contains("renderStageSummary(data.stageSummary)")
                .contains("renderAgentSummary(data.agentSummary)")
                .contains("item.resultStatus")
                .contains("function tableCellDisclosure(")
                .contains("preview.scrollWidth > preview.clientWidth")
                .contains("preview.scrollHeight > preview.clientHeight")
                .contains("document.fonts.ready")
                .doesNotContain("fetch(");
    }

    @Test
    void capDetailRemainsACommonComponentModalWithoutGuidanceOrInlineStyles() throws IOException {
        assertThat(resource("templates/cap/detail-modal.html"))
                .contains("th:fragment=\"modal\"")
                .contains("class=\"surface cap-detail-summary\"")
                .contains("class=\"data-table cap-detail-table\"")
                .contains("class=\"cap-detail-contract-context\"")
                .contains("role=\"progressbar\"")
                .contains("id=\"formula-limit\"")
                .contains("id=\"final-warning-mark\"")
                .contains("id=\"detail-count\"")
                .contains("id=\"detail-included-note\"")
                .contains("<th scope=\"col\">근거</th>")
                .doesNotContain("id=\"sum-kind\"")
                .doesNotContain("CAP-GA-FC-V1")
                .doesNotContain("무엇으로 계산했나")
                .doesNotContain("어떻게 계산했나")
                .doesNotContain("결론은 무엇인가")
                .doesNotContain("STEP 3 ·")
                .doesNotContain("판단 이유는 검증 당시 저장된 필수 값이며")
                .doesNotContain("<main")
                .doesNotContain("page-description")
                .doesNotContain("fgc-")
                .doesNotContain("guidance")
                .doesNotContain("style=");

        assertThat(resource("static/css/features/cap.css"))
                .contains(".cap-detail-layout")
                .contains(".cap-detail-table")
                .contains("@media (max-width: 63.9375rem)")
                .contains("@media (max-width: 47.9375rem)")
                .doesNotContain(".cap-guidance");

        assertThat(resource("static/js/features/cap/cap-list.js"))
                .contains("룰셋 ID ")
                .contains("cap_check #")
                .contains("snapshot.warningUsagePct")
                .contains("item.resultStatus")
                .contains("증빙 미연결 / 후속 연결 대기")
                .doesNotContain("item.checkKind");

        assertThat(resource("static/js/features/contract/contract-tabs.js"))
                .contains("emptyCell.colSpan = 6")
                .contains("capDisclosureCell(detail.decisionReason")
                .contains("status-badge \" + capClassificationClass")
                .contains("syncCapDisclosures(content)")
                .contains("formula-limit")
                .contains("snapshot.warningUsagePct")
                .doesNotContain("item.checkKind");

        assertThat(resource("templates/contract/detail.html"))
                .contains("class=\"modal-header cap-detail-modal-header\"")
                .contains("class=\"modal-footer cap-detail-modal-footer\"")
                .doesNotContain("검증 당시 저장된 값과 항목별 판단을 확인합니다.")
                .doesNotContain("판정과 상세는 읽기 전용이며 이 팝업에서 수정할 수 없습니다.")
                .doesNotContain("page-description");
    }

    private String resource(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
