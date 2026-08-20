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

    /*
     * publishing.css 브리지 스코프에 아직 의존하는 화면.
     *
     * .publishing-page 안에서만 fgc-* 변수가 새 디자인 토큰으로 재매핑되므로(publishing.css:19-29),
     * 레거시 클래스가 남아 있는 동안은 이 클래스가 반드시 있어야 목업 원본 팔레트로 떨어지지 않는다.
     * 공통 컴포넌트 전환이 끝난 화면은 이 목록에서 빼고 아래 FULLY_MIGRATED_TEMPLATES 로 옮긴다
     * — 그래야 화면 하나를 전환할 때 다른 화면 담당자의 테스트가 함께 깨지지 않는다 (#138).
     */
    private static final List<String> BRIDGE_SCOPED_TEMPLATES = List.of(
            "templates/transaction/list.html",
            "templates/transaction/form.html",
            "templates/schedule/detail.html",
            "templates/ledger/list.html",
            "templates/exception/list.html",
            "templates/vrun/list.html",
            "templates/vrun/detail.html"
    );

    /*
     * 공통 컴포넌트 전환이 끝난 화면. 레거시 fgc-* 와 인라인 style 이 남아 있으면 안 된다.
     *
     * publishing-page 클래스는 아직 붙어 있다 — 브리지가 제거되기 전까지는 붙어 있어도 무해하고,
     * 떼는 시점은 assets/fgc.css 정리와 함께 판단한다 (#138 완료 조건).
     */
    private static final List<String> FULLY_MIGRATED_TEMPLATES = List.of(
            "templates/base/index.html",
            "templates/policy/list.html",
            "templates/reco/list.html",
            "templates/audit/list.html",
            "templates/contract/form.html",
            "templates/schedule/list.html",
            "templates/arbitrage/list.html"
    );

    @Test
    void publishedScreensUseProductionShellScopeWithoutPrototypeDependencies() throws IOException {
        for (String resource : PUBLISHED_TEMPLATES) {
            String template = resource(resource);

            assertThat(template)
                    .as(resource)
                    .contains("id=\"main-content\"")
                    .doesNotContain("cdn.tailwindcss.com")
                    .doesNotContain("FGC.SEED");
        }
    }

    @Test
    void screensStillUsingLegacyClassesKeepTheBridgeScope() throws IOException {
        for (String resource : BRIDGE_SCOPED_TEMPLATES) {
            assertThat(resource(resource))
                    .as(resource)
                    .contains("publishing-page");
        }
    }

    @Test
    void migratedScreensDropLegacyClassesAndInlineStyles() throws IOException {
        for (String resource : FULLY_MIGRATED_TEMPLATES) {
            assertThat(resource(resource))
                    .as(resource)
                    .doesNotContain("class=\"fgc-")
                    .doesNotContain("style=\"");
        }
    }

    @Test
    void reconciliationComparisonKeepsPopupPublishingScope() throws IOException {
        assertThat(resource("templates/reco/list.html"))
                .contains("class=\"modal-backdrop reco-compare-backdrop\"");

        assertThat(resource("templates/reco/compare-modal.html"))
                .contains("th:fragment=\"modal\"")
                .contains("class=\"modal modal-large publishing-modal reco-compare-modal\"")
                .contains("role=\"dialog\" aria-modal=\"true\"")
                .contains("class=\"reco-compare-grid\"")
                .contains("class=\"reco-compare-source-list\" id=\"expected-body\"")
                .contains("class=\"reco-compare-source-list\" id=\"actual-body\"")
                .contains("class=\"reco-compare-diff-grid\"")
                .contains("class=\"modal-footer reco-compare-footer\"")
                .contains("class=\"surface reco-compare-panel\"")
                .doesNotContain("class=\"fgc-modal")
                .doesNotContain("class=\"fgc-card")
                .doesNotContain("class=\"fgc-table")
                .doesNotContain("class=\"fgc-banner")
                .doesNotContain("class=\"guidance")
                .doesNotContain("style=")
                .doesNotContain("<main");

        assertThat(resource("static/js/features/reco/reco.js"))
                .contains("class=\"status-badge ")
                .contains("empty-state reco-compare-empty\">예상 없음</div>")
                .contains("empty-state reco-compare-empty\">실제 없음</div>")
                .contains("sourceRow(\"적용 요율\"")
                .contains("sourceRow(\"귀속 ID\"")
                .contains("difference-formula")
                .contains("class=\"button button-secondary\"")
                .doesNotContain("class=\"fgc-btn fgc-btn--ghost\"")
                .doesNotContain("class=\"fgc-th-num\"");
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
                .contains("kpi-card exception-summary-card")
                .contains("kpi-card-header")
                .contains("kpi-value-row")
                .contains("kpi-card-footer")
                .contains("exception-detail-")
                .contains("data-exception-action-form")
                .contains("처리 저장")
                .doesNotContain("유형별 미처리 집계 API 연동 대기");
        assertThat(resource("templates/layout/default.html"))
                .contains("/css/features/exception.css")
                .containsPattern("(?s)<script[^>]*th:src=\"@\\{/js/features/exception/exception-list\\.js}\"[^>]*\\bdefer\\b[^>]*>");
        assertThat(resource("static/js/features/exception/exception-list.js"))
                .containsPattern("(?s)row\\.addEventListener\\(\"keydown\".*?if \\(event\\.target\\.closest\\(\"a, button, details, input, select, textarea\"\\)\\) return;.*?event\\.preventDefault\\(\\)")
                .contains("apiClient.request")
                .doesNotContain("fetch(");
        // 2026-08-19 yslee - #238 병합 후 VRUN-W02 템플릿·스크립트 검증 체인 정리
        // 기존 코드: IF-API-50 전용 검증과 IF-API-50·51 통합 검증이 병합 과정에서 중복됨
        // 문제: 첫 번째 AssertJ 체인의 종결 문자가 유실되어 테스트 소스 컴파일이 실패함
        // 개선: IF-API-50·51 연동 완료 상태를 중복 없는 하나의 템플릿·스크립트 체인으로 검증
        assertThat(resource("templates/vrun/detail.html"))
                .contains("id=\"cond-body\" aria-live=\"polite\"")
                .contains("data-checklist-passed=\"false\"")
                .contains("data-can-finalize=${roleCode == 'GA_ADMIN' or roleCode == 'SYSTEM_ADMIN'}")
                .contains("검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.")
                .containsOnlyOnce("id=\"finalize-action-guide\"")
                .containsOnlyOnce("id=\"btn-finalize\"")
                .doesNotContain("id=\"finalize-actions-pending\"")
                .doesNotContain("확정 조건 API 연동 대기")
                .containsPattern("(?s)<button[^>]*id=\"btn-finalize\"[^>]*\\bdisabled\\b[^>]*>");
        assertThat(resource("static/js/features/vrun/vrun-detail.js"))
                .contains("/finalize-checklist")
                .contains("/finalize\"")
                .contains("safeInternalLink")
                .contains("checklist.conditions.length !== 6")
                .contains("finalizeButton.dataset.checklistPassed = String(checklist.passed)")
                .contains("idempotencyKey: finalizeIdempotencyKey")
                .contains("error.code === \"FGC-VRUN-006\"")
                .contains("finalizeIdempotencyKey = null")
                .contains("runStatus !== \"COMPLETED\"")
                .contains("window.confirm")
                .doesNotContain("fetch(");
    }

    @Test
    void screenGuidanceStaysAlignedWithCurrentUiDecisions() throws IOException {
        assertThat(resource("templates/exception/list.html"))
                .doesNotContain("fgc-page-desc")
                .doesNotContain("fgc-banner")
                .doesNotContain("정상 건은 여기 오지 않습니다.");
        assertThat(resource("templates/ledger/list.html"))
                .contains("이 원장은 회사의 정식 회계장부가 아닙니다. 정산이 맞는지 확인하려고 FGC가 따로 만드는 보조 장부입니다.")
                .contains("data-modal=\"journal-reverse\"")
                .contains("id=\"journal-reverse-reason\"")
                .contains("data-can-reverse=${roleCode == 'SETTLEMENT' or roleCode == 'GA_ADMIN' or roleCode == 'SYSTEM_ADMIN'}")
                .doesNotContain("데이터 렌더링은 IF-API 연동 시 추가");
        assertThat(resource("templates/layout/default.html"))
                .contains("/js/features/ledger/ledger.js");
        assertThat(resource("static/js/features/ledger/ledger.js"))
                .contains("/api/v1/journals/imbalances?validationRunId=")
                .contains("/reverse\"")
                .contains("detail.status === \"POSTED\"")
                .contains("evidenceRef: reverseEvidence.value.trim() || null")
                .doesNotContain("apiClient.request(\"/api/v1/journals?\"")
                .doesNotContain("fetch(");
    }

    @Test
    void ledgerSearchRunsOnlyWhenFilterFormIsSubmitted() throws IOException {
        assertThat(resource("templates/ledger/list.html"))
                .contains("id=\"ledger-filter-form\"")
                .contains("method=\"get\" th:action=\"@{/journals}\"")
                .contains("id=\"f-search\" name=\"searched\" value=\"true\" type=\"submit\"")
                .contains("th:each=\"journal : ${journals.content}\"")
                .contains("조회 조건을 설정하고 조회 버튼을 눌러 주세요.");
        assertThat(resource("static/js/features/ledger/ledger.js"))
                .doesNotContain("form.addEventListener(\"submit\"")
                .doesNotContain("function loadList(")
                .doesNotContain("function query(");
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

    /*
     * #138 선행 정비 — 화면별 이슈가 함께 쓰는 공통 자산이 자리를 잡았는지 확인한다.
     * 이게 없으면 각 화면 담당자가 조건부 링크를 제각각 추가하거나 포맷 유틸을 또 만든다.
     */
    @Test
    void productionLayoutLoadsSharedFormatUtilityAndPendingFeatureStyles() throws IOException {
        String layout = resource("templates/layout/default.html");

        assertThat(layout)
                .containsPattern("(?s)<script[^>]*th:src=\"@\\{/js/common/format\\.js\\}\"[^>]*\\bdefer\\b[^>]*>");

        assertThat(layout)
                .containsPattern("(?s)<link[^>]*th:if=\"\\$\\{#strings\\.startsWith\\(screenId, 'FGC-UI-TRAN-'\\)\\}\"[^>]*th:href=\"@\\{/css/features/transaction\\.css\\}\"[^>]*>")
                .containsPattern("(?s)<link[^>]*th:if=\"\\$\\{#strings\\.startsWith\\(screenId, 'FGC-UI-VRUN-'\\)\\}\"[^>]*th:href=\"@\\{/css/features/vrun\\.css\\}\"[^>]*>")
                .containsPattern("(?s)<link[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-LEDG-W01'\\}\"[^>]*th:href=\"@\\{/css/features/ledger\\.css\\}\"[^>]*>");
        // ARB-W01 의 조건부 링크와 arbitrage.css 는 #295 가 이미 develop 에 넣었다. 여기서 중복 추가하지 않는다.
    }

    /* 같은 도메인 CSS 를 두 번 링크하면 뒤엣것이 앞엣것을 덮어 디버깅이 어려워진다. */
    @Test
    void productionLayoutLinksEachFeatureStylesheetOnce() throws IOException {
        String layout = resource("templates/layout/default.html");

        for (String stylesheet : List.of(
                "audit.css", "arbitrage.css", "base.css", "dashboard.css", "contract.css",
                "schedule.css", "cap.css", "policy.css", "reco.css", "exception.css",
                "transaction.css", "vrun.css", "ledger.css")) {
            assertThat(layout.split("/css/features/" + stylesheet.replace(".", "\\."), -1).length - 1)
                    .as(stylesheet)
                    .isEqualTo(1);
        }
    }

    /*
     * FGC-SIR-008 — 표시 형식을 화면마다 다시 구현하지 않도록 공통 유틸에 계약을 고정한다.
     * 특히 Asia/Seoul 고정과 "화면에서 반올림하지 않는다"(화면정의서 4장 규칙 2)가 지켜져야 한다.
     */
    @Test
    void sharedFormatUtilityFixesDisplayStandardContracts() throws IOException {
        assertThat(resource("static/js/common/format.js"))
                .contains("Asia/Seoul")
                .contains("window.FgcUi.format = format")
                .contains("function errorText(")
                .contains("요청 ID: ")
                .doesNotContain("toFixed(")
                .doesNotContain("Math.round(")
                // 요율 4자리 · 사용률 6자리 (인터페이스정의서 2-4:177). 한 함수로 합치면 CAP 화면이 어긋난다.
                .contains("function rate(")
                .contains("function usageRate(")
                .contains("truncateDecimal(value, 4)")
                .contains("truncateDecimal(value, 6)");
    }

    /*
     * 화면정의서 4장 규칙 5 (docs/FGC_화면정의서_v2_0.md:213) — 요율·한도·판정이 나오는 곳에는
     * 마우스를 올리면 근거가 뜨고, 근거 없는 숫자는 화면에 띄우지 않는다.
     * 즉 근거는 상단 배너가 아니라 값 옆에서 연다.
     * data-table 이 overflow: hidden 이라 popover(top layer)로 두어야 셀 안에서 잘리지 않는다.
     */
    @Test
    void evidencePopoverComponentEscapesTableClipping() throws IOException {
        assertThat(resource("static/css/common/components.css"))
                .contains(".evidence-trigger")
                .contains(".evidence-popover")
                .contains(":popover-open")
                .contains(".data-table td.has-evidence")
                // popover 를 못 쓰는 브라우저에서는 근거가 펼쳐진 채로 남아야 한다.
                .contains(".evidence-popover:not([popover])");
    }

    /*
     * 화면정의서 4장 규칙 5 는 "마우스를 올리면" 이지만, 호버만 지원하면
     * 터치와 키보드에서 근거에 닿을 수 없다. 세 경로가 모두 살아 있어야 한다.
     */
    @Test
    void evidenceOpensOnHoverFocusAndClick() throws IOException {
        assertThat(resource("templates/layout/default.html"))
                .containsPattern("(?s)<script[^>]*th:src=\"@\\{/js/common/evidence\\.js\\}\"[^>]*\\bdefer\\b[^>]*>");

        assertThat(resource("static/js/common/evidence.js"))
                .contains("\"mouseenter\"")
                .contains("\"focusin\"")
                .contains("trigger.addEventListener(\"click\"")
                .contains("showPopover()")
                .contains("--evidence-x")
                .contains("removeAttribute(\"popover\")")
                .doesNotContain("fetch(");
    }

    // FGC-UI-POL-W01: 정책 목록은 공통 컴포넌트와 화면 전용 정적 리소스만 사용한다.
    @Test
    void policyListUsesCommonComponentsWithoutInlinePresentation() throws IOException {
        assertThat(resource("templates/policy/list.html"))
                .contains("class=\"page-header policy-page-header\"")
                .doesNotContain("page-description")
                .doesNotContain("fgc-page-desc")
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
                .contains("class=\"table-cell-disclosure\"")
                .contains("class=\"table-cell-details\" hidden")
                .contains("class=\"table-cell-more\">전체 보기")
                .contains("class=\"table-cell-less\">접기")
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
                .contains("function tableCellDisclosure(")
                .contains("function syncTableCellDisclosures(")
                .contains("preview.scrollWidth > preview.clientWidth")
                .contains("preview.scrollHeight > preview.clientHeight")
                .contains("document.fonts.ready.then")
                .contains("policy-disclosure-cell")
                .doesNotContain("candidate.setAttribute(\"aria-selected\", isSelected");

        assertThat(resource("static/css/common/components.css"))
                .contains(".table-cell-disclosure")
                .contains(".table-cell-details[open] .table-cell-less")
                .contains(".table-cell-full");

        assertThat(resource("templates/layout/default.html"))
                .containsPattern("(?s)<link[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-POL-W01'\\}\"[^>]*th:href=\"@\\{/css/features/policy\\.css\\}\"[^>]*>")
                .containsPattern("(?s)<script[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-POL-W01'\\}\"[^>]*th:src=\"@\\{/js/features/policy/policy-list\\.js\\}\"[^>]*>");

        assertThat(resource("static/css/features/policy.css"))
                .contains(".policy-page")
                .contains(".policy-version-table")
                .contains(".data-table td.policy-disclosure-cell");
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
    void exceptionListUsesFlexibleColumnsSharedBadgesAndProductionToast() throws IOException {
        assertThat(resource("templates/exception/list.html"))
                .contains("<colgroup>")
                .contains("class=\"exception-col-title\"")
                .contains("class=\"status-badge\"")
                .contains("status-badge-warning")
                .contains("status-badge-error")
                .contains("status-badge-review")
                .contains("status-badge-info")
                .contains("status-badge-success")
                .contains("class=\"table-cell-disclosure\"")
                .contains("class=\"table-cell-details\" hidden")
                .contains("class=\"table-cell-more\">전체 보기")
                .contains("class=\"table-cell-less\">접기")
                .contains("referenceValue=|${c.sourceEntityType()}:${c.sourceEntityId()}|")
                .doesNotContain("class=\"fgc-page-desc\"")
                .doesNotContain("class=\"fgc-banner")
                .doesNotContain("<th style=\"width:");

        assertThat(resource("static/css/features/exception.css"))
                .contains(".exception-col-title")
                .contains("width: auto")
                .contains("table-layout: fixed")
                .doesNotContain("width: 22rem")
                .doesNotContain("min-width: 22rem")
                .doesNotContain("max-width: 22rem");

        assertThat(resource("static/js/features/exception/exception-list.js"))
                .contains("STATUS_BADGE_CLASSES")
                .contains("badge.classList.remove(...STATUS_BADGE_CLASS_NAMES)")
                .contains("closest(\"a, button, details, input, select, textarea\")")
                .contains("preview.scrollWidth > preview.clientWidth")
                .contains("preview.scrollHeight > preview.clientHeight")
                .contains("document.fonts.ready")
                .contains("toast(message, \"error\")");

        assertThat(resource("static/css/common/components.css"))
                .contains(".table-cell-disclosure")
                .contains(".table-cell-details[open] .table-cell-less")
                .contains(".table-cell-full");

        assertThat(resource("static/css/common/components.css"))
                .contains("top: calc(var(--layout-header-height) + var(--space-4))")
                .contains("width: min(25rem, calc(100vw - 2rem))")
                .contains("border-radius: var(--radius-12)")
                .contains("box-shadow: var(--shadow-sm)")
                .contains(".toast-icon");

        assertThat(resource("static/js/common/toast.js"))
                .contains("loading: \"progress_activity\"")
                .contains("safeTone === \"error\" || safeTone === \"loading\" ? 0 : 5000")
                .contains("toast.append(iconContainer, content, close)");
    }

    @Test
    void reconciliationListUsesCommonComponentsAndProductionToast() throws IOException {
        assertThat(resource("templates/reco/list.html"))
                .contains("class=\"page-header\"")
                .contains("class=\"filter-bar reco-filter-bar\"")
                .contains("class=\"kpi-grid reco-summary-grid\"")
                .contains("class=\"kpi-card kpi-card-success\"")
                .contains("class=\"surface reco-panel reco-result-panel\"")
                .contains("class=\"data-table reco-result-table\"")
                .contains("class=\"data-table reco-history-table\"")
                .contains("class=\"status-badge\"")
                .contains("class=\"reco-history-row\" tabindex=\"0\"")
                .doesNotContain("class=\"fgc-page-desc\"")
                .doesNotContain("class=\"fgc-banner")
                .doesNotContain("class=\"fgc-kpi")
                .doesNotContain("style=");

        assertThat(resource("templates/layout/default.html"))
                .contains("/css/features/reco.css");

        assertThat(resource("static/css/features/reco.css"))
                .contains(".reco-summary-grid")
                .contains("grid-template-columns: repeat(5, minmax(0, 1fr))")
                .contains(".reco-result-table")
                .contains(".reco-col-action { width: 7rem; }")
                .contains("@media (max-width: 47.9375rem)");

        assertThat(resource("static/js/features/reco/reco.js"))
                .contains("tableCellDisclosure")
                .contains("class=\"table-cell-details\" hidden")
                .contains("table-cell-more\">전체 보기")
                .contains("preview.scrollWidth > preview.clientWidth")
                .contains("preview.scrollHeight > preview.clientHeight")
                .contains("document.fonts.ready")
                .contains("window.FgcUi.toast(")
                .contains("\"success\"")
                .contains("event.key !== \"Enter\" && event.key !== \" \"")
                .doesNotContain("text.length <= limit")
                .doesNotContain("fetch(");
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
                .doesNotContain("page-description")
                .doesNotContain("fgc-page-desc")
                .doesNotContain("class=\"guidance")
                .contains("class=\"tab-list\"")
                .contains("class=\"tab-panel base-panel\"")
                .contains("class=\"filter-bar base-filter-form\"")
                .contains("class=\"filter-actions base-filter-actions\"")
                .contains("class=\"surface base-result-surface\"")
                .contains("class=\"empty-state base-result-message\"")
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
                .contains("class=\"table-cell-disclosure\"")
                .contains("class=\"table-cell-more\">전체 보기")
                .contains("class=\"table-cell-less\">접기")
                .doesNotContain("class=\"surface tab-panel base-panel\"")
                .doesNotContain("base-panel-note")
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
                .contains("function tableCellDisclosure(")
                .contains("tableCellDisclosure(item.itemCode, 18, true)")
                .contains("table-cell-preview")
                .contains("base-disclosure-cell")
                .contains("aria-busy")
                .doesNotContain("fetch(");

        assertThat(resource("static/css/common/components.css"))
                .contains(".table-cell-disclosure")
                .contains(".table-cell-details[open] .table-cell-less")
                .contains(".table-cell-full");

        assertThat(resource("templates/layout/default.html"))
                .contains("/css/features/base.css")
                .contains("/js/features/base/base-api.js")
                .contains("/js/features/base/base-list.js");

        assertThat(resource("static/css/features/base.css"))
                .contains(".base-page")
                .contains(".data-table td.base-disclosure-cell")
                .contains("@media (max-width: 47.9375rem)")
                .doesNotContain(".base-information-banner")
                .doesNotContain(".base-panel-note");
    }

    // FGC-UI-DASH-W01: 업무 대시보드는 공통 컴포넌트만 쓰고, 화면정의서에 없는 "연동 대기" 카드가
    // 남아있지 않아야 한다(#282). 라벨은 템플릿의 T(...).valueOf(...).label() SpEL 반사 호출이 아니라
    // IF-API-03 응답(RecentExceptionResponse/RecentValidationRunResponse)이 서버에서 미리 만들어
    // 내려주는 *Label 필드를 그대로 찍는다 — SIR-008(코드값은 영문 코드+한글 라벨 동봉)이 API 계약
    // 레벨에서 지켜지도록, 화면과 JSON이 같은 라벨을 공유한다.
    @Test
    void dashboardUsesCommonComponentsWithoutStalePlaceholdersOrRawEnumCodes() throws IOException {
        assertThat(resource("templates/dashboard/index.html"))
                .contains("class=\"page-content dashboard-page\"")
                .doesNotContain("page-description")
                .doesNotContain("월별 추세 집계 API 연동 대기")
                .doesNotContain("준비도 집계 API가 아직 제공되지 않습니다")
                .doesNotContain("trend-card")
                .doesNotContain("readiness-card")
                .contains("kpi-card kpi-card-warning")
                .contains("${e.severityLabel()}")
                .contains("${e.exceptionTypeLabel()}")
                .contains("${e.statusLabel()}")
                .contains("${r.statusLabel()}")
                .doesNotContain("T(com.susukkang.fgc.common.code")
                .contains("<th scope=\"col\">유형</th>")
                .contains("class=\"table-cell-disclosure\"")
                .contains("class=\"table-cell-details\" hidden")
                .contains("class=\"table-cell-more\">전체 보기")
                .contains("class=\"table-cell-less\">접기")
                .contains("dashboard-disclosure-cell")
                .doesNotContain("style=")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContainPattern("(?i)<script[\\s>]");

        assertThat(resource("static/css/features/dashboard.css"))
                .doesNotContain(".trend-plot")
                .doesNotContain(".trend-card-legend")
                .doesNotContain(".trend-legend-item")
                .doesNotContain(".trend-legend-swatch")
                .doesNotContain(".progress-bar-error")
                .doesNotContain(".readiness-value")
                .contains(".run-row:focus-visible")
                .contains(".kpi-card:focus-visible")
                .contains(".priority-table td.dashboard-disclosure-cell");

        assertThat(resource("static/js/features/dashboard/dashboard.js"))
                .contains("syncTableCellDisclosures")
                .contains("preview.scrollWidth > preview.clientWidth")
                .contains("preview.scrollHeight > preview.clientHeight")
                .contains("document.fonts.ready")
                .doesNotContain("fetch(");
    }

    private static String resource(String path) throws IOException {
        try (var input = PublishingTemplateStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
