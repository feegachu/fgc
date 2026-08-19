package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublishingTemplateStructureTest {

    private static final List<String> PUBLISHED_TEMPLATES = List.of(
            "templates/base/index.html",
            "templates/contract/form.html",
            "templates/transaction/list.html",
            "templates/transaction/form.html",
            "templates/schedule/list.html",
            "templates/schedule/detail.html",
            "templates/policy/list.html",
            "templates/arbitrage/list.html",
            "templates/ledger/list.html",
            "templates/reco/list.html",
            "templates/exception/list.html",
            "templates/vrun/list.html",
            "templates/vrun/detail.html",
            "templates/audit/list.html"
    );

    @Test
    void publishedScreensUseProductionShellScopeWithoutPrototypeDependencies() throws IOException {
        for (String resource : PUBLISHED_TEMPLATES) {
            String template = resource(resource);

            assertThat(template)
                    .as(resource)
                    .contains("id=\"main-content\"")
                    .contains("publishing-page")
                    .doesNotContain("cdn.tailwindcss.com")
                    .doesNotContain("FGC.SEED");
        }
    }

    @Test
    void reconciliationComparisonKeepsPopupPublishingScope() throws IOException {
        assertThat(resource("templates/reco/compare-modal.html"))
                .contains("th:fragment=\"modal\"")
                .contains("publishing-modal")
                .doesNotContain("<main");
    }

    @Test
    void connectedArbitrageScreenDoesNotExposeStalePendingState() throws IOException {
        assertThat(resource("templates/arbitrage/list.html"))
                .contains("id=\"arb-pagination\"")
                .contains("data-modal=\"arb-recheck\"")
                .doesNotContain("조회 API 연동 대기");
        // EXCP-W01 은 유형별 요약·페이지 목록·행 선택 상세까지 서버 데이터로 연동됐다.
        assertThat(resource("templates/exception/list.html"))
                .contains("exception-pagination")
                .contains("pagination-controls")
                .contains("pagination-button is-active")
                .contains("exception-summary-card")
                .contains("exception-detail-")
                .contains("data-exception-action-form")
                .contains("처리 저장")
                .doesNotContain("유형별 미처리 집계 API 연동 대기");
        assertThat(resource("templates/layout/default.html"))
                .contains("/css/features/exception.css")
                .containsPattern("(?s)<script[^>]*th:src=\"@\\{/js/features/exception/exception-list\\.js}\"[^>]*\\bdefer\\b[^>]*>");
        assertThat(resource("static/js/features/exception/exception-list.js"))
                .containsPattern("(?s)row\\.addEventListener\\(\"keydown\".*?if \\(event\\.target\\.closest\\(\"a\"\\)\\) return;.*?event\\.preventDefault\\(\\)")
                .contains("apiClient.request")
                .doesNotContain("fetch(");
        // VRUN-W02 는 IF-API-50 체크리스트를 연동하고, IF-API-51 연결 전까지 확정 버튼을
        // 정적 disabled 로 유지한다.
        assertThat(resource("templates/vrun/detail.html"))
                .contains("id=\"cond-body\" aria-live=\"polite\"")
                .contains("data-checklist-passed=\"false\"")
                .doesNotContain("확정 조건 API 연동 대기")
                .containsPattern("(?s)<button[^>]*id=\"btn-finalize\"[^>]*\\bdisabled\\b[^>]*>");
        assertThat(resource("static/js/features/vrun/vrun-detail.js"))
                .contains("/finalize-checklist")
                .contains("safeInternalLink")
                .contains("checklist.conditions.length !== 6")
                .contains("finalizeButton.dataset.checklistPassed = String(checklist.passed)")
                .doesNotContain("fetch(");
    }

    @Test
    void fixedBusinessNoticesStayAlignedWithScreenSpecification() throws IOException {
        assertThat(resource("templates/exception/list.html"))
                .contains("정상 건은 여기 오지 않습니다. 여기 있는 건 전부 사람이 봐야 합니다.");
        assertThat(resource("templates/ledger/list.html"))
                .contains("이 원장은 회사의 정식 회계장부가 아닙니다. 정산이 맞는지 확인하려고 FGC가 따로 만드는 보조 장부입니다.");
    }

    @Test
    void productionLayoutLoadsScopedResponsivePublishingStyles() throws IOException {
        assertThat(resource("templates/layout/default.html"))
                .contains("/css/features/publishing.css");

        assertThat(resource("static/css/features/publishing.css"))
                .contains(".app-main > .publishing-page")
                .contains("@media (max-width: 63.9375rem)")
                .contains("@media (max-width: 47.9375rem)")
                .contains("@media (prefers-reduced-motion: reduce)");
    }

    // FGC-UI-POL-W01: 정책 목록은 공통 컴포넌트와 화면 전용 정적 리소스만 사용한다.
    @Test
    void policyListUsesCommonComponentsWithoutInlinePresentation() throws IOException {
        assertThat(resource("templates/policy/list.html"))
                .contains("class=\"page-header policy-page-header\"")
                .contains("class=\"field policy-date-field\"")
                .doesNotContain("class=\"guidance")
                .contains("class=\"tab-list\"")
                .contains("class=\"surface tab-panel policy-tab-panel\"")
                .contains("class=\"data-table-viewport policy-version-table-viewport\"")
                .contains("class=\"data-table policy-version-table\"")
                .contains("type=\"radio\" name=\"policy-version-selection\"")
                .contains("data-policy-select")
                .contains("colspan=\"10\"")
                .contains("class=\"empty-state policy-empty-state\"")
                .doesNotContain("data-policy-row tabindex=")
                .doesNotContain("data-policy-row aria-selected=")
                .doesNotContain("style=")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContainPattern("(?i)<script[\\s>]");

        assertThat(resource("static/js/features/policy/policy-list.js"))
                .contains("new AbortController()")
                .contains("signal: requestController.signal")
                .contains("detailAbortController.abort()")
                .contains("selector.addEventListener(\"change\"")
                .doesNotContain("candidate.setAttribute(\"aria-selected\", isSelected");

        assertThat(resource("templates/layout/default.html"))
                .containsPattern("(?s)<link[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-POL-W01'\\}\"[^>]*th:href=\"@\\{/css/features/policy\\.css\\}\"[^>]*>")
                .containsPattern("(?s)<script[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-POL-W01'\\}\"[^>]*th:src=\"@\\{/js/features/policy/policy-list\\.js\\}\"[^>]*>");

        assertThat(resource("static/css/features/policy.css"))
                .contains(".policy-page")
                .contains(".policy-version-table");
        assertThat(resource("static/js/features/policy/policy-list.js"))
                .contains("[data-policy-tab]")
                .contains("[data-detail-body]");
    }

    @Test
    void auditListUsesCommonComponentsWithoutInlinePresentation() throws IOException {
        assertThat(resource("templates/audit/list.html"))
                .contains("class=\"page-header\"")
                .contains("class=\"filter-bar audit-filter-bar\"")
                .contains("class=\"button button-primary\"")
                .contains("class=\"surface audit-list-panel\"")
                .contains("class=\"data-table-viewport\"")
                .contains("class=\"data-table audit-log-table\"")
                .contains("class=\"empty-state audit-empty-state\"")
                .doesNotContain("style=")
                .doesNotContain("<style>")
                .doesNotContain("<script>");

        assertThat(resource("templates/layout/default.html"))
                .contains("/css/features/audit.css");

        assertThat(resource("static/css/features/audit.css"))
                .contains("@media (max-width: 56.25rem)")
                .doesNotContain("@media (max-width: 71.875rem)");
    }

    @Test
    void contractFormUsesSeparatedApiAndPageScriptsWithoutInlineBehavior() throws IOException {
        assertThat(resource("templates/contract/form.html"))
                .contains("name=\"premiumPerCycleAmount\"")
                .contains("value=\"SINGLE\"")
                .contains("data-field-error=\"organizationId\"")
                .doesNotContain("style=\"")
                .doesNotContain("onclick=")
                .doesNotContain("<script>");

        assertThat(resource("static/js/features/contract/contract-api.js"))
                .contains("getProductOfferings")
                .contains("createContract")
                .contains("updateContract")
                .doesNotContain("fetch(");
        assertThat(resource("static/js/features/contract/contract-form.js"))
                .contains("window.FgcUi.contractApi")
                .contains("error.field")
                .doesNotContain("fetch(");
    }

    @Test
    void baseReferenceScreenUsesSeparatedApiAndPageScripts() throws IOException {
        assertThat(resource("templates/base/index.html"))
                .contains("class=\"page-header base-page-header\"")
                .contains("class=\"guidance guidance-neutral base-information-banner\"")
                .contains("class=\"tab-list\"")
                .contains("class=\"surface tab-panel base-panel\"")
                .contains("class=\"filter-bar base-filter-form\"")
                .contains("class=\"data-table base-table")
                .contains("data-base-tab=\"organization\"")
                .contains("data-base-tab=\"product\"")
                .contains("data-base-tab=\"agent\"")
                .contains("data-base-form=\"commission-item\"")
                .contains("data-base-body=\"commission-item\"")
                .contains("지급/차감")
                .contains("적용 시작일")
                .contains("적용 종료일")
                .contains("status-badge-success")
                .doesNotContain("<script>")
                .doesNotContain("style=\"")
                .doesNotContain("onclick=\"");

        assertThat(resource("static/js/features/base/base-api.js"))
                .contains("/api/v1/base/organizations")
                .contains("/api/v1/base/insurers")
                .contains("/api/v1/base/products")
                .contains("/api/v1/base/agents")
                .contains("/api/v1/base/commission-items");

        assertThat(resource("static/js/features/base/base-list.js"))
                .contains("window.FgcUi.baseApi")
                .contains("new AbortController()")
                .contains("organizationOptionsRequestId")
                .contains("organizationOptionsInitialized")
                .contains("getCommissionItems")
                .contains("PAYMENT: [\"지급\", \"status-badge-success\"]")
                .contains("DEDUCTION: [\"차감\", \"status-badge-warning\"]")
                .contains("SETTLEMENT_SUPPORT: \"정착지원\"")
                .contains("NEWCOMER_SUPPORT: \"신인지원\"")
                .contains("aria-busy")
                .doesNotContain("fetch(");

        assertThat(resource("templates/layout/default.html"))
                .contains("/css/features/base.css")
                .contains("/js/features/base/base-api.js")
                .contains("/js/features/base/base-list.js");

        assertThat(resource("static/css/features/base.css"))
                .contains(".base-page")
                .contains("@media (max-width: 47.9375rem)");
    }

    private static String resource(String path) throws IOException {
        try (var input = PublishingTemplateStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
