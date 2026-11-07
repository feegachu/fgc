# FGC API Postman 사용 가이드 v1.0

| 항목 | 내용 |
|---|---|
| 요구사항 | FGC-PSR-003 Swagger, Postman 테스트 자산. 인수조건: Postman Collection의 핵심 요청이 지정 환경에서 순서대로 성공한다 |
| 기준 코드 | develop `ded47b31` |
| 파일 | `docs/FGC_API.postman_collection.json`, `docs/FGC_API_local.postman_environment.json`, `docs/FGC_API_dev.postman_environment.json` |
| 검증 | 2026-11-07, 로컬 서버(일회용 DB, 시드 적용)에서 16건 순서대로 통과(9장) |

## 1. 무엇을 확인하나

프런트, QA, 발표자가 화면 없이 API만으로 정산 업무의 뼈대를 확인한다. 로그인부터 지급 확정, 월 통합검증과 예외 조회, 로그아웃까지 한 번에 돈다. 전체 API 목록과 응답 형식은 Swagger UI(`/swagger-ui.html`, 로그인 토큰 필요)에서 본다.

## 2. 준비

1. Postman에서 `Import`로 컬렉션과 환경 파일 두 개를 불러온다.
2. 오른쪽 위 환경 선택에서 `FGC 로컬` 또는 `FGC 개발계 (터널)`을 고른다.
3. 환경의 `password` 칸에 그 환경의 `settle01` 비밀번호를 넣는다. 파일에는 비워 두었고, 넣은 값은 내 PC의 Postman에만 남긴다. **비밀번호를 넣은 환경 파일을 저장소에 올리지 않는다.**
   - 로컬 시드 계정의 비밀번호는 실서버 e2e 설정(`frontend/e2e-demo/fixtures.ts`)에 있는 공개 시연값이다.
   - 개발계 비밀번호는 팀이 따로 관리한다.

| 환경 | `baseUrl` | 언제 |
|---|---|---|
| FGC 로컬 | `http://localhost:8081` | `./gradlew bootRun` 기본 포트 |
| FGC 개발계 (터널) | `http://localhost:18088` | `ssh -N -L 18088:127.0.0.1:8088 <개발 서버>`로 터널을 연 뒤 |

**발표 서버에는 이 컬렉션을 돌리지 않는다.** 7, 9번이 지급을 만들고 확정한다. 확정은 되돌릴 수 없고, 시연 데이터의 숫자가 바뀐다.

## 3. 환경 변수

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `loginId` | `settle01` | 정산담당자 계정 |
| `password` | (비움) | 2장 참고 |
| `contractNo` | `FGC-FGL01-202607-0001` | 지급을 붙일 시드 계약 |
| `month` | `2026-07` | 지급의 정산월(`month-01`로 보낸다) |
| `asOf` | `2026-07-01` | 수수료 항목 기준일 |
| `payDate` | `2026-07-31` | 지급예정일과 귀속일 |

`accessToken`, `contractId`, `agentId`, `commissionItemId`, `transactionId`는 요청이 응답에서 꺼내 컬렉션 변수에 넣는다. 직접 넣지 않는다.

## 4. 실행 순서

Collection Runner에서 폴더 순서 그대로(1, 2, 3) 실행한다. 요청마다 상태 코드와 핵심 값을 시험(Tests)으로 확인한다.

| 순서 | 요청 | 기대 결과 |
|---|---|---|
| 1 | 로그인 `POST /api/v1/auth/login` | 200, `accessToken` 저장 |
| 2 | 내 정보 `GET /api/v1/auth/me` | 200, 역할 코드 |
| 3 | 계약 찾기 `GET /api/v1/contracts?contractNo=...` | 200, `contractId` 저장 |
| 4 | 계약 상세 `GET /api/v1/contracts/{contractId}` | 200, `agentId` 저장 |
| 5 | 수수료 항목 `GET /api/v1/base/commission-items?asOf=...` | 200, 기본수수료 `commissionItemId` 저장 |
| 6 | 예상 스케줄 `GET /api/v1/contracts/{contractId}/schedules?paymentStage=GA_TO_FC` | 200 |
| 7 | 지급 초안 등록 `POST /api/v1/transactions` | 201, 10원짜리 초안, `transactionId` 저장 |
| 8 | 사전검증 `POST /api/v1/transactions/{id}/precheck` | 200, `confirmable: true` |
| 9 | 확정 `POST /api/v1/transactions/{id}/confirm` | 200, `status: CONFIRMED` |
| 10 | 월 통합검증 목록 `GET /api/v1/validation-runs` | 200 |
| 11 | 예외 건수 `GET /api/v1/exceptions/counts` | 200 |
| 오류 1 | 토큰 없이 계약 조회 | 401 |
| 오류 2 | 없는 계정으로 로그인 | 401, `FGC-AUTH-001` |
| 오류 3 | 필수값 없이 지급 등록 | 400, `FGC-COMMON-002` |
| 오류 4 | 9번에서 확정한 지급을 다시 확정 | 409, `FGC-TRAN-005` |
| 마무리 | 로그아웃 `POST /api/v1/auth/logout` | 200, 토큰 지움 |

## 5. 인증

- 로그인, 로그아웃은 쿠키를 함께 쓰므로 서버가 `Origin`(요청한 주소와 같아야 함)과 `X-FGC-Client: web` 헤더를 확인한다. 컬렉션에 이미 넣어 두었다. 빠지면 403이다.
- 나머지 요청은 컬렉션 공통 인증(Bearer `{{accessToken}}`)을 쓴다. Access 토큰은 30분 동안 쓸 수 있다.

## 6. 같은 요청을 다시 보낼 때

- 9번 확정은 `Idempotency-Key` 헤더를 `{{$guid}}`로 매번 새로 만든다. 응답이 끊겨 같은 요청을 다시 보낼 때는 같은 키를 써야 한 번만 처리된다.
- 7번 지급 관리번호(`sourceBusinessKey`)는 `POSTMAN-{{$timestamp}}`라 실행할 때마다 다르다. 같은 관리번호로 다시 등록하면 409다.

## 7. 오류 응답 읽기

모든 응답은 `{ data, error, requestId }` 형태다. 오류면 `error.code`와 `error.message`를 보고, 서버 로그는 `requestId`로 찾는다.

## 8. 자주 나는 문제

| 증상 | 원인과 조치 |
|---|---|
| 1번이 403 | `Origin`이 `baseUrl`과 다르다. 환경의 `baseUrl` 끝에 `/`를 붙이지 않는다 |
| 1번이 401 | 비밀번호가 다르거나 비어 있다 |
| 3번에서 계약이 없다 | 시드가 없는 DB다. `contractNo`를 그 환경에 있는 계약번호로 바꾼다 |
| 9번이 422 | 1,200% 한도 등 확정 조건에 걸렸다. 응답 메시지대로 금액을 줄이거나 다른 계약을 쓴다 |

## 9. 검증 기록

- 2026-11-07, develop `ded47b31`로 빌드한 서버를 일회용 DB(시드 적용)에 띄워, 컬렉션 파일을 그대로 읽어 같은 순서로 보내는 스크립트로 16건이 모두 통과했다(핵심 흐름 11, 오류 예시 4, 마무리 1).
- Postman 앱과 newman으로는 이번에 돌리지 않았다. 다음 확인 때 Collection Runner 결과를 이 절에 덧붙인다.
