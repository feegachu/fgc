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
