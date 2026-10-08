# #415 화면 전환 검증 자료

2026-10-08, 로컬 PostgreSQL 시드와 실제 HTTP 응답으로 비교한 수동 캡처다.
운영 데이터·토큰·쿠키는 포함하지 않는다. 같은 기준월(2026-07)의 같은 데이터를 비교했다.
영역별 측정값은 만들지 않았고, 전체 이미지의 픽셀 동일성도 검증 대상이 아니다. 공통 셸(#403) 차이는 비교 범위 밖이다.

| 화면 | 기존 Thymeleaf | React |
|---|---|---|
| VRUN-W01 월 통합검증 실행 목록 | [전](legacy-validation-runs-list.webp) | [후](react-validation-runs-list.webp) |
| VRUN-W02 상세 · 확정 (위쪽) | [전](legacy-validation-run-detail-top.webp) | [후](react-validation-run-detail-top.webp) |
| VRUN-W02 상세 · 확정 (아래쪽) | [전](legacy-validation-run-detail-bottom.webp) | [후](react-validation-run-detail-bottom.webp) |

- 기존: `http://localhost:8081/validation-runs`, `http://localhost:8081/validation-runs/7`
- React: `http://localhost:5173/app/validation-runs?month=2026-07`, `http://localhost:5173/app/validation-runs/7?month=2026-07`

같은 항목: 실행 생성 폼(검증월·실행 유형·생성 버튼·안내 문구), 상태 필터와 조회 버튼, 실행 목록 11열과 행 수·상태 배지(확정은 자물쇠), 상세의 실행 선택·이동, 확정 불가역 경고, 실행 헤더, 10단계 스텝퍼(9·10단계 "사람이 수행")와 진행률, 대상 선별 결과, 결과 요약 4블록, 예외 생성 결과 6카드, 확정 조건 6개와 통과 배지, 확정 영역 안내 문구.

캡처에서 보이는 차이:
- 목록 표의 열 폭 합계가 카드보다 넓어 React 캡처에는 표 아래에 가로 스크롤바가 보인다. 기존 캡처는 마지막 열(상세)이 오른쪽에서 잘려 보인다.
- 열린 탭 목록과 탭 아이콘, 사용자 표시 문구는 공통 셸(#403) 차이다.

검증: 프런트엔드 Vitest(검증 화면 11개 포함), `tsc`, `eslint`, Playwright 1개, 백엔드 `ValidationRunControllerTest`가 통과했다.
