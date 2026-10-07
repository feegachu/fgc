# FGC frontend (React + TypeScript)

2차 Thymeleaf → React 전환 화면. 공존기에는 nginx가 `/app/`에서 서빙하고, 기존 화면은 `/`에 그대로 있다.
웨이브 C(#419)에서 base를 `/`로 바꾼다.

스택: Vite · React 19 · TypeScript(strict) · React Router · TanStack Query · Zustand · Tailwind CSS v3 · ESLint · Vitest + Testing Library · Playwright

## 준비

- Node 22 (`.nvmrc`). `nvm use` 또는 `fnm use`
- 백엔드: 저장소 루트에서 `./gradlew bootRun` → `http://localhost:8081`

## 명령

| 할 일 | 명령 |
|---|---|
| 의존성 설치 | `npm ci` |
| 개발 서버 | `npm run dev` → `http://localhost:5173/app/` (`/api`는 8081로 프록시) |
| 린트 | `npm run lint` |
| 포맷 | `npx prettier --write <파일>` — 설정은 `.prettierrc.json`. 아직 CI 체크는 없고, #444 이전 파일은 일괄 포맷하지 않았다 |
| 단위 테스트 | `npm test` (감시 모드) / `npm test -- --run` (CI와 동일) |
| 빌드 | `npm run build` (`tsc -b` 타입 검사 + `vite build` → `dist/`) |
| E2E | 처음 한 번 `npx playwright install chromium`, 이후 `npm run test:e2e` (시나리오는 #418) |

백엔드를 다른 포트로 띄웠으면 `FGC_API_TARGET=http://localhost:8080 npm run dev`
(PowerShell: `$env:FGC_API_TARGET='http://localhost:8080'; npm run dev`).

PR을 올리기 전에 `npm run lint && npm test -- --run && npm run build`가 통과해야 한다. CI의 `frontend` 필수 체크가 같은 명령을 돌린다.

## 폴더 규칙

```text
src/
├── app/                # 앱 진입·라우터·QueryClient 등 전역 구성
├── components/         # 도메인에 속하지 않는 공통 UI (#403)
├── features/{domain}/  # 화면 단위 코드. domain은 CONTRIBUTING.md 도메인 이름을 쓴다 (contract, cap, ledger …)
├── lib/                # API client·포맷·생성 타입 등 공통 레이어 (#402)
└── test/               # 테스트 공통 설정
```

- 테스트는 대상 파일 옆에 `*.test.tsx`로 둔다.
- 화면 라우트는 인터페이스정의서 §5-2 "2차 React 경로" 열을 따라 `src/app/screens.ts`의 메타데이터와 `src/app/routes.tsx`에 추가한다.
- 업무 규칙(금액 계산·반올림·한도 판정)은 프론트에서 새로 만들지 않는다. 서버 응답을 표시한다.

## 컨테이너

`Containerfile.web` 1단계가 `npm ci && npm run build`를 하고, 2단계 nginx 이미지의 `/usr/share/nginx/html/app/`에 `dist/`를 복사한다.
라우팅은 `nginx/default.conf.template`의 `/app/` location이 담당한다(없는 경로는 `index.html`로 넘기고, `/app/assets/`는 해시 파일이라 1년 캐시).

## 공통 계층 (#402)

화면에서는 `src/lib/api/client.ts`의 `apiClient.request<T>(path, options)`를 사용한다.
반환값은 `Promise<ApiEnvelope<T>>`이며 `{data, error: null, requestId}`를 유지한다.
페이지 응답도 `data.content`, `data.page` 등 서버 형태 그대로다.
오류는 `ApiError`로 던진다(`code`, `message`, `field`, `params`, `detail`, `requestId`, `status`).
`204`는 `data: null`이다. 비JSON 오류의 추적 ID는 `X-Request-Id`에서 읽는다.

```ts
import { apiClient } from './lib/api/client'
import type { components } from './lib/api/schema'

const { data } = await apiClient.request<components['schemas']['MeResponse']>('/api/v1/auth/me')
await apiClient.request('/api/v1/contracts', {
  method: 'POST', body: payload, idempotencyKey: operationKey, signal,
})
```

- `options`: `method`, `headers`, `body`, `signal`, `idempotencyKey`. 일반 body는 JSON,
  `FormData`는 그대로 전송한다. API 경로는 동일 출처의 `/api/v1/` 아래만 허용한다.
- `apiClient.login(loginId, password)`는 토큰을 메모리 Zustand(`useAuthStore`)에 보관한다.
  `apiClient.logout()`은 메모리 토큰을 먼저 삭제하고 서버 쿠키를 무효화한다.
  #404는 로그인·로그아웃 전 `queryClient.clear()`로 이전 사용자의 조회 캐시도 비워야 한다.
- 쿠키 인증인 login/refresh/logout에는 만료된 Bearer를 보내지 않는다. 모든 `/auth/**`에는
  `X-FGC-Client: web`을 붙인다. 브라우저가 Origin과 HttpOnly Refresh 쿠키를 보낸다.
- 업무 API와 `/auth/me`는 Bearer를 사용한다. 401은 refresh 한 번 후 원 요청을 한 번 재시도한다.
  동시 요청은 하나의 refresh Promise를 공유하고, 늦게 도착한 이전 토큰의 401도 새 토큰을 재사용한다.
  재시도도 401이면 종료한다. `FGC-AUTH-004`는 refresh 없이 종료한다.
- 앱 진입점이 화면/조회 훅을 마운트하기 전에 `restoreSession()`을 한 번 호출한다.
  로그인 경로는 제외한다. 실패하면 토큰을 지우고 로그인으로 이동하며 조회 훅을 마운트하지 않는다.
- 로그인 경로는 Vite `BASE_URL + login`이다. `redirect`는 basename을 제외한 경로와 query/hash다.
  #404는 `src/lib/api/redirect.ts`의 `safeRedirect()`를 사용해 복귀 경로를 검사한다.
  중복 로그인은 `reason=duplicate`가 붙는다. base가 `/`로 바뀌어도 같은 함수가 작동한다.
- Access·Refresh를 localStorage/sessionStorage에 저장하지 않는다. UI 설정 저장은 #403 범위다.

TanStack Query는 4xx·취소를 재시도하지 않고 그 밖의 조회 실패만 두 번까지 재시도한다.
상태 변경 mutation은 자동 재시도하지 않는다. 최종 Query/Mutation 오류는
`subscribeApiErrors((message, error) => ...)`로 전달한다. #403 Toast는 이를 구독하고
반환된 해제 함수를 cleanup에서 호출한다. 폼처럼 직접 오류를 표시하는 훅은
`meta: { errorToast: false }`로 전역 Toast를 끈다.
일반 `request()`는 자동 Toast를 띄우지 않아 Query와 중복되지 않는다.

CSV는 `exportCsv(path, options)`를 사용한다. 서버에 `Accept: text/csv`로 요청하고,
blob을 서버 `Content-Disposition` 파일명(UTF-8 포함)으로 저장한다.
헤더가 없으면 `options.filename` 또는 `export.csv`를 쓴다. 오류는 공통 Toast 연결점에 알린 뒤
`ApiError`를 던진다. 클라이언트가 CSV를 만들거나 수식 방어를 재구현하지 않는다.
이를 mutation에서 호출하면 `meta.errorToast=false`로 오류 Toast 중복을 막는다.

### 표시 함수와 1차 화면의 차이

`src/lib/format.ts`는 기존 공통 `format.js` 규칙을 유지한다. `int`, `won`, `isNegative`,
`rate`, `usageRate`, `date`, `dateTime`, `month`, `today`, `errorText`를 각각 import한다.
요율 4자리·사용률 6자리는 문자열을 절사하고, 시각·오늘은 Asia/Seoul 기준이다.
금액 반올림과 업무 계산은 서버 책임이다. `%`는 호출부에서 붙이고, 음수 색상은
`isNegative()` 결과로 적용한다. 1차 테스트의 32개 assert 호출을 모두 Vitest로 이전했다
(반복문의 빈 값 검증도 그대로 유지).

| 기존 소비자 | React 대응 | 이행 시 기록할 차이 |
|---|---|---|
| arbitrage-list.js `number` | `int`, 금액은 `won` | 빈 값 `0` → `-`; #412 |
| cap-list.js `number` | `int`, `won`, `usageRate` | 빈 값 `0` → `-`; #410 |
| reco.js 숫자 표시 | `int`, `won` | 빈 값 `0` → `-`; #414 |
| policy-list.js 숫자·요율 표시 | `int`, `rate` | 기존 문자열 4자리 절사와 동일; #406 |
| exception-list.js 금액 표시 | `won`, `isNegative` | 음수 `-1,234` → `(1,234)원` 및 음수 색상; #415 |
| contract-form.js `int` | `int` | 기존 공통 `format.int`와 동일; 단위는 화면에서 유지; #407 |
| month-selector.js 현재 월 | `month(today())` | 브라우저 시간대 → KST; #403 |

화면 담당자는 위 표시 차이를 화면 PR에 기록한다. 기존 Thymeleaf JS와 구조 테스트는
이 이슈에서 삭제하지 않는다. 실제 화면 전환과 제거는 각 화면 이슈 및 #419 범위다.

### OpenAPI 타입 재생성

`openapi/schema.json`과 `src/lib/api/schema.d.ts`를 함께 추적한다.
`/v3/api-docs`는 기존 인증을 유지한다. 테스트의 인증된 MockMvc 요청으로 스펙을 추출하고,
환경마다 다른 `servers` 주소는 `/`로 고정하고 객체 키를 정렬해 추출 순서 차이를 제거한다. 프론트 CI에는 JDK·백엔드 기동이 필요 없다.
`npm run check:api`가 스펙으로 재생성한 결과와 선언 파일을 대조한다.

저장소 루트에서(Node 22와 Java 21, Docker 필요):

```bash
FGC_EXPORT_OPENAPI=true FGC_NODE_BIN="$(command -v node)" \
  ./gradlew test --tests '*OpenApiFrontendIntegrationTest' --rerun-tasks
cd frontend
npm run generate:api
npm run check:api
npm run lint
npm test -- --run
npm run build
```

추출 테스트는 `test` 프로필의 임시 PostgreSQL과 실제 HTTP 서버를 사용한다.
추출과 함께 프론트 클라이언트의 실제 JWT 만료·동시 refresh·refresh 실패·중복 로그인 처리를 검증한다.
일반 프론트 실행에서 `server.test.ts`의 4개 실서버 검증은 건너뛰고 위 명령이 별도로 실행한다.
일반 백엔드 빌드에서도 추출 테스트는 opt-in이므로 스펙 파일을 자동으로 덮어쓰지 않는다.

TS 6은 #401 결정을 유지한다. openapi-typescript 7.13.0의 TS 5 peer 요구는
`overrides.openapi-typescript.typescript = "$typescript"`로 맞춘다.
`--force`, `--legacy-peer-deps`는 쓰지 않는다. 생성 타입의 응답 필드는 springdoc가 optional로
내보내므로 소비자는 null/undefined를 고려한다. 인증 응답의 토큰은 클라이언트가 필수로 검증한다.

## AppShell와 공통 UI (#403)

`app/screens.ts`가 화면 ID·제목·상위 메뉴·권한·기존 경로의 기준이다. 업무 화면이
전환되기 전에는 `TransitionPage`가 query를 유지한 기존 화면 링크를 제공한다.
`/contracts/:id/edit`도 인터페이스정의서에 추가했다. 로그인/ProtectedRoute는 #404,
업무 화면 구현과 서버 권한 검사는 각 담당 이슈의 책임이다.

사이드바 접힘은 `fgc.sidebar.collapsed.v1`(localStorage), 열린 섹션은
`fgc.sidebar.open-sections.v1`(sessionStorage)을 사용한다. 탭·기준월·수정 표시는
`fgc.react.workspace.v1`(sessionStorage)에만 보관하고 로그인 ID가 같을 때만 복원한다.
복원 시 화면 메타데이터·권한을 다시 검사한다. Access Token이나 업무 응답은 저장하지 않는다.
대시보드는 고정이며 총 10개를 넘으면 가장 오래 열린 업무 탭을 제거한다.
계약 등록·수정은 기존 화면처럼 CONT-W03 탭 하나를 재사용한다.

기준월 우선순위는 유효한 URL `month` → 동일 사용자의 저장 월 → 서버 기본 월이다.
전체 탭에 월 변경을 적용할 때 `page`만 제거하고 나머지 query/hash는 보존한다.
`useWorkspaceStore.getState().markModified(screenId, true/false)`로 폼 수정 표시를 변경한다.
서버 기본 월은 `fgc.demo-month`여야 한다. `/auth/me`의 필수 문자열 `demoMonth`를
사용하며, 이 필드는 요청/세션의 선택 월과 독립적인 서버 기본값이다(#403 담당자 승인).
UI 테스트의 월은 fixture이고 실제 연결은 서버 통합 테스트로 별도 검증한다.
운영 코드에 현재 월이나 고정 월 fallback을 두지 않았다.

### 컴포넌트 계약

모든 컴포넌트는 `src/components/{Name}.tsx`에서 직접 import한다.

| 컴포넌트 | 주요 props/호출 | 동작 |
|---|---|---|
| DataTable<T> | caption, columns, rows, rowKey, sort/onSort, selectedKeys/onSelectionChange, onRowClick | 서버 조회용 정렬 콜백, 현재 페이지 선택, 빈 상태, column.expandable의 전체 보기/접기 |
| Pagination | page, totalPages, onPageChange | 1-base, 5페이지 그룹, 처음/이전/다음/마지막 |
| FilterBar | children, onSubmit, onReset | 조회/초기화; useSearchParamsState(defaults)의 update/reset과 연결 |
| Modal | open, title, onClose, initialFocusRef, closeOnBackdrop, closeOnEscape, footer | 포커스 순환·복원, 기본 배경 클릭 금지, Esc 닫기 |
| ToastRegion | 앱 루트에 한 번 마운트 | toast(message, tone) 또는 API 오류 구독; 일반 5초, 오류/로딩 수동 닫기 |
| EvidenceLink | label, children | hover/focus, 클릭 고정, Esc/외부 클릭 해제 |
| StatusBadge | tone, children | 서버 문구와 상태 색상 함께 표시 |
| MonthSelector | value, onApply, disabledMonths, openTabCount | 12개월 4열, 초안/취소/적용, 방향키, 1~9999년 |
| KpiCard | label, value, unit, footer, tone | 표시 값만 전달; 업무 계산 없음 |
| Button | variant, loading, 일반 button props | primary/secondary/ghost; 처리 중 비활성 |
| Field | label, children, required, error, helper, id | 입력 id·라벨·오류 aria 연결 |

### 토큰과 자산

1차 common의 variables/reset/layout/components/utilities CSS를 그대로 복사했다.
`src/styles/tokens.js`는 CSS 값 대신 `var(--...)`를 Tailwind에 연결한다.
`--color-*` → colors(접두어 제외), `--space-*` → spacing,
`--radius-*` → borderRadius, `--z-*` → zIndex, `--font-*` → fontFamily.
상태 24개·neutral 12개와 모든 spacing/radius/z 항목은 `tokens.test.ts`로 대조한다.
Tailwind 사용 예: `text-status-warning-text bg-neutral-50 p-4 rounded-8 z-modal`.
Noto Sans KR와 Material Symbols는 `public/fonts/`에서 제공한다. 브랜드 자산은
`public/images/brand/`에 있다. 원본 변경 시 React 복사본과 매핑도 함께 갱신한다.

### 검증과 1차 구조 테스트 대응

| 유지하는 1차 테스트 | React 동작 검증 |
|---|---|
| AppShellWorkspaceStructureTest | workspace.test.ts, router.test.tsx, e2e/app-shell.spec.ts(10개 제한·복원·월 동기화) |
| SidebarSectionStateStructureTest | Sidebar.test.tsx, 역할 4종 Playwright(메뉴·접힘·섹션 복원) |
| GlobalMonthSelectorStructureTest | components.test.tsx, workspace.test.ts, Playwright(초안·비활성 월·방향키·취소) |
| StatusBadgeDesignTokenTest | tokens.test.ts(원본 CSS 및 status 24개 값 대조), components.test.tsx(문구·상태 클래스) |
| PublishingTemplateStructureTest | components.test.tsx(표·페이지·모달·알림·근거·필드), tokens.test.ts |

기존 템플릿·JS·위 구조 테스트는 #419까지 유지한다. `npm run test:e2e`는 #403 UI API
fixture 시나리오를 실행한다. 실제 JWT/refresh 검증은 #402의 별도 서버 통합 테스트다.
설치된 Chrome을 쓰려면 `FGC_BROWSER_EXECUTABLE`에 실행 파일 경로를 전달한다.

동일 1440×1000 viewport의 기존 Thymeleaf/React 캡처와 치수 비교는 개발 서버 실행 후:

```bash
FGC_VERIFY_SHELL=true FGC_NODE_BIN="$(command -v node)" \
  ./gradlew test --tests '*AppShellScreenshotIntegrationTest'
```

이 테스트도 임시 PostgreSQL을 사용한다. 기존 화면은 실제 Thymeleaf 렌더러로 생성하고,
조회 API에는 시각 검증용 빈 데이터, React 사용자/월 API에는 UI fixture를 제공한다.
`build/screenshots/app-shell/`에 legacy.png, react.png, react-month.png를 저장한다.
업무 본문은 아직 placeholder이므로 본문 픽셀 일치를 주장하지 않는다. 사이드바 256px,
헤더 52px, 탭 220×40px 및 실제 화면을 비교한다.

## #406 기준정보·정책/룰셋

`/app/base`, `/app/policies`에 조회 화면을 연결했다. 기존 `/base`, `/policies`와 템플릿·JS·테스트는
#419까지 유지한다. API/DB 계약 변경은 없다. #404 로그인/가드가 병합된 develop을 반영했으며 ProtectedRoute·RequirePermission 안에서
화면을 렌더링한다.

### 공용 기준정보 훅

다른 도메인에서도 `src/features/base/api.ts`를 직접 import한다. 모든 요청은 #402 `apiClient`와
React Query를 사용하며 취소 signal을 전달한다. 응답 타입은 생성된 OpenAPI 스키마에서 가져온다.

| 훅 | 파라미터 | 응답 data |
|---|---|---|
| useOrganizations | `{asOf, keyword?, page?, size?}`, enabled? | PageResponseOrganizationResponse |
| useInsurers | `{keyword?, page?, size?}`?, enabled? | PageResponseInsurerResponse |
| useProducts | `{insurerId, asOf, page?, size?}`, enabled? | PageResponseProductResponse |
| useAgents | `{asOf, organizationId?, keyword?, page?, size?}`, enabled? | PageResponseAgentResponse |
| useCommissionItems | asOf, enabled? | CommissionItemResponse[] |
| useInsurerOptions | enabled? | 보험회사 전체 페이지 목록 |
| useOrganizationOptions | asOf, enabled? | 해당 기준일의 조직 전체 페이지 목록 |

일반 페이지는 화면에서 1-base, size=20을 사용한다. 옵션 훅은 size=100으로 모든 페이지를 순회한다.
필수 기준일/보험회사가 없으면 해당 훅은 요청하지 않는다. `activeYn`은 그대로 보존한다.
등록·수정 화면에서는 `activeYn === false`인 option을 disable한다. 기준정보의 읽기 전용 조회 필터는
기존 화면처럼 사용중지 데이터도 조회할 수 있으며 `· 사용중지`를 표시한다.

### URL과 동작

- BASE `tab=organization|insurer|product|agent|commission-item`, `keyword`, `asOf`, `insurerId`,
  `organizationId`, `page`. 기존 tab 식별자를 유지한다. 탭마다 조회 조건과 미조회 초안을 보관한다.
  조회/초기화는 page를 제거하고 월을 보존한다. 새로고침/공유는 현재 탭의 **조회한** 조건을 복원한다.
- POL `tab=versions|commission|cap|refund`, `asOf`, `policyVersionId`. 목록 첫 행을 기본 선택하고
  상세 탭을 열 때만 상세 API를 조회한다. 기준일 변경은 목록을 다시 조회하고 선택 ID를 지운다.
  상세 404가 나도 목록을 유지하고 다른 정책을 선택할 수 있다.
- 기준일이 없으면 현재 전역 기준월의 1일을 사용한다. `asOf=broken` 같은 잘못된 URL은 API에
  전달하여 서버 400 메시지를 표시한다. 인증 오류는 공용 client가 처리한다. 500은 요청 ID만 노출한다.
- 금액/요율/날짜는 공용 format을 사용한다. 브라우저에서 산입·한도·요율을 계산하지 않는다.
  `regulationRefs`와 환급률표 원천 문서는 EvidenceLink로 확인한다.

기존 Thymeleaf 화면의 배치·문구·표 너비를 유지한다. BASE 필터/결과 카드를 분리하고
결과 제목·설명·우측 건수, 초기화→조회 순서, 중앙 이전/다음 및 5페이지 이동 창을 복원했다.
POL 기준일은 제목 오른쪽에 두고, 표의 열 너비와 상세 제목·환급률 메타정보 2열 배치를 유지한다.
필수 입력 검증은 유지하되 기존처럼 별표는 표시하지 않으며 정액은 단위를 열 제목에만 표시한다.
화면 CSS는 공통 CSS 뒤에서 적용한다. DataTable의 선택·열 너비 옵션과 FilterBar/Pagination의
선택적 배치 옵션을 쓰며 다른 화면의 기본 동작은 유지한다.

탭·선택 정책의 URL 복원과 EvidenceLink, 음수 금액의 괄호/빨간색, 요율 4자리 절사는 유지한다.
수수료 항목 분류는 API에 라벨 필드가 없어 `base-list.js`의 표시용 매핑을 그대로 옮겼다.
키보드 선택은 라디오 버튼을 사용하고 근거 버튼·셀 전체 보기 클릭은 행 선택을 바꾸지 않는다.

### 검증·전후 캡처

`src/features/reference/reference.test.tsx`는 탭별 조회·필터·페이지·URL·지연 상세·빈 결과·400/404/500을
검증한다. `e2e/reference.spec.ts`는 역할 4종의 읽기 전용 조회·새로고침·근거 표시를 검증한다.
기존 BaseViewControllerTest, BaseViewIntegrationTest, PolicyViewControllerTest,
PublishingTemplateStructureTest와 policy-list.test.cjs는 유지한다.

개발 서버를 실행한 뒤 실제 HTTP/JWT와 임시 PostgreSQL로 검증하려면 프로젝트 루트에서:

```bash
FGC_VERIFY_REFERENCE=true FGC_NODE_BIN=node \
  ./gradlew test --tests '*ReferenceScreensIntegrationTest' --rerun
```

설치된 Chrome 사용 시 `FGC_BROWSER_EXECUTABLE`도 지정한다. 테스트는 MockMvc로 실제 Thymeleaf
HTML을 렌더링하고, 기존/React 양쪽 브라우저의 업무 요청을 임시 서버에 전달한다. React 시작 세션은
테스트 서버가 발급한 실제 JWT를 주입하고 `/auth/me`부터 실제 API를 사용한다. 운영·개발 DB를 쓰지 않는다.
1440×1000에서 BASE 5탭·POL 4탭의 전후 PNG와 API 상태·행 비교 결과를
`build/screenshots/reference/`에 저장한다. 두 화면의 동일 데이터·표 제목·필터/버튼 순서와 주요 영역의 위치·너비·높이(허용오차 2px)를
비교한다. 전체 이미지의 픽셀 동일성을 주장하지 않는다. #403 공통 셸 변경은 비교 범위 밖이다. 캡처에는 시드 데이터만 들어간다.

긴 기준정보·정책 텍스트는 기존과 같은 1~2줄 미리보기와 전체 보기/접기를 사용한다.
실제 잘림이 발생하는 경우에만 펼침 버튼을 표시하며, 너비 변경 시 ResizeObserver로 다시 확인한다.
상품·설계사 표는 최소 너비 88rem, 정책 버전 표는 84rem을 유지하고 좁은 화면에서 가로 스크롤한다.
