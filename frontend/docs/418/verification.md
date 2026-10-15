# #418 검증 기록

2026-10-15, Windows / Java 21 / Node 24.19.0 / Chromium / PostgreSQL 17.
CI는 저장소 `.nvmrc`의 Node 22.22.2를 사용한다. 아래 로컬 결과와 CI 결과는 구별한다.
기준 develop: `29f3ccb05fe648867850a7bc63a24cb03b5f16eb`.

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
- 자동 리뷰: Claude 도메인 지적 없음, SonarCloud 통과. CodeRabbit의 `job` 컨텍스트 위치 지적을 확인하여 신규 E2E의 컨테이너 ID 환경변수를 job 수준에서 실행 step 수준으로 이동했다. 최신 커밋의 재검증 상태는 PR 체크를 따른다.
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
