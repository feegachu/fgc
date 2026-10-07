# #414 화면 전환 검증 자료

2026-10-07, 최신 develop(#406 포함), 로컬 PostgreSQL 시드와 실제 HTTP 응답으로 비교한 수동 캡처다.
운영 데이터·토큰·쿠키는 포함하지 않는다. 같은 예외(#256)를 선택한 상태의 기본 진입 화면(미처리, 1페이지)을 비교했다.
#406과 달리 영역별 측정값(`evidence.json`)은 만들지 않았고, 전체 이미지의 픽셀 동일성도 검증 대상이 아니다.

| 화면 | 기존 Thymeleaf | React |
|---|---|---|
| EXCP-W01 예외함 | [전](legacy-exception-list.png) | [후](react-exception-list.png) |

- 기존: `http://localhost:8081/exceptions`
- React: `http://localhost:5173/app/exceptions?month=2026-07&selected=256`

같은 항목: 유형별 미처리 요약 카드(4종, 건수), 필터 7종과 순서, 예외 목록 건수(84건), 검출 이력 패널, 처리 패널.

캡처에서 보이는 차이:
- 필터의 `초기화`·`조회` 버튼은 기존 화면에만 아이콘이 있다.
- 기존 캡처는 목록 표가 가로로 스크롤된 상태라 열 위치를 그대로 대조할 수 없다.
- 상세 원인·내용·참조 셀의 "전체 보기"는 기존 화면은 글이 잘릴 때만, React는 공통 `DataTable` 방식으로 항상 보인다.

검증: 프런트엔드 Vitest(예외함 9개 포함), `tsc`, `eslint`, Playwright 1개, 백엔드 예외함 통합 테스트(`ExceptionCaseApiIntegrationTest` 16개 등)가 통과했다.
