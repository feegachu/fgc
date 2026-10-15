# #418 검증 기록

2026-10-15, Windows / Java 21 / Node 24.19.0 / Chromium / PostgreSQL 17.
CI는 저장소 `.nvmrc`의 Node 22.22.2를 사용한다. 아래 로컬 결과와 CI 결과는 구별한다.
기준 develop: `29f3ccb05fe648867850a7bc63a24cb03b5f16eb`.
아래는 최초 구현 검증 기록이며, 리뷰 보완 후 재검증은 문서 하단에 별도로 기록한다.

| 검증 | 명령 / 결과 |
|---|---|
| 기존 백엔드 전체 | `./gradlew.bat build` 성공. JUnit 1,934건, 실패 0, 오류 0, 기존 skip 12 |
| 기존 node | `node --test ../src/test/js/*.test.cjs` 17/17 통과 |
| 프론트 커버리지 | `npm run test:coverage -- --maxWorkers=2` 436 통과, 기존 skip 4. 33 파일 통과, 기존 skip 파일 1 |
| 커버리지 수치 | Statements 93.22%, Branches 84.39%, Functions 93.23%, Lines 95.29%. HTML/lcov/json-summary 생성 |
| 정적 검증 | `npm run lint`, `npm run build`, `npm run check:api`, `npm run check:e2e`, workflow YAML 파싱·액션 SHA 검사 성공 |
| 권한 회귀 주입 | `ValidationRunDetailPage`의 확정 enabled에서 `canFinalize &&`만 임시 제거. SETTLEMENT 테스트가 `toBeDisabled()`에서 실패(exit 1). finally로 원본 복원 후 전체 커버리지 통과 |
| 백엔드 미기동 | `node scripts/check-backend.mjs http://127.0.0.1:18089/login 1000` → exit 1, `FGC E2E backend readiness failed`, URL/1000ms/ECONNREFUSED/backend.log 안내 확인 |
| 기존 브라우저 테스트 | `npm run test:e2e -- --workers=1 --output=e2e-artifacts/ui-test-results` 53 통과, 기존 별도 실서버 활성화 조건의 5개 skip |
| 실제 서버 3회 | `npm run test:e2e:stable` 성공: 11/11 × 3 = 33 통과, skip/retry 0. 회차 간 11개 시나리오의 DB 건수·확정 금액 일치, advisory lock 0. `e2e-artifacts/stability.json`, `round-{1,2,3}/outcomes.json`에 기록 |

권한 테스트는 모든 조건이 통과한 체크리스트가 화면에 도착한 뒤 버튼을 검사한다.
로딩 중 비활성 상태를 권한 차단으로 오인하지 않는다.
권한 회귀 주입은 로컬에서 확인했으며 의도적으로 망가진 코드를 원격 브랜치에 올리지 않았다.

| 회차 (KST, 2026-10-15) | 시작 → 종료 | 결과 |
|---|---|---|
| 1 | 11:02:50 → 11:07:32 | 11/11, exit 0 |
| 2 | 11:07:32 → 11:11:54 | 11/11, exit 0, 1회차 DB 결과 일치 |
| 3 | 11:11:54 → 11:16:11 | 11/11, exit 0, 1회차 DB 결과 일치 |

커버리지 첫 실행은 백엔드 전체 테스트와 병행한 상태에서 기존 스케줄 테스트 1건이 5초 제한을 초과했다.
백엔드 종료 후 동일 코드·동일 제한으로 다시 실행해 전체 통과했다. 제한 시간을 늘리거나 해당 검사를 제외하지 않았다.

## CI 확인

- 기존 필수 체크 `build`, `frontend` 이름 유지. `frontend`에 coverage artifact와 E2E 타입 검사를 추가했다.
- [PR CI 첫 실행](https://github.com/feegachu/fgc/actions/runs/38669718499)은 build/frontend 모두 성공했고 `frontend-coverage` artifact 생성(1,168,183 bytes)을 확인했다.
- 초기 자동 리뷰: Claude 도메인 지적 없음, SonarCloud 통과. 후속 요구사항 리뷰의 보완은 하단에 기록했다. CodeRabbit의 `job` 컨텍스트 위치 지적을 확인하여 신규 E2E의 컨테이너 ID 환경변수를 job 수준에서 실행 step 수준으로 이동했다. 최신 커밋의 재검증 상태는 PR 체크를 따른다.
- `actionlint 1.7.12`로 수정된 `frontend-e2e.yml`과 `ci.yml` 검증 성공. YAML 파싱만으로 확인하지 못한 Actions 컨텍스트 규칙도 검사했다.
- 신규 `Frontend E2E`는 develop push / workflow_dispatch만 실행한다.
- 신규 workflow가 아직 기본 브랜치 develop에 없으므로 현재 브랜치만 push한 상태에서는 수동 실행할 수 없다.
  병합 후 첫 develop 실행의 `frontend-e2e-evidence` artifact와 3회 결과를 확인해야 한다.
- PL 대응표 승인과 GitHub Actions 실서버 3회 기록은 아직 완료가 아니다. #418 / #399를 닫지 않는다.

상세 실행법과 결과 파일 위치는 [README](README.md), 잔여 차이는 [differences](differences.md)를 따른다.

## 실제 브라우저 캡처

첫 전체 시연 실행의 Playwright 첨부를 보관했다. 모두 격리된 합성 데모 데이터다.
캡처는 동작 증거이며 픽셀 비교 기준 이미지를 승인한 것은 아니다.

- 감사로그: [1차](screenshots/legacy-audit.png) / [React 선택·복원 후](screenshots/react-audit.png)
- [1차 계약 목록](screenshots/legacy-contracts.png)
- [React 스케줄 확정 후](screenshots/react-schedule-confirmed.png)
- [React 대사 비교 모달](screenshots/reconciliation-comparison.png)

## Claude 요구사항 리뷰 후속 보완

[원문과 인계를 함께 검토한 두 번째 리뷰](https://github.com/feegachu/fgc/pull/544#issuecomment-6190019671)를 현재 코드와 대조했다.

| 필수 보완 | 반영 위치 / 검증 내용 |
|---|---|
| 검사식 누락 3개 | inventory 생성기에 `isLessThan` 추가. PublishingTemplateStructureTest L922/L1510/L1513 포함, 65개 테스트 / 1,220개 검사식 |
| 거절 지급 → 동일 예외 → 동일 CAP | 지급 ID를 sourceEntityId로 가진 CAP_VIOLATION 예외를 찾아 정확한 번호로 선택. 거절 코드·사유, 링크 유형·계약번호, 조치 요청 ID, CAP ID·계약·위반 상태·새로고침 복원 확인 |
| 실제 로그인 경로 | 비로그인 지급 등록 주소 → 로그인 → 쿼리·해시 복원 1개 추가. 기존 4역할 테스트에 대시보드·시드 사용자명·역할·관리 메뉴 표시/비표시 추가 |
| 계약 저장 알림 | 등록·수정 후 성공 toast 확인 |
| 스케줄 변경 결과 | 같은 계약의 새 ID·버전 +1·재생성 사유, 확정 응답과 재조회 CONFIRMED 확인 |
| 지급 6단계 | 성공은 6단계 모두 통과, 한도 초과는 5단계 차단·6단계 미확인 표시를 서버 사전검증과 함께 확인 |
| 월 검증 진행 UI | 생성 시 0/10, 상태 배지 계산완료·8/10·앞 8단계 완료·수동 2단계 대기 확인 |
| 삭제 전 남은 의존 | D4에 실서버 MPA 로그인·계약·감사 비교 및 readiness 의존 추가. 감사 공통 class 단언 없음/PL 판단을 대응표에 명시 |

효과가 큰 권장 사항도 반영했다. GA_ADMIN으로 미충족 조건 8개와 비활성 사유를 확인하고,
예외 처리 후 8개 통과·확정 가능 상태를 확인한다. 역분개는 원본/역분개 양방향 ID·상태와
각 분개행 계정·계약·차변/대변 반전을 비교한다. 감사 A2 전체 건수와 A4 일반 셀 클릭·패널 제목도 확인한다.
e2e 프로필과 환경변수 설정 역할은 실행 문서에 구분했다.

CAP-001 응답은 현재 예외/CAP ID를 직접 제공하지 않는다. 신규 API 계약을 만들지 않고,
실제 예외 조회 응답의 sourceEntityId·exceptionCaseId·capCheckId로 연결을 검증한다.
계약 업무 필드 변경/자동 스케줄 재생성은 별도 요구로 추가하지 않았다.
시드 파일 이동·CI 타임아웃 증가는 확인된 장애가 없어 변경하지 않았다.
일반 CAP 목록 행 → 모달은 기존 `e2e/cap.spec.ts` 검증을 유지하며, 실서버는 이번 거절의 CAP 근거 연결을 강화했다.

개발 중 첫 실행은 접힌 관리 메뉴의 존재 검사가 실패했다. 메뉴 존재와 펼친 뒤 표시를 구분하여 수정했다.
최종 검토에서 월 검증의 일반 '완료' 문자열 대신 상태 배지의 '계산완료'를 검사하도록 좁혔다.
이전 실행을 최종 코드의 3회 기록에 섞지 않고, 마지막 수정 후 전체 연속 실행을 다시 수행했다.

### 보완 후 재검증 (2026-10-15)

| 검증 | 결과 |
|---|---|
| 최종 실서버 전체 3회 | `npm run test:e2e:stable`: 12/12 × 3 = 36 통과. 실패/skip/retry 0. 12개 시나리오의 회차별 DB 건수·확정 금액 일치, advisory lock 0 |
| Vitest 커버리지 | 436 통과, 기존 skip 4. Lines 95.29%, Statements 93.22%, Branches 84.39%, Functions 93.23% |
| 기존 Playwright UI | 53 통과, 기존 선택 실행 5개 skip. 별도 출력 경로 `e2e-artifacts/ui-test-results` 사용 |
| 기존 node | 17/17 통과 |
| 빌드·정적 검사 | build, check:api, check:e2e, actionlint 성공. lint 오류 0 (로컬 생성 coverage 파일의 기존 unused-disable 경고 6개), 변경 E2E 파일 lint 경고/오류 0 |
| 검사식 목록 | 65개 테스트 / 1,220개 검사식. 재생성 전후 파일 SHA-256 동일 |
| 백엔드 미기동 | 18089 `/login`, 1000ms → exit 1, readiness failed·ECONNREFUSED·URL·로그 안내 재확인 |
| 보존 범위 | 이번 보완에서 `src/main`, `src/test`, `frontend/src`, workflow 변경 없음. 기존 백엔드 전체 검증은 위 최초 구현 기록이며 재실행으로 표기하지 않음 |

| 최종 회차 (KST) | 시작 → 종료 | 결과 |
|---|---|---|
| 1 | 20:13:00 → 20:17:39 | 12/12, exit 0 |
| 2 | 20:17:39 → 20:22:14 | 12/12, exit 0, DB 결과 일치 |
| 3 | 20:22:14 → 20:26:53 | 12/12, exit 0, DB 결과 일치 |

로컬 증거는 `e2e-artifacts/stability.json`과 `round-{1,2,3}/{demo.xml,outcomes.json}`에 있다.
GitHub Actions 증거와 구별한다. 아래 미완료 조건을 완료 처리하지 않는다.

### 남은 완료 조건

- PL: 1,220개 검사식 대응/제외 범위, 감사 class·시각 차이(D2/D3/D9), 시연콘티·대사 준비·예외 조치 순서(D5/D6/D7) 승인.
- CI: 병합 후 develop의 실제 서버 3회 실행 및 `frontend-e2e-evidence` artifact 확인.
- CI 오류 증빙: 권한 회귀 주입과 백엔드 미기동의 명확한 실패를 CI에서 확인. 기존 로컬 실패 검증을 CI 증빙으로 간주하지 않는다.
- #419: Vitest의 원본 파일 참조와 실제 서버 MPA 비교 의존을 먼저 이관한 후 삭제 검토. 기존 Java/node 테스트는 그대로 유지한다.
