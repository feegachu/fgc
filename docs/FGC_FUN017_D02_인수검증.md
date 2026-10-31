# FUN-017 / #647 D-02 환수 규칙 작성 인수 검증

검증일: 2026-10-30 (Asia/Seoul). 기준: `develop`, D-02 승인 PR #766 병합 이후.
작업 브랜치: `codex/647-clawback-policy`. 최종 검증 SHA는 #647 및 연결 PR에 기록한다.

## 변경 계약

- IF79 생성, IF80 DRAFT 수정, IF81 승인본 복제, IF75 제출·반려·타 사용자 승인·활성화에서 CLAWBACK 규칙을 지원한다.
- 기준금액은 D-02의 `PAID_ATTRIBUTED_AMOUNT`, 경과월 구간은 양끝 포함·0 이상, 환수율은 0~100·소수점 최대6자리다. 조건식은 null/빈 객체만 허용하고 `{}`로 저장한다.
- 수정 시 `clawbackRuleId`의 소유권을 검사하고 ID·생성시각을 보존한다. 전체 PUT의 구간 맞교환도 가능하다. 생성 시 기존 ID를 받지 않는다. 복제 시 새 ID를 발급하고 승인 정보·이력을 초기화한다.
- 같은 항목·동일 경과월 구간은 기존 유일키에 따라 저장부터422, 같은 범위·구체성의 경과월 중첩은 제출부터422다. 보험회사·판매버전이 불일치하면 제출부터400이다. 회사별 환수율 정책은 코드를 나누며 서로 다른 코드의 선택 가능한 정책은 공존한다.
- 헤더·자식·감사·멱등 결과는 한 트랜잭션이다. DB 참조 오류는400, 유일키 충돌은422로 변환한다. 실패 시 기존 규칙·헤더·감사·멱등 결과를 부분 저장하지 않는다.
- 기존 `clawback_rule`, V67 작성 잠금·멱등·감사, V68 계약일 승계를 재사용한다. V75는 기존 정책 자식 잠금 함수에 `clawback_rule`을 추가하고 직접 INSERT/UPDATE/DELETE 가드를 설치한다. 새 판은 기존 대상 범위·항목·경과월을 보존하고 요율을 변경할 수 있다.
- 화면 편집 연결은 별도 #722 범위다. 이 증적은 서버/API 인수 결과다.

## CT-P01 정상·실패 대응

실제 Spring MVC 요청·인증과 일회성 PostgreSQL을 사용한다. `PolicyWorkflowIntegrationTest`는 외부 테스트 트랜잭션으로 감싸지 않으므로 실제 커밋/롤백을 검증한다.

| 시나리오 | 테스트 | 기대 결과 |
| --- | --- | --- |
| 환수 초안→수정→제출→다른 사용자 승인→활성화→복제 | `clawbackWorkflowPreservesEditedIdsAndClonesApprovedRulesWithNewIdsAndHistory` | 규칙·소수 문자열·근거·승인 이력·감사 보존, 수정 ID·생성시각 보존, 복제 새 ID·승인 초기화 |
| SYSTEM_ADMIN 자기 승인·REVIEW/ACTIVE 직접 수정 | 위 테스트 | 각각409, 원본 규칙과 승인 감사 보존 |
| 활성화 전 계산 선택 차단, 활성화 후 실제 계산 | `apiWrittenClawbackPolicyIsExcludedUntilActiveAndThenCalculatesUsingD02` | DRAFT/APPROVED 계산 후보 제외, ACTIVE 포함, 확정 귀속100.4→기준100 ×33.333333%→33원, 경과3개월·실제 규칙 ID 연결 |
| 동시 복제·새 버전의 기존 환수 구간 누락 | `clawbackConcurrentClonesHaveDistinctVersionsAndRulesAndCannotDropSuccessorCoverage` | v2/v3·자식4개 ID 중복 없음, 누락본 제출400, 기존 ACTIVE·승계 이력 불변 |
| 기존 수수료 정책 흐름·기간 충돌·승계·동시 제출 | 기존 `PolicyWorkflowIntegrationTest`, `PolicySuccessionIntegrationTest` | 기존 계약의 이전 정책·요율 유지, 다른 코드의 충돌 거절, 실패 시 상태·감사·승계 롤백 |

## CT-P02 정상·실패 대응

| 시나리오 | 테스트 | 기대 결과 |
| --- | --- | --- |
| 다른 정책 자식 ID·생성 시 기존 ID | `clawbackOwnershipAndFailedChildWritePreserveOriginalDocumentAndAllowCorrectedRetry` | 400, 기존 문서 불변 |
| 두 번째 규칙 FK 실패·같은 키 수정 재시도·같은 키 다른 본문 | 위 테스트 | 헤더·자식·감사·멱등키 롤백, 수정 재시도 성공, 동일 요청 재생, 다른 본문409·중복 감사 없음 |
| 빈 규칙·같은 구체성의 양끝 경과월 중첩·보험회사/판매버전 불일치 | `clawbackSubmissionRejectsEmptyRulesAmbiguousBandsAndMismatchedOfferingInsurer` | 400/422/400, DRAFT 유지·제출 이력/감사 없음 |
| 미지원 기준·귀책 조건·초과 정밀도·음수 월·DB 유일키 중복 | `clawbackCreateRejectsUnsupportedBasisConditionsAndPrecisionWithoutPartialWrites` | 400 또는422, 헤더 부분 저장 없음 |
| 0%·100%·6자리율·빈 조건·중첩·구체성 우선 | `PolicyDraftValidatorTest`의 D-02 환수 테스트 | 허용 경계와 잘못된 입력을 구분. 다른 구체성 규칙의 중첩은 허용 |
| 구간 분할·요율 변경·대상 범위/경과월 누락 | `PolicySuccessionValidatorTest.clawbackSuccessionAllowsNewRatesAndSplitBandsButPreservesScopeAndMonthCoverage` | 적용 범위 보존 시 허용, 누락은 거절 |
| 기존 규칙·근거 누락·부분 저장·조회·CURRENT 계산 | 전체 `com.susukkang.fgc.policy.*` 회귀 | 기존 조회·계산 계약 유지 |
| D-02 계산 경계·실제0원·조건/모호성·멱등·원천 변경 | 기존 `ClawbackCalculationIntegrationTest` | 기존 계산 판정과 저장 snapshot 유지 |

## #647 완료 기준 대응

| 완료 기준 | 증적 |
| --- | --- |
| 다른 사용자의 초안→제출→승인→활성화와 규칙·근거·이력 조회 | CT-P01 환수 workflow + 기존 수수료 workflow |
| 자기 승인·REVIEW/ACTIVE 수정·기간 중첩 거절, 원자성 | CT-P01 실패 + CT-P02 FK 롤백 + 기존 정책 승계 회귀 |
| 멱등 재요청·다른 본문409·동시 채번 유일성 | CT-P02 멱등 + 환수 동시 복제 + 기존 동시 제출/복제 |
| GET 필드·CURRENT 활성 정책 선택 회귀 없음 | 정책 전체 회귀 + OpenAPI/실제 JWT HTTP 시험 |
| CT-P01/P02 정상·실패 결과 기록 | 본 문서의 시나리오와 최종 실행 결과·검증 SHA |
| DTO·오류·OpenAPI·문서 일치 | optional `clawbackRuleId`, 기존 COMMON-002/POL-002 매핑, 인증 OpenAPI 내보내기·타입 재생성·check:api, 인터페이스 §4-4-1/5 갱신. V75 환수 규칙 DB 가드 추가 |

## 실행 결과

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
- 보완 후 정책 작성·환수 계산/후보·권한·명세·월 배선·DB 가드 회귀9개 클래스 **135건 PASS(실패0·오류0·건너뜀0)**. 이전에 실패했던 `MonthlyValidationJobIntegrationTest`4건도 같은 실행에서 통과했다. 최종 SQL 상수화 파일의 직접 DB 가드 재검증과 전체 원격 CI 결과는 PR에 기록한다.
