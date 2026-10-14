# #412 ARB-W01 React 전환 검증

2026-10-12, `develop 1eb89141` 기준. 임시 Testcontainers PostgreSQL과 실제 JWT/API로 검증했다.
운영 DB·실제 계약·토큰·쿠키를 캡처나 evidence에 포함하지 않는다.

| 화면 | 기존 Thymeleaf | React |
|---|---|---|
| 차익거래 목록·상세·타임라인 | [전](legacy-arbitrage.png) | [후](react-arbitrage.png) |
| 수동 재검증 모달 | [전](legacy-recheck.png) | [후](react-recheck.png) |
| 모바일 조회 | — | [후](react-mobile.png) |

[실제 API 상태·저장 결과·전후 측정값](evidence.json).
1440×1000에서 필터·요약·목록·상세·표 너비·타임라인 등 12개 영역의 상대 위치·너비·높이 비교를 통과했다(최대 차이 0.5px, 허용 2px).
표 머리글도 일치한다. 전체 이미지의 픽셀 동일성을 주장하지 않으며 #403 공통 셸은 비교 범위 밖이다.
390×844에서 표 가로 스크롤을 화면 영역 안에 두는 것도 검증한다.

## 동작과 의도한 차이

- 기존 즉시 조회 필터, 계약번호 Enter 조회, 초기화, 요약 카드 필터, 전체 페이지 번호, 키보드 행 선택을 유지한다.
- URL `month/status/stage/insurerId/contractNo/page`를 복원한다. `stage=`는 전체 지급단계이며 기본 GA_TO_FC와 구분한다.
- 타임라인은 size=100인 목록 API의 모든 페이지를 조회하고 정확한 계약 ID·지급단계만 남긴다.
  기존 첫 100건 제한과 부분 일치 계약번호의 혼입을 방지한다. 캐시도 계약 ID별로 분리한다.
- 지급예정 포함 판정과 최신 FUN-063의 차이는 #481에서 추적한다. 화면은 반환된 금액·사유·상태를 표시하며 판정·초과액을 재계산하지 않는다.
  기존 상세의 산식 대신 같은 위치에 금액 항목과 **초과액(서버 저장값)**을 표시해 잘못된 등식·규제 산식을 제시하지 않는다.
  후보는 검토대상이며 위반 확정으로 표시하지 않는다. #474·475·477·480·481의 업무 로직 수정은 포함하지 않는다.
- 기존 비처리 역할의 숨겨진 재점검 버튼을 비활성·안내로 바꾼다(#412 인수조건).
  API가 GA_TO_FC로만 실행하므로 INSURER_TO_GA 행의 재점검도 비활성·안내한다.
- 공용 Modal의 닫기 버튼·포커스 잠금/복귀와 필드별 오류 표시를 사용한다. 처리 중 닫기·중복 제출을 막고 오류 시 입력을 보존한다.
  Escape 옵션 변경으로 포커스 효과를 재시작하지 않으며, 처리 중 비활성화된 요소의 포커스를 모달 내부에 유지한다.
- 금액은 공용 format을 쓰며 음수는 괄호·빨강, 양수 초과액은 기존 빨강 강조를 유지한다.
- 공용 Field/FilterBar/Pagination에 선택적 표시 옵션, DataTable에 행 라벨·키보드 선택을 추가했다. 다른 화면의 기존 옵션은 유지한다.

## 검증 결과

- 프런트 전체 220개 통과 / 실제 서버 전용 4개 제외. #412 신규 16개 시나리오와 공용 모달 Escape 옵션 변경 회귀 테스트 포함.
- 브라우저 전체 25개 통과. #412 역할 4종의 조회·URL·재점검·권한·모바일 포함.
  재점검 POST를 지연시켜 처리 중 포커스·Escape 차단과 완료 후 포커스 복귀도 확인한다.
- 백엔드 전체 1,744개 통과 / 선택 실행 11개 제외(총 1,755개). 업무 서버 구현과 API/스키마는 변경하지 않았다.
- 실제 API·전후 비교 선택 테스트 1개 통과. pageerror·React 콘솔 오류 0건.
- 기존 대시보드의 월 변경 테스트 실패를 수정 전 develop 임시 사본에서도 재현했다.
  내부 store만 바꾸던 테스트를 실제 월 선택 UI·URL·API 재조회 검증으로 수정했다. 대시보드 운영 코드 변경은 없다.

실제 DB 시드는 차익거래 결과 행이 없어, 검증 준비 시 기존 재점검 API로 같은 계약의 두 기준일 결과를 저장한다.
두 결과는 REVIEW_REQUIRED이며 자료부족 상태 표시·재점검 저장·재조회 경계를 검증한다.
이는 규제 판정식의 적정성을 인증하는 검증이 아니다. CANDIDATE/CLEAR·금액·권한과 오류 경계는 프런트 테스트의 제어된 응답으로 별도 검증한다.

## 재현

Node 22·Java 21, Docker/Testcontainers·Chrome 환경에서 Vite를 실행한 뒤 프로젝트 루트에서:

```bash
FGC_VERIFY_ARBITRAGE=true FGC_NODE_BIN=node \
  FGC_BROWSER_EXECUTABLE='<Chrome 실행 파일>' \
  ./gradlew test --tests '*ArbitrageScreensIntegrationTest' --rerun
```

Java 테스트가 실제 Thymeleaf HTML을 렌더링하고 임시 HTTP 서버를 연다.
Playwright는 기존/React의 업무 요청을 그 서버로 전달하며 `/auth/me`도 실제 API를 사용한다.
재점검 POST 200 후 같은 결과 ID를 GET으로 재조회해 저장을 확인한다.
JWT는 테스트 프로세스 환경으로만 전달하고 저장하지 않는다. 생성 자료는 `build/screenshots/arbitrage/`에 있다.

## #418에 넘길 시나리오와 기존 테스트 대응

| 유지하는 기존 테스트 | React 대응 |
|---|---|
| ArbitragePublishingStructureTest | arbitrage.test.tsx의 목록·요약·행 선택·타임라인, 전후 배치 비교 |
| ScreenViewControllerTest | routes의 보호된 `/app/arbitrage-checks`, 역할별 브라우저 시나리오 |
| PublishingTemplateStructureTest의 ARB 부분 | 공용 컴포넌트·모달/권한 테스트, 전후 캡처 |

기존 Thymeleaf·JS·뷰/구조 테스트는 #419까지 유지한다.
`e2e/arbitrage.spec.ts`의 네 역할 시나리오를 #418 전체 시연 경로에 연결할 수 있다.
