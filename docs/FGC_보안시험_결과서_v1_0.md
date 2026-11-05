# FGC 보안시험 결과서 v1.0

| 항목 | 내용 |
|---|---|
| 요구사항 | FGC-TER-009 보안 테스트 (요구사항명세서 v3.0 `TER_테스트_요구사항`), 함께 증명: SER-002, SER-004, SER-005, SER-008, SER-010 |
| 추적 이슈 | #677 (출시 전 트래커 #755) |
| 상태 | **RC 전 사전 결과 (2026-11-04).** 동결 RC(11/6 이후)에서 다시 잡을 항목은 표마다 "RC"로 적었다 |
| 근거 | 인수조건은 요구사항명세서 v3.0 원문. 기술 판단은 SRC-028(OWASP Cheat Sheet Series, MDN). TER-009 출처 SRC-006은 근거대장에서 "누락"이라 원문 대조를 못 한다 |
| 공개 범위 | 공개 저장소 문서다. 재현 절차, 대상 주소, 토큰, 쿠키, 원본 스캔 보고서는 넣지 않는다 |

**인수조건 원문 (TER-009):** "1차는 인증·권한·입력검증·비밀정보, 2차 파일기능 포함 보안시험에서 High/Critical 취약점 0건 또는 승인된 예외만 존재한다."
세부내용 원문은 "OWASP 기반 점검과 의존성 취약점 스캔을 포함한다"이다. #677 본문의 "정적/동적 스캔" 인용은 v3.0 원문에 없고, 층별 도구 선택(아래 표)은 #677의 계획이다.

## 1. 발표 1장 요약

| 질문 | 답 |
|---|---|
| 무슨 도구로 | CodeQL(정적), Dependabot, npm audit, Trivy(의존성, 이미지), gitleaks, GitHub Secret scanning(비밀값), 역할 × API 행렬 통합 시험(인가), 공격 입력 통합 시험(입력), OWASP ZAP(동적) |
| 어디까지 | 코드 전체 이력, 쓰기 API 41개 전수 대조(역할 시험 38개, 토큰 API 3개는 기존 시험) × 역할 5개 + 미인증, 대표 공격 입력 139건. 실 서버 스캔과 TLS 실측은 RC에서 한다 |
| High/Critical 건수 | 탐지 15건 → 오탐 3, 이 PR로 조치 10, **예외 2(`.trivyignore` 승인 기재, Dependabot #26은 PM이 해제)**. ZAP High 0 |
| 조치 | 빌드 도구 classpath의 jackson, commons-lang3 올림. 개발 도구 zod 올림. API 이미지에서 쓰지 않는 pebble 제거. nginx 버전 노출 끄기. ZAP이 찾은 500 응답 3종을 400으로. 정적 화면 응답에 빠져 있던 보안 헤더(CSP 등)는 #782로 넣었고 신한 경로까지 확인 |
| 남은 일 | RC에서 ZAP, Trivy, TLS, 롤백 실측. 시연 서버의 계정 보호 상태 확인(3절 층 3). 신한 경로 로그인 제한의 앞단 가정 측정(6절) |

## 2. 대상

| 항목 | 값 |
|---|---|
| 코드 기준 | develop `a2371010` (이 결과를 잰 커밋. 그 뒤 병합분은 RC에서 다시 잡는다). 통합 시험은 이 커밋을 merge한 PR head의 CI로 돌렸다 |
| 이미지 기준 | 게시 이미지 `sha-69f77eff` (api `sha256:beaf2532…`, web `sha256:1cef1ee3…`). 이 PR의 조치 전 상태다. 그 뒤 develop의 #773(화면), #831(지급 보류, V79), #835(지급 재개, 상태 미리보기)는 이미지에 없고 의존성, `Containerfile`, nginx 변경은 없다 |
| RC | `r2.0.0-rc1` 미발행. 미확인 |
| 시험 환경 | Testcontainers PostgreSQL 17, Windows 11 로컬 / GitHub Actions |
| 시험 실행 | PR #858의 CI `build`(Gradle 전체 스위트), `frontend`, `ui`, `real-server`. 로컬: `test_zap_isolated_scan.py`, `test_shinhan_cd.py`, vitest 3개 파일 |
| 공유 개발계, 신한 서버 | **어떤 스캔도 돌리지 않았다.** 능동 스캔은 격리 로컬에서만 한다 |

## 3. 층별 결과 (#677 표 1~8)

| # | 층 | 도구, 버전 | 대상 | 범위 | 발견 | 조치, 판정 | 상태 |
|---|---|---|---|---|---|---|---|
| 1 | 정적 코드 분석 | CodeQL 2.27.4, `security-extended`, 워크플로 `.github/workflows/codeql.yml`(#782) | develop `a2371010`(2026-11-05 분석, RC에서 다시 확인) | java-kotlin 120규칙, javascript-typescript 103규칙, actions 23규칙 | High 3, Medium 1 (누적) | 열린 경고 0. 처리 내역은 4-1 | 충족 |
| 2 | 의존성, 이미지 | Dependabot, npm audit, Trivy 0.70.0 | 4-2 | 운영 jar, 빌드 classpath, npm, API/WEB 이미지 | Critical 2, High 10 | High 10 조치(jackson 5, zod 1, pebble 4). Critical 2는 예외(4-2, 7절) | 병합 뒤 재확인 |
| 3 | 비밀값 | GitHub Secret scanning, Push protection, gitleaks v8.30.1 | 전체 이력 (스캔 시점 develop `185dc854`) | 이력 커밋 1,138개(병합 제외), 로컬의 모든 참조. 그 뒤 develop과 이 PR 커밋 20개는 같은 도구로 추가 1회, 0건 | 자동 탐지 9, 전부 오탐(4-3) | 활성 비밀 자동 탐지 0. 시연 계정 보호는 서버 상태를 봐야 해 미확인 | 일부 미확인 |
| 4 | 인가 | `RoleApiAuthorizationMatrixIntegrationTest` | 앱 전체 매핑 | 쓰기 API 41개 + 역할을 가르는 읽기, 역할 5개 + 미인증 | 불일치 0 | 4-4 | 충족 |
| 5 | 입력 | `InputAttackIntegrationTest`, `PrivateVolumeFileStorageTest`, `CsvExportWriterTest`, `exception.test.tsx`, `noRawHtml.test.ts` | 대표 검색, 정렬, 저장 후 응답, 다운로드 | 4-5 | 저장 후 실행, SQL 실행, 경로 탈출 0 | 4-5 | 통과. 요구사항 해석은 4-5에서 확정 |
| 5-2 | 업로드 | `noHandlerAcceptsFileUpload` | 앱 전체 매핑 | 해당 기능 없음 | 0 | #637 not planned, PR #707 닫힘 (트래커 #755 4번 11/2 결정). 업로드가 돌아오면 이 시험이 깨진다 | 해당 없음 |
| 6 | AI(LLM) 주입 | #639 평가셋 | #639 | 프롬프트 주입, 다른 설계사 자료, 업무 쓰기 유도 | #639로 연결 | CT-G04, CT-G05는 #639가 판정. 서버 쪽 통제는 #833으로 병합: 사용자별 호출 한도(분당 12회), 범위 밖 질문 고정 안내, 지시문 노출 차단, 시간 제한 20초 | #639 |
| 7 | 동적 스캔 | OWASP ZAP, `infra/scripts/zap-isolated-scan.sh` | 격리 로컬 스택, 이미지 `sha-69f77eff` | baseline 1회(수동 관찰), API scan 1회(능동, 7분, 제한 25분 안에 끝남). RC 이미지 재실행과 개발계 baseline은 RC | High 0, Medium 0. Low 1, Info 1(baseline) / Low 4, Info 3(API) | 4-6 | 격리 사전 실행 완료, RC 미확인 |
| 8 | 응답 헤더, TLS | 게시 이미지를 띄워 응답 헤더 수집 | web 이미지 `sha-185dc854`, `sha-5be9285f`, `sha-69f77eff` | 일반 구성, 신한 구성, 브라우저 | CSP 없음 지적 해소 | 4-7. TLS 실측은 RC | CSP 충족, TLS 미확인 |

## 4. 세부

### 4-1. CodeQL 경고 처리 (2026-11-04 `gh api .../code-scanning/alerts`)

| # | 규칙 | 등급 | 상태 | 처리자, 일시 | 사유 | 이번 재확인 |
|---|---|---|---|---|---|---|
| 1 | `js/missing-origin-check` | Medium | fixed | 코드 수정, 2026-10-30 | 설계사 홈 메시지 수신의 출처 검사 | 코드에서 iframe, postMessage 경로가 사라짐 |
| 2 | `java/spring-disabled-csrf-protection` | High | dismissed(false positive) | yslee4601, 2026-11-01 | "CSRF 비활성: 설계 의도" | 같은 판단. 쿠키로 인증하지 않는 Bearer 체인이고 Refresh 쿠키 경로는 Origin과 X-FGC-Client로 막는다(인터페이스정의서 §2-1-1, 근거대장 "2차 JWT CSRF 판정", SRC-028). `tokenEndpointsRejectMissingClientHeaderOrForeignOrigin`이 지킨다 |
| 3 | `java/user-controlled-bypass` | High | dismissed(false positive) | yslee4601, 2026-11-01 | 감사 로그용 로그인 ID 조회 | 같은 판단. `AuthTokenService.logout`의 조건은 감사 기록 여부만 정하고 무효화는 그 앞에서 끝난다 |
| 4 | `java/user-controlled-bypass` | High | dismissed(false positive) | yslee4601, 2026-11-01 | 승인자 표시용 조회 | 같은 판단. `PolicyWriteService.result`는 응답에 승인자 ID를 채울 뿐 권한 판단이 아니다 |

### 4-2. 의존성, 이미지

**Dependabot High/Critical 6건 (열림, 2026-11-04)**

| # | 패키지 | 등급 | 어디서 왔나 (Gradle 해석 결과) | 처리 |
|---|---|---|---|---|
| 17, 19, 20 | jackson-databind | High | 운영 jar는 이미 2.21.7. 경고는 **Boot Gradle 플러그인의 빌드 전용 classpath**(2.21.4)에서 온다 | 이 PR에서 올림(`build.gradle` buildscript). 병합 뒤 dependency-submission이 새 그래프를 내면 닫히는지 확인 |
| 21, 22 | jackson-core | High | 같음 | 같음 |
| 26 | spring-webmvc 6.2.19 | Critical(GitHub CVSS 9.8, Trivy) / 낮음(Spring 공지 벡터 `AC:H/PR:L/UI:R`, 영향 `I:L`) | **운영 jar에 포함.** Boot 3.5.16(3.5 최신)이 쓰는 6.2.19가 Maven Central 마지막 판이고, Spring 공지(spring.io/security/cve-2026-47890) 기준 6.2.20은 Enterprise 전용, OSS 수정본은 7.0.9(Boot 4) | **승인 예외.** 발동 조건인 SSE 뷰 프래그먼트 전송이 없다: `SseEmitter`, `FragmentsRendering`, `text/event-stream`, `ServerSentEvent`, `ResponseBodyEmitter`, webflux 0건(`src/main`, `build.gradle`). `.trivyignore`에 같은 CVE 예외가 있다(기재: HyunJuneKang 2026-10-11, PR #460 리뷰 승인 yslee4601, 만료 2026-11-30). Dependabot 경고는 2026-11-04 해제했다(사유 `not_used`, 실행과 승인 yslee4601(PM), [#677 기록](https://github.com/feegachu/fgc/issues/677#issuecomment-6631123278)) |

같은 원인(빌드 classpath)으로 Medium 7건(#1, #2, #4, #15, #16, #18, #27)도 함께 닫힐 것으로 본다. 병합 전에는 `buildEnvironment`로 `classpath`의 jackson 2.21.7, commons-lang3 3.20.0까지만 확인했다. npm 쪽 #30 minimatch(Medium)는 eslint, openapi-typescript 아래 개발 도구이고 수정본이 메이저 변경이라 이번에 올리지 않았다.

**npm audit (`npm audit --package-lock-only`, 2026-11-04)**

| 범위 | Critical | High | Moderate |
|---|---|---|---|
| 운영 의존성 (`--omit=dev`) | 0 | 0 | 0 |
| 전체, 조치 전 | 0 | 1 (zod, eslint-plugin-react-hooks 아래 개발 도구) | 14 |
| 전체, 조치 후 (zod 4.6.7) | 0 | 0 | 14 |

**Trivy**

| 대상 | 기준 | 결과 |
|---|---|---|
| CI publish 관문, API/WEB 이미지 | develop `69f77eff` (`severity: CRITICAL`, `.trivyignore` 적용, Scan API, Scan WEB 성공) | CRITICAL 0. 예외 2건(CVE-2026-47884, CVE-2026-47890, 만료 2026-11-30) |
| HIGH 포함 로컬 2회 | 게시 이미지 `sha-5be9285f`, `sha-69f77eff`, Trivy 0.70.0, `--severity HIGH,CRITICAL`, `.trivyignore` 적용 | 두 번 같다. WEB(alpine 3.24.3) 0건. API: OS 패키지 0, 운영 jar 0, **HIGH 4건은 전부 `/usr/bin/pebble`**(베이스 이미지의 Go 프로세스 관리자, Go stdlib). 실행 명령이 `catalina.sh`이고 쓰는 곳이 없어 `Containerfile.api`에서 지웠다. 새 이미지 재스캔은 RC |
| RC 이미지 | `r2.0.0-rc1` | 미확인, RC 미발행 |

CI 관문은 CRITICAL만 막는다. SER-010의 "High는 조치 또는 위험수용 근거"는 RC에서 HIGH까지 캡처해 판정한다(6절 명령).

### 4-3. gitleaks 전체 이력 (`zricethezav/gitleaks` v8.30.1, `--redact`)

보고서는 저장소 밖에만 두었고 값은 어디에도 옮기지 않았다. 판정은 redact된 보고서와 문자열을 길이, 문자 부류로만 가린 주변 코드로 했다.

| 건수 | 규칙 | 위치 | 판정 |
|---|---|---|---|
| 2 | generic-api-key | `frontend/e2e-demo/settlement-completion.spec.ts` `sourceBusinessKey` | 오탐. 시험 업무키 |
| 2 | generic-api-key | `ContractControllerTest` `key` | 오탐. 시험용 멱등키(UUID 형식) |
| 2 | generic-api-key | `docs/05_인터페이스정의서_v3_0.md` `Idempotency-Key` 예시 | 오탐. 문서 예시 UUID |
| 1 | generic-api-key | `CapListPage.tsx` `sortKey` | 오탐. 정렬 키 이름 |
| 1 | generic-api-key | `AuthTokenApiIntegrationTest` | 오탐. 쿠키 속성 단언 문자열 |
| 1 | generic-api-key | `JournalCorrectionExceptionRepositoryIntegrationTest` `keyPrefix` | 오탐. 시험 키 접두어 |

전체 이력 스캔 뒤의 커밋(develop `dd1ba27b`까지와 이 PR, 20개)은 같은 도구로 한 번 더 돌려 0건이었다.

GitHub 설정(2026-11-04 `gh api repos/feegachu/fgc`): `secret_scanning` enabled, `secret_scanning_push_protection` enabled. Secret scanning 경고 0건(전체 상태).

**도구로 판정할 수 없는 항목:** 시연 계정 보호(시드 명세 §5, SRC-065)는 저장소가 아니라 서버 상태를 봐야 한다. 신한 서버는 배포 스크립트가 배포 전후로 계정 보호 상태를 검사해 맞지 않으면 멈춘다(`infra/scripts/deploy-shinhan.sh`, #836 병합). 다만 서버 접근이 없어 그 배포가 돌았는지와 실제 상태는 **미확인**이고, 그래서 SER-008은 충족으로 적지 않는다. RC 전에 Runbook(`docs/FGC_운영_장애대응_Runbook_v1_0.md`, #676) 절차로 확인한다.

### 4-4. 인가 행렬 (SER-002)

- 기대값은 코드 상수가 아니라 인터페이스정의서 v3.0 §4-2 역할 열을 옮겨 적었다. 상수를 넓히면 시험이 깨진다.
- 역할은 코드(`Roles.java`)와 문서(인터페이스정의서 §2-1, 화면정의서 §4-1) 모두 5개(SYSTEM_ADMIN, GA_ADMIN, SETTLEMENT, COMPLIANCE, AGENT)다. 미인증을 더해 6열로 시험한다.
- 실제 로그인 토큰으로 URL 규칙, `@PreAuthorize`, 서비스 안 역할 검사(정책 동작, 계산 후보, 가이드)를 함께 지난다.
- 인자 검증이 `@PreAuthorize`보다 먼저 돌아서 형식이 틀린 본문은 거부 역할에게도 400이 된다. 그래서 형식은 맞고 없는 ID를 가리키는 본문을 쓴다.
- `everyWriteMappingHasAMatrixRow`가 실행 중인 매핑을 전수로 뽑아 행렬과 양방향으로 대조한다. 새 쓰기 API는 행을 더해야 CI를 지난다.

| API 묶음 (인터페이스정의서 §4-2) | SYSTEM_ADMIN | GA_ADMIN | SETTLEMENT | COMPLIANCE | AGENT | 미인증 |
|---|---|---|---|---|---|---|
| 계약 IF-18, 19, 19A, 19B, 33 / 계약체결비용 FUN-062 요청(IF 번호 미발급) / 지급 IF-22, 23, 23D, 24, 25 / 스케줄 IF-29, 29A / 대사 IF-38, 42 / 검증 실행 IF-45, 48, 82 | 허용 | 403 | 허용 | 403 | 403 | 401 |
| 검증 확정 IF-51 | 허용 | 허용 | 403 | 403 | 403 | 401 |
| 원장 정정 IF-36, 36A / 예외 처리 IF-44, 44A / 환수 IF-85, 73 / 계약 상태 미리보기 IF-64 | 허용 | 허용 | 허용 | 403 | 403 | 401 |
| 정책 작성 IF-79, 80, 81, IF-75 SUBMIT | 허용 | 허용 | 허용 | 403 | 403 | 401 |
| 정책 승인 IF-75 APPROVE, REJECT, ACTIVATE | 허용 | 허용 | 403 | 403 | 403 | 401 |
| 상품 비교 IF-77, 78 / 계약 확인 기록 IF-74A | 403 | 403 | 403 | 403 | 허용 | 401 |
| 피코 IF-60, IF-60A 잔액, IF-60B/60C 조회 업무 | 허용 | 허용 | 허용 | 허용 | 403 | 401 |
| 피코 처리 IF-60A 후보, IF-60B/60C 처리 업무 | 허용 | 403 | 허용 | 403 | 403 | 401 |
| 읽기: 감사로그 | 허용 | 403 | 403 | 허용 | 403 | 401 |
| 읽기: 생성 문서 IF-67A, 환수 IF-83, 86 | 허용 | 허용 | 허용 | 허용 | 403 | 401 |
| 읽기: 본인 명세 IF-87 본인 경로 | 403 | 403 | 403 | 403 | 허용 | 401 |

"허용"은 역할 검사를 지나 업무 로직까지 갔다는 뜻이다(없는 ID라 대개 4xx, IF-74A는 본인 범위 거절 `FGC-AUTH-005`도 포함). 403은 전부 `FGC-AUTH-003` 봉투로 단언한다. 표와 코드의 **불일치 0건**, 그래서 권한 코드는 고치지 않았다.

- 표기 규칙 예외: IF-74A, 77, 78과 IF-87 본인 경로는 역할 열에 SYSTEM_ADMIN을 생략한 것이 아니라 세션의 설계사 ID로만 동작한다. SYSTEM_ADMIN에게 403인 현재 코드를 맞다고 보고 넓히지 않았다.
- 토큰 API(`/api/v1/auth/login`, `refresh`, `logout`) 3개는 역할이 아니라 Origin과 X-FGC-Client로 막으며 `AuthTokenApiIntegrationTest`가 지킨다.
- 그 밖의 시험: 모든 읽기 매핑의 미인증 401(`anonymousIsRejectedOnEveryReadMapping`), 내부 읽기 17곳의 AGENT 403(`internalReadsStayClosedForAgent`), 쓰기 전수 대조(`everyWriteMappingHasAMatrixRow`).

AGENT 본인 데이터 범위는 #621의 `AgentDataScopeIntegrationTest`가 맡고, 여기서는 역할 대 경로 차단만 본다. DB, 스토리지 서비스 계정 분리(SER-002 세부내용)는 이번 시험 범위 밖이고 미확인이다.

### 4-5. 입력 (SER-004)

인수조건 원문: "대표 SQLi/XSS/경로조작 입력이 저장·실행되지 않는다."

| 공격 | 시험 | 결과 |
|---|---|---|
| 검색값 SQLi 4종 × 검색 11곳 | `sqlInSearchValueMatchesNothingAndChangesNothing` | 전부 200 + 0건, 비밀번호 해시 미노출, 계약 건수 변화 없음 |
| 정렬 파라미터 5종 × 목록 16곳 | `sortOutsideTheAllowListIsRejected`, 기존 `ListSortTest`, `SqlSortTest` | 전부 400 `FGC-COMMON-002`(필드 sort), 입력 문자 미반사 |
| 숫자, 날짜 칸에 공격 문자 | `typedParametersRejectAttackTextWithoutEchoingIt` | 전부 400 JSON, 입력 문자 미반사 |
| 저장 후 응답 XSS | `storedScriptComesBackAsInertJsonText` | `application/json` + `nosniff`, 값은 글자 그대로(변조 없음). 서버가 HTML을 만드는 경로 없음 |
| 화면 출력 XSS | `exception.test.tsx` "저장된 설명과 처리 사유의 스크립트", `noRawHtml.test.ts` | 글자로 표시, `img`, `script` 요소 생성 안 됨. HTML 해석 경로 0건 |
| 스캔이 찾은 입력 오류 | `typedParametersRejectAttackTextWithoutEchoingIt`의 잘못된 `month`, NUL 문자, `csvDownloadErrorStillReturnsTheJsonEnvelope` | 500 대신 400 표준 봉투 (`CapCheckController`, `ConstraintErrorCodeResolver`, `GlobalExceptionHandler`). 저장, 실행은 조치 전에도 없었다 |
| 다운로드 경로 조작 | `documentDownloadRejectsPathManipulation` | 6종 전부 400, 파일 응답과 `Content-Disposition` 없음 |
| 다운로드 파일명 헤더 분리 | `downloadFilenameEncodesHostileContractNumber` | CR, LF, 따옴표가 헤더로 새지 않고 `filename*`에서 `..%2F` 등으로 인코딩 |
| 저장소 키 경로 조작 | 기존 `PrivateVolumeFileStorageTest.rejectsArbitraryStorageKeys` 외 | 키 형식(`[a-f0-9]{32}`) 밖은 거부, 링크 거부 |
| CSV 수식 | 기존 `CsvExportWriterTest` | 수식 시작 문자 무력화 |
| CSRF | 기존 `tokenEndpointsRejectMissingClientHeaderOrForeignOrigin`, `originCheckFollowsHttpsBehindTlsTerminatingProxy` | Refresh 쿠키 경로는 Origin, X-FGC-Client가 맞지 않으면 거부. 나머지 API는 쿠키로 인증하지 않아 해당 없음(CodeQL #2 참고) |

**요구사항 해석:** 자유 입력 칸의 `<script>` 문자열은 입력에서 막지 않고 글자 그대로 보관한다. 세부내용("출력 이스케이프와 허용목록")과 OWASP XSS Prevention Cheat Sheet(SRC-028, 출력 인코딩이 1차 방어)에 맞춘 판단이다. "저장·실행되지 않는다"를 "저장되어 실행되는 일이 없다"로 읽는다. **확정 (2026-11-04, PM 결정, [#677 기록](https://github.com/feegachu/fgc/issues/677#issuecomment-6631123278)).** 이유: 처리규칙이 출력 이스케이프를 수단으로 명시하고, 위 시험에서 저장 후 실행 경로와 HTML 해석 경로가 0건이며, 입력에서 막으면 `A<B` 같은 정상 입력까지 거절한다. 업무 규칙이 아니라 구현 기술 판단이다. 다시 열 조건: SRC-006 원문 확보(지금은 근거대장에 누락) 또는 저장 자체를 막으라는 요구.

### 4-6. 동적 스캔

| 항목 | baseline | API scan |
|---|---|---|
| 도구 | OWASP ZAP 2.17.0 (`ghcr.io/zaproxy/zaproxy:stable`, `sha256:9624669d…`), `zap-baseline.py` | 같은 이미지, `zap-api-scan.py` |
| 대상 | 격리 로컬 스택: 자체 PostgreSQL + 게시 이미지 `sha-69f77eff`(api, web, 이 PR의 조치 전 상태) + 신한 guard. 외부 LLM 키 없음 | 같은 스택 |
| 범위 | 수동 관찰, 스파이더 2분. 스파이더는 JS를 실행하지 않아 SPA 시작 경로와 정적 자산까지만 본다 | OpenAPI 경로 129개(요청 경로 326개), 로그인 토큰 사용, 능동 스캔. 제한 25분 안에 7분 만에 끝났다 |
| 결과 (2026-11-04) | FAIL 0, WARN 2, PASS 60 / High 0, Medium 0, Low 1, Informational 1 | FAIL 0, WARN 4, PASS 112 / High 0, Medium 0, Low 4, Informational 3 |

| 경고 | 등급 | 판단 |
|---|---|---|
| 10036 Server 헤더 버전 노출 | Low | 조치: `nginx/default.conf.template` `server_tokens off`. 앞선 이미지에 새 템플릿을 붙여 `Server: nginx`로 줄어든 것을 확인했다. 새 이미지로 RC에서 다시 확인 |
| 100000 서버 오류 응답 | Low | 조치: 잘못된 `month`, 없는 스케줄의 CSV(이번 실행), NUL 문자(앞선 `sha-5be9285f` 실행)를 이 PR에서 400 표준 봉투로 고쳤다(4-5). 스캔한 이미지에는 없는 수정이라 통합 시험으로 확인했고 새 이미지 재실행은 RC. 저장, 노출은 조치 전에도 없었다 |
| 90022 Application Error Disclosure 1곳 | Low | 위 CSV 500과 같은 응답, 조치. 본문이 비어 내부 정보 노출도 없었다(재현 확인) |
| 100001 Unexpected Content-Type | Low | 오탐. CSV 내려받기와 SPA 경로의 정상 응답 |
| 10109 Modern Web Application | Informational | 결함 아님. AJAX 스파이더를 권하는 안내 |
| 4xx 응답, 인증 요청 식별, URL의 `userId` 필터 | Informational | 결함 아님 |

| 남은 실행 | 상태 |
|---|---|
| API scan, RC 이미지 | 미확인, 사유: RC 미발행 |
| baseline, 개발계 RC | 미확인, 사유: RC 미발행. #677 표대로 수동 관찰만, 공유 서버라 사용자 승인 뒤 실행 |

스크립트 `infra/scripts/zap-isolated-scan.sh`는 대상이 `http://127.0.0.1:<포트>` 또는 `http://localhost:<포트>`가 아니면 docker를 부르기 전에 거부한다(`test_zap_isolated_scan.py`, 사용자 정보와 접두어 우회 포함). API scan은 문서의 `servers`가 무엇이든 대상에 고정하고(`-O`), 로그인에 실패하면 빈 토큰으로 스캔하지 않고 멈춘다. 실제 LLM을 부르지 않게 키 변수를 지우고 시작하며, 스캔 중 격리 DB의 `ai_call_log`는 0건이었다. 원본 보고서는 `build/zap/`(gitignore)에만 둔다.

### 4-7. CSP, 보안 헤더

적용 위치: `nginx/security-headers.conf`, `nginx/default.conf.template`(#782). web 이미지에 들어가며 `infra/scripts/deploy-host.sh`, `infra/shinhan/nginx-guard.conf`에는 HSTS 값 외에 별도 보안 헤더 설정이 없다(`nginx-guard.conf`의 로그인 시도 제한은 #870). 그래서 두 파일을 grep 해서는 나오지 않는다.

게시 이미지 `ghcr.io/feegachu/fgc/web:sha-185dc854`(`sha256:58978f38…`)를 격리 포트로 띄워 응답 헤더를 받았다. 신한 구성은 `deploy-shinhan.sh`와 같은 방식으로 이미지에서 템플릿을 꺼내 `adapt_nginx`를 거치고 `nginx-guard.conf`를 붙였다. `sha-69f77eff` 격리 스택의 `/login`에서도 같은 헤더를 받았다.

| 경로 | 일반 구성 | 신한 구성 |
|---|---|---|
| `/`, `/login`, `/assets/*.js`, `/favicon.ico`, `/app/login`(301), `/api/*`(502도 포함), `/actuator`(신한 404) | CSP, nosniff, X-Frame-Options SAMEORIGIN, Referrer-Policy 있음 | 같음 |
| HSTS | 비움. 운영 TLS 설정(`00-tls.conf`)이 값을 채운다 | **보내지 않음.** TLS를 앞단(edge)이 끝내므로 RC에서 edge 응답을 본다 |

정책: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-src 'self'; frame-ancestors 'self'; object-src 'none'; base-uri 'self'; form-action 'self'`

이미지의 산출물에 인라인 스크립트, 외부 연결, 외부 글꼴이 없음을 grep으로 확인했다. `<style>` 생성은 React DOM 내부 경로 한 곳뿐이고 앱 코드는 쓰지 않는다(`style` 속성은 React가 CSSOM으로 넣으므로 막히지 않는다). PDF, CSV 내려받기는 `blob:` 앵커 다운로드라 CSP 대상이 아니다. 게시 이미지 `sha-5be9285f`를 격리 스택으로 띄워 Chromium으로 내부 화면 16개, 설계사 화면 3개, 도움말, 상품 추천 AI 대화창, CSV 내려받기를 지났고 CSP 위반 콘솔 메시지 0건, 글꼴 2종 로드를 확인했다(2026-11-04). 환수 PDF 내려받기는 발행 자료가 없어 열지 못했고 CSV와 같은 방식이다. 그 뒤의 화면 변경(#834, #856, #773)은 소스 차이에 `<style>` 삽입, 외부 주소, 글꼴 추가가 없음을 grep으로만 봤고, 브라우저 확인은 RC 이미지로 다시 한다(6절).

API 응답에는 Spring Security 기본 헤더(`X-Frame-Options: DENY`, nosniff)와 nginx 헤더가 겹쳐 나간다. 브라우저는 더 엄격한 쪽으로 처리하고 API 응답은 프레임에 넣지 않으므로 결함으로 세지 않는다.

## 5. 비기능 증적 (#677 6개 요구사항, 시나리오 6행)

### 5-1. 시나리오

| # | 시나리오 | 기대 | 결과 | 증거 |
|---|---|---|---|---|
| 1 | access 만료 → refresh | 계속 | 통과 | 서버: `AuthTokenApiIntegrationTest.expiredForgedOrForeignKeyTokensAreRejectedWithEnvelope`, `loginMeRefreshAndCallWithNewAccessToken`. 화면: `client.test.ts` "동시 요청 3개는 refresh 한 번을 공유하고 각각 재시도한다" |
| 2 | HTTPS 실도메인 | 자물쇠 | **미확인** | 사유: 실도메인 주소가 Jenkins 비밀값이고 RC 전이다. 6절 절차 |
| 3 | FINALIZED 수정 시도 | 409 | 통과 | `FinalizedRunWriteConflictIntegrationTest` (실행 재기동, 재확정, 하위 대사 생성 모두 409 + 상태 유지), DB 트리거 `FinalizedValidationRunImmutabilityIntegrationTest` |
| 4 | 위조 토큰 | 401 | 통과 | `expiredForgedOrForeignKeyTokensAreRejectedWithEnvelope`, `complianceTokenCannotWriteEvenWithEscalatedRolesClaim` |
| 5 | 권한 없음 | 403 | 통과 | `RoleApiAuthorizationMatrixIntegrationTest` |
| 6 | 롤백 배포 | 이전 digest | **미확인** | 사유: Stage 실 서버 시연은 RC에서 한다. 정적 확인: `Jenkinsfile.fgc` `ROLLBACK` 파라미터, `infra/scripts/tests/test_shinhan_cd.py` 24건 통과(로컬 `PYTHONUTF8=1`) |

### 5-2. 요구사항별

| 요구사항 | 인수조건 (v3.0 원문) | 결과 | 증거, 재확인 |
|---|---|---|---|
| SIR-001 | 전체 공개 API가 표준 응답구조와 상태코드를 준수한다 | 통과 | `/api/v1/` 전수 `everyApplicationMappingIsVersionedUnderApiV1`, 403 봉투 전 행 단언, `ApiResponseTest`, `PageResponseTest`, `GlobalExceptionHandlerTest`, `OpenApiFailureResponseTest`, `npm run check:api` 통과. ZAP이 찾은 본문 없는 CSV 500은 오류 응답의 형식을 JSON으로 고정해 고쳤다(`csvDownloadErrorStillReturnsTheJsonEnvelope`) |
| SER-007 | HTTP 접근이 HTTPS로 전환되고 유효한 인증서로 접속된다 | **미확인** | 사유: 실 도메인 시험은 RC. 정적: AWS는 `deploy-host.sh` `00-tls.conf`(TLS 1.2, 1.3, 301), 신한은 앞단이 TLS를 끝내고 nginx가 `X-Forwarded-Proto: https`를 넘긴다(`adapt_nginx`, 시험 `test_https_headers_and_spa_paths_preserved`) |
| SAR-003 | 마감 건 수정 시도가 차단되고 정정거래만 생성 가능하다 | 통과 (실행 잠금, 정정 경로) | 시나리오 3, V7, V30, V31 트리거. 정정: `correctionForSameMonthCreatesNextRunNumberWithoutChangingFinalizedRun`, `reverseSwapsDebitAndCreditAndKeepsOriginalImmutable`, `finalizedValidationRunRejectsCorrectionBeforeWritingGroupOrReversal`. 2차 "마감, 승인 절차"는 RTM 기록대로 별도 기능이 없다 |
| SAR-004 | Stage에서 실패 배포 후 이전 버전 복구와 핵심 조회 정상화를 시연한다 | **미확인** | 사유: 실 서버 시연은 RC. 재확인 절차는 Runbook(`docs/FGC_운영_장애대응_Runbook_v1_0.md`, #676). 정적: 배포 전 백업과 실패 시 비기록 `test_backup_failure_aborts_before_config_or_container_changes`, `test_api_failure_does_not_upgrade_web_or_mark_success` |
| ECR-005 | 태그 버전을 Stage에 배포하고 이전 버전 롤백 절차를 시연한다 | **미확인** | 사유: 위와 같음. 정적: GHCR digest 고정 승격 `test_tags_and_wrong_registry_cannot_replace_verified_digest` |
| ECR-002 | React 앱과 Spring API가 독립 빌드, 테스트되고 통합환경에서 동작한다. 1차 Job 2개는 JPA 전환 뒤에도 재실행, 재시작 결과가 같다. 운영, 테스트 코드와 의존성에 MyBatis 참조가 남지 않는다 | 통과 (세부내용 Job 수는 아래 문서 차이) | MyBatis: import, `@MapperScan`, `SqlSession*` 0건, Mapper XML 0건, 의존성 `verifyPersistenceDependencies` 통과. 남은 글자 13개 파일은 이력 주석, 비교 기준 README, `verifyPersistenceDependencies` 검사 코드다. Thymeleaf 0건. 독립 빌드: CI `build`, `frontend` 별도 job. 재시작: `MonthlyJournalRestartIntegrationTest`, `JobInstanceIdentityPolicyIntegrationTest` |

**문서 차이 (ECR-002):** 요구사항 세부내용은 "Job 5개로 확장"인데 인터페이스정의서 `docs/05_인터페이스정의서_v3_0.md:1203`은 "2차에 Job을 무조건 5개로 늘린다는 계획은 사용하지 않는다"이고 코드는 `MonthlyValidationJob`, `DailyChangedContractJob` 2개다. 인수조건 원문에는 5개가 없어 판정에는 영향이 없다. xlsx 세부내용 정리는 RTM 동기화와 함께 요청한다.

## 6. RC 때 다시 할 일

명령의 대상 주소와 자격 증명은 저장소 밖에서 준다.

| 항목 | 방법 |
|---|---|
| CodeQL | RC 커밋의 Security → Code scanning 열린 경고 수, 분석 커밋 기록 |
| Trivy | 이미지마다 한 번씩: `trivy image --severity HIGH,CRITICAL --ignorefile .trivyignore ghcr.io/feegachu/fgc/api:sha-<RC SHA>`, 같은 명령을 `web`으로. 결과 건수 기록 |
| npm audit, Dependabot | `npm audit --package-lock-only`, `gh api repos/feegachu/fgc/dependabot/alerts?state=open` 건수. 병합 뒤 Maven 경고가 #26만 남는지 확인 |
| ZAP baseline | 격리: `API_IMAGE=... WEB_IMAGE=... bash infra/scripts/zap-isolated-scan.sh up` 뒤 `baseline`. 스택은 아래 API scan까지 유지. 개발계 baseline은 #677 표대로 수동 관찰만, 승인 뒤 |
| ZAP API scan | 격리에서만, 같은 스택에서: `ZAP_LOGIN_PASSWORD=<시드 계정 비밀번호> bash infra/scripts/zap-isolated-scan.sh api` 뒤 `down`. 기본 능동 스캔 25분 제한, 문서의 주소와 무관하게 대상 고정. 토큰을 갱신하지 않으므로(수명 30분) 전체 시간과 만료 401 여부를 함께 적고, 만료로 인증 요청이 빠졌으면 그 실행을 합격 근거로 쓰지 않는다 |
| TLS | `curl -I http://<도메인>/` 301, `curl -vI https://<도메인>/` 인증서 유효, `curl -I https://<도메인>/`에서 CSP, nosniff, HSTS 확인. 신한은 edge가 HSTS를 주는지 본다 |
| 롤백 | Runbook 절차로 이전 digest 재배포 후 핵심 조회 확인, 배포 전 백업 파일 기록 |
| CSP | RC 이미지를 격리 스택으로 띄워 화면을 지나며 콘솔의 CSP 위반 0건 확인 |
| 시연 계정 | Runbook 절차로 서버의 계정 보호 상태 확인 |
| 로그인 시도 제한 (신한) | PR #870 6절의 측정. 외부에서 요청마다 다른 `X-Forwarded-For`로 15회 로그인해 429 확인, 앞단 없이 VM 80번에 접속되지 않는지 확인. 어긋나면 앞단 주소로 신뢰 경계를 넣거나 7절에 위험 수용 사유와 승인자를 적는다 |

## 7. 예외 승인

| 대상 | 내용 | 승인자 | 만료 |
|---|---|---|---|
| CVE-2026-47890 (Dependabot #26, `.trivyignore`) | SSE 뷰 프래그먼트 미사용, OSS 6.2 수정본 없음 | `.trivyignore`: HyunJuneKang 기재, PR #460 리뷰 승인 yslee4601. Dependabot 해제: yslee4601(PM), 2026-11-04(사유 `not_used`, [#677 기록](https://github.com/feegachu/fgc/issues/677#issuecomment-6631123278)) | 2026-11-30 |
| CVE-2026-47884 (`.trivyignore`) | `XsltView`, xslt 미사용 (`src/main` 0건 재확인) | `.trivyignore`: HyunJuneKang 기재, PR #458 리뷰 승인 yslee4601 | 2026-11-30 |
