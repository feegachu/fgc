# #634 FUN-085 구현·검증 기록

기준 브랜치는 `feature/634-auth-agent-data-scope`이며 기존 IntelliJ 프로젝트 폴더에서 작업한다. 기존 테이블·컬럼을 확장하지 않고, 서버의 역할·객체 범위 검증과 V64 시드 데이터를 추가한다.

## 시드 데이터의 근거

| 값 | 근거·결정 |
|---|---|
| `AGENT` 역할 | 기능요구사항 CSV FUN-085, 화면정의서 §4-1, 시드 명세서 §5 |
| `agent01` 대표 계정 1개 | 시드 명세서 §5 GOLDEN 사용자 |
| `A-FC-001` 연결 | 시드 명세서 §5의 프로젝트 가정을 그대로 적용. 새 설계사·계약 생성 없음 |
| 계정의 소속 조직 | 연결된 기존 `agent.organization_id`를 조회 |
| 사용자 표시명 | 시연 계정임을 나타내는 표시 문자열. 설계사 개인정보를 새로 생성하지 않음 |
| 비밀번호 | 초기 미지정 표식 `!`·DISABLED. 공통 자격 증명 복제 없음. 관리자가 개별 BCrypt 비밀번호 설정 후 활성화 |
| V64 번호 | 사용자 지정. develop V59~V61 이후 V62·V63을 사용하지 않고 V64로 분리 |

파일은 `src/main/resources/db/demo/V64__seed_agent_role_and_account.sql`이다. 기존 Flyway 파일은 수정하지 않는다. GOLDEN 설계사가 없거나 기존 agent01의 역할·연결이 다르면 조용히 덮어쓰지 않고 마이그레이션을 실패시킨다. DAR-012의 600인 데이터셋 생성·대표 설계사 재연결은 별도 작업이다.

리뷰 중 develop에 #540(`94f6ee1b`)이 병합되어 V59·V60·V61 및 판매기간 배제 제약을 반영했다. 조회 시점의 열린 #685는 실제 V59 파일, #690은 V61 파일을 포함해 develop과 번호가 겹친다. 해당 PR에서 버전 조정이 필요하며 다른 PR의 파일은 이 브랜치에서 수정하지 않는다. 이 PR의 V64는 유지한다.

현재 local·test 및 db/demo를 포함하는 배포 환경에서 적용된다. 배포 계정 보호 대상에 agent01도 포함하도록 시드 명세서를 갱신했다. 테스트에서 생성하는 UUID 비밀번호·담당자 이력·PDF 메타데이터는 일회성 PostgreSQL의 트랜잭션 fixture이며 실제 시드가 아니다.

리뷰에서 확인한 공통 시연 자격 증명 복제를 제거했다. 신규 agent01은 비활성이므로 별도 비밀번호를 설정·활성화하기 전 로그인할 수 없다. 기존에 같은 연결로 있던 계정의 상태·비밀번호를 이 시드가 덮어쓰지는 않는다.

## 구현한 동작

- `app_user.agent_id`를 인증 조회 DTO·FgcUserDetails·JWT claim·/auth/me까지 전달한다. JWT의 roles·agentId로 권한을 확대할 수 없고 Bearer 인증은 기존처럼 DB의 현재 계정 상태를 확인한다.
- /auth/me는 DB의 현재 연결·역할과 기존 권한식을 사용한다. `agentId`는 nullable이고 `isAgent`, `canUseAgentWorkspace`를 추가한다. 기존 정산·감사·역분개·확정 권한은 AGENT에게 부여하지 않는다.
- `AgentRequestAuthorization`은 새 AGENT에만 적용한다. 기존 네 역할과 미인증 요청의 기존 체인 규칙은 유지한다. 현재 객체 검증이 연결된 계약·지급 조회 및 공개 보험회사·상품·수수료 항목만 개방한다.
- 세 체인의 `SessionRoleValidationFilter`는 실제 세션 사용자의 DB 식별자·역할·계정 상태를 다시 확인한다. 변경·잠금·비활성화·AGENT 연결 누락이면 세션을 폐기하고 401 `FGC-AUTH-002`로 재인증한다. Bearer 헤더 문자열만으로 검증을 건너뛰지 않는다. 역할 변경 전 내부 세션으로 전체 설계사를 조회하던 우회를 재현한 후 차단했다.
- 계약은 모집자 또는 서울 업무일 현재 유효한 유지관리 담당자에게 허용한다. 효력일·종료일을 포함하고 미래·종료 이력은 제외한다. 계약 목록·count·CSV에 같은 QueryDSL predicate를 적용한다.
- 지급은 계약 모집자가 아니라 실제 `recipient_agent_id`로 검사한다. 직접 타인 계약·지급 ID를 요청하면 403 `FGC-AUTH-005`이다. 내부 전용 경로는 기존 403 `FGC-AUTH-003`으로 거절한다.
- 계약 상세 기본정보·상태 이력을 제공한다. 여러 수령인이 섞인 기존 하위 원장·스케줄·지급대사와 AI 문서함·원본·추출 행은 AGENT에게 개방하지 않는다.
- React에서 관리자 홈을 호출하지 않고 본인 계약 목록으로 진입한다. 본인 계약·지급 메뉴만 표시하고 전체 설계사·조직 선택지를 호출하지 않는다. 직접 URL·저장 탭·계약 상세 내부 탭으로 내부 화면을 열 수 없다.
- `AgentDataScopeService`는 계약·지급·환수·문의·비교설명·PDF 판·AI/문의 대상 검증에 재사용할 수 있다. 실제 데이터 조회·반환 전에 검사해야 하며 목록·행·합계도 같은 수령인/소유자 범위로 제한해야 한다. AI·문서 등 후속 도구는 인증 문맥이 없으면 401로 거절하고, 비동기 호출 시에도 로그인 권한 문맥을 전달해야 한다.
- 비교설명 확인서는 `source_entity_type=COMPARE_EXPLANATION`과 원본 비교설명의 agent_id를 확인한다. 생성 작업자와 무관하게 비교설명·PDF가 같은 범위를 따르며 계정의 설계사 연결 변경 후 과거 설계사의 PDF는 거절된다. 환수 통지서는 해당 PDF 판의 수신 설계사로 판정한다.
- AGENT 홈 리다이렉트는 유효한 정산월을 유지한다. 이미 열린 워크스페이스에서 다른 월의 홈 링크로 재진입하는 회귀 시험을 포함한다.

## 후속 이슈에 남는 연동

PDF 생성·다운로드(#657 등), 피코(#664), 문의(#663), 설계사 홈(#659), 정제 명세(#660), 계정 관리(#678)의 기능 자체는 이 변경에서 새로 만들지 않는다. 각 PR은 공통 범위 검증을 연결하고 HTTP 메서드·경로를 명시적으로 개방해야 한다. 현재 미구현·미연동 경로는 AGENT에게 거절된다.

따라서 현재 PDF URL/AI 요청의 역할 차단과 실제 DB 기반 공통 검증은 확인하지만, 후속 실제 파일 생성·다운로드·LLM 응답 E2E까지 완료했다고 간주하지 않는다. 문의 등록 같은 쓰기는 대상 검증과 업무 상태 변경을 같은 트랜잭션에서 수행해야 한다. 기존 세션의 역할·상태 변경은 다음 요청에서 재인증을 요구한다.

비교설명 초안의 소유자는 V58의 `agent_id`를 사용한다. 계정별 작성자 이력을 별도로 보존하는 요구로 바뀌면 #657에서 `created_by` 확장 여부를 정한다. 원본 요구사항 XLSX의 RTM·SRC-054 근거대장 원문은 로컬에 없어 원문 확인·갱신 요청을 PR에 남겨야 한다.

## 검증

- 세션 검증 최종 코드의 `gradlew.bat build`: 성공. 264개 suite, 2,157건 중 2,145건 통과·12건 선택 실행 항목 건너뜀. 실제 PostgreSQL(Testcontainers) 사용. 이 실행은 develop `7545c20d` 기준으로 시작했고, 이후 추가된 `94f6ee1b`의 V59~V61은 별도 통합 검증 및 PR CI로 확인한다.
- 세션의 누락된 연결·계정 상태 거절과 서명된 JWT claim 조작 시험을 추가했다. 실제 폼 로그인 세션으로 세 체인의 역할 변경, MPA의 가짜 Bearer 헤더, LOCKED·DISABLED 전환도 회귀 검증한다. 기존 기준정보 화면 시험의 서로 불일치하던 사용자 ID·loginId fixture는 실제 DB 사용자 조회로 교정했다.
- 최신 develop `94f6ee1b` 반영 후 AgentDataScopeIntegrationTest·AgentRequestAuthorizationTest·BaseViewIntegrationTest·FeeRegimeRulesetMigrationIntegrationTest·ProductOfferingSalesPeriodMigrationIntegrationTest를 재실행해 모두 통과했다. V59~V64 마이그레이션 버전 중복은 없다.
- 1차 외부 리뷰 반영: 비교설명·PDF의 소유 기준 통일 및 재연결 거절, 환수·문의·TRANSACTION/CLAWBACK 도구의 실제 PostgreSQL 쿼리 양방향 시험, 비활성 시드의 로그인 거절을 추가했다. 인증·경로 회귀를 포함한 80건이 통과했다. AGENT 홈 정산월 재진입·잘못된 월 회귀를 포함한 프런트 관련 시험 13건도 통과했다.
- `FGC_EXPORT_OPENAPI=true`의 OpenApiFrontendIntegrationTest: 실제 서버 OpenAPI export 및 프런트 HTTP/JWT 연동 4건 통과. 생성 과정에서 기존 코드의 누락됐던 sort 계약도 함께 동기화됨.
- 프런트 `generate:api`, `check:api`, TypeScript/Vite build, ESLint, e2e 타입 검사 통과. 최종 OpenAPI는 nullable agentId까지 실제 서버에서 다시 내보냈다.
- 프런트 전체 테스트의 최초 병렬 실행은 기존 스케줄·지급 폼 3건이 5초 제한을 초과했다. assertion 실패는 없었으며 `npm run test -- --run --maxWorkers=2`로 재검증하여 527건 통과·4건 선택 항목 건너뜀을 확인했다. 테스트 시간 제한은 늘리지 않았다.
- Chromium UI fixture 시험 3건 통과(최종 메뉴 정리 후 재실행): 설계사 홈·메뉴·전체 기준정보 미호출, 내부 탭 미호출, 내부 화면 직접 URL 차단. 서버의 실제 JWT·객체 권한은 Java 통합 테스트로 별도 검증한다.
- 화면 증거: `docs/634-agent-contracts.png` (UI fixture 화면이며 실제 설계사 개인정보가 아님).

`graphify` 실행 파일은 현재 환경에서 찾지 못했으며 실행 완료로 기록하지 않는다. 구현·검증 기록은 출시 승인이나 FUN-085 후속 연동 전체 완료를 뜻하지 않는다.

## 문서 변경 요약

| 항목 | 변경 전 → 변경 후 | 이유·영향 |
|---|---|---|
| 시드 명세서 §5 | AGENT/agent01 미구현 → V64·A-FC-001 연결 명시 | FUN-085 재현 가능한 시드 및 배포 계정 보호 대상 갱신 |
| 화면정의서 §4-1 | AGENT 제안 메모 → 현재 제공 화면·차단 탭·후속 연동 구분 | CONT-W01/W02, TRAN-W01, AGNT-W01 진입 계약 |
| 인터페이스정의서 | /me 기존 필드 → nullable agentId·AGENT 플래그, 객체·역할 거절 구분 | AUTH, 계약·지급 목록·상세·CSV 및 후속 API |
| 테이블·버전 | 기존 스키마 유지, V64 데이터 추가 | app_role·app_user에만 시드 INSERT. DB 구조 변경 없음 |

문서 버전명은 유지하고 이 기록을 #634 변경 이력으로 사용한다. PR에는 RTM FUN-085·TER-009 구현모듈/테스트케이스 갱신 요청을 포함한다.
