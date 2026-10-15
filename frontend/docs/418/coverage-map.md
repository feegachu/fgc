# #418 → #419 회귀 검증 대응표

기준: develop `29f3ccb0` (2026-10-14), #418 및 #404·#407·#408·#409·#410·#411·#413·#414·#415·#416의 본문/코멘트.
**PL 승인 전이다. 이 문서는 구조 테스트 삭제 승인이 아니다. 기존 Java/node 테스트는 모두 유지한다.**

9개 구조 테스트의 48개 메서드, node 4개 파일의 17개 테스트를 아래에 각각 나열했다.
각 메서드 안의 검사식은 [전수 목록](assertion-inventory.md)에 원본 위치와 함께 1개씩 나열한다.
CSS 클래스·스크립트 문자열을 동일하게 만드는 것과 사용자가 하는 동작의 동등성은 구분한다.
행의 '구조 제외'는 Thymeleaf 구현 방식만 제외한다는 뜻이다. 접근성·권한·금액·필터·문구 계약은 제외하지 않는다.
시각적 치수/전체 문구의 미검증 범위는 [차이와 확인 사항](differences.md)에 남긴다.

## 대체 테스트 식별자

모든 경로는 `frontend/` 기준이다. 아래 식별자는 대응표의 링크를 짧게 쓰기 위한 것이다.

| ID | 실행 파일 / 주요 검증 |
|---|---|
| SHELL | `src/stores/workspace.test.ts` (10개 제한, 오른쪽→왼쪽 닫기, 월 동기화), `src/app/router.test.tsx`, `src/app/shell/Sidebar.test.tsx`, `e2e/app-shell.spec.ts` |
| COMMON | `src/components/components.test.tsx` (표·상태·필드·페이지·모달 포커스·근거 hover/focus/click·월 선택) |
| TOKENS | `src/styles/tokens.test.ts` (상태 색상·CSS 토큰). 원본 CSS 비교는 #419 의존이므로 독립 검증과 구별 |
| FORMAT | `src/lib/format.test.ts` (1차 format 8개 테스트와 동일 입력·기대값 + KST 경계) |
| CONTRACT | `src/features/contract/{list,detail,form,api}.test.tsx` (`api`만 `.ts`), `e2e/contract-ui.spec.ts`, `e2e-demo/journey.spec.ts` 계약 시나리오 |
| TRAN | `src/features/transaction/transaction-{list,form}.test.tsx`, `form-model.test.ts`, `e2e/transaction.spec.ts`, `e2e-demo/journey.spec.ts` 지급 성공/거절 |
| SCHEDULE | `src/features/schedule/schedule.test.tsx` (#1 URL·표시·CSV, #2 단계/버전 이동, #3 재생성·확정, #4 사유, #5 서버 오류, #6 권한), 실제 서버 계약→스케줄 |
| CAP | `src/features/cap/cap.test.tsx`, `e2e/cap.spec.ts`, 실제 서버 지급 거절→예외→CAP |
| ARB | `src/features/arbitrage/arbitrage.test.tsx`, `e2e/arbitrage.spec.ts` (키보드·타임라인·수동 재검증·역할) |
| AUDIT | `src/features/audit/AuditLogPage.test.tsx`, `e2e-demo/auth-audit.spec.ts` AUDT A1–A6·Enter/Space |
| REFERENCE | `src/features/reference/reference.test.tsx`, `src/features/reference/legacy-contracts.test.tsx`, `e2e/reference.spec.ts` |
| LEDGER | `src/features/ledger/ledger.test.tsx`, `e2e/ledger.spec.ts`, 실제 서버 역분개 |
| EXCEPTION | `src/features/exception/exception.test.tsx`, `e2e/exception.spec.ts`, 실제 서버 조치 저장 |
| RECO | `src/features/reconciliation/reconciliation.test.tsx`, `e2e/reconciliation.spec.ts`, 실제 서버 대사 비교 |
| VRUN | `src/features/validation/validation.test.tsx`, `e2e/validation.spec.ts`, 실제 서버 생성·실행·8조건·확정 |
| AUTH | `src/features/auth/auth.test.tsx`, `e2e/auth.spec.ts`, `e2e-demo/auth-audit.spec.ts` 로그인·중복 로그인·403 |
| ERROR | `src/features/error/error.test.tsx` (401/403/404/500·요청 ID·스택 비노출) |
| DASH | `src/features/dashboard/dashboard.test.tsx` (KPI·표시·딥링크·빈 상태·오류) |

## PublishingTemplateStructureTest — 30개

전수 목록의 각 검사식은 이 표의 같은 메서드 행에 대응한다. 메서드에 CSS/DOM 구조와 동작이 섞여 있으면 둘을 구분해 기록했다.

| 메서드 | 보장 항목 / 대체 테스트 | 구조 제외·남은 확인 |
|---|---|---|
| publishedScreensUseProductionShellScopeWithoutPrototypeDependencies | SHELL 라우팅·공통 셸 | `th:replace`, production scope, prototype script 경로는 템플릿 전용 |
| screensStillUsingLegacyClassesKeepTheBridgeScope | SHELL | 기존 화면 bridge scope 유지 검사: 해당 템플릿 삭제 후 불필요 |
| migratedScreensDropLegacyClassesAndInlineStyles | 화면별 테스트·SHELL | Thymeleaf class/style 금지 목록은 React에 그대로 이식하지 않음 |
| reconciliationComparisonKeepsPopupPublishingScope | RECO 비교 모달·COMMON 모달 포커스/닫기 | popup 템플릿 scope 제외. 정확한 외형 D3 |
| connectedArbitrageScreenDoesNotExposeStalePendingState | ARB 수동 재검증 단일 제출·완료 toast·오류 입력 보존 | legacy JS 연결/placeholder 문자열 검사 제외 |
| screenGuidanceStaysAlignedWithCurrentUiDecisions | VRUN 확정 안내·SCHEDULE 상태 안내·CAP 근거·AUDIT | 전체 안내문 금지 목록의 동등성은 D3에서 승인 필요 |
| ledgerSearchRunsOnlyWhenFilterFormIsSubmitted | LEDGER `명시적인 조회 전에는 목록 API를 호출하지 않는다`·URL/API 필터 유지 | JS 이벤트 등록 방식 제외 |
| ledgerUsesCommonPageShellFormattingAndAccessibleAjaxStates | LEDGER 검색/selected/금액/상태/오류·FORMAT·COMMON | 템플릿 id/class·script 구조 제외 |
| productionLayoutLoadsScopedResponsivePublishingStyles | SHELL·TOKENS | CSS 링크 순서·scope 제외. 정확한 breakpoint/치수 D3 |
| productionLayoutLoadsSharedFormatUtilityAndPendingFeatureStyles | FORMAT·SCHEDULE·VRUN | script/link 태그 제외 |
| productionLayoutLinksEachFeatureStylesheetOnce | 화면별 모듈 import + `npm run build` | Thymeleaf layout CSS 링크 횟수 검사 제외 |
| sharedFormatUtilityFixesDisplayStandardContracts | FORMAT 동일한 8개 입력/기대값 | 전역 `window.FGC.format` 등록 방식 제외 |
| transactionScreensUseCommonComponentsAndPreserveCriticalContracts | TRAN 검색·숨은 불균형 필터·귀속·6단계 지급 게이트·멱등키·권한·거절·FORMAT | legacy DOM selector/JS 함수명 제외. 지급 6단계와 월검증 8조건은 별개 |
| evidencePopoverComponentEscapesTableClipping | COMMON EvidenceLink 및 표 안 팝오버 | portal의 실제 clipping/배치 전체는 D3 |
| evidenceOpensOnHoverFocusAndClick | COMMON `EvidenceLink는 hover·focus로 열고 클릭 고정·Esc로 해제한다` | 기존 이벤트 함수명 제외 |
| policyListUsesCommonComponentsWithoutInlinePresentation | REFERENCE 정책 탭/수치/빈값/긴 텍스트·FORMAT | legacy inline 금지 및 class 이름 제외 |
| auditListUsesCommonComponentsWithoutInlinePresentation | AUDIT 목록·패널·키보드·403 | 빈 상태 class 차이 D2, CSS import는 layout 링크 대체 |
| exceptionListUsesFlexibleColumnsSharedBadgesAndProductionToast | EXCEPTION URL·선택·조치·이력·권한·금액·CAP 링크 | 템플릿 class와 폭 문자열 제외; 시각 검증 D3 |
| reconciliationListUsesCommonComponentsAndProductionToast | RECO 실행·자동선택·비교·중복 오류·예외 링크·COMMON | 템플릿 scope·id·script 분리 제외 |
| contractListUsesCommonFilterTableAndStatusBadges | CONTRACT 목록 필터·페이지·CSV·금액·상태·빈값 | legacy class·템플릿 script 연결 제외 |
| contractDetailMovesPageBehaviourOutOfTheTemplate | CONTRACT 상세 6탭·서버 값·링크·권한·재검증 | script 파일 분리/DOMContentLoaded 문자열 제외 |
| contractFormUsesSeparatedApiAndPageScriptsWithoutInlineBehavior | CONTRACT 등록·수정·기준정보·409 입력보존·금액·기간·권한 | legacy script/inline 검사 제외 |
| baseReferenceScreenUsesSeparatedApiAndPageScripts | REFERENCE 5탭·날짜·옵션·검색·서버 오류 | legacy JS/템플릿 분리 방식 제외 |
| dashboardUsesCommonComponentsWithoutStalePlaceholdersOrRawEnumCodes | DASH KPI·기준월·권한·빈값·차트 대체/서버 labels | CSS class·템플릿 연결 제외 |
| errorScreensUseCommonComponentsAndShowCodeWithTraceId | ERROR + FORMAT 오류코드/요청번호·권한·404·스택 비노출 | MPA 에러 템플릿/레이아웃 구조 제외 |
| loginScreenKeepsCommonComponentsAndHidesAuthFailureDetail | AUTH 필수값·비밀번호 토글·동일 실패문구·로그아웃·FORMAT | formLogin action·서버 `th:if` 분기 제외 |
| validationRunScreensUseCommonComponentsWithoutInlinePresentation | VRUN 목록·생성·폴링·상태·체크리스트·멱등 확정·권한 | legacy JS/layout 제외. 6→8조건 불일치 D1 수정 |
| scheduleListUsesCommonFilterBarAndDisclosureInsteadOfTitleTooltips | SCHEDULE URL·페이지 보정·라벨·CSV·오류; COMMON | legacy selector 제외. 모든 긴 셀의 실제 overflow D3 |
| scheduleDetailAddsConfirmDialogAndDropsLegacyShell | SCHEDULE 사유·확정 대화상자·버전·권한·실제 서버 경로 | template scope·inline 스타일 금지 제외 |
| productionLayoutLoadsScheduleLabelsBeforeScheduleScreens | SCHEDULE `서버 라벨을 먼저 쓰고 없으면 코드표 → 코드값으로 대체한다` | 전역 script 로드 순서는 ES module import로 대체 |

## 나머지 구조 테스트 — 18개

| 클래스#메서드 | 대체 테스트 / 동작 | 구조 제외·남은 확인 |
|---|---|---|
| AppShellWorkspaceStructureTest#headerRailContainsWorkspaceTabsAndFixedControlsWithoutLegacyTitleArea | SHELL 헤더·탭·고정 대시보드 | `th:replace`·헤더 fragment 인자·legacy title 영역 제외 |
| AppShellWorkspaceStructureTest#shellAndTabsUseProductionDimensionsAndFullWidthAccent | TOKENS·SHELL | 픽셀 치수·수정 탭 hover/포커스 시각 계약 D3 |
| AppShellWorkspaceStructureTest#workspaceTabsPersistStateAndChooseRightThenLeftThenDashboardOnClose | workspace `닫은 활성 탭은 오른쪽, 없으면 왼쪽 탭으로 이동한다`, 10개 제한·복원·변조 배제 | legacy storage 키/ResizeObserver 구현 문자열 제외 |
| ArbitragePublishingStructureTest#arbitrageListUsesCommonComponentsWithoutInlinePresentation | ARB 필터·요약·권한·재검증·toast | class/JS 경로·inline 검사 제외 |
| ArbitragePublishingStructureTest#arbitrageRowsKeepPlainContractTextAndKeyboardSelection | ARB `rows support keyboard activation and timeline uses stored excess without recalculating` | legacy data-* selector 제외 |
| AuditTemplateStructureTest#auditListUsesSharedDisclosureAndAccessibleRowSelection | AUDIT 키보드 Enter/Space·다른 칸 클릭·selected 복원·diff | `th:data-audit-detail-href`/window.location 제외; 긴 값 조건 D2; CSS 원본 의존 D4 |
| CapPublishingTemplateStructureTest#capListUsesCommonComponentsAndServerAggregates | CAP 필터·서버 집계·목록 선택·라벨·사용률·권한 | legacy selector와 CSS scope 제외 |
| CapPublishingTemplateStructureTest#capDetailRemainsACommonComponentModalWithoutGuidanceOrInlineStyles | CAP 상세 딥링크·팝업·오류·COMMON Escape/포커스 | template fragment/inline 금지 제외 |
| GlobalMonthSelectorStructureTest#headerProvidesAccessiblePopoverAndTwelveMonthCells | COMMON MonthSelector 연도·12개월·키보드·비활성 월 | legacy DOM id/스크립트 함수명 제외 |
| GlobalMonthSelectorStructureTest#componentKeepsDraftSeparateUntilApplyAndSupportsDismissal | COMMON `MonthSelector는 선택 초안을 적용하기 전까지 유지…`, 취소/Esc | legacy draft 객체명 제외 |
| GlobalMonthSelectorStructureTest#applySynchronizesEveryWorkspaceTabAndOnlyResetsPagination | SHELL `기준월은 3개 탭의 URL에 반영하고 page만 지운다`, app-shell E2E | 기존 location.replace 방식 제외 |
| GlobalMonthSelectorStructureTest#stylesMatchFigmaDimensionsAndProvideNarrowScreenFallback | TOKENS·SHELL | 상세 치수/좁은 화면 배치 승인 D3 |
| PendingActionButtonStructureTest#scheduleExportUsesCsvDownloadApi | SCHEDULE `상세 CSV 는 회차 표 API 로 내려받는다` | btn-export id·pending class 문자열 제외 |
| PendingActionButtonStructureTest#validationRunFinalizeUsesFrontendChecklistAndRoleGate | VRUN SETTLEMENT 비활성·GA_ADMIN 확정·미충족·7/8조건·멱등 재시도 | data-can-finalize·script 함수명 제외 |
| SidebarSectionStateStructureTest#sidebarSectionsExposeStablePersistenceKeys | SHELL Sidebar 섹션·역할별 표시·접힘 복원 | 기존 data-section-key 이름 제외 |
| SidebarSectionStateStructureTest#appShellContainsSidebarPersistenceHooks | SHELL 초기화/복원·로그아웃 | 초기화 script 삽입 위치·login.js 참조 제외 |
| SidebarSectionStateStructureTest#sidebarCollapseStateUsesExplicitAccessibleControlsAndLocalStorage | SHELL 버튼·aria-expanded·localStorage·역할 4종 Playwright | legacy 함수/클래스 직접 검사 제외 |
| StatusBadgeDesignTokenTest#warningAndRiskSolidBadgesUseProductionColorsWithoutChangingSoftTokens | TOKENS status 24개 + COMMON badge 한글/상태 클래스 | 원본 비교만으로 삭제 승인 불가(D4); inverse 색상·실제 badge 배치 D3 |

## node 테스트 — 17개

| 원본 파일 / 테스트 | 대체 테스트 | 상태 |
|---|---|---|
| format / 금액은 천 단위로 묶고 음수는 숫자만 괄호로 감싼다 | FORMAT 동일 이름·동일 입력 | 대응 |
| format / 요율은 소수 넷째 자리까지 자르기만 하고 반올림하지 않는다 | FORMAT 동일 이름·동일 입력 | 대응 |
| format / 사용률은 소수 여섯째 자리다 | FORMAT 동일 이름·동일 입력 | 대응 |
| format / 서버가 이미 YYYY-MM-DD 로 준 날짜는 그대로 통과시킨다 | FORMAT 동일 이름·동일 입력 | 대응 |
| format / 일시는 Asia/Seoul 로 고정해 표시한다 | FORMAT 동일 이름·동일 입력 | 대응 |
| format / 빈 값과 잘못된 값은 대시로 통일한다 | FORMAT 동일 이름·동일 입력 | 대응 |
| format / 오류 문구에 오류코드와 요청 ID 를 병기한다 | FORMAT 동일 이름·동일 입력 | 대응 |
| format / 본문에 이미 요청 ID 가 있으면 뒤에 다시 붙이지 않는다 | FORMAT 동일 이름·동일 입력 | 대응 |
| policy-list / POL-W01 displays rates to four decimal places without rounding | REFERENCE legacy-contracts `정책 숫자·회차…` + FORMAT | 대응 |
| policy-list / POL-W01 formats serialized policy amounts without trailing zeroes | REFERENCE legacy-contracts `정책 숫자·회차…` + FORMAT | 대응 |
| policy-list / POL-W01 renders missing or malformed policy numbers as a dash | REFERENCE legacy-contracts `정책 숫자·회차…` | 대응 |
| policy-list / POL-W01 only provides full-view disclosure for long escaped values | REFERENCE legacy-contracts `긴 정책 값…` | 대응: React escaping, 길이·overflow·접기 |
| schedule-list / empty schedule result normalizes an out-of-range page to page 1 | SCHEDULE `범위를 벗어난 page 는 마지막 페이지(결과 없음이면 1)로 맞춘다` | 대응 |
| schedule-list / FGC-QUR-001 non-empty result preserves boundaries and rejects malformed pages | SCHEDULE 같은 normalizePage 테스트 | 대응 |
| schedule-list / schedule labels prefer API values and keep code-map fallback | SCHEDULE `서버 라벨을 먼저 쓰고 없으면 코드표 → 코드값으로 대체한다` | 대응 |
| transaction-list / FGC-FUN-044 TRAN-W01 keeps the attribution imbalance filter when submitting a search | TRAN `검색 제출은 숨은 불균형 필터를 보존하고 초기화만 해제한다` | 대응 |
| transaction-list / FGC-FUN-044 TRAN-W01 clears the hidden attribution imbalance filter on reset | TRAN 동일 테스트의 초기화 후 `onlyAttributionImbalance` 미전송 검사 | 대응 |

## 승인 조건

- 검사식 전수 목록과 위 대응의 의미를 PL이 확인한다. 단순 class/JS 문자열 제외와 사용자 계약 제외를 혼동하지 않는다.
- D2/D3의 부분 대응·의도한 차이에 처리 방향을 남긴다. 승인 없는 차이를 '대체 완료'로 간주하지 않는다.
- D4의 원본 참조 테스트는 #419에서 함께 다룬다. 이 PR은 기존 테스트를 삭제하지 않는다.
- 실제 서버 3회 실행 증거와 CI 실행 결과를 확인한다. 로컬 성공과 GitHub Actions 성공을 구별한다.
