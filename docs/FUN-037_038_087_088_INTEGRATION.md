# FUN-037·038·087·088 통합 검증

검증일: 2026-11-03

## 통합 내용

#828 `f4ca40f8` 작업본에 #821 `1ea942a8`의 공통 가이드 보완을 통합했다. #822 `78b842ad`는 기존 작업본의 기반이다. 최신 develop `ef9922cc`의 홈 화면 표시·회귀 테스트 변경도 반영했다. 이번 검증 결과는 이 변경들을 합친 작업 트리 기준이며 원격 PR 병합 결과가 아니다.

- 예정 생성/재생성 도움말의 screenId를 `CONT-W02`, `SCHE-W02`로 각각 명시했다. 정적 검사기가 조건식 화면 ID를 놓치던 호환 문제를 해결했고, 임의 동적 ID 허용으로 검사를 완화하지 않았다.
- build의 `check:guides`에서 예정 화면 검사와 전체 화면 target/진입점 검사, 검사기 테스트, ScreenHelp 테스트를 모두 수행한다.
- #821의 화면별 서버 저장·실행·처리·확정 결과 연결, 미저장 입력 보호, 정정 후 오래된 검증 결과 차단, 대사 수치 유효성 검사를 유지했다.
- #828의 기본 month URL 정규화, 예정 생성/재생성 안내, 접힌 도움말 카드 위치 보완을 유지했다. 기능·권한·도움말 생성 카탈로그를 통합 소스로 다시 생성했다.
- 087 실서버 지급 테스트에 다중 계약과 증빙 누락 저장 차단을 추가했다. 제외 판정에서 증빙 없이 저장 불가, 증빙 입력 후 저장 가능을 확인하고 실제 지급은 산입 판정으로 복구해 진행한다.
- 스케줄 전용 fixture/Chrome 검증 스크립트가 `FGC_E2E_SCHEDULE_YEARS=4|7`을 지원한다. 기본값은 기존과 같은 7이며 월 배치 fixture 옵션은 기존 7년 범위를 유지한다.

## 검증 결과

| 검증 | 결과 |
| --- | --- |
| 전체 서버 Gradle test + bootJar | 2,515 통과, 20 건너뜀, 실패/오류 0 |
| 전체 프런트 Vitest | 1,040 통과, 5 건너뜀 |
| production build | 성공, 전체 도움말 검사와 예정 도움말 검사 포함 |
| 정적 검사 | lint, API 계약, E2E 타입, 기능 등록, 도움말 지식, diff 검사 통과 |
| 도움말 등록 | 29개 화면 진입점·54개 target, 기능 655개·지식 위치 1,115개·워크플로 28개 |
| Chrome API-fixture 회귀 | 계약 화면 및 4/7년 예정 화면 7개 통과 |
| 실제 서버 087 | 단일 계약 및 다중 계약 지급 2개 통과 |
| 실제 서버 088 | 신규 시작·기존 시작·원천 정정부터 최종 확정까지 3개 통과 |
| 실제 서버 037/038 | 각 최초 생성·재생성·CSV·응답 유실 재시도 통과 |

087은 초안 저장 → 사전검증 → 확정 → 가이드 완료 표시 → 목록 이동을 검증했다. 단일/다중 계약 모두 서버 CONFIRMED 및 DB 저장 금액, 귀속 계약 개수를 확인했다. 가이드의 다음 버튼만 누를 때 업무 쓰기가 없으며 확정 요청이 한 번임을 확인했다.

088은 불일치 → 원천 지급 정정 → 새 월 검증·대사 → 관리자 최종 확정을 검증했다. 기존 지급 취소 및 대체 지급 확인, 잘못된 정정 지급 번호 거절, 8개 체크리스트, SETTLEMENT의 확정 차단과 GA_ADMIN 인계, 서버/DB FINALIZED 이후 안내 완료를 확인했다. 신규 계약 저장 후 기본 month가 붙는 동선도 정상 통과했다.

037 결과는 48회차/48행/2,160,000원, 038 결과는 84회차/108행/2,304,000원이다. 038 장기유지 61~84회차 24행은 기준금액 100,000원·정액 12,000원이다. 이 수치는 전용 정책 fixture의 값이며 운영 규제값이 아니다.

두 스케줄 모두 정책 누락 지급단계와 빈 사유의 생성 차단, 서버 저장 후 가이드 완료 표시를 유지한 뒤 이동, 실제 서버 응답만 유실시킨 동일 키/본문 재시도, v1 조회 및 과거판 재생성 차단, 예정 확정 비활성, 다운로드 CSV 검산을 확인했다. 업무 결과를 목업으로 대체하지 않았다.

## 재현

Java 21과 프로젝트 Node 22를 사용한다. 실서버 테스트는 기존 `playwright.demo.config.ts`와 폐기 가능한 E2E DB를 사용한다. 설정된 기본 포트를 다른 작업에서 사용 중이면 임시 검증 사본의 frontend/backend 주소를 함께 변경하고 fixtures의 허용 baseURL도 동일하게 맞춘다. 수동 테스트 DB를 E2E 초기화 대상으로 지정하지 않는다.

```sh
./gradlew test bootJar
cd frontend
npm exec vitest run
npm run build
npm run lint
npm run check:api
npm run check:e2e
npm run check:guide-features
npm run check:guide-knowledge
npm exec playwright test -- e2e/schedule-preview.spec.ts e2e/contract-ui.spec.ts
npm exec playwright test -- --config playwright.demo.config.ts payment-guide.spec.ts settlement-guide.spec.ts settlement-completion.spec.ts
```

예정 검증은 `scripts/seed-schedule-live.py`로 새 합성 계약·승인 정책을 준비하고 `frontend/scripts/verify-schedule-live.mjs`에 반환된 contract ID를 전달한다. 양쪽 모두 `FGC_E2E_SCHEDULE_YEARS=4` 또는 `7`을 동일하게 설정한다. 서버 origin, 전용 DB, 로그인/승인 계정은 환경변수로 전달하고 `FGC_E2E_REGENERATE=true`로 생성·재생성을 허용한다. 상세 변수는 각 스크립트와 `FUN-038_SPEC_AUDIT.md`를 따른다.

## 검증 경계

자동 예정 생성의 계약·일 배치·월 검증 경로는 전체 서버 테스트에 포함된다. 이번 Chrome 검증의 월 검증·재대사는 088 현행 업무 흐름이며 4/7년 상품 월 배치 전체를 별도 Chrome 시나리오로 반복하지 않았다. 4/7년 지급자격·월한도 확정 등 문서상 제외 범위를 추가하지 않았다.

API-fixture 회귀의 최초 실행은 테스트 웹 서버의 localhost/IPv4 주소 불일치로 기동 대기에서 종료됐다. 이미 준비한 격리 frontend 주소로 실행하여 7개 모두 통과했다. 제품 코드 실패나 테스트 단언 완화는 없었다.

기존 수동 테스트 DB를 보존했다. 원격 PR 병합은 이 검증과 별개이며 리뷰 승인과 해당 커밋의 CI 확인이 필요하다.
