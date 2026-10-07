# #406 화면 전환 검증 자료

2026-10-07, 최신 develop(#404 포함), 1440×1000. 임시 PostgreSQL 시드와 실제 HTTP/JWT 응답으로 비교했다.
운영 데이터·토큰·쿠키는 포함하지 않는다. 실제 Thymeleaf 렌더링과 React 화면의 동일 행·문구·필터/버튼 순서와 주요 영역의 위치·너비·높이(허용오차 2px)를 비교했다. 전체 이미지의 픽셀 동일성은 검증 대상이 아니다. #403 공통 셸 변경은 비교 범위 밖이다.

| 화면 | 기존 Thymeleaf | React |
|---|---|---|
| BASE 조직 | [전](legacy-base-organization.png) | [후](react-base-organization.png) |
| BASE 보험회사 | [전](legacy-base-insurer.png) | [후](react-base-insurer.png) |
| BASE 상품 | [전](legacy-base-product.png) | [후](react-base-product.png) |
| BASE 설계사 | [전](legacy-base-agent.png) | [후](react-base-agent.png) |
| BASE 수수료 항목 | [전](legacy-base-commission-item.png) | [후](react-base-commission-item.png) |
| POL 정책 버전 | [전](legacy-policies-versions.png) | [후](react-policies-versions.png) |
| POL 수수료 규칙 | [전](legacy-policies-commission.png) | [후](react-policies-commission.png) |
| POL 1,200% 룰셋 | [전](legacy-policies-cap.png) | [후](react-policies-cap.png) |
| POL 예상 해약환급률표 | [전](legacy-policies-refund.png) | [후](react-policies-refund.png) |

실제 API 상태·행 수·전후 영역 측정값: [evidence.json](evidence.json). 재현 명령·화면 유지 범위·기존 테스트는 [frontend README](../../README.md) 참고.

검증 결과: 프런트엔드 138개 통과(서버 전용 4개 제외), 브라우저 20개 통과, 선행 구현의 백엔드 전체 1,637개 통과(선택 실행 10개 제외). 실제 API·화면 비교 1개 별도 통과, 브라우저 pageerror 0건.

복원 항목: BASE 필터/결과 카드 분리, 결과 제목·설명·우측 건수, 넓은 검색 필드, 초기화→조회,
중앙 페이지 이동, POL 우측 기준일·탭별 제목/선택 정책, 정책 열 너비, 룰셋·환급률 메타정보 배치.
긴 텍스트는 실제 잘림이 있을 때만 전체 보기를 표시한다. 펼침이 정책 선택을 바꾸지 않는지도 검증한다.

1440×1000에서 62개 영역의 측정값이 일치했다(최대 차이 0px).
