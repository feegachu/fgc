# #406 화면 전환 검증 자료

2026-10-07, 최신 develop(#404 포함), 1440×1000. 임시 PostgreSQL 시드와 실제 HTTP/JWT 응답으로 비교했다.
운영 데이터·토큰·쿠키는 포함하지 않는다. 실제 Thymeleaf 렌더링과 React 화면의 동일 행 표시를 검증했으며 픽셀 동일성은 검증 대상이 아니다.

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

실제 API 상태와 행 수: [evidence.json](evidence.json). 재현 명령·의도적인 화면 차이·유지하는 기존 테스트는 [frontend README](../../README.md) 참고.

검증 결과: 프런트엔드 137개 통과(서버 전용 4개 제외), 브라우저 20개 통과, 백엔드 전체 1,637개 통과(선택 실행 10개 제외). 실제 API·화면 비교 1개 별도 통과, 브라우저 pageerror 0건.
