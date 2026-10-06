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
- 화면 라우트는 인터페이스정의서 §5-2 "2차 React 경로" 열을 따라 `src/app/router.tsx`에 추가한다.
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
일반 프론트 실행에서 `server.test.ts`의 3개 실서버 검증은 건너뛰고 위 명령이 별도로 실행한다.
일반 백엔드 빌드에서도 추출 테스트는 opt-in이므로 스펙 파일을 자동으로 덮어쓰지 않는다.

TS 6은 #401 결정을 유지한다. openapi-typescript 7.13.0의 TS 5 peer 요구는
`overrides.openapi-typescript.typescript = "$typescript"`로 맞춘다.
`--force`, `--legacy-peer-deps`는 쓰지 않는다. 생성 타입의 응답 필드는 springdoc가 optional로
내보내므로 소비자는 null/undefined를 고려한다. 인증 응답의 토큰은 클라이언트가 필수로 검증한다.
