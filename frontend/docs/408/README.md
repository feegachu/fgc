# 보험계약 React 전환 검증

이슈 #408의 CONT-W01 목록, CONT-W02 상세 6개 탭, CONT-W03 등록·수정을 `/app/contracts`에 연결한다. 기존 스케줄·CAP·기준정보 전환과 동일하게 공통 API 클라이언트, OpenAPI 타입, TanStack Query, 공통 컴포넌트와 표시 함수를 사용한다. 기존 Thymeleaf 화면은 계속 제공한다.

## 구현과 안정성

- 목록의 9개 검색조건을 URL에 유지하며 페이지는 1부터, 한 화면은 20행으로 조회한다. CSV에는 동일한 검색조건을 전달한다.
- 기준정보는 마지막 페이지까지 조회한다. 보험사 변경 시 상품, 조직 변경 시 설계사 선택을 초기화하고 이전 비동기 응답은 새 선택에 반영하지 않는다.
- 상세는 기본정보·예상 스케줄·1,200%·차익거래·원장·지급 및 대사를 제공한다. `?tab=cap` 진입과 브라우저 뒤로가기를 지원하며 `CapDetailModal`을 직접 재사용한다.
- 등록·수정은 서버 `canProcess`를 사용하는 기존 라우트 가드를 따른다. 상세 작업은 권한이 없으면 비활성화하고 이유를 표시한다. 서버 권한 검사는 그대로 유지한다.
- 수정 폼은 원래 금액·납입기간·공제액·계약 당시 소속과 `updatedAt` 정밀도를 보존한다. 동시수정 충돌은 입력을 유지하고 확인 후 최신 계약을 다시 조회한다.
- 중복 제출을 막고, 완료 시 계약·스케줄·CAP 캐시를 무효화한다. 다른 화면으로 이동한 뒤 늦게 도착한 저장·재검증 결과가 현재 화면을 이동시키지 않는다.
- 서버 오류는 코드를 기준으로 처리하고 메시지는 그대로 표시한다. `FGC-COMMON-500`은 조회·저장·CSV·재검증 모두 요청 ID만 표시한다.

## API 호환성

기존 API 메서드·주소·파라미터·응답 봉투를 유지한다. 다음 읽기 전용 라벨만 추가하고 인터페이스정의서 IF-API-11·12·16과 OpenAPI를 함께 갱신했다.

| 응답 | 추가 필드 |
| --- | --- |
| ContractView | contractStatusLabel, capResultStatusLabel, dataOriginLabel |
| ContractDetailResponse | contractStatusLabel, paymentCycleCodeLabel, dataOriginLabel |
| ContractStatusEventResponse | previousStatusLabel, newStatusLabel |

DB·Flyway·업무 계산·영속성 로직 변경은 없다. CSV 공통 함수에는 `notifyError` 옵션을 추가해 계약 화면이 자체 오류 표시를 할 수 있게 했으며 기본 동작은 유지한다.

## 검증 방법

Java 21, Node 22, PostgreSQL 17을 사용한다.

```sh
./gradlew build
cd frontend
npm run check:api
npm run lint
npm test -- --run --maxWorkers=4
npm run build
npx playwright test --workers=2
```

전체 백엔드 결과는 1,794개 중 1,784개 통과, 10개 조건부 제외, 실패 0개다. 별도 OpenAPI 추출 테스트와 실제 HTTP JWT 테스트 4개도 통과했다. 프런트엔드 전체 Vitest는 272개 통과·실서버 전용 4개 제외이며, OpenAPI 정합성·타입·린트·프로덕션 빌드가 통과했다.

실서버 시연은 운영·개발 DB와 분리한 새 PostgreSQL에 기존 migration/demo를 적용한다. `FGC_CONTRACT_REAL_SERVER=1`, `FGC_CONTRACT_QA_LOGIN_ID`, `FGC_CONTRACT_QA_PASSWORD`를 설정하고 `npx playwright test e2e/contract.spec.ts`를 실행한다. 스크린샷은 `FGC_CONTRACT_CAPTURE=1`일 때 생성한다. 이 테스트는 QA 접두어 계약을 실제로 등록·수정하므로 격리 DB에서만 활성화한다. 테스트 코드와 캡처에는 인증 정보를 저장하지 않는다.

전체 Playwright는 24개 통과·별도 CAP 실서버 전용 1개 제외다. 실서버에서 기존 화면과 React 목록의 GET 응답 전체·표 행 수를 비교하고, 상세 6개 탭 조회와 등록 → 상세 성공 메시지 → 수정 → 변경값 갱신을 확인했다. 404·500·권한 거부·중복 제출·동시수정 충돌·느린 응답 순서 역전은 Vitest로 검증한다.

## 전후 화면

2026-10-12 격리 DB의 가상 시연 데이터로 촬영했다.

| 화면 | Thymeleaf | React |
| --- | --- | --- |
| CONT-W01 | [목록 전](legacy-contract-list.png) | [목록 후](react-contract-list.png) |
| CONT-W02 | [상세 전](legacy-contract-detail.png) | [상세 후](react-contract-detail.png) |
| CONT-W03 | [등록 전](legacy-contract-form.png) | [등록 후](react-contract-form.png) |

## 표시 차이와 기존 테스트 인계

| 항목 | React에서의 차이 |
| --- | --- |
| 배치 | 공통 Field·DataTable·Pagination을 사용한다. 입력 카드는 세로로 배치하고 상세 기본정보와 상태이력은 한 패널에서 순서대로 표시한다. 표는 콘텐츠 영역 안에서 가로 스크롤한다. |
| 상태이력 조회 실패 | 자료 없음과 구분해 오류·재시도를 제공한다. |
| 기준정보 | 기존 첫 100건 제한 대신 모든 페이지를 읽는다. URL에 저장된 과거 선택은 선택 ID를 표시해 유지한다. |
| 초기화 | 조회 전 입력한 조건도 함께 지운다. |
| 저장 안내 | 전역 sessionStorage 대신 라우트 state로 상세 화면에 한 번 전달한다. |
| 상세 데이터 관리 | 내부 API 주소·최종 조회 시각 보조 카드는 노출하지 않는다. 업무 정보와 상태이력은 유지한다. |

| 기존 테스트 | React 대체 검증 |
| --- | --- |
| ContractViewControllerTest | list.test.tsx, form.test.tsx, detail.test.tsx의 경로·권한·데이터·필드 오류 검증 |
| CapPublishingTemplateStructureTest 계약 부분 | detail.test.tsx의 CAP 딥링크·공통 모달·예외 목록 연결 |
| PublishingTemplateStructureTest 계약 부분 | 공통 컴포넌트를 사용하는 페이지 행동 테스트와 contract.spec.ts 실서버 비교 |
| frontend/src/legacy/contract-form.test.ts | 유지하며 새 form.test.tsx에서 저장값 보존·금액·동시수정·비동기 경합을 추가 검증 |

기존 contract 템플릿·static JS·뷰 컨트롤러와 구조 테스트는 #419에서 제거한다. #418에는 `e2e/contract.spec.ts`의 실제 등록·상세·수정 시연을 인계한다. 도메인 담당자·PL 및 요청된 리뷰어의 승인은 GitHub 리뷰에서 별도로 받는다.
