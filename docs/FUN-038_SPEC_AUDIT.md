# FUN-038 7년·장기유지 예정 스케줄 구현 및 검증

## 기준과 의존성

이슈 #650, 기능요구사항 v3.0의 FUN-038, 화면정의서 SCHE-W01/W02, 인터페이스 IF-API-19A/27/28/29/29A를 기준으로 한다.
기반은 develop `afd53efa`와 FUN-037 PR #822 `319b7a39`다. **#822 선행 병합 후 develop 차이를 다시 확인한다.**
PR #821의 공통 ScreenHelp/SaveGuide를 재사용했다. #821 병합 시 이 파일들의 중복 변경을 정리한다.

## 구현

- 선택된 승인·활성 정책의 SEVEN_YEAR_2029 규칙으로 최대 84회차의 SIMULATION 예정판을 생성한다. 회차 수와 수수료 항목 행 수를 구분하고 정책에 없는 회차를 추가하지 않는다.
- 장기유지 구간·요율·정액은 정책에서 읽는다. 61 등 특정 시작 회차를 계산기에 고정하지 않는다. 구간/규칙 미설정은 미준비로 안내한다.
- 정액 행의 기준금액은 정책 basis에 따른 실제 금액으로 저장하고, CSV 마지막 열에 정액을 내보낸다. 정률 행의 정액 열은 빈 값이다.
- 계약·일 배치와 월 검증의 기존 진입점에서 같은 예정 생성기를 사용한다. 원천·정책 fingerprint가 같으면 기존 결과를 재사용하고 최초 validation_run_id를 보존한다.
- 월 배치에서는 두 지급단계 중 하나가 취소판 또는 선택 불가 정책이면 해당 계약의 생성을 롤백하고 예외로 남긴다. 기존 계약·일 배치의 단계별 검토 등록 방식은 유지한다.
- 원천 버전·멱등키·잠금·이전판·감사 이력을 유지한다. 예정판 확정은 409로 차단하며 CURRENT 운영판은 보존한다.
- 생성 사유 → 실제 서버 저장 → 가이드 완료 표시 → 사용자 종료 후 상세 이동을 연결했다. 실패 시 입력 보호, 응답 유실 시 동일 요청 재확인, 화면/대상 변경 후 늦은 응답 이동 방지를 유지한다.
- develop의 data-guide-feature와 생성 도움말 data-guide-target을 함께 유지한다. 빌드에서 생성 도움말 등록·target 누락/중복을 검사한다.
- 실서버 CI의 schedule.detail fixture는 reason을 URL query가 아닌 현재 API의 JSON 본문으로 전달하도록 수정했다.

## 검증 결과 (2026-11-03)

| 검증 | 결과 |
| --- | --- |
| 전체 서버 Gradle test | 2,512 통과, 20 건너뜀, 실패 0 |
| 전체 프런트 Vitest | 971 통과, 5 건너뜀, 실패 0 |
| Chrome API-fixture 회귀 | 7 통과: 4/7년 조회·재생성 및 계약 화면 |
| 실서버 CI schedule.detail | COMPLIANCE 2/2단계 통과 (격리 포트·DB, Chrome, 영상 기록만 로컬 비활성) |
| 정적 검사 | production build, ESLint, API 계약, E2E 타입, 가이드 기능/지식 등록, diff 통과 |
| 실제 Spring/PostgreSQL + Chrome | 생성 → 재생성 → 가이드 완료·이동, CSV 검산 통과 |

실서버 fixture는 월납환산 초회보험료 100,000원, 일반 1~84회차 24%, 장기유지 61~84회차 정액 12,000원이다. 이는 검산용 값이며 운영 규제값이 아니다.
결과는 **84회차/108행/총 2,304,000원**이며 장기유지 24행의 기준금액·정액·예상금액 및 실제 다운로드 CSV를 확인했다.
서버 저장 후 응답 전달만 중단하여 재시도한 경우 동일 키·본문으로 같은 헤더를 반환했다. DB에는 v1/v2만 존재하고 v2만 활성이다.
사유/정책 누락 생성 차단, 예정 확정 차단, 구버전 재생성 차단, 미저장 입력 유지·폐기, 도움말 접기·Esc 종료를 실제 화면에서 확인했다.

## 재현

- 서버: `./gradlew test` (Java 21, Docker/Testcontainers).
- 프런트: `npm exec vitest run`, `npm run build`, `npm run lint`, `npm run check:api`, `npm run check:e2e`, `npm run check:guide-features`, `npm run check:guide-knowledge`.
- 화면 회귀: `npm exec playwright test e2e/schedule-preview.spec.ts e2e/contract-ui.spec.ts`.
- 실서버 전용 fixture: `scripts/seed-schedule-live.py`. FGC_E2E_DB_CONTAINER/DB_NAME/DB_USER, API_ORIGIN, LOGIN_ID/PASSWORD, APPROVER_ID/APPROVER_PASSWORD를 명시한다. 기존 데이터를 삭제하지 않고 새 합성 계약·승인 정책을 추가한다. 일반 DB가 아닌 전용 검증 DB에서 실행한다.
- 브라우저: `node frontend/scripts/verify-schedule-live.mjs`. FGC_E2E_ORIGIN, CONTRACT_ID 또는 SCHEDULE_ID, LOGIN_ID/PASSWORD, BROWSER_EXECUTABLE, OUTPUT을 지정한다. 생성·재생성은 FGC_E2E_REGENERATE=true가 필요하며 새 버전이 추가된다.
- 최초 생성: 계약 상세의 스케줄 탭 → GA→설계사 도움말 → 사유 `7년 예정 최초 생성` → 다음 → 7년 예정 생성 → 다음 → 가이드 완료 → 안내 마치기.
- 재생성: 상세에서 사유 `7년 예정 새 버전 검증` → 7년 예정 새 버전 만들기. 이전판 보존·활성판 1개·확정 비활성·CSV를 확인한다.

## 경계

신규 DDL/Flyway 변경은 없다. FUN-064 지급자격·유지조건·월한도 확정, FUN-062, 상태별 보류·재개는 포함하지 않는다.
이번 실서버 재검증은 예정 화면 흐름이며 월 배치 전체 재실행과 구분한다. 개인 로컬 경로·계정 비밀번호·브라우저 산출물은 PR에 포함하지 않는다.
