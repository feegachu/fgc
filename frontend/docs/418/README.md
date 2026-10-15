# #418 실제 서버 시연 E2E

기존 UI API-fixture 테스트를 유지하며 PostgreSQL/백엔드 브라우저 시연을 추가한다.
경로는 `/app/` 유지. 신규 운영 API/DB migration은 없다.

- [구조 9개·node 4개 대응표](coverage-map.md)
- [검사식 전수 목록](assertion-inventory.md)
- [차이·확인 사항·Thymeleaf 의존](differences.md)
- [로컬 검증 기록·CI 확인 사항](verification.md)

## 실행

Java 21, `.nvmrc`의 Node, Docker가 필요하다. 개발 DB(fgc-db/5433)를 사용하지 않는다.

```sh
# 저장소 루트. 이미 존재하는 컨테이너를 임의로 삭제하지 않는다.
docker run --detach --name fgc-e2e-418 --label com.fgc.e2e=418 \
  -e POSTGRES_DB=fgc_e2e -e POSTGRES_USER=fgc_e2e -e POSTGRES_PASSWORD=fgc_e2e_only \
  -p 127.0.0.1:55418:5432 postgres:17
./gradlew bootJar
cd frontend
npm ci
npx playwright install chromium
FGC_E2E_DB_CONTAINER=fgc-e2e-418 npm run test:e2e:demo
# 전체 시나리오 3회 + 회차 간 DB 건수/금액 비교
FGC_E2E_DB_CONTAINER=fgc-e2e-418 npm run test:e2e:stable
```

PowerShell: `./gradlew.bat bootJar`, `$env:FGC_E2E_DB_CONTAINER='fgc-e2e-418'` 후 npm 명령.
`FGC_E2E_DB_PORT` 기본 55418, `FGC_E2E_DB_PASSWORD` 기본 전용 DB 값,
`FGC_E2E_PASSWORD` 기본값은 V3 시드의 합성 demo 계정 비밀번호다. 운영 자격 증명을 쓰지 않는다.

e2e 프로필 bootJar는 18081, Vite는 15173에서 실행한다. 기존 서버를 재사용하지 않는다.
`SPRING_PROFILES_ACTIVE=e2e`는 기본 local 프로필과 application-local.yml의 로컬 설정을 배제한다.
별도 application-e2e.yml은 없으며 DB/포트/Flyway/예약 배치 설정은 `scripts/e2e-server.mjs`의 환경변수로 주입한다.
Flyway는 기존 db/migration,db/demo를 적용하고 예약 배치를 끈다.
첫 기동 후 pg_dump로 초기 상태를 보관하고 시나리오 전·후에 복원한다. workers=1, retries=0.
초기화는 전용 컨테이너 label·DB명·사용자명을 검사하며 진행 중 검증이 있으면 거부한다.
운영용 reset API나 인증 우회는 없다.

## 시나리오와 CI

| 파일 | 인계 / 경로 |
|---|---|
| e2e-demo/auth-audit.spec.ts | #404 역할 4종·로그아웃·중복 로그인 복귀, #407 A1–A6·키보드, #409 COMPLIANCE 버튼 권한 |
| e2e-demo/journey.spec.ts | #408 계약 등록/수정 → #409 스케줄, #410 복수 계약 지급·한도 거절 → #414 조치 → #411 CAP, #415 월 검증/확정, #416 대사, #413 역분개 |

동작은 실제 화면/API를 사용한다. 준비용 기준정보 조회·월 배치는 실제 API를 사용한다.
대사 결과 초기화와 월 검증 예외 처리 이유는 D6/D7에 기록했다.
감사/계약 1차와 React 캡처는 Playwright 첨부로 남긴다.

- 기존 build/frontend 이름과 액션 SHA 고정을 유지한다.
- frontend: Vitest V8 text/html/lcov/json-summary, frontend-coverage artifact.
- Frontend E2E: develop push 또는 workflow_dispatch, PostgreSQL 서비스, 기존 브라우저 테스트와 실제 서버 3회 실행.
- 3회는 별도 Playwright 호출이다. 재시도로 성공한 것을 무흔들림 성공으로 세지 않는다.
- 증거: e2e-artifacts/stability.json, 회차별 outcomes.json/JUnit/서버 로그, HTML report, 실패 trace/video/screenshot.
- 기동 실패는 FGC E2E backend readiness failed와 URL·대기 시간·backend.log 위치를 출력한다.
- `npx playwright show-trace <trace.zip>`으로 요청/화면을 확인한다.
- demo-seed.sql은 초기화 전용이며 CI artifact에서 제외한다.

## 리뷰

Related to #418, Related to #399. PL 승인·CI 증거 전에는 Closes를 사용하지 않는다.
사용자가 만든 418-test-react-playwright-e2e 브랜치를 유지한다.
CONTRIBUTING/PR 템플릿에 변경 라우트(없음)·API(없음)·차이·검증·캡처·원본 의존을 기록한다.
