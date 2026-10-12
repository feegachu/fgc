# #411 CAP 화면 전환 검증 자료

브랜치·커밋·파일 구성·PR 양식은 최근 React 전환 PR #464(스케줄), #453(감사로그), #452(예외함)를 기준으로 맞춘다.

## 공유 모달 계약

`features/cap/CapDetailModal.tsx`의 named export `CapDetailModal`을 사용한다.
props는 `capCheckId: string | number | null | undefined`, `onClose: () => void`이다. null·undefined·빈 문자열은 닫힘/조회 안 함을 의미한다. 빈 ID 쿼리를 수동으로 실행해도 API 요청 전에 차단한다.
QueryClientProvider와 Router 안에서 렌더링하며, URL/선택 상태는 호출자가 관리한다.
#408 담당자에게 이슈 댓글로 제안했으며, 합의·리뷰 완료로 간주하지 않는다.

## 검증

- Node 22.22.2, Java 21 사용
- 리뷰 반영 후 Vitest: `npm test -- --run` — 222 passed / 4 skipped (CAP 19개 포함). 빈 ID 3종·서버 주의선 위치·위치 부재 회귀를 검증한다.
- API 타입: `npm run check:api`, 린트: `npm run lint`, 프로덕션 빌드: `npm run build`
- 기존 화면: `./gradlew test --tests '*CapPublishingTemplateStructureTest' --tests '*PublishingTemplateStructureTest' --tests '*ScreenViewControllerTest'` — 47 passed
- 브라우저 UI 회귀: `npx playwright test e2e/cap.spec.ts` (API fixture, 실제 업무 서버 검증과 구분)
- 전체 Playwright: 24 passed (CAP 3개 = fixture 2개 + 실서버 1개 포함)
- 실서버: 격리 PostgreSQL 17 DB에 기존 Flyway migration/demo를 적용하고 시연 계약 1~5의 스케줄 생성·CAP 검증 API로 결과를 준비한다. 개발자/운영 DB는 사용하지 않는다.
- `FGC_CAP_REAL_SERVER=1`, `FGC_CAP_CAPTURE=1`, `FGC_CAP_QA_MONTH`를 설정한 뒤 같은 Playwright 파일의 실서버 테스트를 실행한다. 실서버 테스트는 준비된 결과를 조회만 한다.
- 계정은 `FGC_CAP_QA_LOGIN_ID`, `FGC_CAP_QA_PASSWORD` 환경변수로만 전달한다. 코드/캡처/PR에는 비밀번호·토큰·쿠키를 저장하지 않는다.

## 전후 화면

후속 리뷰 반영: GA→설계사 수식 fixture를 준법경영비 0·한도 912,000원으로 맞췄다. 최신 develop `308e3973`의 #508을 따라 주의선은 서버 `warningStartUsagePct`로 표시하며, 정책 90%와 사용률 표시선 위치를 구분한다. OpenAPI 스펙과 생성 타입에 기존 서버 필드 1개만 동기화했다. API 경로·백엔드 판정 계산을 새로 변경한 것은 아니다. 아래 캡처는 최초 전환 당시 기록이다.

2026-10-12 로컬 격리 PostgreSQL 17.11 + Java 21 실서버에서 확보했다. 토큰·쿠키·운영 데이터는 포함하지 않는다.
기존 Flyway 시연 데이터를 사용하고 기존 API로 시연 계약 1~5의 운영 스케줄과 판정을 생성했다.
수동 검증의 기준일은 서버의 오늘(2026-10-12)이므로 비교 월은 2026-10이다. PC 날짜로 화면 기본월을 만드는 것과는 별개다.

- 동일 GET 목록 응답 전체를 Thymeleaf/React에서 비교: 일치
- 목록 10건(5계약 × 2단계), 단계별 각 5건, 설계사 합계 3행
- 같은 상세 `cap_check #10`의 항목 16행, 입력·사용률·저장 판정 대조
- 정상 판정 실데이터로 비교했으며, 주의/위반·6자리 경계·음수·없는 ID·0건은 자동화 fixture로 검증
- 픽셀 동일성은 검증 대상이 아니다. 표 가로 스크롤과 모달 세로 스크롤은 각 콘텐츠 영역 안에서 동작한다.

| 화면    | Thymeleaf                   | React                      |
| ------- | --------------------------- | -------------------------- |
| CAP-W01 | [전](legacy-cap-list.png)   | [후](react-cap-list.png)   |
| CAP-W02 | [전](legacy-cap-detail.png) | [후](react-cap-detail.png) |

## 알려진 차이·QA 및 남은 의존

- 사용률: 문서 CAP-W01의 일부 주의 문구는 반올림이라 적혀 있으나 이슈 #411/인터페이스 공통 규칙/공통 usageRate를 따라 6자리 절삭한다. 저장 판정은 재계산하지 않는다.
- 상세 배치: 1차처럼 왼쪽 ①②④, 오른쪽 ③을 유지한다(문서의 위→아래 4단과 차이).
- 룰셋 코드·REG ID: 기존 API는 capRuleSetId만 제공하므로 코드명을 추정하지 않고 ID를 유지한다. 규제 근거는 공통 EvidenceLink로 표시한다. 항목 evidenceRef는 저장 문자열 그대로이며 실행 가능한 외부 링크/HTML로 해석하지 않는다.
- 상세 footer의 산입 항목 검산은 1차와 동일하되, 서버 저장 합계/한도/사용률/판정을 대체하지 않는다.
- 기본월: PC 날짜가 아닌 서버 demoMonth를 초기화한 공통 workspace를 사용한다.
- 검색 URL: 브라우저 뒤로/앞으로 복원 지원. 잘못된 코드/페이지/행 수는 보정한다. 상세 ID는 목록과 독립적이다.
- 공통 버튼·필터·페이징·모달의 아이콘 및 padding은 React 공통 스타일을 따른다. 공통 컴포넌트 파일 변경은 없다.
- CAP-W02는 조회 전용이다. #408 실제 계약 페이지 통합/합의는 담당자 리뷰가 필요하다. 소비자 계약 테스트는 동일 컴포넌트를 계약 라우트에 렌더링한다.
- 기존 `cap/list.html`, `cap/detail-modal.html`, `cap-list.js`, 뷰 컨트롤러/구조 테스트는 그대로 둔다. 삭제는 #419.

| 기존 테스트                              | React 대체/인계                                                |
| ---------------------------------------- | -------------------------------------------------------------- |
| CapPublishingTemplateStructureTest 2개   | cap.test.tsx 목록 컬럼·공제 표시·상세 스냅샷/합계, cap.spec.ts |
| ScreenViewControllerTest CAP 부분        | cap.test.tsx 실제 routes 진입 및 역할 4종                      |
| PublishingTemplateStructureTest CAP 부분 | cap.test.tsx 공통 컴포넌트·문구·딥링크, cap.spec.ts            |

#418에는 필터 조회 → 산입금액 → 계산근거 → 예외함, 예외함 capCheckId 직접 진입, 잘못된 ID, 계약 상세 소비자 통합 시연을 인계한다.
실제 #408 통합 확인·리뷰 2인 승인 전에는 #411을 종료하지 않는다.
