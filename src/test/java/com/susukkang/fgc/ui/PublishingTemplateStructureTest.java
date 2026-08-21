package com.susukkang.fgc.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublishingTemplateStructureTest {

    private static final List<String> PUBLISHED_TEMPLATES = List.of(
            "templates/base/index.html",
            "templates/contract/list.html",
            "templates/contract/detail.html",
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
            "templates/audit/list.html",
            "templates/error/403.html",
            "templates/error/404.html",
            "templates/error/500.html"
    );

    /*
     * publishing.css 브리지 스코프에 아직 의존하는 화면.
     *
     * .publishing-page 안에서만 fgc-* 변수가 새 디자인 토큰으로 재매핑되므로(publishing.css:19-29),
     * 레거시 클래스가 남아 있는 동안은 이 클래스가 반드시 있어야 목업 원본 팔레트로 떨어지지 않는다.
     * 공통 컴포넌트 전환이 끝난 화면은 이 목록에서 빼고 아래 FULLY_MIGRATED_TEMPLATES 로 옮긴다
     * — 그래야 화면 하나를 전환할 때 다른 화면 담당자의 테스트가 함께 깨지지 않는다 (#138).
     */
    private static final List<String> BRIDGE_SCOPED_TEMPLATES = List.of();

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
            "templates/contract/list.html",
            "templates/contract/detail.html",
            "templates/contract/form.html",
            "templates/transaction/list.html",
            "templates/transaction/form.html",
            "templates/schedule/list.html",
            "templates/schedule/detail.html",
            "templates/ledger/list.html",
            "templates/exception/list.html",
            "templates/vrun/list.html",
            "templates/vrun/detail.html",
            "templates/arbitrage/list.html",
            "templates/error/403.html",
            "templates/error/404.html",
            "templates/error/500.html"
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
                // 확정 확인이 브라우저 기본 대화상자에서 공통 Modal 로 바뀌었다 (#286).
                // 기본 대화상자는 화면정의서 :1487 의 송금·회계 마감 아님 문구를 담을 수 없다.
                .contains("modal.open(\"vrun-finalize\")")
                .doesNotContain("window.confirm")
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
    void ledgerUsesCommonPageShellFormattingAndAccessibleAjaxStates() throws IOException {
        String template = resource("templates/ledger/list.html");

        // 페이지 셸 전환 (#287) — 기능 훅은 유지하고 레거시 클래스·인라인 표현은 다시 들어오지 못하게 한다.
        assertThat(template)
                .contains("class=\"page-content ledger-page\"")
                .contains("class=\"page-header\"")
                .contains("class=\"page-title\"")
                .contains("class=\"guidance guidance-neutral\" aria-labelledby=\"notice-ledger\"")
                .contains("id=\"imbalance-banner\" role=\"alert\"")
                .contains("id=\"balance-banner\" role=\"status\" aria-live=\"polite\"")
                .contains("class=\"filter-bar ledger-filter-bar\"")
                .contains("class=\"filter-fields ledger-filter-fields\"")
                .contains("class=\"surface\"")
                .contains("class=\"data-table-viewport ledger-table-viewport\"")
                .contains("class=\"data-table ledger-table\"")
                .contains("<caption class=\"visually-hidden\">")
                .contains("<th scope=\"col\">")
                .contains("class=\"table-cell-disclosure\"")
                .contains("class=\"pagination-controls ledger-pagination\"")
                .contains("aria-label=\"검증원장 페이지 이동\"")
                .contains("aria-selected=\"false\"")
                // Modal 은 이미 공통 구조를 쓰고 있으므로 data-* 계약까지 함께 고정한다.
                .contains("data-modal=\"journal-reverse\" data-close-on-backdrop=\"true\"")
                .contains("data-modal-initial-focus")
                .contains("data-modal-close")
                .doesNotContain("class=\"fgc-")
                .doesNotContain("publishing-page")
                .doesNotContain("style=\"")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContainPattern("(?i)<script[\\s>]");

        assertThat(resource("static/css/features/ledger.css"))
                .contains(".ledger-work-grid")
                .contains("grid-template-columns: minmax(0, 1.7fr) minmax(20rem, 0.9fr)")
                .contains("@media (max-width: 63.9375rem)")
                .contains("@media (max-width: 47.9375rem)")
                .contains(".ledger-page :is(a, button, input, select, textarea, summary, [tabindex]):focus-visible")
                .contains("var(--space-")
                .contains("var(--color-")
                .doesNotContainPattern("#[0-9a-fA-F]{3,8}\\b")
                // 공통 컴포넌트의 구현을 화면 CSS 에 복제하지 않는다.
                .doesNotContain("overscroll-behavior-inline");

        assertThat(resource("static/js/features/ledger/ledger.js"))
                .contains("document.querySelector(\".ledger-page\")")
                .contains("format.won(value)")
                .contains("format.isNegative(value)")
                .contains("format.errorText(error, fallback)")
                .contains("function syncTableCellDisclosures()")
                .contains("preview.scrollHeight > preview.clientHeight")
                .contains("document.fonts.ready")
                .contains("row.setAttribute(\"aria-selected\", String(selected))")
                .contains("event.target.closest(\"a, button, details, input, select, textarea\")")
                .contains("detailBody.setAttribute(\"aria-busy\", \"true\")")
                .contains("window.FgcUi.toast(message, \"error\")")
                .contains("sessionStorage.setItem(SUCCESS_TOAST_KEY")
                .contains("window.FgcUi.toast(successMessage, \"success\")")
                .doesNotContain("toLocaleString")
                .doesNotContain("wrapper.style")
                .doesNotContain("className = \"fgc-");
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

    // FGC-UI-TRAN-W01·W02: 지급 목록·등록 화면은 공통 컴포넌트와 TRAN 전용 자산만 사용한다.
    @Test
    void transactionScreensUseCommonComponentsAndPreserveCriticalContracts() throws IOException {
        assertThat(resource("templates/transaction/list.html"))
                .contains("class=\"page-content publishing-page transaction-page transaction-list-page\"")
                .contains("class=\"page-header transaction-page-header\"")
                .contains("class=\"filter-bar transaction-filter-bar\"")
                .contains("class=\"surface transaction-list-panel\"")
                .contains("class=\"data-table transaction-list-table\"")
                .contains("class=\"transaction-pagination\"")
                .contains("data-transaction-filter-form")
                .contains("data-transaction-page-numbers")
                .contains("<caption class=\"visually-hidden\"")
                .contains("scope=\"col\"")
                .doesNotContain("schedule-")
                .doesNotContain("page-description")
                .doesNotContain("fgc-page-desc")
                .doesNotContain("style=")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContainPattern("(?i)<script[\\s>]");

        assertThat(resource("templates/transaction/form.html"))
                .contains("class=\"page-content publishing-page transaction-page transaction-form-page\"")
                .contains("data-transaction-form aria-busy=\"true\"")
                .contains("class=\"surface transaction-form-panel\"")
                .contains("class=\"data-table transaction-attribution-table\"")
                .contains("data-modal=\"transaction-confirm\"")
                .contains("확정 후에는 이 화면에서 되돌릴 수 없습니다")
                .contains("초년도 구간은 [계약일, 1주년일)입니다")
                .contains("id=\"transaction-permission-help\"")
                .contains("id=\"err-bizKey\"")
                .contains("id=\"err-attributions\"")
                // 게이트 패널 제목은 화면정의서 :718 "위 6단계" 와 제31조를 그대로 인용한다.
                .contains("확정 게이트 6단계")
                /*
                 * FGC-FUN-034(한도 예외 생성)·FGC-FUN-052(예외함) / REG-08 — 확정 거부 후 후속 동선(#257).
                 * ID 링크는 여전히 만들 수 없다 — 미리보기는 capCheckId 를 내려주지 않고
                 * 확정 실패 응답에도 exceptionCaseId 가 없다.
                 * 대신 EXCP-W01 이 이미 받는 검색조건(type·contractNo)으로 연다.
                 */
                .contains("id=\"transaction-confirm-followup\"")
                .doesNotContain("transaction-confirm-links")
                .doesNotContain("page-description")
                .doesNotContain("fgc-page-desc")
                .doesNotContain("style=")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContainPattern("(?i)<script[\\s>]");

        assertThat(resource("static/css/features/transaction.css"))
                .contains(".transaction-form-layout")
                .contains(".transaction-pagination")
                .contains(".transaction-gate-step")
                .contains("@media (max-width: 63.9375rem)")
                .contains("@media (max-width: 47.9375rem)")
                .contains("var(--color-status-error-solid)")
                .doesNotContainPattern("#[0-9a-fA-F]{3,8}\\b");

        assertThat(resource("static/css/common/components.css"))
                .doesNotContain("[data-transaction-page-numbers]")
                .doesNotContain("[data-transaction-pagination]");

        assertThat(resource("static/js/features/transaction/transaction-list.js"))
                .contains("buildTransactionFilterState(fields, page, attributionImbalanceOnly)")
                .contains("attributionImbalanceOnly: attributionImbalanceOnly === true")
                .contains("format.won(")
                .contains("status-badge ")
                .contains("aria-disabled")
                .contains("다시 시도")
                .doesNotContain("new Intl.NumberFormat")
                .doesNotContain("style.")
                .doesNotContain("fetch(");

        assertThat(resource("static/js/features/transaction/transaction-form.js"))
                .contains("modal.open(\"transaction-confirm\")")
                .contains("modal.close(\"transaction-confirm\")")
                .contains("idempotencyKey: \"TRAN-CONFIRM-\" + paymentId")
                .contains("var GATES = [")
                .contains("renderGates([], null)")
                /*
                 * FGC-FUN-033(1,200% 사전검증)·FGC-FUN-034(한도 예외 생성) / REG-08(초년도 1,200%) — #257.
                 * 규제 판정 차단(FGC-CAP-001·002)은 확정 요청이 서버에 닿아야
                 * cap_check 과 exception_case 가 남는다. 화면이 요청을 막으면 위반 이력이
                 * 영구히 생기지 않아 DASH-W01 위반 KPI 가 항상 0 이 된다.
                 * 입력 오류(FGC-TRAN-*)는 여기 넣지 않는다 — 보내면 DATA_QUALITY 예외만 쌓인다.
                 *
                 * 한도·초년도 경계값 자체는 여기서 검사하지 않는다 — 이 테스트는 소스 문자열
                 * 구조만 본다. 경계는 실 DB 통합테스트가 검증한다:
                 * CapIncludedAmountMapperIntegrationTest(계약일+1년-1일 포함 / +1년 제외),
                 * CapCheckMapperIntegrationTest(1주년 당일 집계 제외).
                 */
                .contains("var RECORDABLE_BLOCKER_CODES = [\"FGC-CAP-001\", \"FGC-CAP-002\"]")
                .contains("hasRecordableBlocker(lastPrecheckResult)")
                .contains("확정 시도 · 예외 등록")
                .contains("renderFollowUpLinks(")
                /*
                 * 확정 게이트는 화면정의서 :710-718 · 운영정책서 제31조의 6단계 그대로다.
                 * 문서에 없는 게이트(차익거래·업무키)를 만들어 넣지 않는다 —
                 * precheck 가 그런 코드를 발행하지 않아 영구히 판정되지 않는 칸이 된다.
                 */
                .contains("{ label: \"작성중(DRAFT) 저장\", codes: [\"FGC-TRAN-005\"] }")
                .contains("{ label: \"귀속행 입력\", codes: [\"FGC-TRAN-002\"] }")
                .contains("{ label: \"귀속합계 = 지급액\", codes: [\"FGC-TRAN-003\"] }")
                .contains("{ label: \"검토필요 귀속 해소\", codes: [\"FGC-CAP-002\"] }")
                .contains("{ label: \"1,200% 사전검증\", codes: [\"FGC-CAP-001\", \"FGC-CAP-003\", \"FGC-CAP-004\"] }")
                .doesNotContain("차익거래 검증")
                .doesNotContain("증빙·업무키 검증")
                /*
                 * blocker 분류는 오류 카탈로그 코드로 한다. Blocker.message 는 부록 A 한글 문구라
                 * 영문 키워드 부분일치는 "FGC-CAP-*" 가 전부 "CAP" 에 걸리는 오분류를 낳았다.
                 */
                .doesNotContain("ATTRIBUTION_REQUIRED")
                .doesNotContain("haystack")
                /*
                 * 계산근거·예외함 링크는 내려줄 ID 가 없어 제거했다 (capCheckId 는 IF-API-24 에서 항상 null).
                 * 동작하지 않는 버튼을 되살리지 않는다.
                 */
                .doesNotContain("configureResultLinks")
                .doesNotContain("transaction-cap-link")
                .doesNotContain("transaction-exception-link")
                .contains("format.today().slice(0, 7)")
                .contains("format.won(")
                .contains("showValidationError")
                .doesNotContain("new Intl.NumberFormat")
                .doesNotContain("statusStyle(")
                .doesNotContain("statusColor(")
                .doesNotContain("style.")
                .doesNotContain("fetch(");
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
        // 페이지 셸 전환 (#290) — 전에는 셸이 레거시인 채로 이 테스트를 통과했다.
        assertThat(resource("templates/exception/list.html"))
                .contains("class=\"page-content exception-page\"")
                .contains("class=\"page-header\"")
                .contains("class=\"page-title\"")
                .contains("class=\"surface\"")
                .contains("class=\"filter-bar exception-filter-bar\"")
                .contains("class=\"filter-field-label\"")
                .contains("class=\"data-table-viewport exception-table-viewport\"")
                .contains("class=\"data-table exception-table\"")
                .contains("class=\"empty-state")
                // 상세 원인 컬럼에도 disclosure 를 넣었다 (PR #269 리뷰 지적)
                .contains("th:text=\"${c.reasonLabel()} ?: '-'\"")
                // 필터·행 선택 계약은 골격이 바뀌어도 그대로여야 한다
                .contains("data-selected-exception-id=${selectedExceptionId}")
                .contains("id=\"summary-cards\"")
                .contains("id=\"f-type\"")
                .contains("id=\"f-reason\"")
                .contains("id=\"f-reset\"")
                // select 변경만으로 제출되던 동작을 조회 버튼으로 바꿨다
                .doesNotContain("onchange=")
                .doesNotContain("class=\"fgc-")
                .doesNotContain("publishing-page")
                .doesNotContain("style=\"")
                .doesNotContainPattern("(?i)<style[\s>]")
                .doesNotContainPattern("(?i)<script[\s>]");

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
                .contains("class=\"modal-backdrop journal-correction-backdrop\"")
                .contains("data-modal=|journal-correction-${c.exceptionCaseId()}|")
                .contains("class=\"modal modal-large publishing-modal journal-correction-modal\"")
                .contains("class=\"journal-correction-compare-grid\"")
                .contains("data-journal-correction-open")
                .contains("data-correction-read-only")
                .contains("data-correction-read-only-notice")
                .contains("data-close-on-backdrop=\"true\" hidden aria-hidden=\"true\"")
                .doesNotContain("class=\"fgc-page-desc\"")
                .doesNotContain("class=\"fgc-banner")
                .doesNotContain("<th style=\"width:");

        assertThat(resource("static/css/features/exception.css"))
                // 공통 data-table-viewport 를 복제하던 정의를 걷어냈다 (가이드 §4.2)
                .doesNotContain("overscroll-behavior-inline")
                .doesNotContain(".publishing-page .exception-table")
                .contains(".exception-page")
                .contains(".exception-filter-fields")
                .contains(".exception-col-title")
                .contains(".journal-correction-original-lines")
                .contains(".journal-correction-original-line.is-heading")
                .contains(".journal-correction-modal")
                .contains(".journal-correction-compare-grid")
                .contains("width: auto")
                .contains("table-layout: fixed")
                .doesNotContain("width: 22rem")
                .doesNotContain("min-width: 22rem")
                .doesNotContain("max-width: 22rem");

        assertThat(resource("static/js/features/exception/exception-list.js"))
                .contains("STATUS_BADGE_CLASSES")
                .contains("badge.classList.remove(...STATUS_BADGE_CLASS_NAMES)")
                .contains("renderOriginalJournal(originalContainer, journal)")
                .contains("date.textContent = `분개일 ${journal.journalDate}`")
                .contains("(journal.lines || []).forEach((line) =>")
                .contains("journal.balanced ? \"차변·대변 검증 통과\"")
                .contains("journal.repostedJournalHeaderId")
                .contains("repostedLink.href = `/journals?selected=${journal.repostedJournalHeaderId}`")
                .contains("closest(\"a, button, details, input, select, textarea\")")
                .contains("preview.scrollWidth > preview.clientWidth")
                .contains("preview.scrollHeight > preview.clientHeight")
                .contains("document.fonts.ready")
                .contains("toast(message, \"error\")")
                // 표시 형식과 오류코드·요청 ID 를 공통 유틸로 통일했다 (#290)
                .contains("format.dateTime(value)")
                .contains("format.errorText(error, fallback)")
                .doesNotContain("Number(journal.debitTotal).toLocaleString")
                .contains("action.actionType === \"START_REVIEW\"")
                .contains("action.actionType === \"REOPEN\"")
                .contains("action.toStatus === \"IN_REVIEW\"")
                .contains("setJournalCorrectionMode")
                .contains("openButton.hidden = status === \"NEW\"")
                .contains("option.dataset.supportsRejected === \"true\"")
                .contains("window.FgcUi.modal.open(`journal-correction-${form.dataset.exceptionId}`)")
                .doesNotContain("window.location.assign(`/exceptions?selected=${form.dataset.exceptionId}`)");

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
                .contains(".reco-col-action { width: 4rem; }")
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

    /*
     * FGC-UI-CONT-W01 보험계약 목록 (#283) — FGC-FUN-018 (화면정의서 화면목록표 CONT-W01 행).
     * 자체 필터 카드·표 뷰포트를 공통 컴포넌트로 바꾸고, 계약상태·데이터 출처를 배지로 세웠다.
     */
    @Test
    void contractListUsesCommonFilterTableAndStatusBadges() throws IOException {
        assertThat(resource("templates/contract/list.html"))
                .doesNotContain("page-description")
                .doesNotContain("contract-filter-grid")
                .doesNotContain("contract-premium-help")
                .contains("class=\"filter-bar contract-filter-bar\"")
                .contains("class=\"filter-fields contract-filter-fields\"")
                .contains("class=\"filter-field\"")
                .contains("class=\"filter-field-label\"")
                .contains("class=\"filter-actions contract-filter-actions\"")
                .contains("class=\"data-table-viewport contract-table-viewport\" tabindex=\"0\" role=\"region\"")
                // 계약상태는 상태에 따라 색이 갈려야 한다 — 예전에는 전부 neutral 이었다.
                .contains("status-badge-success")
                .contains("status-badge-warning")
                .contains("status-badge-error")
                .contains("status-badge-info")
                // 데이터 출처는 CONT-W02 와 같은 배지 표현을 쓴다.
                .contains("status-badge status-badge-neutral\" th:text=\"${dataOriginLabels[contract.dataOrigin]}\"")
                .contains("data-disclosure")
                // 처리 버튼 검증(ContractViewControllerTest·ScreenViewControllerTest)이 의존한다.
                .contains("data-fgc-action=\"create\"")
                .doesNotContain("publishing-page")
                .doesNotContain("style=\"")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContainPattern("(?i)<script[\\s>]");

        assertThat(resource("static/js/features/contract/contract-list.js"))
                .contains("format.today()")
                .contains("table-cell-disclosure")
                .contains("preview.scrollWidth > preview.clientWidth")
                .contains("document.fonts.ready")
                .contains("contract-export-button")
                .contains("CSV 파일을 내려받았습니다.")
                .contains("aria-busy");

        assertThat(resource("templates/layout/default.html"))
                .containsPattern("(?s)<script[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-CONT-W01'\\}\"[^>]*"
                        + "th:src=\"@\\{/js/features/contract/contract-list\\.js}\"[^>]*\\bdefer\\b[^>]*>");
    }

    /*
     * FGC-UI-CONT-W02 보험계약 상세 (#283) — FGC-FUN-018·032·035·036·063, REG-08·12 (화면정의서 화면목록표 CONT-W02 행).
     * 인라인 <script> 321줄을 contract-detail.js 로 뺐다. layout 의 page(...) fragment 는
     * ~{::main} 만 삽입하므로 <main> 밖 <script>는 사라진다 — 인라인이 되살아나면 안 된다.
     */
    @Test
    void contractDetailMovesPageBehaviourOutOfTheTemplate() throws IOException {
        assertThat(resource("templates/contract/detail.html"))
                .doesNotContainPattern("(?i)<script[\\s>]")
                .doesNotContain("style=\"")
                .doesNotContain("publishing-page")
                // 공통 탭 컴포넌트
                .contains("class=\"tab-list contract-detail-tabs\" role=\"tablist\"")
                .contains("class=\"tab-button contract-detail-tab\"")
                .doesNotContain("contract-detail-tab-list")
                // 탭 패널은 키보드로 도달 가능해야 하고, 헤더는 교체 대상 밖에 있어야 한다.
                .contains("role=\"tabpanel\"")
                .contains("data-tab-body")
                .contains("data-tab-state")
                // 화면정의서 CONT-W02 ① 요약 헤더 필수 항목
                .contains("id=\"contract-summary-insurer\"")
                .contains("id=\"contract-summary-agent\"")
                .contains("id=\"contract-summary-organization\"")
                // 기본정보 탭 계약상태·데이터 출처도 배지
                .contains("<span id=\"contract-info-status\" class=\"status-badge")
                .contains("<span id=\"contract-info-origin\" class=\"status-badge")
                // REG-08 — 두 규제를 더하지 말라는 경고는 회색 소문단이 아니라 경고 톤이다.
                .contains("class=\"guidance guidance-warning contract-tab-guidance\"")
                .contains("두 규제를 더하지 마세요")
                // 라벨은 서버 enum label() 하나만 쓴다.
                .contains("data-label-group=\"contractStatus\"")
                .contains("data-label-group=\"paymentCycle\"")
                // 1,200% 와 차익거래 판정은 그룹을 나눈다. 둘 다 REVIEW_REQUIRED 를 쓰는데
                // 라벨이 "검토필요"·"자료부족" 으로 달라, 합치면 labelMap() 이 뒤엣것으로 덮어써
                // 1,200% 탭이 화면정의서 4-2 대조표(:277 검토필요)와 다른 라벨을 표시한다.
                .contains("data-label-group=\"capResultStatus\"")
                .contains("data-label-group=\"arbitrageResultStatus\"")
                .doesNotContain("data-label-group=\"resultStatus\"")
                // 구현이 끝난 탭에 "API 연동 대기" 를 남겨 두지 않는다.
                .doesNotContain("API 연동 대기")
                .doesNotContain("연동 대기 상태로 표시됩니다")
                .contains("data-fgc-action=\"recheck\"")
                .contains("data-fgc-action=\"regenerate\"")
                .contains("data-fgc-action=\"edit\"");

        assertThat(resource("static/js/features/contract/contract-detail.js"))
                .contains("window.FgcUi.contractDetail")
                .contains("STATUS_BADGE_CLASSES")
                // className 통째 덮어쓰기는 병행 클래스를 잃는다 — exception-list.js 와 같은 방식이어야 한다.
                .contains("classList.remove.apply(element.classList, STATUS_BADGE_CLASS_NAMES)")
                .doesNotContain(".className = \"status-badge \"")
                .contains("event.key === \"Home\"")
                .contains("event.key === \"End\"")
                .contains("aria-busy")
                .contains("format.dateTime")
                .doesNotContain("toLocaleString(\"ko-KR\")")
                .doesNotContain("fetch(");

        assertThat(resource("static/js/features/contract/contract-tabs.js"))
                // 탭 제어를 두 파일이 각각 하지 않는다.
                .doesNotContain("querySelectorAll(\".contract-detail-tab\")")
                .contains("contract:tab-activate")
                .contains("detail.contractId")
                // 패널 전체가 아니라 본문만 교체해야 헤더가 남는다.
                .doesNotContain("panel.replaceChildren(")
                .contains("[data-tab-body]")
                .contains("visually-hidden")
                .contains("th.scope = \"col\"")
                .contains("detail.badgeClass(status)")
                .contains("format.won")
                // 라벨 폴백은 판정 종류를 명시해서 읽는다 — 그룹을 안 주면 두 판정이 섞인다.
                .contains("statusBadge(row.resultStatusLabel, row.resultStatus, \"capResultStatus\")")
                .contains("statusBadge(row.resultStatusLabel, row.resultStatus, \"arbitrageResultStatus\")")
                .doesNotContain("codeLabel(\"resultStatus\"")
                .doesNotContain("fetch(");

        assertThat(resource("templates/layout/default.html"))
                .containsPattern("(?s)<script[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-CONT-W02'\\}\"[^>]*"
                        + "th:src=\"@\\{/js/features/contract/contract-detail\\.js}\"[^>]*\\bdefer\\b[^>]*>");
        // contract-tabs.js 는 contract-detail.js 가 올려 둔 전역을 읽는다 — 순서가 바뀌면 탭이 죽는다.
        String layout = resource("templates/layout/default.html");
        assertThat(layout.indexOf("contract/contract-detail.js"))
                .isLessThan(layout.indexOf("contract/contract-tabs.js"));
    }

    /*
     * FGC-UI-CONT-W03 보험계약 등록·수정 (#283) — FGC-FUN-018·036·040 (화면정의서 화면목록표 CONT-W03 행), IF-API-18·19.
     * 인라인 동작을 contract-api.js · contract-form.js 로 분리한 상태를 고정한다.
     */
    @Test
    void contractFormUsesSeparatedApiAndPageScriptsWithoutInlineBehavior() throws IOException {
        assertThat(resource("templates/contract/form.html"))
                .contains("name=\"premiumPerCycleAmount\"")
                .contains("cycle.name() != 'OTHER'")
                .contains("data-field-error=\"organizationId\"")
                .doesNotContain("page-description")
                // 브리지 스코프를 뗐다 — 레거시 fgc-* 가 0건이라 재매핑이 필요 없다.
                .doesNotContain("publishing-page")
                // 설명 Guidance 는 지우되 규제 근거와 필수 입력 안내는 자리를 옮겨 유지한다 (가이드 §7.2).
                .doesNotContain("contract-form-guidance")
                .doesNotContain("contract-action-guidance")
                .contains("id=\"organization-helper\"")
                .contains("aria-describedby=\"contract-save-hint\"")
                .contains("REG-07")
                .contains("REG-08")
                .contains("REG-12")
                .contains("REG-19")
                .contains("class=\"evidence\" data-evidence")
                // 납입주기·계약상태 라벨은 서버 enum 하나만 쓴다.
                .contains("T(com.susukkang.fgc.contract.domain.PaymentCycleCode).values()")
                .contains("T(com.susukkang.fgc.contract.domain.ContractStatus).values()")
                .doesNotContain("3개월납")
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
                /*
                 * 주기 보험료는 입력 필드다 — 화면정의서 CONT-W03 항목표(:619)
                 * "주기 보험료 | 입력 | 금액 | O | 0 이상", 그리고 :550 "premium_per_cycle_amount 와
                 * monthly_equivalent_first_premium 은 다른 값입니다".
                 * 자동 계산은 비어 있을 때 채우는 데까지만 하고 저장값·사용자 입력을 덮어쓰지 않는다.
                 */
                .doesNotContain("premiumPerCycleAmount.readOnly")
                .contains("isPremiumPerCycleUserValue")
                // 기준정보 로드 실패는 Toast, 필드 오류는 필드 옆 (가이드 §11).
                .contains("function showLoadFailure(")
                .contains("format.errorText")
                // 저장 성공 안내는 상세 진입 후에 뜬다 — redirect 로 사라지면 안 된다.
                .contains("fgc.contract.saveMessage")
                .contains("scheduleHeaderIds")
                .contains("format.today()")
                .contains("aria-busy")
                /*
                 * 등록(POST)·수정(PUT) 두 경로 모두 이동이 시작될 때까지 프라미스를 붙들어야 한다.
                 * 반환하지 않으면 finally 가 먼저 돌아 저장 버튼이 다시 열리고 두 번째 요청이 나간다.
                 */
                .contains("function redirectAfter(")
                .contains("if (isEditMode) return redirectAfter(0, redirect);")
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

    /*
     * 오류 화면 3종(#291).
     *
     * 전환 전에는 publishing-page 클래스가 없어 publishing.css 의 토큰 재매핑 스코프 밖이었다 —
     * 그래서 fgc-* 레거시 클래스가 assets/fgc.css 의 원본 HEX 팔레트를 그대로 써서 업무 화면과 색이
     * 어긋났다. 공통 컴포넌트로 옮기면 브리지 스코프 자체가 필요 없어지므로 여기서는
     * publishing-page 를 요구하지 않고 레거시가 되돌아오지 않는 것만 막는다.
     */
    @Test
    void errorScreensUseCommonComponentsAndShowCodeWithTraceId() throws IOException {
        for (String resource : List.of(
                "templates/error/403.html", "templates/error/404.html", "templates/error/500.html")) {
            assertThat(resource(resource))
                    .as(resource)
                    .contains("class=\"page-content error-page\"")
                    .contains("class=\"surface error-card\"")
                    .contains("class=\"surface-header\"")
                    .contains("class=\"surface-title\"")
                    .contains("class=\"surface-body error-card-body\"")
                    .contains("class=\"button button-secondary error-action\"")
                    // 추적 ID 는 세 화면 모두 서버 모델(FgcErrorAttributes)에서 온다.
                    // 표현식 모양은 화면마다 다르므로(500 은 문구 안 치환) 정확한 형태는 아래에서 따로 본다.
                    .contains("requestId")
                    .doesNotContain("fgc-main")
                    .doesNotContain("fgc-card")
                    .doesNotContain("fgc-btn")
                    .doesNotContain("fgc-help")
                    .doesNotContain("style=")
                    .doesNotContainPattern("(?i)<style[\\s>]")
                    .doesNotContainPattern("(?i)<script[\\s>]");
        }

        /*
         * 오류코드는 3-2 오류코드 표준 매핑표(05_인터페이스정의서 :242~261)에 등재된 것만 화면에 띄운다.
         *
         * 403(FGC-AUTH-003)·500(FGC-COMMON-500)은 표에 있다. 404 는 표에 **없다** —
         * FgcErrorCode.COMMON_004 는 enum 에만 있고 3-1 절(:205)의 404 는 "그 ID 의 자료가 없음"
         * 이라, 경로 자체가 없는 이 화면에 재사용하면 한 코드가 문구 둘을 갖게 되어 SIR-007
         * 규칙 3 을 오히려 어긴다. 그래서 404 는 코드 없이 요청 ID 만 보여준다
         * (2026-08-20 PR #313 리뷰 판정). 표에 "경로 없음" 코드가 등재되면 그때 되살린다.
         */
        assertThat(resource("templates/error/403.html"))
                .contains("${errorCode}")
                .contains("' · 요청 ID: ' + ${requestId}");
        assertThat(resource("templates/error/404.html"))
                .contains("'요청 ID: ' + ${requestId}")
                .doesNotContain("${errorCode}")
                .doesNotContain("FGC-COMMON-004");
        // 500 은 error.common.internal 문구가 이미 {requestId} 를 품고 있어 코드만 덧붙인다
        // (format.js:errorText() 의 중복 방지 규칙과 같은 처리).
        assertThat(resource("templates/error/500.html"))
                .contains("${errorCode}")
                .contains("'{requestId}', requestId ?: '-'");

        // SIR-007 규칙 4 — 화면 문구는 전부 메시지 키로. 하드코딩이 되돌아오면 여기서 걸린다.
        assertThat(resource("templates/error/403.html"))
                .contains("#{error.auth.forbidden}")
                .contains("#{error.auth.forbiddenHelp}");
        assertThat(resource("templates/error/404.html"))
                .contains("#{error.common.pageNotFound}")
                .contains("#{error.common.pageNotFoundHelp}");

        assertThat(resource("templates/layout/default.html"))
                .contains("th:if=\"${#strings.startsWith(screenId, 'FGC-UI-ERR-')}\" th:href=\"@{/css/features/error.css}\"");

        assertThat(resource("static/css/features/error.css"))
                .contains(".error-page")
                .contains(".error-card")
                .contains(".error-trace")
                .contains("var(--space-")
                .contains("var(--color-text-tertiary)")
                .doesNotContain("var(--fgc-");
    }

    /*
     * AUTH-W01 회귀 방지(#291) — 이미 공통 컴포넌트로 전환이 끝난 화면이라 현재 상태를 고정만 한다.
     *
     * 로그인은 앱 셸(layout/default :: page) 밖이 의도된 설계다. 그래서 id="main-content" 도
     * toast-region 도 없다 — 인라인 role="alert" 하나로 충분하고, 셸로 끌어들이면 미인증 화면이
     * 사이드바·워크스페이스 탭 스크립트를 함께 로드하게 된다.
     *
     * 오류코드·추적 ID 를 찍지 않는 것도 의도다 — 부록 A FGC-AUTH-001 은 "아이디 또는 비밀번호가
     * 맞지 않습니다." 한 줄만 규정하고, 실패 사유를 갈라 보여주면 계정 존재 여부가 새어 나간다.
     */
    @Test
    void loginScreenKeepsCommonComponentsAndHidesAuthFailureDetail() throws IOException {
        assertThat(resource("templates/auth/login.html"))
                .contains("class=\"field auth-field\"")
                .contains("class=\"field-label\"")
                .contains("class=\"field-control auth-control\"")
                .contains("class=\"button button-primary button-large auth-submit\"")
                .contains("class=\"icon-button auth-password-toggle\"")
                .contains("aria-pressed=\"false\"")
                .contains("role=\"alert\" aria-live=\"polite\"")
                // 부록 A FGC-AUTH-001 표준 문구를 메시지 키로 가져온다 (SIR-007 규칙 4).
                .contains("#{error.auth.invalidCredentials}")
                // 2026-08-20 팀 결정 — 브랜드 패널의 기능 소개 리스트와 접속 보안 안내 패널을 걷어냈다.
                // 화면정의서 AUTH-W01 "화면 구성" 은 ①시스템명 ②폼 ③실패 안내 ④푸터 넷만 규정하고
                // 이 둘은 규정에 없던 장식이었다. 30분 세션은 만료 시 FGC-AUTH-002 문구로 안내하는 것이
                // 규정된 경로이고(인터페이스정의서 2-1-1·3-2), 로그인 화면 사전 고지는 요구되지 않는다.
                .doesNotContain("auth-capability")
                .doesNotContain("auth-security-notice")
                .doesNotContain("30분간 사용하지 않으면 자동으로 로그아웃됩니다.")
                .doesNotContain("fgc-")
                .doesNotContain("toast-region")
                .doesNotContain("FGC-AUTH-")
                .doesNotContain("requestId")
                .doesNotContain("style=")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContain("<script>");

        assertThat(resource("static/js/features/auth/login.js"))
                // 비밀번호 표시 토글은 aria-pressed·aria-label·아이콘을 함께 갱신한다.
                .contains("passwordToggle.setAttribute(\"aria-pressed\", String(willShow))")
                .contains("passwordToggle.setAttribute(\"aria-label\", willShow ? \"비밀번호 숨기기\" : \"비밀번호 표시\")")
                .doesNotContain("FGC-AUTH-")
                .doesNotContain("fetch(");
    }

    /*
     * VRUN-W01·W02 월 통합검증 화면 (#286).
     *
     * 두 화면 모두 레거시 골격 그대로였다. 회귀 방지가 특히 중요한 두 가지를 인수조건으로 고정한다.
     *   · 비가역인 확정을 브라우저 기본 대화상자로 확인했다 — 규제 고정 문구를 담을 수 없다
     *   · 확정 성공 Toast 를 띄우자마자 reload() 해서 사용자에게 보이지 않았다
     */
    @Test
    void validationRunScreensUseCommonComponentsWithoutInlinePresentation() throws IOException {
        assertThat(resource("templates/vrun/list.html"))
                .contains("class=\"page-content vrun-page vrun-list-page\"")
                .contains("class=\"filter-bar vrun-filter-bar\"")
                .contains("class=\"data-table-viewport vrun-table-viewport\" tabindex=\"0\" role=\"region\"")
                .contains("<colgroup>")
                .contains("scope=\"col\"")
                .contains("class=\"visually-hidden\">월 통합검증 실행 목록")
                .contains("class=\"table-cell-disclosure\"")
                .contains("class=\"pagination-controls\"")
                .contains("aria-label=\"월 통합검증 실행 목록 페이지\"")
                // PRG 결과는 배너가 아니라 Toast 로 — 문구를 data-* 로 넘긴다
                .contains("data-success-message=${successMessage}")
                .contains("data-error-message=${errorMessage}")
                // 폼 계약은 골격이 바뀌어도 그대로여야 한다
                .contains("id=\"btn-create\"")
                .contains("data-fgc-action=\"create\"")
                .contains("name=\"month\"")
                .contains("name=\"runType\"")
                .contains("name=\"status\"")
                // select 변경만으로 제출되던 동작을 명시적 조회 버튼으로 바꿨다 (LEDG-W01 선례)
                .doesNotContain("onchange=")
                .doesNotContain("class=\"fgc-")
                .doesNotContain("fgc-banner")
                .doesNotContain("publishing-page")
                .doesNotContain("<th style=\"width:")
                .doesNotContain("style=\"");

        assertThat(resource("templates/vrun/detail.html"))
                .contains("class=\"page-content vrun-page vrun-detail-page\"")
                // 확정 확인 모달 — 규제 고정 문구를 담는다
                .contains("data-modal=\"vrun-finalize\"")
                .contains("id=\"btn-finalize-submit\"")
                .contains("data-modal-initial-focus")
                // 확정 완료 모달 — reload 로 사라지던 성공 피드백을 대신한다 (목업 :195-210)
                .contains("data-modal=\"vrun-finalized\"")
                .contains("id=\"btn-finalized-close\"")
                // 결과 요약이 "플레이스홀더" 클래스가 아니라 공통 KPI 카드다
                .contains("class=\"kpi-grid vrun-summary-grid\"")
                .contains("class=\"kpi-card\"")
                // 스텝퍼는 색만으로 상태를 전달하지 않는다 (규칙 4)
                .contains("class=\"vrun-stepper\"")
                .contains("data-step-state")
                // 진행률 % 를 상세에도 표시한다 (화면정의서 :1442)
                .contains("id=\"progress-text\"")
                // 비활성 사유는 title 이 아니라 가시 텍스트 + aria-describedby
                .contains("id=\"finalize-action-note\"")
                .contains("id=\"execute-action-note\"")
                .contains("aria-describedby=\"finalize-action-guide finalize-action-note\"")
                // 기존 구조 테스트가 고정하던 계약은 그대로 유지한다
                .contains("id=\"cond-body\" aria-live=\"polite\"")
                .contains("data-checklist-passed=\"false\"")
                .contains("data-can-finalize=${roleCode == 'GA_ADMIN' or roleCode == 'SYSTEM_ADMIN'}")
                .contains("검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.")
                .containsOnlyOnce("id=\"finalize-action-guide\"")
                .containsOnlyOnce("id=\"btn-finalize\"")
                .doesNotContain("class=\"fgc-")
                .doesNotContain("publishing-result-placeholder")
                .doesNotContain("publishing-page")
                .doesNotContain("style=\"")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContain("<script>");

        // 서버 렌더링 배지 매핑을 한 곳으로 모았다 — 전에는 두 템플릿과 JS 에 4벌이 흩어져 있었다
        assertThat(resource("templates/vrun/status-badge.html"))
                .contains("th:fragment=\"runStatusBadge(status, label)\"")
                .contains("status-badge-error")
                .contains("status-badge-review")
                .contains("status-badge-success")
                .contains("status-badge-info")
                .contains("status-badge-neutral")
                .contains("vrun-badge-lock");

        assertThat(resource("static/js/features/vrun/vrun-detail.js"))
                // JS 배지가 서버 렌더링과 같은 클래스를 쓴다. CREATED 와 RUNNING 을 구분한다
                .contains("STATUS_BADGE_CLASSES")
                .contains("CREATED: \"status-badge-neutral\"")
                .contains("RUNNING: \"status-badge-info\"")
                .contains("statusBadge.classList.remove.apply(statusBadge.classList, STATUS_BADGE_CLASS_NAMES)")
                .doesNotContain("statusBadge.className =")
                // 폴링 오류가 더는 무음이 아니다
                .contains("pollFailures >= 3")
                // 확정 성공은 완료 모달로 알린다 — Toast 직후 reload 하지 않는다
                .contains("modal.open(\"vrun-finalized\")")
                // 비활성 사유는 title 이 아니다
                .doesNotContain("finalizeButton.title =")
                .contains("finalizeNote.textContent = reason");

        assertThat(resource("static/js/features/vrun/vrun-list.js"))
                .contains("main.dataset[attribute]")
                .contains("delete main.dataset[attribute]")
                .contains("flash(\"successMessage\", \"success\", 5000)")
                .contains("flash(\"errorMessage\", \"error\", 0)")
                .doesNotContain("fetch(");

        assertThat(resource("static/css/features/vrun.css"))
                .contains(".vrun-stepper")
                .contains(".vrun-inline-state")
                .contains(".vrun-kv")
                .contains(".vrun-mid-grid")
                // 인라인 <script> 의 resize 리스너를 CSS 미디어쿼리로 대체했다
                .contains("@media (max-width: 63.9375rem)")
                .contains(":focus-visible");

        assertThat(resource("templates/layout/default.html"))
                .contains("th:src=\"@{/js/features/vrun/vrun-list.js}\" defer");
    }

    /*
     * SCHE-W01 예상 스케줄 목록 (#285).
     *
     * 이 화면은 레거시가 이미 0건이었고 남은 것은 화면 설명문·자체 필터 골격·title 안티패턴이었다.
     * 가장 중요한 회귀 방지 대상은 잘린 값을 title 속성으로만 알리던 부분이다 —
     * 가이드 §10.2 가 "잘라 놓거나 title 속성만 제공하면 안 된다" 고 금지한다.
     */
    @Test
    void scheduleListUsesCommonFilterBarAndDisclosureInsteadOfTitleTooltips() throws IOException {
        assertThat(resource("templates/schedule/list.html"))
                .contains("class=\"page-content schedule-list-page\"")
                .contains("class=\"filter-bar schedule-filter-bar\"")
                .contains("class=\"filter-fields schedule-filter-fields\"")
                .contains("class=\"filter-field-label\"")
                .contains("class=\"filter-actions schedule-filter-actions\"")
                // 필터 계약(name·쿼리 파라미터·JS 훅)은 골격이 바뀌어도 그대로여야 한다
                .contains("data-schedule-filter-form")
                .contains("data-schedule-filter-reset")
                .contains("name=\"contractNo\"")
                .contains("name=\"stage\"")
                .contains("name=\"regime\"")
                .contains("name=\"purpose\"")
                .contains("name=\"status\"")
                // 화면 설명문·설명 배너는 지우되 REG-19 근거는 근거 툴팁으로 남긴다
                .contains("id=\"schedule-regime-evidence\"")
                .contains("REG-19 · 적용 체계 판정")
                .doesNotContain("class=\"page-description\"")
                .doesNotContain("schedule-guidance")
                .doesNotContain("publishing-page");

        assertThat(resource("static/css/features/schedule.css"))
                .contains(".schedule-filter-bar")
                .contains(".schedule-filter-fields")
                .contains(":focus-visible")
                // Guidance 를 지우면서 전용 CSS 4블록도 함께 걷어냈다
                .doesNotContain(".schedule-guidance")
                .doesNotContain(".schedule-filter-grid")
                .doesNotContain(".schedule-policy-value");

        assertThat(resource("static/js/features/schedule/schedule-list.js"))
                .contains("table-cell-disclosure")
                .contains("table-cell-preview is-single-line")
                .contains("preview.scrollWidth > preview.clientWidth")
                .contains("format.won(")
                .contains("format.int(")
                .contains("scheduleLabels.statusTone(schedule.status)")
                .contains("scheduleLabels.isLocked(schedule.status)")
                // CSV 는 성공·실패 모두 알린다 — 전에는 location.assign 만 있었다
                .contains("function exportCsv(url)")
                .contains("예상 스케줄 CSV를 내려받았습니다.")
                // src/test/js/schedule-list.test.cjs 가 require 하는 export 계약
                .contains("normalizePage: normalizePage")
                .contains("responseLabel: require(\"./schedule-labels.js\").responseLabel")
                // title 안티패턴과 자체 포맷 구현이 되살아나면 안 된다
                .doesNotContain("policyValue.title")
                .doesNotContain("function formatWon")
                .doesNotContain("function formatInteger");
    }

    /*
     * SCHE-W02 예상 스케줄 상세 (#285).
     *
     * 이 화면에서 가장 실질적인 결함 두 가지를 인수조건으로 고정한다.
     *   · 비가역인 확정에 확인 절차가 없었다 — 가역인 재생성에만 모달이 있었다
     *   · 금액에 소수 4자리를 허용하고 날짜를 YYYY.MM.DD 로 찍고 시간대가 없었다
     */
    @Test
    void scheduleDetailAddsConfirmDialogAndDropsLegacyShell() throws IOException {
        assertThat(resource("templates/schedule/detail.html"))
                .contains("class=\"page-content schedule-detail-page\"")
                .contains("class=\"surface schedule-detail-card\"")
                .contains("class=\"data-table-viewport schedule-line-viewport\" tabindex=\"0\" role=\"region\"")
                .contains("class=\"visually-hidden\">회차별 예상 지급 금액")
                .contains("<colgroup>")
                .contains("scope=\"col\"")
                // 확정 확인 모달 — 재생성 모달과 같은 공통 Modal 계약을 쓴다
                .contains("data-modal=\"schedule-confirm\"")
                .contains("data-close-on-backdrop=\"true\"")
                .contains("role=\"dialog\" aria-modal=\"true\"")
                .contains("id=\"btn-confirm-submit\"")
                .contains("되돌릴 수 없습니다 — 확정된 스케줄은 새 버전으로만 바꿉니다.")
                // 재생성 모달 구조는 그대로 두되 사유 오류 슬롯을 더했다
                .contains("data-modal=\"schedule-regenerate\"")
                .contains("data-modal-initial-focus")
                .contains("id=\"regenerate-reason-error\"")
                .contains("class=\"field-error\"")
                // ruleRef 는 IF-API-28 응답에 이미 있는데 표에 없었다
                .contains(">규칙 ID<")
                // 권한 검증(ScreenViewControllerTest)과 API 훅은 보존
                .contains("id=\"btn-regenerate\"")
                .contains("data-fgc-action=\"regenerate\"")
                .contains("data-fgc-action=\"confirm\"")
                .contains("th:disabled=\"${!canProcess}\"")
                // 비활성 사유는 title 이 아니라 가시 텍스트 + aria-describedby 로 전달한다
                .contains("id=\"schedule-action-note\"")
                .contains("aria-describedby=\"schedule-action-note\"")
                .doesNotContain("class=\"fgc-")
                .doesNotContain("fgc-banner")
                .doesNotContain("style=\"")
                .doesNotContainPattern("(?i)<style[\\s>]")
                .doesNotContain("<script>");

        assertThat(resource("static/js/features/schedule/schedule-detail.js"))
                // 표시 형식은 공통 유틸만 쓴다 (FGC-SIR-008)
                .contains("format.won(")
                .contains("format.date(")
                .contains("format.dateTime(")
                .contains("format.rate(")
                .doesNotContain("maximumFractionDigits")
                .doesNotContain("replace(/-/g")
                .doesNotContain("Intl.")
                // 확정은 모달을 거친다 — 클릭 즉시 POST 가 나가면 안 된다
                .contains("confirmButton.addEventListener(\"click\", openConfirmDialog)")
                .contains("confirmSubmit.addEventListener(\"click\", confirmSchedule)")
                .contains("openModal(\"schedule-confirm\")")
                // 로딩·빈·오류를 클래스로 나누고 오류에는 재시도를 붙인다
                .contains("schedule-inline-state")
                .contains("is-loading")
                .contains("is-empty")
                .contains("is-error")
                .contains("retry.textContent = \"다시 시도\"")
                // 현재 버전은 굵기가 아니라 공통 선택 상태로 표시한다
                .contains("row.className = \"is-selected\"")
                .contains("row.setAttribute(\"aria-current\", \"true\")")
                .doesNotContain("row.style.fontWeight")
                // 라벨·톤은 공용 모듈 한 벌만 쓴다
                .contains("window.FgcUi.scheduleLabels")
                .doesNotContain("SCHEDULE_STATUS_LABELS = {")
                // 업무 판정을 화면에서 다시 계산하지 않는다
                .doesNotContain("contractMonthNo) >= 1")
                // 사용자에게 안 보이는 채널로만 오류를 흘리지 않는다
                .doesNotContain("console.error");

        assertThat(resource("static/js/features/schedule/schedule-labels.js"))
                .contains("module.exports = api")
                .contains("window.FgcUi.scheduleLabels = api")
                .contains("CONFIRMED: \"status-badge-review\"")
                .contains("HOLD: \"status-badge-review\"")
                .contains("CANCELLED: \"status-badge-neutral\"")
                .contains("LOCKED_STATUSES");

        assertThat(resource("static/css/features/schedule.css"))
                .contains(".schedule-detail-kv")
                .contains(".schedule-inline-state")
                .contains(".schedule-line-viewport")
                .contains(".schedule-version-table tbody tr.is-selected")
                .doesNotContainPattern("#[0-9a-fA-F]{3,8}\\b");
    }

    /*
     * 로드 순서 고정 — schedule-list.js · schedule-detail.js 가 schedule-labels.js 의
     * window.FgcUi.scheduleLabels 를 읽는다. 순서가 바뀌면 두 화면이 예외 없이 조용히 멈춘다
     * (두 파일 모두 모듈이 없으면 early return 한다).
     */
    @Test
    void productionLayoutLoadsScheduleLabelsBeforeScheduleScreens() throws IOException {
        String layout = resource("templates/layout/default.html");

        assertThat(layout)
                .contains("th:src=\"@{/js/features/schedule/schedule-labels.js}\" defer");

        assertThat(layout.indexOf("schedule-labels.js"))
                .as("schedule-labels.js 는 schedule-list.js 보다 먼저 등록되어야 한다")
                .isLessThan(layout.indexOf("schedule-list.js"));
        assertThat(layout.indexOf("schedule-labels.js"))
                .as("schedule-labels.js 는 schedule-detail.js 보다 먼저 등록되어야 한다")
                .isLessThan(layout.indexOf("schedule-detail.js"));
    }

    private static String resource(String path) throws IOException {
        try (var input = PublishingTemplateStructureTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
