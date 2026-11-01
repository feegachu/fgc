# FUN-017 / #647 D-02 환수 규칙 작성 인수 검증

검증일: 2026-10-30 (Asia/Seoul). 기준: `develop`, D-02 승인 PR #766 병합 이후.
작업 브랜치: `codex/647-clawback-policy`. 최종 검증 SHA는 #647 및 연결 PR에 기록한다.

## 변경 계약 (2026-11-01 #800 병합 후 기준)

이 브랜치는 POL-W01 화면·서버 계약을 담은 #800(`codex/722-policy-completion`)을 병합한 위에 #647 전용 차이만 남긴다. 겹치던 서버 계약은 #800 기준으로 통일했다.

- IF79 생성, IF80 DRAFT 수정, IF81 승인본 복제, IF75 제출·반려·타 사용자 승인·활성화에서 CLAWBACK 규칙을 지원한다(#800).
- 기준금액은 D-02의 `PAID_ATTRIBUTED_AMOUNT`, 경과월 구간은 양끝 포함·0 이상, 환수율은 0~100·소수점 최대6자리 문자열이다. 조건식은 생략/null/빈 객체만 허용한다(#800).
- 같은 대상의 경과월 중첩과 `uq_clawback_rule` 키 중복은 **저장 단계부터** 422 POL-002다. 코드가 다른 환수 정책도 보험회사·판매버전 지정이 같고 적용 기간이 겹치면 승인·활성화를 막는다. 상품 비교(FUN-079)가 이 범위로 정책을 고르기 때문이다(#800).
- 제출 근거·규칙 누락은 422 POL-004, 없는 보험회사·판매버전·항목 ID는 400 POL-005다. `uq_clawback_rule` 위반은 POL-002로 매핑한다(#800, #647).
- **#647 전용**: 수정 요청의 선택 필드 `clawbackRuleId`는 그 정책에 속한 ID만 허용하고(아니면 400, field=`clawbackRules`), 유지한 행의 ID·생성시각을 보존한다. 같은 ID의 중복 지정과 생성 요청의 ID 지정은 400이다. 전체 PUT에서 두 구간을 맞바꿔도 된다. 복제는 새 ID를 발급한다. 화면도 편집 시 `clawbackRuleId`를 그대로 보낸다.
- **#647 전용**: OpenAPI `ClawbackRuleDraft.clawbackRatePct`는 서버 응답과 같은 문자열 타입이고 `clawbackRuleId`를 선택 필드로 노출한다.
- V75는 #800과 같은 파일이다. 승인·활성·종료 환수 규칙의 직접 INSERT/UPDATE/DELETE와 부모 재귀속을 DB에서 막는다.

## CT-P01·CT-P02 대응 (병합 후 테스트)

| 시나리오 | 테스트 | 기대 결과 |
| --- | --- | --- |
| 초안→수정→제출→다른 사용자 승인→활성화→복제, 원본 보존 | `clawbackDraftsPersistUpdateSubmitApproveActivateAndCloneWithoutChangingApprovedRules` | 소수 문자열 보존, 복제 새 ID, 원본 규칙 불변 |
| 수정 시 ID·생성시각 보존, 구간 맞교환, 복제 새 ID | `clawbackEditsPreserveRuleIdsAndCreationTimesWhileClonesReceiveNewIds` | PUT 전후 `(clawback_rule_id, created_at)` 동일, 복제본 ID는 원본과 겹치지 않음 |
| 다른 정책 ID·중복 ID·생성 시 ID | `clawbackRuleIdsMustBelongToTheEditedPolicyAndAreRejectedOnCreate` | 400(field `clawbackRules`, `clawbackRules[1].clawbackRuleId`), 문서 불변 |
| 없는 대상 ID의 생성·수정 롤백 | `missingClawbackTargetsReturnInputErrorsAndRollback` | 400 POL-005, 헤더·멱등 기록·기존 규칙 롤백 |
| 빈 규칙·근거 누락 제출 | `missingSubmissionRequirementsHaveSpecificMessagesInsteadOfOverlap`, `clawbackInvalidRulesRollbackAndEmptyRulesCannotBeSubmitted` | 422 POL-004, DRAFT 유지 |
| 타 코드 같은 범위 차단·다른 보험회사 공존·비교 선택 | `insurerSpecificClawbackCoexistsWithGenericDemoButIdenticalScopeOverlapIsRejected` | 같은 범위 422, 다른 보험회사 허용, 0·11·12·23개월 비교 선택 정상 |
| DB 직접 변경 차단 | `ClawbackPolicyLockIntegrationTest` | 승인·활성·종료 규칙 변경 거절, DRAFT 허용 |
| 승계본의 범위·경과월 누락 | `PolicySuccessionValidatorTest`의 환수 테스트 2건 | 범위 보존 시 허용, 누락 거절 |
| 오류 매핑 | `ConstraintErrorCodeResolverTest` | FK 3개 → POL-005, `uq_clawback_rule` → POL-002 |

## 실행 결과 (2026-10-30, #800 병합 전)

아래 기록은 #800 병합 전 브랜치의 결과다. 병합 후 결과는 맨 아래 절에 적는다.


정책·환수 회귀: **21개 클래스 253건 PASS, 실패0·오류0·건너뜀0**. 인증 OpenAPI 시험 안에서 실행한 실제 JWT HTTP 시험은 별도 **5건 PASS**다. PostgreSQL17 Testcontainers와 Node22.22.2를 사용했다.

```powershell
$env:FGC_EXPORT_OPENAPI='true'
$env:FGC_NODE_BIN='<Node 22.22.2 node.exe 절대 경로>'
.\gradlew.bat --no-daemon test `
  --tests 'com.susukkang.fgc.policy.*' `
  --tests '*ConstraintErrorCodeResolverTest' `
  --tests '*ClawbackCalculationIntegrationTest' `
  --tests '*ClawbackCandidateIntegrationTest' `
  --tests '*OpenApiFrontendIntegrationTest' --console=plain
```

최종 DTO 문자열 명세·검증 함수 분리 후 `PolicyDraftValidatorTest`, `PolicyWorkflowIntegrationTest`, `OpenApiFrontendIntegrationTest`를 재실행해 **71건 PASS, 실패0·오류0·건너뜀0**을 확인했다. 이 실행의 실제 JWT HTTP 시험도 **5건 PASS**다. 회귀와 재검증 건수는 중복 테스트를 포함하므로 합산한 고유 건수로 표시하지 않는다.

```powershell
.\gradlew.bat --no-daemon test --tests '*PolicyDraftValidatorTest' `
  --tests '*PolicyWorkflowIntegrationTest' --tests '*OpenApiFrontendIntegrationTest' --console=plain
# 기존 명세 충돌을 보존하기 위해 두 환수 컴포넌트를 서버 내보내기와 동기화한 뒤 frontend에서 실행
npm run generate:api
npm run check:api
npm run build
```

OpenAPI 대상 컴포넌트·생성 타입 일치 및 TypeScript/Vite 프론트 빌드 **PASS**. 병합 전까지 이슈는 열어 두며, 화면 #722의 완료를 주장하지 않는다.

백엔드 `./gradlew.bat --no-daemon bootJar verifyPersistenceDependencies --console=plain`도 **PASS**했다. 전체 애플리케이션 시험군을 재실행한 결과를 뜻하지 않으며 이번 정책·환수 범위의 결과만 기록한다.

### 기존 OpenAPI 스냅샷 주의점

전체 인증 내보내기에서 기존 `CompareRankingResponse.CapSummary`와 `ValidationRunDetailResponse.CapSummary`가 같은 스키마 이름을 사용하여 검증 상세의 count 필드가 덮이는 문제를 확인했다. 전체 스냅샷을 교체하면 기존 검증 화면의 TypeScript 빌드가 실패한다. 이번 변경은 서버에서 검증한 `ClawbackRuleDraft`·`PolicyDraft` 컴포넌트만 스냅샷에 반영하고 타입을 재생성한다. 전체 서버 내보내기 원본은 로컬 `build/verification-647`에 보존한다. 이 기존 명세 충돌의 수정은 #647에 포함하지 않는다.

## 2026-10-31 CI·리뷰 보완

- 첫 원격 CI는2365건 중 월 검증 배선 시험2건이 실패했다. 정책 API 시험이 만든2000년 계약이 공유 테스트 DB에 남아, 월 배선 시험의 대상 없음 전제를 깨뜨렸다. `PolicyWorkflowIntegrationTest`를 전용 PostgreSQL DB로 격리하여 API의 실제 커밋/롤백 검증을 유지한다. 월 배선의 기대값·운영 코드는 바꾸지 않는다.
- 리뷰에서 확인한 환수 규칙 DB 가드 누락은 `V75__clawback_rule_policy_lock.sql`로 보완한다. #800의 `64672319`의 동일 파일을 재사용해 번호·내용 충돌을 피한다. 이미 적용된 마이그레이션·데이터는 수정하지 않는다.
- `ClawbackPolicyLockIntegrationTest`는 별도 임시 DB에서 APPROVED·ACTIVE·RETIRED의 직접 INSERT/UPDATE/DELETE와 양방향 부모 재귀속 거절, 원본 환수율/부모 보존, DRAFT 변경 허용을 검증한다(파라미터 포함4건). 가드는 부모를 ID 순으로 `FOR SHARE` 잠가 기존 정책 자식과 같은 활성화 경합 통제를 적용한다.
- 기존 권한·명세·환수 시험의 준비 과정도 승인본 직접 변경 대신 DRAFT 작성 또는 정상 RETIRE를 사용한다. 실제로 테스트할 미지원 조건식은 승인 전에 넣는다.
- Sonar 지적4건은 중복 오류 문구·오류 파라미터 키 상수화, 복제 검증 헬퍼 분리, 자식 ID 목록에 대한 직접 포함 여부 검증으로 정리한다.
- 재분석의 빈 목록 허위 통과 지적(S5841)은 원본 자식 ID 목록에 `isNotEmpty()`를 먼저 적용해 보완한다.
- 보완 후 정책 작성·환수 계산/후보·권한·명세·월 배선·DB 가드 회귀9개 클래스 **135건 PASS(실패0·오류0·건너뜀0)**. 이전에 실패했던 `MonthlyValidationJobIntegrationTest`4건도 같은 실행에서 통과했다. 최종 SQL 상수화 파일의 직접 DB 가드 재검증과 전체 원격 CI 결과는 PR에 기록한다.
- 최종 V75·기존 분급 DB 가드 **5건 재검증 PASS**, 빈 목록 검증을 추가한 복제 인수 시험 **1건 PASS**. `defa48d0`의 원격 전체 build는 **2369건 실행·2355통과·14건너뜀·실패0**으로 통과했다. 마지막 검증문 보완 뒤 전체 검사·리뷰 상태는 PR의 최신 커밋에서 다시 확인한다.

## 2026-11-01 #800 병합 후 결과

- #800과 이 PR이 같은 서버 파일을 다르게 바꿔 13개 파일이 충돌했다. 서로 반대되던 계약(중첩 검사 시점, 누락 오류 400/422, 다른 코드 같은 범위 공존, FK 오류 코드)은 #800 기준으로 통일했다. 근거: #800은 화면·상품 비교(FUN-079)와 함께 검증됐고, 누락·FK 오류를 전용 코드(POL-004/005)로 나눴다.
- 이 PR에는 #647 전용 차이만 남겼다: `clawbackRuleId` 소유권·ID·생성시각 보존, 중복 ID·생성 시 ID 거절, `uq_clawback_rule`→POL-002 매핑, OpenAPI 환수율 문자열 타입, 복제 순서 고정, 화면의 편집 시 ID 전송.
- 백엔드: 정책·환수 계산/후보·권한·명세·상품 비교·오류 매핑 **33개 클래스 364건 실행, 실패0·오류0·건너뜀2**(PostgreSQL 17 Testcontainers).
- 프론트: `check:api`, `lint`, `tsc -b`, `build` 통과. 정책 테스트 **55건 PASS**.
