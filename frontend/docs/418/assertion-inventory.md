# 1차 검사 항목 전수 목록

생성: `node scripts/legacy-test-inventory.mjs`. 9개 Java 클래스의 48개 메서드 + node 4개 파일의 17개 테스트.
이 목록은 문자열/정규식/값 검사를 개별 행으로 나열한다. 각 메서드의 대체 테스트·제외 사유·잔여 차이는 [대응표](coverage-map.md)를 함께 읽는다.
반복문에서 검사하는 템플릿 집합과 helper의 맥락은 연결된 원본을 따른다. 항목 수는 실행된 JUnit 테스트 수가 아니다.

## PublishingTemplateStructureTest#publishedScreensUseProductionShellScopeWithoutPrototypeDependencies

<a id="mapping-1"></a>

대체 테스트: SHELL 라우팅·공통 셸.

구조 제외·남은 확인: `th:replace`, production scope, prototype script 경로는 템플릿 전용.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L80](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L80) | `contains("id=\"main-content\"")` | [이 메서드의 대응·제외](#mapping-1) |
| [L81](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L81) | `doesNotContain("cdn.tailwindcss.com")` | [이 메서드의 대응·제외](#mapping-1) |
| [L82](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L82) | `doesNotContain("FGC.SEED")` | [이 메서드의 대응·제외](#mapping-1) |

## PublishingTemplateStructureTest#screensStillUsingLegacyClassesKeepTheBridgeScope

<a id="mapping-2"></a>

대체 테스트: SHELL.

구조 제외·남은 확인: 기존 화면 bridge scope 유지 검사: 해당 템플릿 삭제 후 불필요.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L91](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L91) | `contains("publishing-page")` | [이 메서드의 대응·제외](#mapping-2) |

## PublishingTemplateStructureTest#migratedScreensDropLegacyClassesAndInlineStyles

<a id="mapping-3"></a>

대체 테스트: 화면별 테스트·SHELL.

구조 제외·남은 확인: Thymeleaf class/style 금지 목록은 React에 그대로 이식하지 않음.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L100](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L100) | `doesNotContain("class=\"fgc-")` | [이 메서드의 대응·제외](#mapping-3) |
| [L101](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L101) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-3) |

## PublishingTemplateStructureTest#reconciliationComparisonKeepsPopupPublishingScope

<a id="mapping-4"></a>

대체 테스트: RECO 비교 모달·COMMON 모달 포커스/닫기.

구조 제외·남은 확인: popup 템플릿 scope 제외. 정확한 외형 D3.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L108](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L108) | `contains("class=\"modal-backdrop reco-compare-backdrop\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L111](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L111) | `contains("th:fragment=\"modal\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L112](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L112) | `contains("class=\"modal modal-large publishing-modal reco-compare-modal\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L113](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L113) | `contains("role=\"dialog\" aria-modal=\"true\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L114](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L114) | `contains("class=\"reco-compare-grid\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L115](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L115) | `contains("class=\"reco-compare-source-list\" id=\"expected-body\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L116](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L116) | `contains("class=\"reco-compare-source-list\" id=\"actual-body\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L117](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L117) | `contains("class=\"reco-compare-diff-grid\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L118](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L118) | `contains("class=\"modal-footer reco-compare-footer\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L119](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L119) | `contains("class=\"surface reco-compare-panel\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L120](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L120) | `doesNotContain("class=\"fgc-modal")` | [이 메서드의 대응·제외](#mapping-4) |
| [L121](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L121) | `doesNotContain("class=\"fgc-card")` | [이 메서드의 대응·제외](#mapping-4) |
| [L122](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L122) | `doesNotContain("class=\"fgc-table")` | [이 메서드의 대응·제외](#mapping-4) |
| [L123](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L123) | `doesNotContain("class=\"fgc-banner")` | [이 메서드의 대응·제외](#mapping-4) |
| [L124](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L124) | `doesNotContain("class=\"guidance")` | [이 메서드의 대응·제외](#mapping-4) |
| [L125](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L125) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-4) |
| [L126](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L126) | `doesNotContain("<main")` | [이 메서드의 대응·제외](#mapping-4) |
| [L129](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L129) | `contains("class=\"status-badge ")` | [이 메서드의 대응·제외](#mapping-4) |
| [L130](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L130) | `contains("empty-state reco-compare-empty\">예상 없음</div>")` | [이 메서드의 대응·제외](#mapping-4) |
| [L131](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L131) | `contains("empty-state reco-compare-empty\">실제 없음</div>")` | [이 메서드의 대응·제외](#mapping-4) |
| [L132](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L132) | `contains("sourceRow(\"적용 요율\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L133](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L133) | `contains("sourceRow(\"귀속 ID\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L134](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L134) | `contains("difference-formula")` | [이 메서드의 대응·제외](#mapping-4) |
| [L135](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L135) | `contains("class=\"button button-secondary\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L136](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L136) | `doesNotContain("class=\"fgc-btn fgc-btn--ghost\"")` | [이 메서드의 대응·제외](#mapping-4) |
| [L137](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L137) | `doesNotContain("class=\"fgc-th-num\"")` | [이 메서드의 대응·제외](#mapping-4) |

## PublishingTemplateStructureTest#connectedArbitrageScreenDoesNotExposeStalePendingState

<a id="mapping-5"></a>

대체 테스트: ARB 수동 재검증 단일 제출·완료 toast·오류 입력 보존.

구조 제외·남은 확인: legacy JS 연결/placeholder 문자열 검사 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L143](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L143) | `contains("id=\"arb-pagination\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L144](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L144) | `contains("data-modal=\"arb-recheck\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L145](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L145) | `doesNotContain("조회 API 연동 대기")` | [이 메서드의 대응·제외](#mapping-5) |
| [L148](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L148) | `contains("exception-pagination")` | [이 메서드의 대응·제외](#mapping-5) |
| [L149](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L149) | `contains("pagination-controls")` | [이 메서드의 대응·제외](#mapping-5) |
| [L150](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L150) | `contains("pagination-button is-active")` | [이 메서드의 대응·제외](#mapping-5) |
| [L151](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L151) | `contains("kpi-card exception-summary-card")` | [이 메서드의 대응·제외](#mapping-5) |
| [L152](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L152) | `contains("kpi-card-header")` | [이 메서드의 대응·제외](#mapping-5) |
| [L153](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L153) | `contains("kpi-value-row")` | [이 메서드의 대응·제외](#mapping-5) |
| [L154](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L154) | `contains("kpi-card-footer")` | [이 메서드의 대응·제외](#mapping-5) |
| [L155](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L155) | `contains("exception-detail-")` | [이 메서드의 대응·제외](#mapping-5) |
| [L156](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L156) | `contains("data-exception-action-form")` | [이 메서드의 대응·제외](#mapping-5) |
| [L157](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L157) | `contains("처리 저장")` | [이 메서드의 대응·제외](#mapping-5) |
| [L158](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L158) | `doesNotContain("유형별 미처리 집계 API 연동 대기")` | [이 메서드의 대응·제외](#mapping-5) |
| [L160](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L160) | `contains("/css/features/exception.css")` | [이 메서드의 대응·제외](#mapping-5) |
| [L161](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L161) | `containsPattern("(?s)<script[^>]*th:src=\"@\\{/js/features/exception/exception-list\\.js}\"[^>]*\\bdefer\\b[^>]*>")` | [이 메서드의 대응·제외](#mapping-5) |
| [L163](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L163) | `containsPattern("(?s)row\\.addEventListener\\(\"keydown\".*?if \\(event\\.target\\.closest\\(\"a, button, details, input, select, textarea\"\\)\\) return;.*?event\\.preventDefault\\(\\)")` | [이 메서드의 대응·제외](#mapping-5) |
| [L164](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L164) | `contains("apiClient.request")` | [이 메서드의 대응·제외](#mapping-5) |
| [L165](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L165) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-5) |
| [L171](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L171) | `contains("id=\"cond-body\" aria-live=\"polite\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L172](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L172) | `contains("data-checklist-passed=\"false\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L173](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L173) | `contains("data-can-finalize=${roleCode == 'GA_ADMIN' or roleCode == 'SYSTEM_ADMIN'}")` | [이 메서드의 대응·제외](#mapping-5) |
| [L175](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L175) | `contains("data-run-finalizable=${detail.header().runType().isFinalizable()}")` | [이 메서드의 대응·제외](#mapping-5) |
| [L176](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L176) | `contains("검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.")` | [이 메서드의 대응·제외](#mapping-5) |
| [L177](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L177) | `containsOnlyOnce("id=\"finalize-action-guide\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L178](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L178) | `containsOnlyOnce("id=\"btn-finalize\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L179](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L179) | `doesNotContain("id=\"finalize-actions-pending\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L180](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L180) | `doesNotContain("확정 조건 API 연동 대기")` | [이 메서드의 대응·제외](#mapping-5) |
| [L181](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L181) | `containsPattern("(?s)<button[^>]*id=\"btn-finalize\"[^>]*\\bdisabled\\b[^>]*>")` | [이 메서드의 대응·제외](#mapping-5) |
| [L183](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L183) | `contains("/finalize-checklist")` | [이 메서드의 대응·제외](#mapping-5) |
| [L184](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L184) | `contains("/finalize\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L185](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L185) | `contains("safeInternalLink")` | [이 메서드의 대응·제외](#mapping-5) |
| [L186](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L186) | `contains("var CHECKLIST_CONDITION_COUNT = 8;")` | [이 메서드의 대응·제외](#mapping-5) |
| [L188](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L188) | `containsPattern("(?s)if \\(runStatus === \"FINALIZED\"\\) \\{\\s*reason = .*?\\} else if \\(!runFinalizable\\)")` | [이 메서드의 대응·제외](#mapping-5) |
| [L189](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L189) | `contains("failing.length === 1 && failing[0].no === 8")` | [이 메서드의 대응·제외](#mapping-5) |
| [L190](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L190) | `contains("checklist.conditions.length !== CHECKLIST_CONDITION_COUNT")` | [이 메서드의 대응·제외](#mapping-5) |
| [L191](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L191) | `contains("finalizeButton.dataset.checklistPassed = String(checklist.passed)")` | [이 메서드의 대응·제외](#mapping-5) |
| [L192](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L192) | `contains("idempotencyKey: finalizeIdempotencyKey")` | [이 메서드의 대응·제외](#mapping-5) |
| [L193](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L193) | `contains("error.code === \"FGC-VRUN-006\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L194](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L194) | `contains("finalizeIdempotencyKey = null")` | [이 메서드의 대응·제외](#mapping-5) |
| [L195](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L195) | `contains("runStatus !== \"COMPLETED\"")` | [이 메서드의 대응·제외](#mapping-5) |
| [L198](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L198) | `contains("modal.open(\"vrun-finalize\")")` | [이 메서드의 대응·제외](#mapping-5) |
| [L199](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L199) | `doesNotContain("window.confirm")` | [이 메서드의 대응·제외](#mapping-5) |
| [L200](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L200) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-5) |

## PublishingTemplateStructureTest#screenGuidanceStaysAlignedWithCurrentUiDecisions

<a id="mapping-6"></a>

대체 테스트: VRUN 확정 안내·SCHEDULE 상태 안내·CAP 근거·AUDIT.

구조 제외·남은 확인: 전체 안내문 금지 목록의 동등성은 D3에서 승인 필요.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L206](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L206) | `doesNotContain("fgc-page-desc")` | [이 메서드의 대응·제외](#mapping-6) |
| [L207](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L207) | `doesNotContain("fgc-banner")` | [이 메서드의 대응·제외](#mapping-6) |
| [L208](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L208) | `doesNotContain("정상 건은 여기 오지 않습니다.")` | [이 메서드의 대응·제외](#mapping-6) |
| [L210](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L210) | `contains("이 원장은 회사의 정식 회계장부가 아닙니다. 정산이 맞는지 확인하려고 FGC가 따로 만드는 보조 장부입니다.")` | [이 메서드의 대응·제외](#mapping-6) |
| [L211](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L211) | `contains("data-modal=\"journal-reverse\"")` | [이 메서드의 대응·제외](#mapping-6) |
| [L212](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L212) | `contains("id=\"journal-reverse-reason\"")` | [이 메서드의 대응·제외](#mapping-6) |
| [L213](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L213) | `contains("data-can-reverse=${roleCode == 'SETTLEMENT' or roleCode == 'GA_ADMIN' or roleCode == 'SYSTEM_ADMIN'}")` | [이 메서드의 대응·제외](#mapping-6) |
| [L214](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L214) | `doesNotContain("데이터 렌더링은 IF-API 연동 시 추가")` | [이 메서드의 대응·제외](#mapping-6) |
| [L216](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L216) | `contains("/js/features/ledger/ledger.js")` | [이 메서드의 대응·제외](#mapping-6) |
| [L218](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L218) | `contains("/api/v1/journals/imbalances?validationRunId=")` | [이 메서드의 대응·제외](#mapping-6) |
| [L219](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L219) | `contains("/reverse\"")` | [이 메서드의 대응·제외](#mapping-6) |
| [L220](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L220) | `contains("detail.status === \"POSTED\"")` | [이 메서드의 대응·제외](#mapping-6) |
| [L221](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L221) | `contains("evidenceRef: reverseEvidence.value.trim() &#124;&#124; null")` | [이 메서드의 대응·제외](#mapping-6) |
| [L222](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L222) | `doesNotContain("apiClient.request(\"/api/v1/journals?\"")` | [이 메서드의 대응·제외](#mapping-6) |
| [L223](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L223) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-6) |

## PublishingTemplateStructureTest#ledgerSearchRunsOnlyWhenFilterFormIsSubmitted

<a id="mapping-7"></a>

대체 테스트: LEDGER `명시적인 조회 전에는 목록 API를 호출하지 않는다`·URL/API 필터 유지.

구조 제외·남은 확인: JS 이벤트 등록 방식 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L229](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L229) | `contains("id=\"ledger-filter-form\"")` | [이 메서드의 대응·제외](#mapping-7) |
| [L230](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L230) | `contains("method=\"get\" th:action=\"@{/journals}\"")` | [이 메서드의 대응·제외](#mapping-7) |
| [L231](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L231) | `contains("id=\"f-search\" name=\"searched\" value=\"true\" type=\"submit\"")` | [이 메서드의 대응·제외](#mapping-7) |
| [L232](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L232) | `contains("th:each=\"journal : ${journals.content}\"")` | [이 메서드의 대응·제외](#mapping-7) |
| [L233](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L233) | `contains("조회 조건을 설정하고 조회 버튼을 눌러 주세요.")` | [이 메서드의 대응·제외](#mapping-7) |
| [L235](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L235) | `doesNotContain("form.addEventListener(\"submit\"")` | [이 메서드의 대응·제외](#mapping-7) |
| [L236](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L236) | `doesNotContain("function loadList(")` | [이 메서드의 대응·제외](#mapping-7) |
| [L237](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L237) | `doesNotContain("function query(")` | [이 메서드의 대응·제외](#mapping-7) |

## PublishingTemplateStructureTest#ledgerUsesCommonPageShellFormattingAndAccessibleAjaxStates

<a id="mapping-8"></a>

대체 테스트: LEDGER 검색/selected/금액/상태/오류·FORMAT·COMMON.

구조 제외·남은 확인: 템플릿 id/class·script 구조 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L246](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L246) | `contains("class=\"page-content ledger-page\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L247](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L247) | `contains("class=\"page-header\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L248](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L248) | `contains("class=\"page-title\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L249](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L249) | `contains("class=\"guidance guidance-neutral\" aria-labelledby=\"notice-ledger\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L250](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L250) | `contains("id=\"imbalance-banner\" role=\"alert\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L251](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L251) | `contains("id=\"balance-banner\" role=\"status\" aria-live=\"polite\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L252](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L252) | `contains("class=\"filter-bar ledger-filter-bar\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L253](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L253) | `contains("class=\"filter-fields ledger-filter-fields\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L254](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L254) | `contains("class=\"surface\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L255](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L255) | `contains("class=\"data-table-viewport ledger-table-viewport\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L256](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L256) | `contains("class=\"data-table ledger-table\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L257](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L257) | `contains("<caption class=\"visually-hidden\">")` | [이 메서드의 대응·제외](#mapping-8) |
| [L258](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L258) | `contains("<th scope=\"col\">")` | [이 메서드의 대응·제외](#mapping-8) |
| [L259](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L259) | `contains("class=\"table-cell-disclosure\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L260](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L260) | `contains("class=\"pagination-controls ledger-pagination\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L261](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L261) | `contains("aria-label=\"검증원장 페이지 이동\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L262](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L262) | `contains("aria-selected=\"false\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L264](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L264) | `contains("data-modal=\"journal-reverse\" data-close-on-backdrop=\"true\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L265](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L265) | `contains("data-modal-initial-focus")` | [이 메서드의 대응·제외](#mapping-8) |
| [L266](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L266) | `contains("data-modal-close")` | [이 메서드의 대응·제외](#mapping-8) |
| [L267](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L267) | `doesNotContain("class=\"fgc-")` | [이 메서드의 대응·제외](#mapping-8) |
| [L268](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L268) | `doesNotContain("publishing-page")` | [이 메서드의 대응·제외](#mapping-8) |
| [L269](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L269) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-8) |
| [L270](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L270) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-8) |
| [L271](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L271) | `doesNotContainPattern("(?i)<script[\\s>]")` | [이 메서드의 대응·제외](#mapping-8) |
| [L274](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L274) | `contains(".ledger-work-grid")` | [이 메서드의 대응·제외](#mapping-8) |
| [L275](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L275) | `contains("grid-template-columns: minmax(0, 1.7fr) minmax(20rem, 0.9fr)")` | [이 메서드의 대응·제외](#mapping-8) |
| [L276](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L276) | `contains("@media (max-width: 63.9375rem)")` | [이 메서드의 대응·제외](#mapping-8) |
| [L277](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L277) | `contains("@media (max-width: 47.9375rem)")` | [이 메서드의 대응·제외](#mapping-8) |
| [L278](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L278) | `contains(".ledger-page :is(a, button, input, select, textarea, summary, [tabindex]):focus-visible")` | [이 메서드의 대응·제외](#mapping-8) |
| [L279](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L279) | `contains("var(--space-")` | [이 메서드의 대응·제외](#mapping-8) |
| [L280](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L280) | `contains("var(--color-")` | [이 메서드의 대응·제외](#mapping-8) |
| [L281](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L281) | `doesNotContainPattern("#[0-9a-fA-F]{3,8}\\b")` | [이 메서드의 대응·제외](#mapping-8) |
| [L283](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L283) | `doesNotContain("overscroll-behavior-inline")` | [이 메서드의 대응·제외](#mapping-8) |
| [L286](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L286) | `contains("document.querySelector(\".ledger-page\")")` | [이 메서드의 대응·제외](#mapping-8) |
| [L287](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L287) | `contains("format.won(value)")` | [이 메서드의 대응·제외](#mapping-8) |
| [L288](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L288) | `contains("format.isNegative(value)")` | [이 메서드의 대응·제외](#mapping-8) |
| [L289](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L289) | `contains("format.errorText(error, fallback)")` | [이 메서드의 대응·제외](#mapping-8) |
| [L290](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L290) | `contains("function syncTableCellDisclosures()")` | [이 메서드의 대응·제외](#mapping-8) |
| [L291](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L291) | `contains("preview.scrollHeight > preview.clientHeight")` | [이 메서드의 대응·제외](#mapping-8) |
| [L292](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L292) | `contains("document.fonts.ready")` | [이 메서드의 대응·제외](#mapping-8) |
| [L293](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L293) | `contains("row.setAttribute(\"aria-selected\", String(selected))")` | [이 메서드의 대응·제외](#mapping-8) |
| [L294](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L294) | `contains("event.target.closest(\"a, button, details, input, select, textarea\")")` | [이 메서드의 대응·제외](#mapping-8) |
| [L295](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L295) | `contains("detailBody.setAttribute(\"aria-busy\", \"true\")")` | [이 메서드의 대응·제외](#mapping-8) |
| [L296](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L296) | `contains("window.FgcUi.toast(message, \"error\")")` | [이 메서드의 대응·제외](#mapping-8) |
| [L297](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L297) | `contains("sessionStorage.setItem(SUCCESS_TOAST_KEY")` | [이 메서드의 대응·제외](#mapping-8) |
| [L298](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L298) | `contains("window.FgcUi.toast(successMessage, \"success\")")` | [이 메서드의 대응·제외](#mapping-8) |
| [L299](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L299) | `doesNotContain("toLocaleString")` | [이 메서드의 대응·제외](#mapping-8) |
| [L300](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L300) | `doesNotContain("wrapper.style")` | [이 메서드의 대응·제외](#mapping-8) |
| [L301](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L301) | `doesNotContain("className = \"fgc-")` | [이 메서드의 대응·제외](#mapping-8) |

## PublishingTemplateStructureTest#productionLayoutLoadsScopedResponsivePublishingStyles

<a id="mapping-9"></a>

대체 테스트: SHELL·TOKENS.

구조 제외·남은 확인: CSS 링크 순서·scope 제외. 정확한 breakpoint/치수 D3.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L307](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L307) | `contains("/css/features/publishing.css")` | [이 메서드의 대응·제외](#mapping-9) |
| [L310](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L310) | `contains(".app-main > .publishing-page")` | [이 메서드의 대응·제외](#mapping-9) |
| [L311](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L311) | `contains("@media (max-width: 63.9375rem)")` | [이 메서드의 대응·제외](#mapping-9) |
| [L312](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L312) | `contains("@media (max-width: 47.9375rem)")` | [이 메서드의 대응·제외](#mapping-9) |
| [L313](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L313) | `contains("@media (prefers-reduced-motion: reduce)")` | [이 메서드의 대응·제외](#mapping-9) |

## PublishingTemplateStructureTest#productionLayoutLoadsSharedFormatUtilityAndPendingFeatureStyles

<a id="mapping-10"></a>

대체 테스트: FORMAT·SCHEDULE·VRUN.

구조 제외·남은 확인: script/link 태그 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L325](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L325) | `containsPattern("(?s)<script[^>]*th:src=\"@\\{/js/common/format\\.js\\}\"[^>]*\\bdefer\\b[^>]*>")` | [이 메서드의 대응·제외](#mapping-10) |
| [L328](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L328) | `containsPattern("(?s)<link[^>]*th:if=\"\\$\\{#strings\\.startsWith\\(screenId, 'FGC-UI-TRAN-'\\)\\}\"[^>]*th:href=\"@\\{/css/features/transaction\\.css\\}\"[^>]*>")` | [이 메서드의 대응·제외](#mapping-10) |
| [L329](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L329) | `containsPattern("(?s)<link[^>]*th:if=\"\\$\\{#strings\\.startsWith\\(screenId, 'FGC-UI-VRUN-'\\)\\}\"[^>]*th:href=\"@\\{/css/features/vrun\\.css\\}\"[^>]*>")` | [이 메서드의 대응·제외](#mapping-10) |
| [L330](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L330) | `containsPattern("(?s)<link[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-LEDG-W01'\\}\"[^>]*th:href=\"@\\{/css/features/ledger\\.css\\}\"[^>]*>")` | [이 메서드의 대응·제외](#mapping-10) |

## PublishingTemplateStructureTest#productionLayoutLinksEachFeatureStylesheetOnce

<a id="mapping-11"></a>

대체 테스트: 화면별 모듈 import + `npm run build`.

구조 제외·남은 확인: Thymeleaf layout CSS 링크 횟수 검사 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L345](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L345) | `isEqualTo(1)` | [이 메서드의 대응·제외](#mapping-11) |

## PublishingTemplateStructureTest#sharedFormatUtilityFixesDisplayStandardContracts

<a id="mapping-12"></a>

대체 테스트: FORMAT 동일한 8개 입력/기대값.

구조 제외·남은 확인: 전역 `window.FGC.format` 등록 방식 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L356](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L356) | `contains("Asia/Seoul")` | [이 메서드의 대응·제외](#mapping-12) |
| [L357](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L357) | `contains("window.FgcUi.format = format")` | [이 메서드의 대응·제외](#mapping-12) |
| [L358](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L358) | `contains("function errorText(")` | [이 메서드의 대응·제외](#mapping-12) |
| [L359](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L359) | `contains("요청 ID: ")` | [이 메서드의 대응·제외](#mapping-12) |
| [L360](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L360) | `doesNotContain("toFixed(")` | [이 메서드의 대응·제외](#mapping-12) |
| [L361](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L361) | `doesNotContain("Math.round(")` | [이 메서드의 대응·제외](#mapping-12) |
| [L363](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L363) | `contains("function rate(")` | [이 메서드의 대응·제외](#mapping-12) |
| [L364](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L364) | `contains("function usageRate(")` | [이 메서드의 대응·제외](#mapping-12) |
| [L365](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L365) | `contains("truncateDecimal(value, 4)")` | [이 메서드의 대응·제외](#mapping-12) |
| [L366](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L366) | `contains("truncateDecimal(value, 6)")` | [이 메서드의 대응·제외](#mapping-12) |

## PublishingTemplateStructureTest#transactionScreensUseCommonComponentsAndPreserveCriticalContracts

<a id="mapping-13"></a>

대체 테스트: TRAN 검색·숨은 불균형 필터·귀속·6단계 지급 게이트·멱등키·권한·거절·FORMAT.

구조 제외·남은 확인: legacy DOM selector/JS 함수명 제외. 지급 6단계와 월검증 8조건은 별개.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L373](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L373) | `contains("class=\"page-content publishing-page transaction-page transaction-list-page\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L374](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L374) | `contains("class=\"page-header transaction-page-header\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L375](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L375) | `contains("class=\"filter-bar transaction-filter-bar\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L376](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L376) | `contains("class=\"surface transaction-list-panel\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L377](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L377) | `contains("class=\"data-table transaction-list-table\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L378](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L378) | `contains("class=\"transaction-pagination\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L379](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L379) | `contains("data-transaction-filter-form")` | [이 메서드의 대응·제외](#mapping-13) |
| [L380](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L380) | `contains("data-transaction-page-numbers")` | [이 메서드의 대응·제외](#mapping-13) |
| [L381](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L381) | `contains("<caption class=\"visually-hidden\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L382](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L382) | `contains("scope=\"col\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L383](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L383) | `doesNotContain("schedule-")` | [이 메서드의 대응·제외](#mapping-13) |
| [L384](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L384) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-13) |
| [L385](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L385) | `doesNotContain("fgc-page-desc")` | [이 메서드의 대응·제외](#mapping-13) |
| [L386](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L386) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-13) |
| [L387](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L387) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-13) |
| [L388](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L388) | `doesNotContainPattern("(?i)<script[\\s>]")` | [이 메서드의 대응·제외](#mapping-13) |
| [L391](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L391) | `contains("class=\"page-content publishing-page transaction-page transaction-form-page\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L392](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L392) | `contains("data-transaction-form aria-busy=\"true\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L393](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L393) | `contains("class=\"surface transaction-form-panel\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L394](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L394) | `contains("class=\"data-table transaction-attribution-table\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L395](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L395) | `contains("data-modal=\"transaction-confirm\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L396](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L396) | `contains("확정 후에는 이 화면에서 되돌릴 수 없습니다")` | [이 메서드의 대응·제외](#mapping-13) |
| [L397](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L397) | `contains("초년도 구간은 [계약일, 1주년일)입니다")` | [이 메서드의 대응·제외](#mapping-13) |
| [L398](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L398) | `contains("id=\"transaction-permission-help\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L399](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L399) | `contains("id=\"err-bizKey\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L400](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L400) | `contains("id=\"err-attributions\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L402](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L402) | `contains("확정 게이트 6단계")` | [이 메서드의 대응·제외](#mapping-13) |
| [L409](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L409) | `contains("id=\"transaction-confirm-followup\"")` | [이 메서드의 대응·제외](#mapping-13) |
| [L410](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L410) | `doesNotContain("transaction-confirm-links")` | [이 메서드의 대응·제외](#mapping-13) |
| [L411](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L411) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-13) |
| [L412](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L412) | `doesNotContain("fgc-page-desc")` | [이 메서드의 대응·제외](#mapping-13) |
| [L413](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L413) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-13) |
| [L414](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L414) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-13) |
| [L415](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L415) | `doesNotContainPattern("(?i)<script[\\s>]")` | [이 메서드의 대응·제외](#mapping-13) |
| [L418](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L418) | `contains(".transaction-form-layout")` | [이 메서드의 대응·제외](#mapping-13) |
| [L419](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L419) | `contains(".transaction-pagination")` | [이 메서드의 대응·제외](#mapping-13) |
| [L420](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L420) | `contains(".transaction-gate-step")` | [이 메서드의 대응·제외](#mapping-13) |
| [L421](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L421) | `contains("@media (max-width: 63.9375rem)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L422](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L422) | `contains("@media (max-width: 47.9375rem)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L423](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L423) | `contains("var(--color-status-error-solid)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L424](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L424) | `doesNotContainPattern("#[0-9a-fA-F]{3,8}\\b")` | [이 메서드의 대응·제외](#mapping-13) |
| [L427](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L427) | `doesNotContain("[data-transaction-page-numbers]")` | [이 메서드의 대응·제외](#mapping-13) |
| [L428](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L428) | `doesNotContain("[data-transaction-pagination]")` | [이 메서드의 대응·제외](#mapping-13) |
| [L431](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L431) | `contains("buildTransactionFilterState(fields, page, attributionImbalanceOnly)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L432](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L432) | `contains("attributionImbalanceOnly: attributionImbalanceOnly === true")` | [이 메서드의 대응·제외](#mapping-13) |
| [L433](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L433) | `contains("format.won(")` | [이 메서드의 대응·제외](#mapping-13) |
| [L434](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L434) | `contains("status-badge ")` | [이 메서드의 대응·제외](#mapping-13) |
| [L435](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L435) | `contains("aria-disabled")` | [이 메서드의 대응·제외](#mapping-13) |
| [L436](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L436) | `contains("다시 시도")` | [이 메서드의 대응·제외](#mapping-13) |
| [L437](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L437) | `doesNotContain("new Intl.NumberFormat")` | [이 메서드의 대응·제외](#mapping-13) |
| [L438](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L438) | `doesNotContain("style.")` | [이 메서드의 대응·제외](#mapping-13) |
| [L439](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L439) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-13) |
| [L442](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L442) | `contains("modal.open(\"transaction-confirm\")")` | [이 메서드의 대응·제외](#mapping-13) |
| [L443](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L443) | `contains("modal.close(\"transaction-confirm\")")` | [이 메서드의 대응·제외](#mapping-13) |
| [L444](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L444) | `contains("idempotencyKey: \"TRAN-CONFIRM-\" + paymentId")` | [이 메서드의 대응·제외](#mapping-13) |
| [L445](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L445) | `contains("var GATES = [")` | [이 메서드의 대응·제외](#mapping-13) |
| [L446](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L446) | `contains("renderGates([], null)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L459](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L459) | `contains("var RECORDABLE_BLOCKER_CODES = [\"FGC-CAP-001\", \"FGC-CAP-002\"]")` | [이 메서드의 대응·제외](#mapping-13) |
| [L460](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L460) | `contains("hasRecordableBlocker(lastPrecheckResult)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L466](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L466) | `contains("RECORDABLE_BLOCKER_CODES.indexOf(blockers[0].code) >= 0")` | [이 메서드의 대응·제외](#mapping-13) |
| [L467](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L467) | `doesNotContain("(result.blockers &#124;&#124; []).some(")` | [이 메서드의 대응·제외](#mapping-13) |
| [L468](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L468) | `contains("확정 시도 · 예외 등록")` | [이 메서드의 대응·제외](#mapping-13) |
| [L475](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L475) | `contains("renderRecordedFollowUp(error)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L476](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L476) | `contains("var contractNo = blockedContractNo(lastPrecheckResult, error.code)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L477](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L477) | `contains("var target = blockedContract(result, errorCode)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L482](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L482) | `contains("귀속행 판정 다시 확인")` | [이 메서드의 대응·제외](#mapping-13) |
| [L483](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L483) | `contains("예외함에서 처리하기")` | [이 메서드의 대응·제외](#mapping-13) |
| [L484](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L484) | `contains("RECORDABLE_BLOCKER_CODES.indexOf(blocker.code) >= 0")` | [이 메서드의 대응·제외](#mapping-13) |
| [L485](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L485) | `contains("String(item.contractId) === String(target.contractId)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L491](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L491) | `contains("{ label: \"작성중(DRAFT) 저장\", codes: [\"FGC-TRAN-005\"] }")` | [이 메서드의 대응·제외](#mapping-13) |
| [L492](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L492) | `contains("{ label: \"귀속행 입력\", codes: [\"FGC-TRAN-002\"] }")` | [이 메서드의 대응·제외](#mapping-13) |
| [L493](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L493) | `contains("{ label: \"귀속합계 = 지급액\", codes: [\"FGC-TRAN-003\"] }")` | [이 메서드의 대응·제외](#mapping-13) |
| [L494](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L494) | `contains("{ label: \"검토필요 귀속 해소\", codes: [\"FGC-CAP-002\"] }")` | [이 메서드의 대응·제외](#mapping-13) |
| [L495](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L495) | `contains("{ label: \"1,200% 사전검증\", codes: [\"FGC-CAP-001\", \"FGC-CAP-003\", \"FGC-CAP-004\"] }")` | [이 메서드의 대응·제외](#mapping-13) |
| [L496](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L496) | `doesNotContain("차익거래 검증")` | [이 메서드의 대응·제외](#mapping-13) |
| [L497](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L497) | `doesNotContain("증빙·업무키 검증")` | [이 메서드의 대응·제외](#mapping-13) |
| [L502](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L502) | `doesNotContain("ATTRIBUTION_REQUIRED")` | [이 메서드의 대응·제외](#mapping-13) |
| [L503](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L503) | `doesNotContain("haystack")` | [이 메서드의 대응·제외](#mapping-13) |
| [L508](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L508) | `doesNotContain("configureResultLinks")` | [이 메서드의 대응·제외](#mapping-13) |
| [L509](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L509) | `doesNotContain("transaction-cap-link")` | [이 메서드의 대응·제외](#mapping-13) |
| [L510](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L510) | `doesNotContain("transaction-exception-link")` | [이 메서드의 대응·제외](#mapping-13) |
| [L511](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L511) | `contains("format.today().slice(0, 7)")` | [이 메서드의 대응·제외](#mapping-13) |
| [L512](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L512) | `contains("format.won(")` | [이 메서드의 대응·제외](#mapping-13) |
| [L513](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L513) | `contains("showValidationError")` | [이 메서드의 대응·제외](#mapping-13) |
| [L514](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L514) | `doesNotContain("new Intl.NumberFormat")` | [이 메서드의 대응·제외](#mapping-13) |
| [L515](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L515) | `doesNotContain("statusStyle(")` | [이 메서드의 대응·제외](#mapping-13) |
| [L516](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L516) | `doesNotContain("statusColor(")` | [이 메서드의 대응·제외](#mapping-13) |
| [L517](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L517) | `doesNotContain("style.")` | [이 메서드의 대응·제외](#mapping-13) |
| [L518](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L518) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-13) |

## PublishingTemplateStructureTest#evidencePopoverComponentEscapesTableClipping

<a id="mapping-14"></a>

대체 테스트: COMMON EvidenceLink 및 표 안 팝오버.

구조 제외·남은 확인: portal의 실제 clipping/배치 전체는 D3.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L530](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L530) | `contains(".evidence-trigger")` | [이 메서드의 대응·제외](#mapping-14) |
| [L531](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L531) | `contains(".evidence-popover")` | [이 메서드의 대응·제외](#mapping-14) |
| [L532](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L532) | `contains(":popover-open")` | [이 메서드의 대응·제외](#mapping-14) |
| [L533](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L533) | `contains(".data-table td.has-evidence")` | [이 메서드의 대응·제외](#mapping-14) |
| [L535](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L535) | `contains(".evidence-popover:not([popover])")` | [이 메서드의 대응·제외](#mapping-14) |

## PublishingTemplateStructureTest#evidenceOpensOnHoverFocusAndClick

<a id="mapping-15"></a>

대체 테스트: COMMON `EvidenceLink는 hover·focus로 열고 클릭 고정·Esc로 해제한다`.

구조 제외·남은 확인: 기존 이벤트 함수명 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L545](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L545) | `containsPattern("(?s)<script[^>]*th:src=\"@\\{/js/common/evidence\\.js\\}\"[^>]*\\bdefer\\b[^>]*>")` | [이 메서드의 대응·제외](#mapping-15) |
| [L548](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L548) | `contains("\"mouseenter\"")` | [이 메서드의 대응·제외](#mapping-15) |
| [L549](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L549) | `contains("\"focusin\"")` | [이 메서드의 대응·제외](#mapping-15) |
| [L550](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L550) | `contains("trigger.addEventListener(\"click\"")` | [이 메서드의 대응·제외](#mapping-15) |
| [L551](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L551) | `contains("showPopover()")` | [이 메서드의 대응·제외](#mapping-15) |
| [L552](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L552) | `contains("--evidence-x")` | [이 메서드의 대응·제외](#mapping-15) |
| [L553](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L553) | `contains("removeAttribute(\"popover\")")` | [이 메서드의 대응·제외](#mapping-15) |
| [L554](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L554) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-15) |

## PublishingTemplateStructureTest#policyListUsesCommonComponentsWithoutInlinePresentation

<a id="mapping-16"></a>

대체 테스트: REFERENCE 정책 탭/수치/빈값/긴 텍스트·FORMAT.

구조 제외·남은 확인: legacy inline 금지 및 class 이름 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L561](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L561) | `contains("class=\"page-header policy-page-header\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L562](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L562) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-16) |
| [L563](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L563) | `doesNotContain("fgc-page-desc")` | [이 메서드의 대응·제외](#mapping-16) |
| [L564](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L564) | `contains("class=\"field policy-date-field\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L565](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L565) | `doesNotContain("class=\"guidance")` | [이 메서드의 대응·제외](#mapping-16) |
| [L566](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L566) | `contains("class=\"tab-list\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L567](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L567) | `contains("class=\"surface tab-panel policy-tab-panel\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L568](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L568) | `contains("class=\"data-table-viewport policy-version-table-viewport\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L569](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L569) | `contains("class=\"data-table policy-version-table\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L570](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L570) | `contains("type=\"radio\" name=\"policy-version-selection\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L571](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L571) | `contains("data-policy-select")` | [이 메서드의 대응·제외](#mapping-16) |
| [L572](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L572) | `contains("colspan=\"10\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L573](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L573) | `contains("class=\"empty-state policy-empty-state\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L574](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L574) | `contains("class=\"table-cell-disclosure\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L575](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L575) | `contains("class=\"table-cell-details\" hidden")` | [이 메서드의 대응·제외](#mapping-16) |
| [L576](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L576) | `contains("class=\"table-cell-more\">전체 보기")` | [이 메서드의 대응·제외](#mapping-16) |
| [L577](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L577) | `contains("class=\"table-cell-less\">접기")` | [이 메서드의 대응·제외](#mapping-16) |
| [L578](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L578) | `doesNotContain("data-policy-row tabindex=")` | [이 메서드의 대응·제외](#mapping-16) |
| [L579](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L579) | `doesNotContain("data-policy-row aria-selected=")` | [이 메서드의 대응·제외](#mapping-16) |
| [L580](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L580) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-16) |
| [L581](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L581) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-16) |
| [L582](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L582) | `doesNotContainPattern("(?i)<script[\\s>]")` | [이 메서드의 대응·제외](#mapping-16) |
| [L585](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L585) | `contains("new AbortController()")` | [이 메서드의 대응·제외](#mapping-16) |
| [L586](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L586) | `contains("signal: requestController.signal")` | [이 메서드의 대응·제외](#mapping-16) |
| [L587](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L587) | `contains("detailAbortController.abort()")` | [이 메서드의 대응·제외](#mapping-16) |
| [L588](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L588) | `contains("selector.addEventListener(\"change\"")` | [이 메서드의 대응·제외](#mapping-16) |
| [L589](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L589) | `contains("function tableCellDisclosure(")` | [이 메서드의 대응·제외](#mapping-16) |
| [L590](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L590) | `contains("function syncTableCellDisclosures(")` | [이 메서드의 대응·제외](#mapping-16) |
| [L591](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L591) | `contains("preview.scrollWidth > preview.clientWidth")` | [이 메서드의 대응·제외](#mapping-16) |
| [L592](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L592) | `contains("preview.scrollHeight > preview.clientHeight")` | [이 메서드의 대응·제외](#mapping-16) |
| [L593](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L593) | `contains("document.fonts.ready.then")` | [이 메서드의 대응·제외](#mapping-16) |
| [L594](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L594) | `contains("policy-disclosure-cell")` | [이 메서드의 대응·제외](#mapping-16) |
| [L595](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L595) | `doesNotContain("candidate.setAttribute(\"aria-selected\", isSelected")` | [이 메서드의 대응·제외](#mapping-16) |
| [L598](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L598) | `contains(".table-cell-disclosure")` | [이 메서드의 대응·제외](#mapping-16) |
| [L599](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L599) | `contains(".table-cell-details[open] .table-cell-less")` | [이 메서드의 대응·제외](#mapping-16) |
| [L600](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L600) | `contains(".table-cell-full")` | [이 메서드의 대응·제외](#mapping-16) |
| [L603](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L603) | `containsPattern("(?s)<link[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-POL-W01'\\}\"[^>]*th:href=\"@\\{/css/features/policy\\.css\\}\"[^>]*>")` | [이 메서드의 대응·제외](#mapping-16) |
| [L604](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L604) | `containsPattern("(?s)<script[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-POL-W01'\\}\"[^>]*th:src=\"@\\{/js/features/policy/policy-list\\.js\\}\"[^>]*>")` | [이 메서드의 대응·제외](#mapping-16) |
| [L607](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L607) | `contains(".policy-page")` | [이 메서드의 대응·제외](#mapping-16) |
| [L608](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L608) | `contains(".policy-version-table")` | [이 메서드의 대응·제외](#mapping-16) |
| [L609](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L609) | `contains(".data-table td.policy-disclosure-cell")` | [이 메서드의 대응·제외](#mapping-16) |
| [L611](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L611) | `contains("[data-policy-tab]")` | [이 메서드의 대응·제외](#mapping-16) |
| [L612](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L612) | `contains("[data-detail-body]")` | [이 메서드의 대응·제외](#mapping-16) |

## PublishingTemplateStructureTest#auditListUsesCommonComponentsWithoutInlinePresentation

<a id="mapping-17"></a>

대체 테스트: AUDIT 목록·패널·키보드·403. 공통 class 이름 자체의 대체 단언은 없음.

구조 제외·남은 확인: `filter-bar audit-filter-bar`, `surface audit-list-panel`, `data-table audit-log-table`, `empty-state audit-empty-state` 보존/제외는 PL 판단 필요(D2/D3). 빈 상태 class 차이 D2, CSS import는 layout 링크 대체.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L618](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L618) | `contains("class=\"page-header\"")` | [이 메서드의 대응·제외](#mapping-17) |
| [L619](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L619) | `contains("class=\"filter-bar audit-filter-bar\"")` | [이 메서드의 대응·제외](#mapping-17) |
| [L620](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L620) | `contains("class=\"button button-primary\"")` | [이 메서드의 대응·제외](#mapping-17) |
| [L621](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L621) | `contains("class=\"surface audit-list-panel\"")` | [이 메서드의 대응·제외](#mapping-17) |
| [L622](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L622) | `contains("class=\"data-table-viewport\"")` | [이 메서드의 대응·제외](#mapping-17) |
| [L623](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L623) | `contains("class=\"data-table audit-log-table\"")` | [이 메서드의 대응·제외](#mapping-17) |
| [L624](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L624) | `contains("class=\"empty-state audit-empty-state\"")` | [이 메서드의 대응·제외](#mapping-17) |
| [L625](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L625) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-17) |
| [L626](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L626) | `doesNotContain("<style>")` | [이 메서드의 대응·제외](#mapping-17) |
| [L627](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L627) | `doesNotContain("<script>")` | [이 메서드의 대응·제외](#mapping-17) |
| [L630](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L630) | `contains("/css/features/audit.css")` | [이 메서드의 대응·제외](#mapping-17) |
| [L633](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L633) | `contains("@media (max-width: 56.25rem)")` | [이 메서드의 대응·제외](#mapping-17) |
| [L634](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L634) | `doesNotContain("@media (max-width: 71.875rem)")` | [이 메서드의 대응·제외](#mapping-17) |

## PublishingTemplateStructureTest#exceptionListUsesFlexibleColumnsSharedBadgesAndProductionToast

<a id="mapping-18"></a>

대체 테스트: EXCEPTION URL·선택·조치·이력·권한·금액·CAP 링크.

구조 제외·남은 확인: 템플릿 class와 폭 문자열 제외; 시각 검증 D3.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L641](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L641) | `contains("class=\"page-content exception-page\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L642](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L642) | `contains("class=\"page-header\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L643](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L643) | `contains("class=\"page-title\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L644](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L644) | `contains("class=\"surface\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L645](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L645) | `contains("class=\"filter-bar exception-filter-bar\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L646](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L646) | `contains("class=\"filter-field-label\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L647](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L647) | `contains("class=\"data-table-viewport exception-table-viewport\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L648](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L648) | `contains("class=\"data-table exception-table\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L649](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L649) | `contains("class=\"empty-state")` | [이 메서드의 대응·제외](#mapping-18) |
| [L651](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L651) | `contains("th:text=\"${c.reasonLabel()} ?: '-'\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L653](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L653) | `contains("data-selected-exception-id=${selectedExceptionId}")` | [이 메서드의 대응·제외](#mapping-18) |
| [L654](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L654) | `contains("id=\"summary-cards\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L655](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L655) | `contains("id=\"f-type\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L656](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L656) | `contains("id=\"f-reason\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L657](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L657) | `contains("id=\"f-reset\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L659](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L659) | `doesNotContain("onchange=")` | [이 메서드의 대응·제외](#mapping-18) |
| [L660](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L660) | `doesNotContain("class=\"fgc-")` | [이 메서드의 대응·제외](#mapping-18) |
| [L661](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L661) | `doesNotContain("publishing-page")` | [이 메서드의 대응·제외](#mapping-18) |
| [L662](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L662) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L663](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L663) | `doesNotContainPattern("(?i)<style[\s>]")` | [이 메서드의 대응·제외](#mapping-18) |
| [L664](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L664) | `doesNotContainPattern("(?i)<script[\s>]")` | [이 메서드의 대응·제외](#mapping-18) |
| [L667](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L667) | `contains("<colgroup>")` | [이 메서드의 대응·제외](#mapping-18) |
| [L668](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L668) | `contains("class=\"exception-col-title\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L669](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L669) | `contains("class=\"status-badge\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L670](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L670) | `contains("status-badge-warning")` | [이 메서드의 대응·제외](#mapping-18) |
| [L671](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L671) | `contains("status-badge-error")` | [이 메서드의 대응·제외](#mapping-18) |
| [L672](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L672) | `contains("status-badge-review")` | [이 메서드의 대응·제외](#mapping-18) |
| [L673](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L673) | `contains("status-badge-info")` | [이 메서드의 대응·제외](#mapping-18) |
| [L674](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L674) | `contains("status-badge-success")` | [이 메서드의 대응·제외](#mapping-18) |
| [L675](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L675) | `contains("class=\"table-cell-disclosure\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L676](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L676) | `contains("class=\"table-cell-details\" hidden")` | [이 메서드의 대응·제외](#mapping-18) |
| [L677](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L677) | `contains("class=\"table-cell-more\">전체 보기")` | [이 메서드의 대응·제외](#mapping-18) |
| [L678](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L678) | `contains("class=\"table-cell-less\">접기")` | [이 메서드의 대응·제외](#mapping-18) |
| [L679](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L679) | `contains("referenceValue=&#124;${c.sourceEntityType()}:${c.sourceEntityId()}&#124;")` | [이 메서드의 대응·제외](#mapping-18) |
| [L680](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L680) | `contains("class=\"modal-backdrop journal-correction-backdrop\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L681](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L681) | `contains("data-modal=&#124;journal-correction-${c.exceptionCaseId()}&#124;")` | [이 메서드의 대응·제외](#mapping-18) |
| [L682](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L682) | `contains("class=\"modal modal-large publishing-modal journal-correction-modal\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L683](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L683) | `contains("class=\"journal-correction-compare-grid\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L684](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L684) | `contains("data-journal-correction-open")` | [이 메서드의 대응·제외](#mapping-18) |
| [L685](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L685) | `contains("data-correction-read-only")` | [이 메서드의 대응·제외](#mapping-18) |
| [L686](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L686) | `contains("data-correction-read-only-notice")` | [이 메서드의 대응·제외](#mapping-18) |
| [L687](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L687) | `contains("data-close-on-backdrop=\"true\" hidden aria-hidden=\"true\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L688](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L688) | `doesNotContain("class=\"fgc-page-desc\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L689](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L689) | `doesNotContain("class=\"fgc-banner")` | [이 메서드의 대응·제외](#mapping-18) |
| [L690](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L690) | `doesNotContain("<th style=\"width:")` | [이 메서드의 대응·제외](#mapping-18) |
| [L694](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L694) | `doesNotContain("overscroll-behavior-inline")` | [이 메서드의 대응·제외](#mapping-18) |
| [L695](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L695) | `doesNotContain(".publishing-page .exception-table")` | [이 메서드의 대응·제외](#mapping-18) |
| [L696](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L696) | `contains(".exception-page")` | [이 메서드의 대응·제외](#mapping-18) |
| [L697](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L697) | `contains(".exception-filter-fields")` | [이 메서드의 대응·제외](#mapping-18) |
| [L698](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L698) | `contains(".exception-col-title")` | [이 메서드의 대응·제외](#mapping-18) |
| [L699](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L699) | `contains(".journal-correction-original-lines")` | [이 메서드의 대응·제외](#mapping-18) |
| [L700](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L700) | `contains(".journal-correction-original-line.is-heading")` | [이 메서드의 대응·제외](#mapping-18) |
| [L701](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L701) | `contains(".journal-correction-modal")` | [이 메서드의 대응·제외](#mapping-18) |
| [L702](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L702) | `contains(".journal-correction-compare-grid")` | [이 메서드의 대응·제외](#mapping-18) |
| [L703](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L703) | `contains("width: auto")` | [이 메서드의 대응·제외](#mapping-18) |
| [L704](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L704) | `contains("table-layout: fixed")` | [이 메서드의 대응·제외](#mapping-18) |
| [L705](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L705) | `doesNotContain("width: 22rem")` | [이 메서드의 대응·제외](#mapping-18) |
| [L706](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L706) | `doesNotContain("min-width: 22rem")` | [이 메서드의 대응·제외](#mapping-18) |
| [L707](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L707) | `doesNotContain("max-width: 22rem")` | [이 메서드의 대응·제외](#mapping-18) |
| [L710](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L710) | `contains("STATUS_BADGE_CLASSES")` | [이 메서드의 대응·제외](#mapping-18) |
| [L711](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L711) | `contains("badge.classList.remove(...STATUS_BADGE_CLASS_NAMES)")` | [이 메서드의 대응·제외](#mapping-18) |
| [L712](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L712) | `contains("renderOriginalJournal(originalContainer, journal)")` | [이 메서드의 대응·제외](#mapping-18) |
| [L713](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L713) | `contains("date.textContent = &#96;분개일 ${journal.journalDate}&#96;")` | [이 메서드의 대응·제외](#mapping-18) |
| [L714](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L714) | `contains("(journal.lines &#124;&#124; []).forEach((line) =>")` | [이 메서드의 대응·제외](#mapping-18) |
| [L715](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L715) | `contains("journal.balanced ? \"차변·대변 검증 통과\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L716](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L716) | `contains("journal.repostedJournalHeaderId")` | [이 메서드의 대응·제외](#mapping-18) |
| [L717](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L717) | `contains("repostedLink.href = &#96;/journals?selected=${journal.repostedJournalHeaderId}&#96;")` | [이 메서드의 대응·제외](#mapping-18) |
| [L718](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L718) | `contains("closest(\"a, button, details, input, select, textarea\")")` | [이 메서드의 대응·제외](#mapping-18) |
| [L719](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L719) | `contains("preview.scrollWidth > preview.clientWidth")` | [이 메서드의 대응·제외](#mapping-18) |
| [L720](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L720) | `contains("preview.scrollHeight > preview.clientHeight")` | [이 메서드의 대응·제외](#mapping-18) |
| [L721](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L721) | `contains("document.fonts.ready")` | [이 메서드의 대응·제외](#mapping-18) |
| [L722](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L722) | `contains("toast(message, \"error\")")` | [이 메서드의 대응·제외](#mapping-18) |
| [L724](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L724) | `contains("format.dateTime(value)")` | [이 메서드의 대응·제외](#mapping-18) |
| [L725](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L725) | `contains("format.errorText(error, fallback)")` | [이 메서드의 대응·제외](#mapping-18) |
| [L726](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L726) | `doesNotContain("Number(journal.debitTotal).toLocaleString")` | [이 메서드의 대응·제외](#mapping-18) |
| [L727](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L727) | `contains("action.actionType === \"START_REVIEW\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L728](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L728) | `contains("action.actionType === \"REOPEN\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L729](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L729) | `contains("action.toStatus === \"IN_REVIEW\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L730](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L730) | `contains("setJournalCorrectionMode")` | [이 메서드의 대응·제외](#mapping-18) |
| [L731](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L731) | `contains("openButton.hidden = status === \"NEW\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L732](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L732) | `contains("option.dataset.supportsRejected === \"true\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L733](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L733) | `contains("window.FgcUi.modal.open(&#96;journal-correction-${form.dataset.exceptionId}&#96;)")` | [이 메서드의 대응·제외](#mapping-18) |
| [L734](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L734) | `doesNotContain("window.location.assign(&#96;/exceptions?selected=${form.dataset.exceptionId}&#96;)")` | [이 메서드의 대응·제외](#mapping-18) |
| [L737](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L737) | `contains(".table-cell-disclosure")` | [이 메서드의 대응·제외](#mapping-18) |
| [L738](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L738) | `contains(".table-cell-details[open] .table-cell-less")` | [이 메서드의 대응·제외](#mapping-18) |
| [L739](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L739) | `contains(".table-cell-full")` | [이 메서드의 대응·제외](#mapping-18) |
| [L742](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L742) | `contains("top: calc(var(--layout-header-height) + var(--space-4))")` | [이 메서드의 대응·제외](#mapping-18) |
| [L743](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L743) | `contains("width: min(25rem, calc(100vw - 2rem))")` | [이 메서드의 대응·제외](#mapping-18) |
| [L744](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L744) | `contains("border-radius: var(--radius-12)")` | [이 메서드의 대응·제외](#mapping-18) |
| [L745](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L745) | `contains("box-shadow: var(--shadow-sm)")` | [이 메서드의 대응·제외](#mapping-18) |
| [L746](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L746) | `contains(".toast-icon")` | [이 메서드의 대응·제외](#mapping-18) |
| [L749](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L749) | `contains("loading: \"progress_activity\"")` | [이 메서드의 대응·제외](#mapping-18) |
| [L750](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L750) | `contains("safeTone === \"error\" &#124;&#124; safeTone === \"loading\" ? 0 : 5000")` | [이 메서드의 대응·제외](#mapping-18) |
| [L751](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L751) | `contains("toast.append(iconContainer, content, close)")` | [이 메서드의 대응·제외](#mapping-18) |

## PublishingTemplateStructureTest#reconciliationListUsesCommonComponentsAndProductionToast

<a id="mapping-19"></a>

대체 테스트: RECO 실행·자동선택·비교·중복 오류·예외 링크·COMMON.

구조 제외·남은 확인: 템플릿 scope·id·script 분리 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L757](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L757) | `contains("class=\"page-header\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L758](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L758) | `contains("class=\"filter-bar reco-filter-bar\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L759](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L759) | `contains("class=\"kpi-grid reco-summary-grid\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L760](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L760) | `contains("class=\"kpi-card kpi-card-success\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L761](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L761) | `contains("class=\"surface reco-panel reco-result-panel\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L762](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L762) | `contains("class=\"data-table reco-result-table\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L763](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L763) | `contains("class=\"data-table reco-history-table\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L764](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L764) | `contains("class=\"status-badge\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L765](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L765) | `contains("class=\"reco-history-row\" tabindex=\"0\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L766](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L766) | `doesNotContain("class=\"fgc-page-desc\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L767](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L767) | `doesNotContain("class=\"fgc-banner")` | [이 메서드의 대응·제외](#mapping-19) |
| [L768](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L768) | `doesNotContain("class=\"fgc-kpi")` | [이 메서드의 대응·제외](#mapping-19) |
| [L769](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L769) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-19) |
| [L772](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L772) | `contains("/css/features/reco.css")` | [이 메서드의 대응·제외](#mapping-19) |
| [L775](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L775) | `contains(".reco-summary-grid")` | [이 메서드의 대응·제외](#mapping-19) |
| [L776](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L776) | `contains("grid-template-columns: repeat(5, minmax(0, 1fr))")` | [이 메서드의 대응·제외](#mapping-19) |
| [L777](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L777) | `contains(".reco-result-table")` | [이 메서드의 대응·제외](#mapping-19) |
| [L778](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L778) | `contains(".reco-col-action { width: 4rem; }")` | [이 메서드의 대응·제외](#mapping-19) |
| [L779](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L779) | `contains("@media (max-width: 47.9375rem)")` | [이 메서드의 대응·제외](#mapping-19) |
| [L782](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L782) | `contains("tableCellDisclosure")` | [이 메서드의 대응·제외](#mapping-19) |
| [L783](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L783) | `contains("class=\"table-cell-details\" hidden")` | [이 메서드의 대응·제외](#mapping-19) |
| [L784](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L784) | `contains("table-cell-more\">전체 보기")` | [이 메서드의 대응·제외](#mapping-19) |
| [L785](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L785) | `contains("preview.scrollWidth > preview.clientWidth")` | [이 메서드의 대응·제외](#mapping-19) |
| [L786](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L786) | `contains("preview.scrollHeight > preview.clientHeight")` | [이 메서드의 대응·제외](#mapping-19) |
| [L787](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L787) | `contains("document.fonts.ready")` | [이 메서드의 대응·제외](#mapping-19) |
| [L788](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L788) | `contains("window.FgcUi.toast(")` | [이 메서드의 대응·제외](#mapping-19) |
| [L789](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L789) | `contains("\"success\"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L790](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L790) | `contains("event.key !== \"Enter\" && event.key !== \" \"")` | [이 메서드의 대응·제외](#mapping-19) |
| [L791](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L791) | `doesNotContain("text.length <= limit")` | [이 메서드의 대응·제외](#mapping-19) |
| [L792](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L792) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-19) |

## PublishingTemplateStructureTest#contractListUsesCommonFilterTableAndStatusBadges

<a id="mapping-20"></a>

대체 테스트: CONTRACT 목록 필터·페이지·CSV·금액·상태·빈값.

구조 제외·남은 확인: legacy class·템플릿 script 연결 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L802](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L802) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-20) |
| [L803](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L803) | `doesNotContain("contract-filter-grid")` | [이 메서드의 대응·제외](#mapping-20) |
| [L804](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L804) | `doesNotContain("contract-premium-help")` | [이 메서드의 대응·제외](#mapping-20) |
| [L805](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L805) | `contains("class=\"filter-bar contract-filter-bar\"")` | [이 메서드의 대응·제외](#mapping-20) |
| [L806](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L806) | `contains("class=\"filter-fields contract-filter-fields\"")` | [이 메서드의 대응·제외](#mapping-20) |
| [L807](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L807) | `contains("class=\"filter-field\"")` | [이 메서드의 대응·제외](#mapping-20) |
| [L808](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L808) | `contains("class=\"filter-field-label\"")` | [이 메서드의 대응·제외](#mapping-20) |
| [L809](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L809) | `contains("class=\"filter-actions contract-filter-actions\"")` | [이 메서드의 대응·제외](#mapping-20) |
| [L810](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L810) | `contains("class=\"data-table-viewport contract-table-viewport\" tabindex=\"0\" role=\"region\"")` | [이 메서드의 대응·제외](#mapping-20) |
| [L812](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L812) | `contains("status-badge-success")` | [이 메서드의 대응·제외](#mapping-20) |
| [L813](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L813) | `contains("status-badge-warning")` | [이 메서드의 대응·제외](#mapping-20) |
| [L814](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L814) | `contains("status-badge-error")` | [이 메서드의 대응·제외](#mapping-20) |
| [L815](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L815) | `contains("status-badge-info")` | [이 메서드의 대응·제외](#mapping-20) |
| [L817](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L817) | `contains("status-badge status-badge-neutral\" th:text=\"${dataOriginLabels[contract.dataOrigin]}\"")` | [이 메서드의 대응·제외](#mapping-20) |
| [L818](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L818) | `contains("data-disclosure")` | [이 메서드의 대응·제외](#mapping-20) |
| [L820](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L820) | `contains("data-fgc-action=\"create\"")` | [이 메서드의 대응·제외](#mapping-20) |
| [L821](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L821) | `doesNotContain("publishing-page")` | [이 메서드의 대응·제외](#mapping-20) |
| [L822](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L822) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-20) |
| [L823](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L823) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-20) |
| [L824](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L824) | `doesNotContainPattern("(?i)<script[\\s>]")` | [이 메서드의 대응·제외](#mapping-20) |
| [L827](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L827) | `contains("format.today()")` | [이 메서드의 대응·제외](#mapping-20) |
| [L828](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L828) | `contains("table-cell-disclosure")` | [이 메서드의 대응·제외](#mapping-20) |
| [L829](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L829) | `contains("preview.scrollWidth > preview.clientWidth")` | [이 메서드의 대응·제외](#mapping-20) |
| [L830](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L830) | `contains("document.fonts.ready")` | [이 메서드의 대응·제외](#mapping-20) |
| [L831](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L831) | `contains("contract-export-button")` | [이 메서드의 대응·제외](#mapping-20) |
| [L832](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L832) | `contains("CSV 파일을 내려받았습니다.")` | [이 메서드의 대응·제외](#mapping-20) |
| [L833](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L833) | `contains("aria-busy")` | [이 메서드의 대응·제외](#mapping-20) |
| [L836](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L836) | `containsPattern("(?s)<script[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-CONT-W01'\\}\"[^>]*"                         + "th:src=\"@\\{/js/features/contract/contract-list\\.js}\"[^>]*\\bdefer\\b[^>]*>")` | [이 메서드의 대응·제외](#mapping-20) |

## PublishingTemplateStructureTest#contractDetailMovesPageBehaviourOutOfTheTemplate

<a id="mapping-21"></a>

대체 테스트: CONTRACT 상세 6탭·서버 값·링크·권한·재검증.

구조 제외·남은 확인: script 파일 분리/DOMContentLoaded 문자열 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L848](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L848) | `doesNotContainPattern("(?i)<script[\\s>]")` | [이 메서드의 대응·제외](#mapping-21) |
| [L849](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L849) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L850](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L850) | `doesNotContain("publishing-page")` | [이 메서드의 대응·제외](#mapping-21) |
| [L852](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L852) | `contains("class=\"tab-list contract-detail-tabs\" role=\"tablist\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L853](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L853) | `contains("class=\"tab-button contract-detail-tab\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L854](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L854) | `doesNotContain("contract-detail-tab-list")` | [이 메서드의 대응·제외](#mapping-21) |
| [L856](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L856) | `contains("role=\"tabpanel\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L857](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L857) | `contains("data-tab-body")` | [이 메서드의 대응·제외](#mapping-21) |
| [L858](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L858) | `contains("data-tab-state")` | [이 메서드의 대응·제외](#mapping-21) |
| [L860](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L860) | `contains("id=\"contract-summary-insurer\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L861](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L861) | `contains("id=\"contract-summary-agent\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L862](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L862) | `contains("id=\"contract-summary-organization\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L864](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L864) | `contains("<span id=\"contract-info-status\" class=\"status-badge")` | [이 메서드의 대응·제외](#mapping-21) |
| [L865](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L865) | `contains("<span id=\"contract-info-origin\" class=\"status-badge")` | [이 메서드의 대응·제외](#mapping-21) |
| [L867](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L867) | `contains("class=\"guidance guidance-warning contract-tab-guidance\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L868](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L868) | `contains("두 규제를 더하지 마세요")` | [이 메서드의 대응·제외](#mapping-21) |
| [L870](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L870) | `contains("data-label-group=\"contractStatus\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L871](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L871) | `contains("data-label-group=\"paymentCycle\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L875](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L875) | `contains("data-label-group=\"capResultStatus\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L876](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L876) | `contains("data-label-group=\"arbitrageResultStatus\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L877](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L877) | `doesNotContain("data-label-group=\"resultStatus\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L879](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L879) | `doesNotContain("API 연동 대기")` | [이 메서드의 대응·제외](#mapping-21) |
| [L880](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L880) | `doesNotContain("연동 대기 상태로 표시됩니다")` | [이 메서드의 대응·제외](#mapping-21) |
| [L881](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L881) | `contains("data-fgc-action=\"recheck\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L882](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L882) | `contains("data-fgc-action=\"regenerate\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L883](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L883) | `contains("data-fgc-action=\"edit\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L886](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L886) | `contains("window.FgcUi.contractDetail")` | [이 메서드의 대응·제외](#mapping-21) |
| [L887](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L887) | `contains("STATUS_BADGE_CLASSES")` | [이 메서드의 대응·제외](#mapping-21) |
| [L889](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L889) | `contains("classList.remove.apply(element.classList, STATUS_BADGE_CLASS_NAMES)")` | [이 메서드의 대응·제외](#mapping-21) |
| [L890](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L890) | `doesNotContain(".className = \"status-badge \"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L891](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L891) | `contains("event.key === \"Home\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L892](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L892) | `contains("event.key === \"End\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L893](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L893) | `contains("aria-busy")` | [이 메서드의 대응·제외](#mapping-21) |
| [L894](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L894) | `contains("format.dateTime")` | [이 메서드의 대응·제외](#mapping-21) |
| [L895](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L895) | `doesNotContain("toLocaleString(\"ko-KR\")")` | [이 메서드의 대응·제외](#mapping-21) |
| [L896](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L896) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-21) |
| [L900](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L900) | `doesNotContain("querySelectorAll(\".contract-detail-tab\")")` | [이 메서드의 대응·제외](#mapping-21) |
| [L901](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L901) | `contains("contract:tab-activate")` | [이 메서드의 대응·제외](#mapping-21) |
| [L902](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L902) | `contains("detail.contractId")` | [이 메서드의 대응·제외](#mapping-21) |
| [L904](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L904) | `doesNotContain("panel.replaceChildren(")` | [이 메서드의 대응·제외](#mapping-21) |
| [L905](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L905) | `contains("[data-tab-body]")` | [이 메서드의 대응·제외](#mapping-21) |
| [L906](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L906) | `contains("visually-hidden")` | [이 메서드의 대응·제외](#mapping-21) |
| [L907](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L907) | `contains("th.scope = \"col\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L908](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L908) | `contains("detail.badgeClass(status)")` | [이 메서드의 대응·제외](#mapping-21) |
| [L909](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L909) | `contains("format.won")` | [이 메서드의 대응·제외](#mapping-21) |
| [L911](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L911) | `contains("statusBadge(row.resultStatusLabel, row.resultStatus, \"capResultStatus\")")` | [이 메서드의 대응·제외](#mapping-21) |
| [L912](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L912) | `contains("statusBadge(row.resultStatusLabel, row.resultStatus, \"arbitrageResultStatus\")")` | [이 메서드의 대응·제외](#mapping-21) |
| [L913](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L913) | `doesNotContain("codeLabel(\"resultStatus\"")` | [이 메서드의 대응·제외](#mapping-21) |
| [L914](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L914) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-21) |
| [L917](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L917) | `containsPattern("(?s)<script[^>]*th:if=\"\\$\\{screenId == 'FGC-UI-CONT-W02'\\}\"[^>]*"                         + "th:src=\"@\\{/js/features/contract/contract-detail\\.js}\"[^>]*\\bdefer\\b[^>]*>")` | [이 메서드의 대응·제외](#mapping-21) |
| [L922](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L922) | `isLessThan(layout.indexOf("contract/contract-tabs.js"))` | [이 메서드의 대응·제외](#mapping-21) |

## PublishingTemplateStructureTest#contractFormUsesSeparatedApiAndPageScriptsWithoutInlineBehavior

<a id="mapping-22"></a>

대체 테스트: CONTRACT 등록·수정·기준정보·409 입력보존·금액·기간·권한.

구조 제외·남은 확인: legacy script/inline 검사 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L932](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L932) | `contains("name=\"premiumPerCycleAmount\"")` | [이 메서드의 대응·제외](#mapping-22) |
| [L933](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L933) | `contains("cycle.name() != 'OTHER'")` | [이 메서드의 대응·제외](#mapping-22) |
| [L934](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L934) | `contains("data-field-error=\"organizationId\"")` | [이 메서드의 대응·제외](#mapping-22) |
| [L935](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L935) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-22) |
| [L937](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L937) | `doesNotContain("publishing-page")` | [이 메서드의 대응·제외](#mapping-22) |
| [L939](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L939) | `doesNotContain("contract-form-guidance")` | [이 메서드의 대응·제외](#mapping-22) |
| [L940](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L940) | `doesNotContain("contract-action-guidance")` | [이 메서드의 대응·제외](#mapping-22) |
| [L941](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L941) | `contains("id=\"organization-helper\"")` | [이 메서드의 대응·제외](#mapping-22) |
| [L942](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L942) | `contains("aria-describedby=\"contract-save-hint\"")` | [이 메서드의 대응·제외](#mapping-22) |
| [L943](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L943) | `contains("REG-07")` | [이 메서드의 대응·제외](#mapping-22) |
| [L944](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L944) | `contains("REG-08")` | [이 메서드의 대응·제외](#mapping-22) |
| [L945](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L945) | `contains("REG-12")` | [이 메서드의 대응·제외](#mapping-22) |
| [L946](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L946) | `contains("REG-19")` | [이 메서드의 대응·제외](#mapping-22) |
| [L947](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L947) | `contains("class=\"evidence\" data-evidence")` | [이 메서드의 대응·제외](#mapping-22) |
| [L949](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L949) | `contains("T(com.susukkang.fgc.common.code.PaymentCycleCode).values()")` | [이 메서드의 대응·제외](#mapping-22) |
| [L950](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L950) | `contains("T(com.susukkang.fgc.common.code.ContractStatus).values()")` | [이 메서드의 대응·제외](#mapping-22) |
| [L951](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L951) | `doesNotContain("3개월납")` | [이 메서드의 대응·제외](#mapping-22) |
| [L952](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L952) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-22) |
| [L953](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L953) | `doesNotContain("onclick=")` | [이 메서드의 대응·제외](#mapping-22) |
| [L954](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L954) | `doesNotContain("<script>")` | [이 메서드의 대응·제외](#mapping-22) |
| [L957](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L957) | `contains("getProductOfferings")` | [이 메서드의 대응·제외](#mapping-22) |
| [L958](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L958) | `contains("createContract")` | [이 메서드의 대응·제외](#mapping-22) |
| [L959](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L959) | `contains("updateContract")` | [이 메서드의 대응·제외](#mapping-22) |
| [L960](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L960) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-22) |
| [L962](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L962) | `contains("window.FgcUi.contractApi")` | [이 메서드의 대응·제외](#mapping-22) |
| [L963](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L963) | `contains("error.field")` | [이 메서드의 대응·제외](#mapping-22) |
| [L970](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L970) | `doesNotContain("premiumPerCycleAmount.readOnly")` | [이 메서드의 대응·제외](#mapping-22) |
| [L971](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L971) | `contains("isPremiumPerCycleUserValue")` | [이 메서드의 대응·제외](#mapping-22) |
| [L973](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L973) | `contains("function showLoadFailure(")` | [이 메서드의 대응·제외](#mapping-22) |
| [L974](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L974) | `contains("format.errorText")` | [이 메서드의 대응·제외](#mapping-22) |
| [L976](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L976) | `contains("fgc.contract.saveMessage")` | [이 메서드의 대응·제외](#mapping-22) |
| [L977](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L977) | `contains("scheduleHeaderIds")` | [이 메서드의 대응·제외](#mapping-22) |
| [L978](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L978) | `contains("format.today()")` | [이 메서드의 대응·제외](#mapping-22) |
| [L979](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L979) | `contains("aria-busy")` | [이 메서드의 대응·제외](#mapping-22) |
| [L984](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L984) | `contains("function redirectAfter(")` | [이 메서드의 대응·제외](#mapping-22) |
| [L985](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L985) | `contains("if (isEditMode) return redirectAfter(0, redirect);")` | [이 메서드의 대응·제외](#mapping-22) |
| [L986](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L986) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-22) |

## PublishingTemplateStructureTest#baseReferenceScreenUsesSeparatedApiAndPageScripts

<a id="mapping-23"></a>

대체 테스트: REFERENCE 5탭·날짜·옵션·검색·서버 오류.

구조 제외·남은 확인: legacy JS/템플릿 분리 방식 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L992](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L992) | `contains("class=\"page-header base-page-header\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L993](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L993) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-23) |
| [L994](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L994) | `doesNotContain("fgc-page-desc")` | [이 메서드의 대응·제외](#mapping-23) |
| [L995](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L995) | `doesNotContain("class=\"guidance")` | [이 메서드의 대응·제외](#mapping-23) |
| [L996](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L996) | `contains("class=\"tab-list\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L997](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L997) | `contains("class=\"tab-panel base-panel\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L998](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L998) | `contains("class=\"filter-bar base-filter-form\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L999](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L999) | `contains("class=\"filter-actions base-filter-actions\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1000](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1000) | `contains("class=\"surface base-result-surface\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1001](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1001) | `contains("class=\"empty-state base-result-message\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1002](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1002) | `contains("class=\"data-table base-table")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1003](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1003) | `contains("data-base-tab=\"organization\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1004](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1004) | `contains("data-base-tab=\"product\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1005](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1005) | `contains("data-base-tab=\"agent\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1006](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1006) | `contains("data-base-form=\"commission-item\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1007](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1007) | `contains("data-base-body=\"commission-item\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1008](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1008) | `contains("지급/차감")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1009](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1009) | `contains("적용 시작일")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1010](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1010) | `contains("적용 종료일")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1011](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1011) | `contains("status-badge-success")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1012](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1012) | `contains("class=\"table-cell-disclosure\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1013](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1013) | `contains("class=\"table-cell-more\">전체 보기")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1014](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1014) | `contains("class=\"table-cell-less\">접기")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1015](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1015) | `doesNotContain("class=\"surface tab-panel base-panel\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1016](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1016) | `doesNotContain("base-panel-note")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1017](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1017) | `doesNotContain("<script>")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1018](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1018) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1019](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1019) | `doesNotContain("onclick=\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1022](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1022) | `contains("/api/v1/base/organizations")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1023](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1023) | `contains("/api/v1/base/insurers")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1024](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1024) | `contains("/api/v1/base/products")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1025](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1025) | `contains("/api/v1/base/agents")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1026](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1026) | `contains("/api/v1/base/commission-items")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1029](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1029) | `contains("window.FgcUi.baseApi")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1030](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1030) | `contains("new AbortController()")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1031](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1031) | `contains("organizationOptionsRequestId")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1032](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1032) | `contains("organizationOptionsInitialized")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1033](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1033) | `contains("getCommissionItems")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1034](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1034) | `contains("PAYMENT: [\"지급\", \"status-badge-success\"]")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1035](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1035) | `contains("DEDUCTION: [\"차감\", \"status-badge-warning\"]")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1036](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1036) | `contains("SETTLEMENT_SUPPORT: \"정착지원\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1037](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1037) | `contains("NEWCOMER_SUPPORT: \"신인지원\"")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1038](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1038) | `contains("function tableCellDisclosure(")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1039](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1039) | `contains("tableCellDisclosure(item.itemCode, 18, true)")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1040](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1040) | `contains("table-cell-preview")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1041](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1041) | `contains("base-disclosure-cell")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1042](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1042) | `contains("aria-busy")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1043](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1043) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1046](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1046) | `contains(".table-cell-disclosure")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1047](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1047) | `contains(".table-cell-details[open] .table-cell-less")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1048](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1048) | `contains(".table-cell-full")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1051](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1051) | `contains("/css/features/base.css")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1052](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1052) | `contains("/js/features/base/base-api.js")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1053](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1053) | `contains("/js/features/base/base-list.js")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1056](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1056) | `contains(".base-page")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1057](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1057) | `contains(".data-table td.base-disclosure-cell")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1058](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1058) | `contains("@media (max-width: 47.9375rem)")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1059](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1059) | `doesNotContain(".base-information-banner")` | [이 메서드의 대응·제외](#mapping-23) |
| [L1060](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1060) | `doesNotContain(".base-panel-note")` | [이 메서드의 대응·제외](#mapping-23) |

## PublishingTemplateStructureTest#dashboardUsesCommonComponentsWithoutStalePlaceholdersOrRawEnumCodes

<a id="mapping-24"></a>

대체 테스트: DASH KPI·기준월·권한·빈값·차트 대체/서버 labels.

구조 제외·남은 확인: CSS class·템플릿 연결 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L1071](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1071) | `contains("class=\"page-content dashboard-page\"")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1072](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1072) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1073](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1073) | `doesNotContain("월별 추세 집계 API 연동 대기")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1074](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1074) | `doesNotContain("준비도 집계 API가 아직 제공되지 않습니다")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1075](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1075) | `doesNotContain("trend-card")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1076](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1076) | `doesNotContain("readiness-card")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1077](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1077) | `contains("kpi-card kpi-card-warning")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1078](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1078) | `contains("${e.severityLabel()}")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1079](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1079) | `contains("${e.exceptionTypeLabel()}")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1080](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1080) | `contains("${e.statusLabel()}")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1081](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1081) | `contains("${r.statusLabel()}")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1082](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1082) | `doesNotContain("T(com.susukkang.fgc.common.code")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1083](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1083) | `contains("<th scope=\"col\">유형</th>")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1084](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1084) | `contains("class=\"table-cell-disclosure\"")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1085](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1085) | `contains("class=\"table-cell-details\" hidden")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1086](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1086) | `contains("class=\"table-cell-more\">전체 보기")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1087](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1087) | `contains("class=\"table-cell-less\">접기")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1088](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1088) | `contains("dashboard-disclosure-cell")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1089](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1089) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1090](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1090) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1091](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1091) | `doesNotContainPattern("(?i)<script[\\s>]")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1094](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1094) | `doesNotContain(".trend-plot")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1095](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1095) | `doesNotContain(".trend-card-legend")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1096](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1096) | `doesNotContain(".trend-legend-item")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1097](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1097) | `doesNotContain(".trend-legend-swatch")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1098](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1098) | `doesNotContain(".progress-bar-error")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1099](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1099) | `doesNotContain(".readiness-value")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1100](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1100) | `contains(".run-row:focus-visible")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1101](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1101) | `contains(".kpi-card:focus-visible")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1102](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1102) | `contains(".priority-table td.dashboard-disclosure-cell")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1105](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1105) | `contains("syncTableCellDisclosures")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1106](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1106) | `contains("preview.scrollWidth > preview.clientWidth")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1107](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1107) | `contains("preview.scrollHeight > preview.clientHeight")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1108](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1108) | `contains("document.fonts.ready")` | [이 메서드의 대응·제외](#mapping-24) |
| [L1109](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1109) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-24) |

## PublishingTemplateStructureTest#errorScreensUseCommonComponentsAndShowCodeWithTraceId

<a id="mapping-25"></a>

대체 테스트: ERROR + FORMAT 오류코드/요청번호·권한·404·스택 비노출.

구조 제외·남은 확인: MPA 에러 템플릿/레이아웃 구조 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L1126](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1126) | `contains("class=\"page-content error-page\"")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1127](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1127) | `contains("class=\"surface error-card\"")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1128](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1128) | `contains("class=\"surface-header\"")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1129](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1129) | `contains("class=\"surface-title\"")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1130](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1130) | `contains("class=\"surface-body error-card-body\"")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1131](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1131) | `contains("class=\"button button-secondary error-action\"")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1134](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1134) | `contains("requestId")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1135](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1135) | `doesNotContain("fgc-main")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1136](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1136) | `doesNotContain("fgc-card")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1137](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1137) | `doesNotContain("fgc-btn")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1138](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1138) | `doesNotContain("fgc-help")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1139](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1139) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1140](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1140) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1141](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1141) | `doesNotContainPattern("(?i)<script[\\s>]")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1154](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1154) | `contains("${errorCode}")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1155](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1155) | `contains("' · 요청 ID: ' + ${requestId}")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1157](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1157) | `contains("'요청 ID: ' + ${requestId}")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1158](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1158) | `doesNotContain("${errorCode}")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1159](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1159) | `doesNotContain("FGC-COMMON-004")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1163](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1163) | `contains("${errorCode}")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1164](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1164) | `contains("'{requestId}', requestId ?: '-'")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1168](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1168) | `contains("#{error.auth.forbidden}")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1169](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1169) | `contains("#{error.auth.forbiddenHelp}")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1171](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1171) | `contains("#{error.common.pageNotFound}")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1172](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1172) | `contains("#{error.common.pageNotFoundHelp}")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1175](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1175) | `contains("th:if=\"${#strings.startsWith(screenId, 'FGC-UI-ERR-')}\" th:href=\"@{/css/features/error.css}\"")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1178](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1178) | `contains(".error-page")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1179](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1179) | `contains(".error-card")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1180](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1180) | `contains(".error-trace")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1181](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1181) | `contains("var(--space-")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1182](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1182) | `contains("var(--color-text-tertiary)")` | [이 메서드의 대응·제외](#mapping-25) |
| [L1183](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1183) | `doesNotContain("var(--fgc-")` | [이 메서드의 대응·제외](#mapping-25) |

## PublishingTemplateStructureTest#loginScreenKeepsCommonComponentsAndHidesAuthFailureDetail

<a id="mapping-26"></a>

대체 테스트: AUTH 필수값·비밀번호 토글·동일 실패문구·로그아웃·FORMAT.

구조 제외·남은 확인: formLogin action·서버 `th:if` 분기 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L1199](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1199) | `contains("class=\"field auth-field\"")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1200](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1200) | `contains("class=\"field-label\"")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1201](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1201) | `contains("class=\"field-control auth-control\"")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1202](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1202) | `contains("class=\"button button-primary button-large auth-submit\"")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1203](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1203) | `contains("class=\"icon-button auth-password-toggle\"")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1204](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1204) | `contains("aria-pressed=\"false\"")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1205](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1205) | `contains("role=\"alert\" aria-live=\"polite\"")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1207](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1207) | `contains("#{error.auth.invalidCredentials}")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1212](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1212) | `doesNotContain("auth-capability")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1213](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1213) | `doesNotContain("auth-security-notice")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1214](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1214) | `doesNotContain("30분간 사용하지 않으면 자동으로 로그아웃됩니다.")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1215](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1215) | `doesNotContain("fgc-")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1216](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1216) | `doesNotContain("toast-region")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1217](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1217) | `doesNotContain("FGC-AUTH-")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1218](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1218) | `doesNotContain("requestId")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1219](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1219) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1220](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1220) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1221](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1221) | `doesNotContain("<script>")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1225](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1225) | `contains("passwordToggle.setAttribute(\"aria-pressed\", String(willShow))")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1226](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1226) | `contains("passwordToggle.setAttribute(\"aria-label\", willShow ? \"비밀번호 숨기기\" : \"비밀번호 표시\")")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1227](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1227) | `doesNotContain("FGC-AUTH-")` | [이 메서드의 대응·제외](#mapping-26) |
| [L1228](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1228) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-26) |

## PublishingTemplateStructureTest#validationRunScreensUseCommonComponentsWithoutInlinePresentation

<a id="mapping-27"></a>

대체 테스트: VRUN 목록·생성·폴링·상태·체크리스트·멱등 확정·권한.

구조 제외·남은 확인: legacy JS/layout 제외. 6→8조건 불일치 D1 수정.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L1241](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1241) | `contains("class=\"page-content vrun-page vrun-list-page\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1242](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1242) | `contains("class=\"filter-bar vrun-filter-bar\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1243](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1243) | `contains("class=\"data-table-viewport vrun-table-viewport\" tabindex=\"0\" role=\"region\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1244](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1244) | `contains("<colgroup>")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1245](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1245) | `contains("scope=\"col\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1246](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1246) | `contains("class=\"visually-hidden\">월 통합검증 실행 목록")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1247](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1247) | `contains("class=\"table-cell-disclosure\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1248](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1248) | `contains("class=\"pagination-controls\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1249](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1249) | `contains("aria-label=\"월 통합검증 실행 목록 페이지\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1251](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1251) | `contains("data-success-message=${successMessage}")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1252](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1252) | `contains("data-error-message=${errorMessage}")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1254](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1254) | `contains("id=\"btn-create\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1255](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1255) | `contains("data-fgc-action=\"create\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1256](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1256) | `contains("name=\"month\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1257](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1257) | `contains("name=\"runType\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1258](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1258) | `contains("name=\"status\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1260](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1260) | `doesNotContain("onchange=")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1261](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1261) | `doesNotContain("class=\"fgc-")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1262](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1262) | `doesNotContain("fgc-banner")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1263](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1263) | `doesNotContain("publishing-page")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1264](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1264) | `doesNotContain("<th style=\"width:")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1265](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1265) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1268](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1268) | `contains("class=\"page-content vrun-page vrun-detail-page\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1270](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1270) | `contains("data-modal=\"vrun-finalize\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1271](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1271) | `contains("id=\"btn-finalize-submit\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1272](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1272) | `contains("data-modal-initial-focus")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1274](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1274) | `contains("data-modal=\"vrun-finalized\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1275](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1275) | `contains("id=\"btn-finalized-close\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1277](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1277) | `contains("class=\"kpi-grid vrun-summary-grid\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1278](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1278) | `contains("class=\"kpi-card\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1280](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1280) | `contains("class=\"vrun-stepper\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1281](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1281) | `contains("data-step-state")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1283](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1283) | `contains("id=\"progress-text\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1285](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1285) | `contains("id=\"finalize-action-note\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1286](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1286) | `contains("id=\"execute-action-note\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1287](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1287) | `contains("aria-describedby=\"finalize-action-guide finalize-action-note\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1289](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1289) | `contains("id=\"cond-body\" aria-live=\"polite\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1290](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1290) | `contains("data-checklist-passed=\"false\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1291](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1291) | `contains("data-can-finalize=${roleCode == 'GA_ADMIN' or roleCode == 'SYSTEM_ADMIN'}")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1292](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1292) | `contains("검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1293](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1293) | `containsOnlyOnce("id=\"finalize-action-guide\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1294](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1294) | `containsOnlyOnce("id=\"btn-finalize\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1295](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1295) | `doesNotContain("class=\"fgc-")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1296](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1296) | `doesNotContain("publishing-result-placeholder")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1297](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1297) | `doesNotContain("publishing-page")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1298](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1298) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1299](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1299) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1300](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1300) | `doesNotContain("<script>")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1304](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1304) | `contains("th:fragment=\"runStatusBadge(status, label)\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1305](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1305) | `contains("status-badge-error")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1306](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1306) | `contains("status-badge-review")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1307](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1307) | `contains("status-badge-success")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1308](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1308) | `contains("status-badge-info")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1309](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1309) | `contains("status-badge-neutral")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1310](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1310) | `contains("vrun-badge-lock")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1314](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1314) | `contains("STATUS_BADGE_CLASSES")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1315](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1315) | `contains("CREATED: \"status-badge-neutral\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1316](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1316) | `contains("RUNNING: \"status-badge-info\"")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1317](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1317) | `contains("statusBadge.classList.remove.apply(statusBadge.classList, STATUS_BADGE_CLASS_NAMES)")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1318](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1318) | `doesNotContain("statusBadge.className =")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1320](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1320) | `contains("pollFailures >= 3")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1322](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1322) | `contains("modal.open(\"vrun-finalized\")")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1324](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1324) | `doesNotContain("finalizeButton.title =")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1325](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1325) | `contains("finalizeNote.textContent = reason")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1328](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1328) | `contains("main.dataset[attribute]")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1329](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1329) | `contains("delete main.dataset[attribute]")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1330](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1330) | `contains("flash(\"successMessage\", \"success\", 5000)")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1331](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1331) | `contains("flash(\"errorMessage\", \"error\", 0)")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1332](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1332) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1335](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1335) | `contains(".vrun-stepper")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1336](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1336) | `contains(".vrun-inline-state")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1337](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1337) | `contains(".vrun-kv")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1338](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1338) | `contains(".vrun-mid-grid")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1340](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1340) | `contains("@media (max-width: 63.9375rem)")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1341](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1341) | `contains(":focus-visible")` | [이 메서드의 대응·제외](#mapping-27) |
| [L1344](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1344) | `contains("th:src=\"@{/js/features/vrun/vrun-list.js}\" defer")` | [이 메서드의 대응·제외](#mapping-27) |

## PublishingTemplateStructureTest#scheduleListUsesCommonFilterBarAndDisclosureInsteadOfTitleTooltips

<a id="mapping-28"></a>

대체 테스트: SCHEDULE URL·페이지 보정·라벨·CSV·오류; COMMON.

구조 제외·남은 확인: legacy selector 제외. 모든 긴 셀의 실제 overflow D3.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L1357](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1357) | `contains("class=\"page-content schedule-list-page\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1358](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1358) | `contains("class=\"filter-bar schedule-filter-bar\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1359](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1359) | `contains("class=\"filter-fields schedule-filter-fields\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1360](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1360) | `contains("class=\"filter-field-label\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1361](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1361) | `contains("class=\"filter-actions schedule-filter-actions\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1363](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1363) | `contains("data-schedule-filter-form")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1364](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1364) | `contains("data-schedule-filter-reset")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1365](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1365) | `contains("name=\"contractNo\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1366](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1366) | `contains("name=\"stage\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1367](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1367) | `contains("name=\"regime\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1368](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1368) | `contains("name=\"purpose\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1369](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1369) | `contains("name=\"status\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1371](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1371) | `contains("id=\"schedule-regime-evidence\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1372](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1372) | `contains("REG-19 · 적용 체계 판정")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1373](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1373) | `doesNotContain("class=\"page-description\"")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1374](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1374) | `doesNotContain("schedule-guidance")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1375](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1375) | `doesNotContain("publishing-page")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1378](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1378) | `contains(".schedule-filter-bar")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1379](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1379) | `contains(".schedule-filter-fields")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1380](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1380) | `contains(":focus-visible")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1382](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1382) | `doesNotContain(".schedule-guidance")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1383](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1383) | `doesNotContain(".schedule-filter-grid")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1384](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1384) | `doesNotContain(".schedule-policy-value")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1387](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1387) | `contains("table-cell-disclosure")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1388](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1388) | `contains("table-cell-preview is-single-line")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1389](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1389) | `contains("preview.scrollWidth > preview.clientWidth")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1390](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1390) | `contains("format.won(")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1391](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1391) | `contains("format.int(")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1392](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1392) | `contains("scheduleLabels.statusTone(schedule.status)")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1393](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1393) | `contains("scheduleLabels.isLocked(schedule.status)")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1395](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1395) | `contains("function exportCsv(url)")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1396](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1396) | `contains("예상 스케줄 CSV를 내려받았습니다.")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1398](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1398) | `contains("normalizePage: normalizePage")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1399](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1399) | `contains("responseLabel: require(\"./schedule-labels.js\").responseLabel")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1401](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1401) | `doesNotContain("policyValue.title")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1402](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1402) | `doesNotContain("function formatWon")` | [이 메서드의 대응·제외](#mapping-28) |
| [L1403](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1403) | `doesNotContain("function formatInteger")` | [이 메서드의 대응·제외](#mapping-28) |

## PublishingTemplateStructureTest#scheduleDetailAddsConfirmDialogAndDropsLegacyShell

<a id="mapping-29"></a>

대체 테스트: SCHEDULE 사유·확정 대화상자·버전·권한·실제 서버 경로.

구조 제외·남은 확인: template scope·inline 스타일 금지 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L1416](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1416) | `contains("class=\"page-content schedule-detail-page\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1417](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1417) | `contains("class=\"surface schedule-detail-card\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1418](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1418) | `contains("class=\"data-table-viewport schedule-line-viewport\" tabindex=\"0\" role=\"region\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1419](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1419) | `contains("class=\"visually-hidden\">회차별 예상 지급 금액")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1420](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1420) | `contains("<colgroup>")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1421](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1421) | `contains("scope=\"col\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1423](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1423) | `contains("data-modal=\"schedule-confirm\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1424](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1424) | `contains("data-close-on-backdrop=\"true\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1425](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1425) | `contains("role=\"dialog\" aria-modal=\"true\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1426](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1426) | `contains("id=\"btn-confirm-submit\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1427](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1427) | `contains("되돌릴 수 없습니다 — 확정된 스케줄은 새 버전으로만 바꿉니다.")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1429](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1429) | `contains("data-modal=\"schedule-regenerate\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1430](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1430) | `contains("data-modal-initial-focus")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1431](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1431) | `contains("id=\"regenerate-reason-error\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1432](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1432) | `contains("class=\"field-error\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1434](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1434) | `contains(">규칙 ID<")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1436](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1436) | `contains("id=\"btn-regenerate\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1437](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1437) | `contains("data-fgc-action=\"regenerate\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1438](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1438) | `contains("data-fgc-action=\"confirm\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1439](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1439) | `contains("th:disabled=\"${!canProcess}\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1441](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1441) | `contains("id=\"schedule-action-note\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1442](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1442) | `contains("aria-describedby=\"schedule-action-note\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1443](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1443) | `doesNotContain("class=\"fgc-")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1444](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1444) | `doesNotContain("fgc-banner")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1445](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1445) | `doesNotContain("style=\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1446](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1446) | `doesNotContainPattern("(?i)<style[\\s>]")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1447](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1447) | `doesNotContain("<script>")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1451](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1451) | `contains("format.won(")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1452](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1452) | `contains("format.date(")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1453](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1453) | `contains("format.dateTime(")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1454](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1454) | `contains("format.rate(")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1455](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1455) | `doesNotContain("maximumFractionDigits")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1456](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1456) | `doesNotContain("replace(/-/g")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1457](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1457) | `doesNotContain("Intl.")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1459](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1459) | `contains("confirmButton.addEventListener(\"click\", openConfirmDialog)")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1460](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1460) | `contains("confirmSubmit.addEventListener(\"click\", confirmSchedule)")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1461](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1461) | `contains("openModal(\"schedule-confirm\")")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1463](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1463) | `contains("schedule-inline-state")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1464](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1464) | `contains("is-loading")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1465](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1465) | `contains("is-empty")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1466](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1466) | `contains("is-error")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1467](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1467) | `contains("retry.textContent = \"다시 시도\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1469](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1469) | `contains("row.className = \"is-selected\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1470](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1470) | `contains("row.setAttribute(\"aria-current\", \"true\")")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1471](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1471) | `doesNotContain("row.style.fontWeight")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1473](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1473) | `contains("window.FgcUi.scheduleLabels")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1474](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1474) | `doesNotContain("SCHEDULE_STATUS_LABELS = {")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1476](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1476) | `doesNotContain("contractMonthNo) >= 1")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1478](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1478) | `doesNotContain("console.error")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1481](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1481) | `contains("module.exports = api")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1482](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1482) | `contains("window.FgcUi.scheduleLabels = api")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1483](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1483) | `contains("CONFIRMED: \"status-badge-review\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1484](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1484) | `contains("HOLD: \"status-badge-review\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1485](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1485) | `contains("CANCELLED: \"status-badge-neutral\"")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1486](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1486) | `contains("LOCKED_STATUSES")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1489](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1489) | `contains(".schedule-detail-kv")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1490](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1490) | `contains(".schedule-inline-state")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1491](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1491) | `contains(".schedule-line-viewport")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1492](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1492) | `contains(".schedule-version-table tbody tr.is-selected")` | [이 메서드의 대응·제외](#mapping-29) |
| [L1493](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1493) | `doesNotContainPattern("#[0-9a-fA-F]{3,8}\\b")` | [이 메서드의 대응·제외](#mapping-29) |

## PublishingTemplateStructureTest#productionLayoutLoadsScheduleLabelsBeforeScheduleScreens

<a id="mapping-30"></a>

대체 테스트: SCHEDULE `서버 라벨을 먼저 쓰고 없으면 코드표 → 코드값으로 대체한다`.

구조 제외·남은 확인: 전역 script 로드 순서는 ES module import로 대체.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L1506](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1506) | `contains("th:src=\"@{/js/features/schedule/schedule-labels.js}\" defer")` | [이 메서드의 대응·제외](#mapping-30) |
| [L1510](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1510) | `isLessThan(layout.indexOf("schedule-list.js"))` | [이 메서드의 대응·제외](#mapping-30) |
| [L1513](../../../src/test/java/com/susukkang/fgc/ui/PublishingTemplateStructureTest.java#L1513) | `isLessThan(layout.indexOf("schedule-detail.js"))` | [이 메서드의 대응·제외](#mapping-30) |

## AppShellWorkspaceStructureTest#headerRailContainsWorkspaceTabsAndFixedControlsWithoutLegacyTitleArea

<a id="mapping-31"></a>

대체 테스트: SHELL 헤더·탭·고정 대시보드.

구조 제외·남은 확인: `th:replace`·헤더 fragment 인자·legacy title 영역 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L18](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L18) | `contains("class=\"app-shell-layout\"")` | [이 메서드의 대응·제외](#mapping-31) |
| [L19](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L19) | `contains("header :: header(${screenId}, ${title})")` | [이 메서드의 대응·제외](#mapping-31) |
| [L20](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L20) | `contains("/js/common/sidebar-state-init.js")` | [이 메서드의 대응·제외](#mapping-31) |
| [L21](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L21) | `doesNotContain("app-sidebar-backdrop")` | [이 메서드의 대응·제외](#mapping-31) |
| [L24](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L24) | `contains("class=\"app-header app-header-rail\"")` | [이 메서드의 대응·제외](#mapping-31) |
| [L25](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L25) | `contains("workspaceTabs(${activeTabId}, ${activeTabTitle})")` | [이 메서드의 대응·제외](#mapping-31) |
| [L26](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L26) | `contains("class=\"app-header-actions\"")` | [이 메서드의 대응·제외](#mapping-31) |
| [L27](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L27) | `doesNotContain("app-header-role")` | [이 메서드의 대응·제외](#mapping-31) |
| [L28](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L28) | `doesNotContain("app-header-leading")` | [이 메서드의 대응·제외](#mapping-31) |
| [L29](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L29) | `doesNotContain("app-header-breadcrumb")` | [이 메서드의 대응·제외](#mapping-31) |

## AppShellWorkspaceStructureTest#shellAndTabsUseProductionDimensionsAndFullWidthAccent

<a id="mapping-32"></a>

대체 테스트: TOKENS·SHELL.

구조 제외·남은 확인: 픽셀 치수·수정 탭 hover/포커스 시각 계약 D3.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L35](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L35) | `contains("--layout-sidebar-width: 16rem")` | [이 메서드의 대응·제외](#mapping-32) |
| [L36](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L36) | `contains("--layout-sidebar-collapsed-width: 4.5rem")` | [이 메서드의 대응·제외](#mapping-32) |
| [L37](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L37) | `contains("--layout-header-height: 3.25rem")` | [이 메서드의 대응·제외](#mapping-32) |
| [L38](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L38) | `contains("--layout-workspace-tabs-height: 2.5rem")` | [이 메서드의 대응·제외](#mapping-32) |
| [L39](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L39) | `contains("--layout-workspace-tab-width: 13.75rem")` | [이 메서드의 대응·제외](#mapping-32) |
| [L42](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L42) | `contains("grid-template-columns: var(--layout-sidebar-current-width) minmax(0, 1fr)")` | [이 메서드의 대응·제외](#mapping-32) |
| [L43](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L43) | `contains(".app-shell.is-sidebar-collapsed")` | [이 메서드의 대응·제외](#mapping-32) |
| [L44](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L44) | `contains("flex: 0 0 var(--layout-workspace-tab-width)")` | [이 메서드의 대응·제외](#mapping-32) |
| [L45](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L45) | `contains(".workspace-tab.is-modified:not(:hover):not(:focus-within)")` | [이 메서드의 대응·제외](#mapping-32) |
| [L46](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L46) | `contains("background: var(--color-bg-surface)")` | [이 메서드의 대응·제외](#mapping-32) |
| [L47](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L47) | `contains(".workspace-tab.is-active::before")` | [이 메서드의 대응·제외](#mapping-32) |
| [L48](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L48) | `contains("top: 0;\n  right: 0;\n  left: 0;")` | [이 메서드의 대응·제외](#mapping-32) |

## AppShellWorkspaceStructureTest#workspaceTabsPersistStateAndChooseRightThenLeftThenDashboardOnClose

<a id="mapping-33"></a>

대체 테스트: workspace `닫은 활성 탭은 오른쪽, 없으면 왼쪽 탭으로 이동한다`, 10개 제한·복원·변조 배제.

구조 제외·남은 확인: legacy storage 키/ResizeObserver 구현 문자열 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L56](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L56) | `contains("fgc.workspace-tabs.v1")` | [이 메서드의 대응·제외](#mapping-33) |
| [L57](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L57) | `contains("window.sessionStorage.getItem")` | [이 메서드의 대응·제외](#mapping-33) |
| [L58](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L58) | `contains("window.sessionStorage.setItem")` | [이 메서드의 대응·제외](#mapping-33) |
| [L59](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L59) | `contains("tab.modified === true")` | [이 메서드의 대응·제외](#mapping-33) |
| [L60](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L60) | `contains("workspace-tab-modified")` | [이 메서드의 대응·제외](#mapping-33) |
| [L61](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L61) | `contains("markModified")` | [이 메서드의 대응·제외](#mapping-33) |
| [L62](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L62) | `contains("tabs[Math.min(index, tabs.length - 1)]")` | [이 메서드의 대응·제외](#mapping-33) |
| [L63](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L63) | `contains("id: DASHBOARD_TAB_ID")` | [이 메서드의 대응·제외](#mapping-33) |
| [L64](../../../src/test/java/com/susukkang/fgc/ui/AppShellWorkspaceStructureTest.java#L64) | `contains("ResizeObserver")` | [이 메서드의 대응·제외](#mapping-33) |

## ArbitragePublishingStructureTest#arbitrageListUsesCommonComponentsWithoutInlinePresentation

<a id="mapping-34"></a>

대체 테스트: ARB 필터·요약·권한·재검증·toast.

구조 제외·남은 확인: class/JS 경로·inline 검사 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L15](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L15) | `contains("class=\"page-header\"")` | [이 메서드의 대응·제외](#mapping-34) |
| [L16](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L16) | `contains("class=\"filter-bar arb-filter-bar\"")` | [이 메서드의 대응·제외](#mapping-34) |
| [L17](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L17) | `contains("class=\"kpi-grid arb-summary-grid\"")` | [이 메서드의 대응·제외](#mapping-34) |
| [L18](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L18) | `contains("class=\"surface arb-list-panel\"")` | [이 메서드의 대응·제외](#mapping-34) |
| [L19](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L19) | `contains("class=\"data-table arb-list-table\"")` | [이 메서드의 대응·제외](#mapping-34) |
| [L20](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L20) | `contains("id=\"arb-pagination\"")` | [이 메서드의 대응·제외](#mapping-34) |
| [L21](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L21) | `contains("data-modal=\"arb-recheck\"")` | [이 메서드의 대응·제외](#mapping-34) |
| [L22](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L22) | `doesNotContain("fgc-page-desc")` | [이 메서드의 대응·제외](#mapping-34) |
| [L23](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L23) | `doesNotContain("fgc-banner")` | [이 메서드의 대응·제외](#mapping-34) |
| [L24](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L24) | `doesNotContain("class=\"guidance")` | [이 메서드의 대응·제외](#mapping-34) |
| [L25](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L25) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-34) |
| [L26](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L26) | `doesNotContain("<script>")` | [이 메서드의 대응·제외](#mapping-34) |
| [L29](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L29) | `contains("/css/features/arbitrage.css")` | [이 메서드의 대응·제외](#mapping-34) |
| [L31](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L31) | `contains(".arb-page")` | [이 메서드의 대응·제외](#mapping-34) |
| [L32](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L32) | `contains(".arb-result-row.is-selected")` | [이 메서드의 대응·제외](#mapping-34) |
| [L33](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L33) | `contains("@media (max-width: 71.875rem)")` | [이 메서드의 대응·제외](#mapping-34) |

## ArbitragePublishingStructureTest#arbitrageRowsKeepPlainContractTextAndKeyboardSelection

<a id="mapping-35"></a>

대체 테스트: ARB `rows support keyboard activation and timeline uses stored excess without recalculating`.

구조 제외·남은 확인: legacy data-* selector 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L39](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L39) | `contains("document.querySelector(\".arb-page\")")` | [이 메서드의 대응·제외](#mapping-35) |
| [L40](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L40) | `contains("status-badge status-badge-success")` | [이 메서드의 대응·제외](#mapping-35) |
| [L41](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L41) | `contains("class=\"arb-result-row\"")` | [이 메서드의 대응·제외](#mapping-35) |
| [L42](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L42) | `contains("tabindex=\"0\" aria-selected=\"false\"")` | [이 메서드의 대응·제외](#mapping-35) |
| [L43](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L43) | `contains("event.key !== \"Enter\" && event.key !== \" \"")` | [이 메서드의 대응·제외](#mapping-35) |
| [L44](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L44) | `doesNotContain("arb-row-select")` | [이 메서드의 대응·제외](#mapping-35) |
| [L45](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L45) | `doesNotContain("fgc-btn")` | [이 메서드의 대응·제외](#mapping-35) |
| [L46](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L46) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-35) |
| [L47](../../../src/test/java/com/susukkang/fgc/ui/ArbitragePublishingStructureTest.java#L47) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-35) |

## AuditTemplateStructureTest#auditListUsesSharedDisclosureAndAccessibleRowSelection

<a id="mapping-36"></a>

대체 테스트: AUDIT 키보드 Enter/Space·다른 칸 클릭·selected 복원·diff.

구조 제외·남은 확인: `th:data-audit-detail-href`/window.location 제외; 긴 값 조건 D2; CSS 원본 의존 D4.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L15](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L15) | `doesNotContain("class=\"audit-notice\"")` | [이 메서드의 대응·제외](#mapping-36) |
| [L16](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L16) | `doesNotContain("class=\"page-description\"")` | [이 메서드의 대응·제외](#mapping-36) |
| [L17](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L17) | `doesNotContain("바뀐 칸만 노랗게 표시")` | [이 메서드의 대응·제외](#mapping-36) |
| [L18](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L18) | `contains("tabindex=\"0\"")` | [이 메서드의 대응·제외](#mapping-36) |
| [L19](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L19) | `contains("aria-controls=\"diff-body\"")` | [이 메서드의 대응·제외](#mapping-36) |
| [L20](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L20) | `contains("th:data-audit-detail-href")` | [이 메서드의 대응·제외](#mapping-36) |
| [L21](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L21) | `contains("class=\"table-cell-disclosure\"")` | [이 메서드의 대응·제외](#mapping-36) |
| [L22](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L22) | `contains("class=\"table-cell-details\" hidden")` | [이 메서드의 대응·제외](#mapping-36) |
| [L23](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L23) | `contains("전체 보기")` | [이 메서드의 대응·제외](#mapping-36) |
| [L24](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L24) | `contains("접기")` | [이 메서드의 대응·제외](#mapping-36) |
| [L27](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L27) | `doesNotContain(".audit-notice")` | [이 메서드의 대응·제외](#mapping-36) |
| [L28](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L28) | `contains(".audit-log-row:focus-visible")` | [이 메서드의 대응·제외](#mapping-36) |
| [L29](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L29) | `contains(".data-table td.audit-disclosure-cell")` | [이 메서드의 대응·제외](#mapping-36) |
| [L32](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L32) | `contains(".table-cell-disclosure")` | [이 메서드의 대응·제외](#mapping-36) |
| [L33](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L33) | `contains(".table-cell-preview.is-single-line")` | [이 메서드의 대응·제외](#mapping-36) |
| [L34](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L34) | `contains(".table-cell-details summary:focus-visible")` | [이 메서드의 대응·제외](#mapping-36) |
| [L35](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L35) | `contains(".table-cell-details[open] .table-cell-less")` | [이 메서드의 대응·제외](#mapping-36) |
| [L38](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L38) | `contains("/js/features/audit/audit-list.js")` | [이 메서드의 대응·제외](#mapping-36) |
| [L41](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L41) | `contains("[data-audit-detail-href]")` | [이 메서드의 대응·제외](#mapping-36) |
| [L42](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L42) | `contains("row.addEventListener(\"click\"")` | [이 메서드의 대응·제외](#mapping-36) |
| [L43](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L43) | `contains("row.addEventListener(\"keydown\"")` | [이 메서드의 대응·제외](#mapping-36) |
| [L44](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L44) | `contains("event.key !== \"Enter\"")` | [이 메서드의 대응·제외](#mapping-36) |
| [L45](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L45) | `contains("event.target !== row")` | [이 메서드의 대응·제외](#mapping-36) |
| [L46](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L46) | `contains("a, button, input, select, textarea, summary, details")` | [이 메서드의 대응·제외](#mapping-36) |
| [L47](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L47) | `contains("window.location.assign(href)")` | [이 메서드의 대응·제외](#mapping-36) |
| [L48](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L48) | `contains("preview.scrollWidth > preview.clientWidth")` | [이 메서드의 대응·제외](#mapping-36) |
| [L49](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L49) | `contains("preview.scrollHeight > preview.clientHeight")` | [이 메서드의 대응·제외](#mapping-36) |
| [L50](../../../src/test/java/com/susukkang/fgc/ui/AuditTemplateStructureTest.java#L50) | `contains("document.fonts.ready")` | [이 메서드의 대응·제외](#mapping-36) |

## CapPublishingTemplateStructureTest#capListUsesCommonComponentsAndServerAggregates

<a id="mapping-37"></a>

대체 테스트: CAP 필터·서버 집계·목록 선택·라벨·사용률·권한.

구조 제외·남은 확인: legacy selector와 CSS scope 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L15](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L15) | `contains("class=\"surface cap-filter-panel\"")` | [이 메서드의 대응·제외](#mapping-37) |
| [L16](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L16) | `contains("class=\"kpi-card cap-kpi-card")` | [이 메서드의 대응·제외](#mapping-37) |
| [L17](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L17) | `contains("class=\"data-table cap-contract-table\"")` | [이 메서드의 대응·제외](#mapping-37) |
| [L18](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L18) | `contains("class=\"data-table cap-agent-table\"")` | [이 메서드의 대응·제외](#mapping-37) |
| [L19](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L19) | `contains("지급단계별 한도 게이지")` | [이 메서드의 대응·제외](#mapping-37) |
| [L20](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L20) | `contains("모니터링 지표 · 규제 판정 아님")` | [이 메서드의 대응·제외](#mapping-37) |
| [L21](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L21) | `contains("data-modal=\"cap-detail\"")` | [이 메서드의 대응·제외](#mapping-37) |
| [L22](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L22) | `contains("class=\"modal-footer cap-detail-modal-footer\"")` | [이 메서드의 대응·제외](#mapping-37) |
| [L23](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L23) | `contains("class=\"button button-primary\"")` | [이 메서드의 대응·제외](#mapping-37) |
| [L24](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L24) | `doesNotContain("기준월과 계약 조건으로 저장된 판정 결과를 조회합니다.")` | [이 메서드의 대응·제외](#mapping-37) |
| [L25](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L25) | `doesNotContain("서로 다른 규제이므로 두 단계를 합산하지 않습니다.")` | [이 메서드의 대응·제외](#mapping-37) |
| [L26](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L26) | `doesNotContain("규제 판정 단위는 계약 1건입니다.")` | [이 메서드의 대응·제외](#mapping-37) |
| [L27](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L27) | `doesNotContain("GA → 설계사 지급단계만 집계합니다.")` | [이 메서드의 대응·제외](#mapping-37) |
| [L28](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L28) | `doesNotContain("검증 당시 저장된 값과 항목별 판단을 확인합니다.")` | [이 메서드의 대응·제외](#mapping-37) |
| [L29](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L29) | `doesNotContain("판정과 상세는 읽기 전용이며 이 팝업에서 수정할 수 없습니다.")` | [이 메서드의 대응·제외](#mapping-37) |
| [L30](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L30) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-37) |
| [L31](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L31) | `doesNotContain("cap-guidance")` | [이 메서드의 대응·제외](#mapping-37) |
| [L32](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L32) | `doesNotContain("fgc-banner")` | [이 메서드의 대응·제외](#mapping-37) |
| [L33](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L33) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-37) |
| [L36](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L36) | `contains("/api/v1/cap-checks?")` | [이 메서드의 대응·제외](#mapping-37) |
| [L37](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L37) | `contains("renderStageSummary(data.stageSummary)")` | [이 메서드의 대응·제외](#mapping-37) |
| [L38](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L38) | `contains("renderAgentSummary(data.agentSummary)")` | [이 메서드의 대응·제외](#mapping-37) |
| [L39](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L39) | `contains("item.resultStatus")` | [이 메서드의 대응·제외](#mapping-37) |
| [L40](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L40) | `contains("function tableCellDisclosure(")` | [이 메서드의 대응·제외](#mapping-37) |
| [L41](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L41) | `contains("preview.scrollWidth > preview.clientWidth")` | [이 메서드의 대응·제외](#mapping-37) |
| [L42](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L42) | `contains("preview.scrollHeight > preview.clientHeight")` | [이 메서드의 대응·제외](#mapping-37) |
| [L43](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L43) | `contains("document.fonts.ready")` | [이 메서드의 대응·제외](#mapping-37) |
| [L44](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L44) | `doesNotContain("fetch(")` | [이 메서드의 대응·제외](#mapping-37) |

## CapPublishingTemplateStructureTest#capDetailRemainsACommonComponentModalWithoutGuidanceOrInlineStyles

<a id="mapping-38"></a>

대체 테스트: CAP 상세 딥링크·팝업·오류·COMMON Escape/포커스.

구조 제외·남은 확인: template fragment/inline 금지 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L50](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L50) | `contains("th:fragment=\"modal\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L51](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L51) | `contains("class=\"surface cap-detail-summary\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L52](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L52) | `contains("class=\"data-table cap-detail-table\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L53](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L53) | `contains("class=\"cap-detail-contract-context\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L54](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L54) | `contains("role=\"progressbar\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L55](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L55) | `contains("id=\"formula-limit\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L56](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L56) | `contains("id=\"final-warning-mark\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L57](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L57) | `contains("id=\"detail-count\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L58](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L58) | `contains("id=\"detail-included-note\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L59](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L59) | `contains("<th scope=\"col\">근거</th>")` | [이 메서드의 대응·제외](#mapping-38) |
| [L60](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L60) | `doesNotContain("id=\"sum-kind\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L61](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L61) | `doesNotContain("CAP-GA-FC-V1")` | [이 메서드의 대응·제외](#mapping-38) |
| [L62](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L62) | `doesNotContain("무엇으로 계산했나")` | [이 메서드의 대응·제외](#mapping-38) |
| [L63](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L63) | `doesNotContain("어떻게 계산했나")` | [이 메서드의 대응·제외](#mapping-38) |
| [L64](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L64) | `doesNotContain("결론은 무엇인가")` | [이 메서드의 대응·제외](#mapping-38) |
| [L65](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L65) | `doesNotContain("STEP 3 ·")` | [이 메서드의 대응·제외](#mapping-38) |
| [L66](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L66) | `doesNotContain("판단 이유는 검증 당시 저장된 필수 값이며")` | [이 메서드의 대응·제외](#mapping-38) |
| [L67](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L67) | `doesNotContain("<main")` | [이 메서드의 대응·제외](#mapping-38) |
| [L68](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L68) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-38) |
| [L69](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L69) | `doesNotContain("fgc-")` | [이 메서드의 대응·제외](#mapping-38) |
| [L70](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L70) | `doesNotContain("guidance")` | [이 메서드의 대응·제외](#mapping-38) |
| [L71](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L71) | `doesNotContain("style=")` | [이 메서드의 대응·제외](#mapping-38) |
| [L74](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L74) | `contains(".cap-detail-layout")` | [이 메서드의 대응·제외](#mapping-38) |
| [L75](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L75) | `contains(".cap-detail-table")` | [이 메서드의 대응·제외](#mapping-38) |
| [L76](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L76) | `contains("@media (max-width: 63.9375rem)")` | [이 메서드의 대응·제외](#mapping-38) |
| [L77](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L77) | `contains("@media (max-width: 47.9375rem)")` | [이 메서드의 대응·제외](#mapping-38) |
| [L78](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L78) | `doesNotContain(".cap-guidance")` | [이 메서드의 대응·제외](#mapping-38) |
| [L81](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L81) | `contains("룰셋 ID ")` | [이 메서드의 대응·제외](#mapping-38) |
| [L82](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L82) | `contains("cap_check #")` | [이 메서드의 대응·제외](#mapping-38) |
| [L83](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L83) | `contains("data.warningStartUsagePct")` | [이 메서드의 대응·제외](#mapping-38) |
| [L84](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L84) | `contains("item.resultStatus")` | [이 메서드의 대응·제외](#mapping-38) |
| [L85](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L85) | `contains("증빙 미연결 / 후속 연결 대기")` | [이 메서드의 대응·제외](#mapping-38) |
| [L86](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L86) | `doesNotContain("item.checkKind")` | [이 메서드의 대응·제외](#mapping-38) |
| [L89](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L89) | `contains("emptyCell.colSpan = 6")` | [이 메서드의 대응·제외](#mapping-38) |
| [L90](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L90) | `contains("capDisclosureCell(detail.decisionReason")` | [이 메서드의 대응·제외](#mapping-38) |
| [L91](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L91) | `contains("status-badge \" + capClassificationClass")` | [이 메서드의 대응·제외](#mapping-38) |
| [L92](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L92) | `contains("syncCapDisclosures(content)")` | [이 메서드의 대응·제외](#mapping-38) |
| [L93](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L93) | `contains("formula-limit")` | [이 메서드의 대응·제외](#mapping-38) |
| [L94](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L94) | `contains("data.warningStartUsagePct")` | [이 메서드의 대응·제외](#mapping-38) |
| [L95](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L95) | `doesNotContain("item.checkKind")` | [이 메서드의 대응·제외](#mapping-38) |
| [L98](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L98) | `contains("class=\"modal-header cap-detail-modal-header\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L99](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L99) | `contains("class=\"modal-footer cap-detail-modal-footer\"")` | [이 메서드의 대응·제외](#mapping-38) |
| [L100](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L100) | `doesNotContain("검증 당시 저장된 값과 항목별 판단을 확인합니다.")` | [이 메서드의 대응·제외](#mapping-38) |
| [L101](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L101) | `doesNotContain("판정과 상세는 읽기 전용이며 이 팝업에서 수정할 수 없습니다.")` | [이 메서드의 대응·제외](#mapping-38) |
| [L102](../../../src/test/java/com/susukkang/fgc/ui/CapPublishingTemplateStructureTest.java#L102) | `doesNotContain("page-description")` | [이 메서드의 대응·제외](#mapping-38) |

## GlobalMonthSelectorStructureTest#headerProvidesAccessiblePopoverAndTwelveMonthCells

<a id="mapping-39"></a>

대체 테스트: COMMON MonthSelector 연도·12개월·키보드·비활성 월.

구조 제외·남은 확인: legacy DOM id/스크립트 함수명 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L17](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L17) | `contains("data-month-selector")` | [이 메서드의 대응·제외](#mapping-39) |
| [L18](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L18) | `contains("aria-haspopup=\"dialog\"")` | [이 메서드의 대응·제외](#mapping-39) |
| [L19](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L19) | `contains("aria-expanded=\"false\"")` | [이 메서드의 대응·제외](#mapping-39) |
| [L20](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L20) | `contains("role=\"dialog\"")` | [이 메서드의 대응·제외](#mapping-39) |
| [L21](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L21) | `contains("role=\"grid\"")` | [이 메서드의 대응·제외](#mapping-39) |
| [L22](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L22) | `contains("tabindex=\"-1\"")` | [이 메서드의 대응·제외](#mapping-39) |
| [L23](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L23) | `contains("data-month-index=${monthIndex}")` | [이 메서드의 대응·제외](#mapping-39) |
| [L24](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L24) | `contains("${#numbers.sequence(0, 2)}")` | [이 메서드의 대응·제외](#mapping-39) |
| [L25](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L25) | `contains("${#numbers.sequence(1, 4)}")` | [이 메서드의 대응·제외](#mapping-39) |
| [L26](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L26) | `contains("data-action=\"cancel-month-selector\"")` | [이 메서드의 대응·제외](#mapping-39) |
| [L27](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L27) | `contains("data-action=\"apply-month-selector\"")` | [이 메서드의 대응·제외](#mapping-39) |

## GlobalMonthSelectorStructureTest#componentKeepsDraftSeparateUntilApplyAndSupportsDismissal

<a id="mapping-40"></a>

대체 테스트: COMMON `MonthSelector는 선택 초안을 적용하기 전까지 유지…`, 취소/Esc.

구조 제외·남은 확인: legacy draft 객체명 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L33](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L33) | `contains("displayYear: Number(value.slice(0, 4))")` | [이 메서드의 대응·제외](#mapping-40) |
| [L34](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L34) | `contains("nextYear < 1 &#124;&#124; nextYear > 9999")` | [이 메서드의 대응·제외](#mapping-40) |
| [L35](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L35) | `contains("previousYearButton")` | [이 메서드의 대응·제외](#mapping-40) |
| [L36](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L36) | `contains("nextYearButton")` | [이 메서드의 대응·제외](#mapping-40) |
| [L37](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L37) | `contains("cancelButton")` | [이 메서드의 대응·제외](#mapping-40) |
| [L38](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L38) | `contains("state.draftValue = state.value")` | [이 메서드의 대응·제외](#mapping-40) |
| [L39](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L39) | `contains("state.draftValue = cell.dataset.month")` | [이 메서드의 대응·제외](#mapping-40) |
| [L40](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L40) | `contains("state.draftValue = null")` | [이 메서드의 대응·제외](#mapping-40) |
| [L41](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L41) | `contains("settings.onApply(nextValue, state.value)")` | [이 메서드의 대응·제외](#mapping-40) |
| [L42](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L42) | `contains("event.key === \"Escape\"")` | [이 메서드의 대응·제외](#mapping-40) |
| [L43](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L43) | `contains("event.key === \"Enter\"")` | [이 메서드의 대응·제외](#mapping-40) |
| [L44](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L44) | `contains("event.key === \" \"")` | [이 메서드의 대응·제외](#mapping-40) |
| [L45](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L45) | `contains("!root.contains(event.target)")` | [이 메서드의 대응·제외](#mapping-40) |
| [L45](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L45) | `contains(event.target)` | [이 메서드의 대응·제외](#mapping-40) |
| [L46](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L46) | `contains("trigger.focus()")` | [이 메서드의 대응·제외](#mapping-40) |
| [L47](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L47) | `contains("ArrowLeft: -1")` | [이 메서드의 대응·제외](#mapping-40) |
| [L48](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L48) | `contains("ArrowUp: -4")` | [이 메서드의 대응·제외](#mapping-40) |
| [L49](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L49) | `contains("cell.tabIndex = selected && !disabled ? 0 : -1")` | [이 메서드의 대응·제외](#mapping-40) |
| [L50](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L50) | `contains("cell.disabled = disabled")` | [이 메서드의 대응·제외](#mapping-40) |

## GlobalMonthSelectorStructureTest#applySynchronizesEveryWorkspaceTabAndOnlyResetsPagination

<a id="mapping-41"></a>

대체 테스트: SHELL `기준월은 3개 탭의 URL에 반영하고 page만 지운다`, app-shell E2E.

구조 제외·남은 확인: 기존 location.replace 방식 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L56](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L56) | `contains("function applyGlobalMonth(month)")` | [이 메서드의 대응·제외](#mapping-41) |
| [L57](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L57) | `contains("previousTabs.map")` | [이 메서드의 대응·제외](#mapping-41) |
| [L58](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L58) | `contains("url.searchParams.set(\"month\", month)")` | [이 메서드의 대응·제외](#mapping-41) |
| [L59](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L59) | `contains("url.searchParams.delete(\"page\")")` | [이 메서드의 대응·제외](#mapping-41) |
| [L60](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L60) | `contains("getOpenTabCount")` | [이 메서드의 대응·제외](#mapping-41) |
| [L61](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L61) | `contains("restoreTabs")` | [이 메서드의 대응·제외](#mapping-41) |
| [L62](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L62) | `contains("tab.id === tabId")` | [이 메서드의 대응·제외](#mapping-41) |
| [L65](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L65) | `contains("workspaceTabs.applyGlobalMonth(month)")` | [이 메서드의 대응·제외](#mapping-41) |
| [L66](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L66) | `contains("navigateToGlobalMonth(month)")` | [이 메서드의 대응·제외](#mapping-41) |
| [L67](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L67) | `contains("resolve();")` | [이 메서드의 대응·제외](#mapping-41) |
| [L68](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L68) | `contains("workspaceTabs.restoreTabs(previousTabs)")` | [이 메서드의 대응·제외](#mapping-41) |

## GlobalMonthSelectorStructureTest#stylesMatchFigmaDimensionsAndProvideNarrowScreenFallback

<a id="mapping-42"></a>

대체 테스트: TOKENS·SHELL.

구조 제외·남은 확인: 상세 치수/좁은 화면 배치 승인 D3.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L74](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L74) | `contains(".month-selector-popover")` | [이 메서드의 대응·제외](#mapping-42) |
| [L75](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L75) | `contains("width: 22.5rem")` | [이 메서드의 대응·제외](#mapping-42) |
| [L76](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L76) | `contains("top: calc(100% + var(--space-2))")` | [이 메서드의 대응·제외](#mapping-42) |
| [L77](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L77) | `contains("grid-template-columns: repeat(4, minmax(0, 1fr))")` | [이 메서드의 대응·제외](#mapping-42) |
| [L78](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L78) | `contains(".month-selector-cell.is-selected")` | [이 메서드의 대응·제외](#mapping-42) |
| [L79](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L79) | `contains(".month-selector-cell.is-current")` | [이 메서드의 대응·제외](#mapping-42) |
| [L80](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L80) | `contains("prefers-reduced-motion: reduce")` | [이 메서드의 대응·제외](#mapping-42) |
| [L81](../../../src/test/java/com/susukkang/fgc/ui/GlobalMonthSelectorStructureTest.java#L81) | `contains("position: fixed")` | [이 메서드의 대응·제외](#mapping-42) |

## PendingActionButtonStructureTest#scheduleExportUsesCsvDownloadApi

<a id="mapping-43"></a>

대체 테스트: SCHEDULE `상세 CSV 는 회차 표 API 로 내려받는다`.

구조 제외·남은 확인: btn-export id·pending class 문자열 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L19](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L19) | `contains("id=\"btn-export\"")` | [이 메서드의 대응·제외](#mapping-43) |
| [L20](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L20) | `contains("CSV 내보내기")` | [이 메서드의 대응·제외](#mapping-43) |
| [L21](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L21) | `doesNotContain("schedule-export-pending")` | [이 메서드의 대응·제외](#mapping-43) |
| [L22](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L22) | `doesNotContain("API 화면 연동 대기")` | [이 메서드의 대응·제외](#mapping-43) |
| [L23](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L23) | `contains("/export.csv")` | [이 메서드의 대응·제외](#mapping-43) |

## PendingActionButtonStructureTest#validationRunFinalizeUsesFrontendChecklistAndRoleGate

<a id="mapping-44"></a>

대체 테스트: VRUN SETTLEMENT 비활성·GA_ADMIN 확정·미충족·7/8조건·멱등 재시도.

구조 제외·남은 확인: data-can-finalize·script 함수명 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L41](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L41) | `containsPattern("(?s)<button(?=[^>]*\\bid=\"btn-finalize\")"                         + "(?=[^>]*\\btype=\"button\")(?=[^>]*\\sdisabled\\b)"                         + "(?=[^>]*data-checklist-passed=\"false\")"                         + "(?=[^>]*data-can-finalize=)[^>]*>")` | [이 메서드의 대응·제외](#mapping-44) |
| [L45](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L45) | `contains("id=\"finalize-action-guide\"")` | [이 메서드의 대응·제외](#mapping-44) |
| [L46](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L46) | `contains("검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.")` | [이 메서드의 대응·제외](#mapping-44) |
| [L47](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L47) | `containsOnlyOnce("id=\"finalize-action-guide\"")` | [이 메서드의 대응·제외](#mapping-44) |
| [L48](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L48) | `containsOnlyOnce("id=\"btn-finalize\"")` | [이 메서드의 대응·제외](#mapping-44) |
| [L49](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L49) | `doesNotContain("id=\"finalize-actions-pending\"")` | [이 메서드의 대응·제외](#mapping-44) |
| [L50](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L50) | `doesNotContain("확정 조건 확인과 확정 작업은 API 연동 대기입니다.")` | [이 메서드의 대응·제외](#mapping-44) |
| [L52](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L52) | `contains("/finalize-checklist")` | [이 메서드의 대응·제외](#mapping-44) |
| [L53](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L53) | `contains("/finalize\"")` | [이 메서드의 대응·제외](#mapping-44) |
| [L54](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L54) | `contains("error.code === \"FGC-VRUN-006\"")` | [이 메서드의 대응·제외](#mapping-44) |
| [L55](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L55) | `contains("finalizeIdempotencyKey = null")` | [이 메서드의 대응·제외](#mapping-44) |
| [L56](../../../src/test/java/com/susukkang/fgc/ui/PendingActionButtonStructureTest.java#L56) | `contains("idempotencyKey: finalizeIdempotencyKey")` | [이 메서드의 대응·제외](#mapping-44) |

## SidebarSectionStateStructureTest#sidebarSectionsExposeStablePersistenceKeys

<a id="mapping-45"></a>

대체 테스트: SHELL Sidebar 섹션·역할별 표시·접힘 복원.

구조 제외·남은 확인: 기존 data-section-key 이름 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L17](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L17) | `contains("data-sidebar-section=\"contracts\"")` | [이 메서드의 대응·제외](#mapping-45) |
| [L18](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L18) | `contains("data-sidebar-section=\"compliance\"")` | [이 메서드의 대응·제외](#mapping-45) |
| [L19](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L19) | `contains("data-sidebar-section=\"ledger\"")` | [이 메서드의 대응·제외](#mapping-45) |
| [L20](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L20) | `contains("data-sidebar-section=\"admin\"")` | [이 메서드의 대응·제외](#mapping-45) |

## SidebarSectionStateStructureTest#appShellContainsSidebarPersistenceHooks

<a id="mapping-46"></a>

대체 테스트: SHELL 초기화/복원·로그아웃.

구조 제외·남은 확인: 초기화 script 삽입 위치·login.js 참조 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L28](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L28) | `contains("fgc.sidebar.open-sections.v1")` | [이 메서드의 대응·제외](#mapping-46) |
| [L29](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L29) | `contains("window.sessionStorage.getItem")` | [이 메서드의 대응·제외](#mapping-46) |
| [L30](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L30) | `contains("window.sessionStorage.setItem")` | [이 메서드의 대응·제외](#mapping-46) |
| [L31](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L31) | `contains("section.addEventListener(\"toggle\"")` | [이 메서드의 대응·제외](#mapping-46) |
| [L32](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L32) | `contains("if (savedOpenSections.indexOf(sectionId) !== -1) section.open = true")` | [이 메서드의 대응·제외](#mapping-46) |

## SidebarSectionStateStructureTest#sidebarCollapseStateUsesExplicitAccessibleControlsAndLocalStorage

<a id="mapping-47"></a>

대체 테스트: SHELL 버튼·aria-expanded·localStorage·역할 4종 Playwright.

구조 제외·남은 확인: legacy 함수/클래스 직접 검사 제외.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L43](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L43) | `contains("aria-label=\"사이드바 접기\"")` | [이 메서드의 대응·제외](#mapping-47) |
| [L44](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L44) | `contains("aria-label=\"사이드바 펼치기\"")` | [이 메서드의 대응·제외](#mapping-47) |
| [L45](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L45) | `contains("keyboard_double_arrow_left")` | [이 메서드의 대응·제외](#mapping-47) |
| [L46](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L46) | `contains("width=\"48\" height=\"48\"")` | [이 메서드의 대응·제외](#mapping-47) |
| [L49](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L49) | `doesNotContain("data-action=\"open-app-sidebar\"")` | [이 메서드의 대응·제외](#mapping-47) |
| [L50](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L50) | `doesNotContain("app-header-title")` | [이 메서드의 대응·제외](#mapping-47) |
| [L53](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L53) | `contains("fgc.sidebar.collapsed.v1")` | [이 메서드의 대응·제외](#mapping-47) |
| [L54](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L54) | `contains("window.localStorage.getItem")` | [이 메서드의 대응·제외](#mapping-47) |
| [L55](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L55) | `contains("window.localStorage.setItem")` | [이 메서드의 대응·제외](#mapping-47) |
| [L56](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L56) | `contains("narrowSidebarMedia.matches")` | [이 메서드의 대응·제외](#mapping-47) |
| [L57](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L57) | `contains("is-sidebar-collapsed")` | [이 메서드의 대응·제외](#mapping-47) |
| [L58](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L58) | `contains("sidebar.contains(event.target)")` | [이 메서드의 대응·제외](#mapping-47) |
| [L58](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L58) | `contains(event.target)` | [이 메서드의 대응·제외](#mapping-47) |
| [L59](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L59) | `contains("sectionSummary.closest(\"details\")")` | [이 메서드의 대응·제외](#mapping-47) |
| [L60](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L60) | `contains("if (section) section.open = true")` | [이 메서드의 대응·제외](#mapping-47) |
| [L61](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L61) | `doesNotContain("event.target.closest(\"a, button, summary")` | [이 메서드의 대응·제외](#mapping-47) |
| [L64](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L64) | `contains("fgc.sidebar.collapsed.v1")` | [이 메서드의 대응·제외](#mapping-47) |
| [L65](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L65) | `contains("window.localStorage.getItem")` | [이 메서드의 대응·제외](#mapping-47) |
| [L66](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L66) | `contains("document.body.classList.toggle(\"is-sidebar-collapsed\"")` | [이 메서드의 대응·제외](#mapping-47) |
| [L67](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L67) | `contains("document.body.classList.add(\"is-sidebar-ready\"")` | [이 메서드의 대응·제외](#mapping-47) |
| [L70](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L70) | `contains("[sessionStorage, localStorage]")` | [이 메서드의 대응·제외](#mapping-47) |
| [L71](../../../src/test/java/com/susukkang/fgc/ui/SidebarSectionStateStructureTest.java#L71) | `contains("key.startsWith(\"fgc.\")")` | [이 메서드의 대응·제외](#mapping-47) |

## StatusBadgeDesignTokenTest#warningAndRiskSolidBadgesUseProductionColorsWithoutChangingSoftTokens

<a id="mapping-48"></a>

대체 테스트: TOKENS status 24개 + COMMON badge 한글/상태 클래스.

구조 제외·남은 확인: 원본 비교만으로 삭제 승인 불가(D4); inverse 색상·실제 badge 배치 D3.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L18](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L18) | `contains("--color-status-warning-solid: #ea993e;")` | [이 메서드의 대응·제외](#mapping-48) |
| [L19](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L19) | `contains("--color-status-risk-solid: #ec7825;")` | [이 메서드의 대응·제외](#mapping-48) |
| [L20](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L20) | `contains("--color-status-warning-bg: #fffbeb;")` | [이 메서드의 대응·제외](#mapping-48) |
| [L21](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L21) | `contains("--color-status-warning-border: #fcd34d;")` | [이 메서드의 대응·제외](#mapping-48) |
| [L22](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L22) | `contains("--color-status-risk-bg: #fff7ed;")` | [이 메서드의 대응·제외](#mapping-48) |
| [L23](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L23) | `contains("--color-status-risk-border: #fdba74;")` | [이 메서드의 대응·제외](#mapping-48) |
| [L24](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L24) | `doesNotContain("--color-status-warning-solid: #b85c15;")` | [이 메서드의 대응·제외](#mapping-48) |
| [L25](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L25) | `doesNotContain("--color-status-risk-solid: #c95117;")` | [이 메서드의 대응·제외](#mapping-48) |
| [L28](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L28) | `contains("color: var(--color-text-inverse);")` | [이 메서드의 대응·제외](#mapping-48) |
| [L29](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L29) | `contains(".status-badge-warning {\n  background: var(--color-status-warning-solid);\n}")` | [이 메서드의 대응·제외](#mapping-48) |
| [L30](../../../src/test/java/com/susukkang/fgc/ui/StatusBadgeDesignTokenTest.java#L30) | `contains(".status-badge-risk {\n  background: var(--color-status-risk-solid);\n}")` | [이 메서드의 대응·제외](#mapping-48) |

## format.test.cjs#금액은 천 단위로 묶고 음수는 숫자만 괄호로 감싼다 (화면정의서 4장 규칙 1)

<a id="mapping-49"></a>

대체 테스트: FORMAT 동일 이름·동일 입력.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L7](../../../src/test/js/format.test.cjs#L7) | `assert.equal(format.won(1517944), "1,517,944원");` | [이 메서드의 대응·제외](#mapping-49) |
| [L9](../../../src/test/js/format.test.cjs#L9) | `assert.equal(format.won(-1517944), "(1,517,944)원");` | [이 메서드의 대응·제외](#mapping-49) |
| [L10](../../../src/test/js/format.test.cjs#L10) | `assert.equal(format.won(0), "0원");` | [이 메서드의 대응·제외](#mapping-49) |
| [L11](../../../src/test/js/format.test.cjs#L11) | `assert.equal(format.isNegative(-1), true);` | [이 메서드의 대응·제외](#mapping-49) |
| [L12](../../../src/test/js/format.test.cjs#L12) | `assert.equal(format.isNegative(0), false);` | [이 메서드의 대응·제외](#mapping-49) |
| [L13](../../../src/test/js/format.test.cjs#L13) | `assert.equal(format.isNegative(null), false);` | [이 메서드의 대응·제외](#mapping-49) |

## format.test.cjs#요율은 소수 넷째 자리까지 자르기만 하고 반올림하지 않는다 (규칙 3)

<a id="mapping-50"></a>

대체 테스트: FORMAT 동일 이름·동일 입력.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L17](../../../src/test/js/format.test.cjs#L17) | `assert.equal(format.rate("650.000000"), "650.0000");` | [이 메서드의 대응·제외](#mapping-50) |
| [L18](../../../src/test/js/format.test.cjs#L18) | `assert.equal(format.rate("1.234567"), "1.2345");` | [이 메서드의 대응·제외](#mapping-50) |
| [L19](../../../src/test/js/format.test.cjs#L19) | `assert.equal(format.rate("104.166667"), "104.1666");` | [이 메서드의 대응·제외](#mapping-50) |
| [L20](../../../src/test/js/format.test.cjs#L20) | `assert.equal(format.rate("1200"), "1,200.0000");` | [이 메서드의 대응·제외](#mapping-50) |

## format.test.cjs#사용률은 소수 여섯째 자리다 — 요율과 자리수가 다르다 (인터페이스정의서 2-4)

<a id="mapping-51"></a>

대체 테스트: FORMAT 동일 이름·동일 입력.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L25](../../../src/test/js/format.test.cjs#L25) | `assert.equal(format.usageRate("74.166667"), "74.166667");` | [이 메서드의 대응·제외](#mapping-51) |
| [L26](../../../src/test/js/format.test.cjs#L26) | `assert.equal(format.usageRate("104.166667"), "104.166667");` | [이 메서드의 대응·제외](#mapping-51) |
| [L28](../../../src/test/js/format.test.cjs#L28) | `assert.notEqual(format.usageRate("104.166667"), format.rate("104.166667"));` | [이 메서드의 대응·제외](#mapping-51) |
| [L30](../../../src/test/js/format.test.cjs#L30) | `assert.equal(format.usageRate("1200"), "1,200.000000");` | [이 메서드의 대응·제외](#mapping-51) |
| [L31](../../../src/test/js/format.test.cjs#L31) | `assert.equal(format.usageRate("1.23456789"), "1.234567");` | [이 메서드의 대응·제외](#mapping-51) |
| [L32](../../../src/test/js/format.test.cjs#L32) | `assert.equal(format.usageRate("not-a-rate"), "-");` | [이 메서드의 대응·제외](#mapping-51) |

## format.test.cjs#서버가 이미 YYYY-MM-DD 로 준 날짜는 그대로 통과시킨다

<a id="mapping-52"></a>

대체 테스트: FORMAT 동일 이름·동일 입력.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L36](../../../src/test/js/format.test.cjs#L36) | `assert.equal(format.date("2026-07-01"), "2026-07-01");` | [이 메서드의 대응·제외](#mapping-52) |
| [L37](../../../src/test/js/format.test.cjs#L37) | `assert.equal(format.month("2026-07-01"), "2026-07");` | [이 메서드의 대응·제외](#mapping-52) |

## format.test.cjs#일시는 Asia/Seoul 로 고정해 표시한다 (인터페이스정의서 2-4)

<a id="mapping-53"></a>

대체 테스트: FORMAT 동일 이름·동일 입력.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L42](../../../src/test/js/format.test.cjs#L42) | `assert.equal(format.dateTime("2026-08-03T14:05:22+09:00"), "2026-08-03 14:05");` | [이 메서드의 대응·제외](#mapping-53) |
| [L43](../../../src/test/js/format.test.cjs#L43) | `assert.equal(format.dateTime("2026-08-03T05:05:22Z"), "2026-08-03 14:05");` | [이 메서드의 대응·제외](#mapping-53) |
| [L45](../../../src/test/js/format.test.cjs#L45) | `assert.equal(format.dateTime("2026-08-02T15:30:00Z"), "2026-08-03 00:30");` | [이 메서드의 대응·제외](#mapping-53) |
| [L46](../../../src/test/js/format.test.cjs#L46) | `assert.equal(format.date("2026-08-02T15:30:00Z"), "2026-08-03");` | [이 메서드의 대응·제외](#mapping-53) |

## format.test.cjs#빈 값과 잘못된 값은 대시로 통일한다

<a id="mapping-54"></a>

대체 테스트: FORMAT 동일 이름·동일 입력.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L51](../../../src/test/js/format.test.cjs#L51) | `assert.equal(format.won(value), "-");` | [이 메서드의 대응·제외](#mapping-54) |
| [L52](../../../src/test/js/format.test.cjs#L52) | `assert.equal(format.int(value), "-");` | [이 메서드의 대응·제외](#mapping-54) |
| [L54](../../../src/test/js/format.test.cjs#L54) | `assert.equal(format.rate("not-a-rate"), "-");` | [이 메서드의 대응·제외](#mapping-54) |
| [L55](../../../src/test/js/format.test.cjs#L55) | `assert.equal(format.date(""), "-");` | [이 메서드의 대응·제외](#mapping-54) |
| [L56](../../../src/test/js/format.test.cjs#L56) | `assert.equal(format.dateTime("not-a-date"), "-");` | [이 메서드의 대응·제외](#mapping-54) |

## format.test.cjs#오류 문구에 오류코드와 요청 ID 를 병기한다 (FGC-SIR-007)

<a id="mapping-55"></a>

대체 테스트: FORMAT 동일 이름·동일 입력.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L60](../../../src/test/js/format.test.cjs#L60) | `assert.equal(     format.errorText({ code: "FGC-CONT-001", message: "저장 불가 — 이미 등록된 계약번호입니다.", requestId: "20260820-1a2b3c" }),     "저장 불가 — 이미 등록된 계약번호입니다. (FGC-CONT-001 · 요청 ID: 20260820-1a2b3c)"   );` | [이 메서드의 대응·제외](#mapping-55) |
| [L64](../../../src/test/js/format.test.cjs#L64) | `assert.equal(format.errorText({ message: "저장 불가" }), "저장 불가");` | [이 메서드의 대응·제외](#mapping-55) |
| [L65](../../../src/test/js/format.test.cjs#L65) | `assert.equal(format.errorText(null, "요청을 처리하지 못했습니다."), "요청을 처리하지 못했습니다.");` | [이 메서드의 대응·제외](#mapping-55) |

## format.test.cjs#본문에 이미 요청 ID 가 있으면 뒤에 다시 붙이지 않는다 (FGC-COMMON-500)

<a id="mapping-56"></a>

대체 테스트: FORMAT 동일 이름·동일 입력.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L77](../../../src/test/js/format.test.cjs#L77) | `assert.equal(text.match(new RegExp(requestId, "g")).length, 1);` | [이 메서드의 대응·제외](#mapping-56) |
| [L78](../../../src/test/js/format.test.cjs#L78) | `assert.equal(     text,     &#96;처리 중 오류가 발생했습니다. 요청번호 ${requestId}를 담당자에게 알려주세요. (FGC-COMMON-500)&#96;   );` | [이 메서드의 대응·제외](#mapping-56) |

## policy-list.test.cjs#POL-W01 displays rates to four decimal places without rounding

<a id="mapping-57"></a>

대체 테스트: REFERENCE legacy-contracts `정책 숫자·회차…` + FORMAT.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L12](../../../src/test/js/policy-list.test.cjs#L12) | `assert.equal(formatRate("650.000000"), "650.0000");` | [이 메서드의 대응·제외](#mapping-57) |
| [L13](../../../src/test/js/policy-list.test.cjs#L13) | `assert.equal(formatRate("34.800000"), "34.8000");` | [이 메서드의 대응·제외](#mapping-57) |
| [L14](../../../src/test/js/policy-list.test.cjs#L14) | `assert.equal(formatRate("1.234567"), "1.2345");` | [이 메서드의 대응·제외](#mapping-57) |
| [L15](../../../src/test/js/policy-list.test.cjs#L15) | `assert.equal(formatRate("1200"), "1,200.0000");` | [이 메서드의 대응·제외](#mapping-57) |

## policy-list.test.cjs#POL-W01 formats serialized policy amounts without trailing zeroes

<a id="mapping-58"></a>

대체 테스트: REFERENCE legacy-contracts `정책 숫자·회차…` + FORMAT.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L19](../../../src/test/js/policy-list.test.cjs#L19) | `assert.equal(formatNumber(1200000, { maximumFractionDigits: 0 }), "1,200,000");` | [이 메서드의 대응·제외](#mapping-58) |

## policy-list.test.cjs#POL-W01 renders missing or malformed policy numbers as a dash

<a id="mapping-59"></a>

대체 테스트: REFERENCE legacy-contracts `정책 숫자·회차…`.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L23](../../../src/test/js/policy-list.test.cjs#L23) | `assert.equal(formatNumber(null), "-");` | [이 메서드의 대응·제외](#mapping-59) |
| [L24](../../../src/test/js/policy-list.test.cjs#L24) | `assert.equal(formatNumber(""), "-");` | [이 메서드의 대응·제외](#mapping-59) |
| [L25](../../../src/test/js/policy-list.test.cjs#L25) | `assert.equal(formatNumber("   "), "-");` | [이 메서드의 대응·제외](#mapping-59) |
| [L26](../../../src/test/js/policy-list.test.cjs#L26) | `assert.equal(formatNumber("not-a-number"), "-");` | [이 메서드의 대응·제외](#mapping-59) |
| [L27](../../../src/test/js/policy-list.test.cjs#L27) | `assert.equal(formatRate(null), "-");` | [이 메서드의 대응·제외](#mapping-59) |
| [L28](../../../src/test/js/policy-list.test.cjs#L28) | `assert.equal(formatRate("not-a-rate"), "-");` | [이 메서드의 대응·제외](#mapping-59) |
| [L29](../../../src/test/js/policy-list.test.cjs#L29) | `assert.equal(formatRange(null, null), "-");` | [이 메서드의 대응·제외](#mapping-59) |
| [L30](../../../src/test/js/policy-list.test.cjs#L30) | `assert.equal(formatRange(null, 12), "- ~ 12회차");` | [이 메서드의 대응·제외](#mapping-59) |
| [L31](../../../src/test/js/policy-list.test.cjs#L31) | `assert.equal(formatRange(1, null), "1 ~ -회차");` | [이 메서드의 대응·제외](#mapping-59) |

## policy-list.test.cjs#POL-W01 only provides full-view disclosure for long escaped values

<a id="mapping-60"></a>

대체 테스트: REFERENCE legacy-contracts `긴 정책 값…`.

구조 제외·남은 확인: 대응: React escaping, 길이·overflow·접기.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L35](../../../src/test/js/policy-list.test.cjs#L35) | `assert.equal(tableCellDisclosure("짧은 정책명", 20, false), "짧은 정책명");` | [이 메서드의 대응·제외](#mapping-60) |
| [L38](../../../src/test/js/policy-list.test.cjs#L38) | `assert.match(disclosure, /table-cell-disclosure/);` | [이 메서드의 대응·제외](#mapping-60) |
| [L39](../../../src/test/js/policy-list.test.cjs#L39) | `assert.match(disclosure, /table-cell-details' hidden/);` | [이 메서드의 대응·제외](#mapping-60) |
| [L40](../../../src/test/js/policy-list.test.cjs#L40) | `assert.match(disclosure, /전체 보기/);` | [이 메서드의 대응·제외](#mapping-60) |
| [L41](../../../src/test/js/policy-list.test.cjs#L41) | `assert.match(disclosure, /매우 긴 &lt;` | [이 메서드의 대응·제외](#mapping-60) |
| [L42](../../../src/test/js/policy-list.test.cjs#L42) | `assert.doesNotMatch(disclosure, /<정책>/);` | [이 메서드의 대응·제외](#mapping-60) |

## schedule-list.test.cjs#empty schedule result normalizes an out-of-range page to page 1

<a id="mapping-61"></a>

대체 테스트: SCHEDULE `범위를 벗어난 page 는 마지막 페이지(결과 없음이면 1)로 맞춘다`.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L9](../../../src/test/js/schedule-list.test.cjs#L9) | `assert.equal(normalizePage(response.page, response.totalPages), 1);` | [이 메서드의 대응·제외](#mapping-61) |

## schedule-list.test.cjs#FGC-QUR-001 non-empty result preserves boundaries and rejects malformed pages

<a id="mapping-62"></a>

대체 테스트: SCHEDULE 같은 normalizePage 테스트.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L13](../../../src/test/js/schedule-list.test.cjs#L13) | `assert.equal(normalizePage(1, 5), 1);` | [이 메서드의 대응·제외](#mapping-62) |
| [L14](../../../src/test/js/schedule-list.test.cjs#L14) | `assert.equal(normalizePage(2, 5), 2);` | [이 메서드의 대응·제외](#mapping-62) |
| [L15](../../../src/test/js/schedule-list.test.cjs#L15) | `assert.equal(normalizePage(999, 5), 5);` | [이 메서드의 대응·제외](#mapping-62) |
| [L16](../../../src/test/js/schedule-list.test.cjs#L16) | `assert.equal(normalizePage("2abc", 5), 1);` | [이 메서드의 대응·제외](#mapping-62) |

## schedule-list.test.cjs#schedule labels prefer API values and keep code-map fallback

<a id="mapping-63"></a>

대체 테스트: SCHEDULE `서버 라벨을 먼저 쓰고 없으면 코드표 → 코드값으로 대체한다`.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L22](../../../src/test/js/schedule-list.test.cjs#L22) | `assert.equal(responseLabel("서버 예정", labels, "PLANNED"), "서버 예정");` | [이 메서드의 대응·제외](#mapping-63) |
| [L23](../../../src/test/js/schedule-list.test.cjs#L23) | `assert.equal(responseLabel("", labels, "PLANNED"), "예정");` | [이 메서드의 대응·제외](#mapping-63) |
| [L24](../../../src/test/js/schedule-list.test.cjs#L24) | `assert.equal(responseLabel(null, labels, "UNKNOWN"), "UNKNOWN");` | [이 메서드의 대응·제외](#mapping-63) |

## transaction-list.test.cjs#FGC-FUN-044 TRAN-W01 keeps the attribution imbalance filter when submitting a search

<a id="mapping-64"></a>

대체 테스트: TRAN `검색 제출은 숨은 불균형 필터를 보존하고 초기화만 해제한다`.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L25](../../../src/test/js/transaction-list.test.cjs#L25) | `assert.equal(state.attributionImbalanceOnly, true);` | [이 메서드의 대응·제외](#mapping-64) |
| [L26](../../../src/test/js/transaction-list.test.cjs#L26) | `assert.equal(state.settlementMonth, "2026-08");` | [이 메서드의 대응·제외](#mapping-64) |
| [L27](../../../src/test/js/transaction-list.test.cjs#L27) | `assert.equal(state.contractNo, "C004");` | [이 메서드의 대응·제외](#mapping-64) |

## transaction-list.test.cjs#FGC-FUN-044 TRAN-W01 clears the hidden attribution imbalance filter on reset

<a id="mapping-65"></a>

대체 테스트: TRAN 동일 테스트의 초기화 후 `onlyAttributionImbalance` 미전송 검사.

구조 제외·남은 확인: 대응.

| 원본 위치 | 검사 항목 | 대체 / 제외 근거 |
|---|---|---|
| [L33](../../../src/test/js/transaction-list.test.cjs#L33) | `assert.equal(state.attributionImbalanceOnly, false);` | [이 메서드의 대응·제외](#mapping-65) |
