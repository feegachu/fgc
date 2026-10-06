# FGC 차익거래 JPA 전환 검증 v0.1

- 작성일: 2026-10-06
- 작업: [#378](https://github.com/feegachu/fgc/issues/378), 상위 [#366](https://github.com/feegachu/fgc/issues/366)
- 기준: `origin/develop`의 `aabf5cc7`
- 브랜치: `refactor/378-migrate-arbitrage-to-jpa`

## 변경 내용과 보존 범위

`ArbitrageMapper` 및 운영 XML을 제거하고 `ArbitrageCheck` 엔티티, `ArbitrageCheckRepository`와 전용 조회 fragment로 옮겼다. 수동 검증과 배치 소비자가 JPA 저장소를 사용한다. 계약·정책·실행·예외 공용 엔티티를 새로 정의하지 않았다.

- 검증 실행 상태 전이: #379가 제공한 `ValidationRunRepository` 사용.
- 배치 대상 조회: #379가 제공한 `ValidationTargetRepository.selectSelectedContractIds` 사용.
- 환급률표 후보: 기존 `RefundRateTable`·`RefundRateLine` 엔티티를 사용한 JPQL.
- 결과 단건 조회와 기본 CRUD: `JpaRepository<ArbitrageCheck, Long>`.
- 수동/기존 실행의 계산 결과 저장: 네이티브 UPSERT로 실제 결과 ID를 반환.
- 계산 결과·예외 검출 변경 전 `flush`, 변경 후 `clear`로 미반영 JPA 쓰기와 이전 엔티티 상태의 재사용을 방지.
- 계약별 배치의 `REQUIRES_NEW` 전파와 실패 계약의 롤백을 유지.

API 경로·메서드·DTO·금액 계산·반올림·환급률 선택·판정·정책 스냅샷·DB 스키마·Flyway 파일은 변경하지 않았다. 조회 서비스에는 읽기 전용 트랜잭션을 명시했다. Controller와 요청/응답 DTO의 변경은 없다.

## 유지한 네이티브 SQL의 이유

| 처리 | 유지 이유 |
|---|---|
| 입력 금융 스냅샷 | PostgreSQL LATERAL로 기준일 이하 최신 스냅샷을 선정한다. 같은 날짜에서 ACTUAL 우선·PK 역순 규칙을 보존한다. |
| 확정·예정 수수료 | 귀속행 및 지급예정행별 ROUND 후 합산, 지급단계 분리, 확정 차감 차감, 운영·활성·PLANNED 조건을 보존한다. |
| 목록·요약·시계열 | DISTINCT ON으로 계약·지급단계·기준일별 최신 결과를 먼저 선정하고 상태 필터를 적용한다. JSONB 판정 근거도 같은 조회에서 읽는다. |
| 결과 저장 | 동일 업무키의 동시 실행에 대한 ON CONFLICT와 RETURNING의 원자성, 기존 생성 시각을 보존한다. |
| 예외 탐지 | 공통 DB 함수 `fgc.record_exception_detection`이 업무키, 실행별 증거, 재검출과 재개방을 함께 관리한다. |

## 공유 계약 및 후속 연계

#380의 공유 예외 탐지 JPA API는 작성 시점에 `develop`에 병합되지 않았다. 차익거래 소유 호출부는 `ArbitrageExceptionRepository`를 통해 기존 DB 함수를 JPA EntityManager로 호출한다. 공용 `ExceptionCase` 엔티티나 DB 검출 규칙을 중복 구현하지 않았다. 기존 `CapExceptionRepository`의 함수 호출·flush/clear 방식과 일관되게 구성했다.

| 대상 | 제공/소비 계약 | 후속 확인 |
|---|---|---|
| #379 / PR #396 | CREATED→RUNNING, currentStep=5, RUNNING→COMPLETED의 행 수를 검사한다. SELECTED 계약 ID는 오름차순이다. | 기존 공유 API를 직접 소비한다. |
| #380 / PR #397 | 후보/검토필요 검출 입력은 실행·계약·차익 결과 ID, 지급단계, 사유다. 없는 실행은 null, 재검출은 기존 업무건 ID다. 호출자의 쓰기 트랜잭션에 참여한다. | 공유 JPA API 병합 후 제공자와 어댑터 수렴 방식을 확인한다. 외부 이슈·댓글은 아직 수정하지 않았다. |
| D의 #376 | 기존 공용 예외 테이블·엔티티·조치 계약을 유지한다. | 공용 엔티티 변경 없음. |
| #383 | 차익거래 운영 코드의 Mapper 참조는 0이다. 타 도메인의 `ValidationRunMapper`·`validation.ExceptionCaseMapper`는 다른 소비자 때문에 유지한다. | 소비자 전체 전환 후 해당 소유자가 제거한다. 테스트 전용 baseline은 런타임 패키징에 포함되지 않는다. |

## 자동 검증

환경: Java 21, 프로젝트 Gradle Wrapper, Docker Desktop, Testcontainers PostgreSQL 17. Flyway의 migration/demo를 적용한 임시 DB를 사용하며 개발 DB는 사용하지 않았다.

전환 전:

```sh
./gradlew test --tests 'com.susukkang.fgc.arbitrage.*' --console=plain
```

기존 10개 테스트가 통과했다.

전환 후 관련 테스트: 28개 통과, 실패·오류·건너뛰기 0.

| 테스트 | 검증 내용 |
|---|---|
| ArbitrageServiceTest (10) | 기존 계산·감사 호출·상태 필터 독립 요약, 금융 스냅샷 부재, 36/37개월 가산 경계, 원 단위 HALF_UP과 엄격한 양수 판정, 환급률표 불일치 |
| ArbitrageCheckRepositoryIntegrationTest (4) | 필터·페이징·요약, 재실행 최신 결과 접기, 판정 변경 |
| ArbitragePersistenceComparisonTest (5) | 같은 데이터에서 기존 MyBatis/JPA 입력·환급률 후보·양 지급단계 합계·기준일 경계·필터·시계열 비교, ID 유지 UPSERT와 영속성 컨텍스트 갱신, 예외 업무키·재검출·재개방, 성능 비교 |
| ArbitrageManualCheckIntegrationTest (1) | 기존 실행에 결과 저장 |
| ArbitrageCheckBatchAdapterTest (1) | 선별 계약 처리·복구 가능한 실패 skip |
| ArbitrageTransactionBoundaryIntegrationTest (2) | 결과·예외·증거를 쓴 뒤 실패 시 계약 전체 롤백, 재시도 중복 없음, 외부 청크 롤백과 독립된 성공 계약 커밋, 수동 실행 생성·정책 스냅샷·완료·감사 기록 |
| ArbitrageApiIntegrationTest (5) | 실제 내장 웹 서버 기동, 조회 응답 봉투·빈 페이징·입력 검증, 비로그인 401, 읽기 역할 403, 기존 세션 CSRF 유지 |

전체 검증:

```sh
./gradlew clean build --console=plain
./gradlew build --console=plain
```

전체 테스트 1,607개 중 1,601개 통과, 조건부 성능 테스트 6개 건너뛰기, 실패·오류 0. 후자의 6개는 각 도메인의 별도 성능 실행용 테스트다. JAR/WAR 빌드 성공. GitHub CI는 push하지 않아 아직 실행하지 않았다.

## 성능 비교

`ArbitragePersistenceComparisonTest`가 테스트 전용으로 보관한 기존 Mapper/XML 및 서비스를 같은 데이터에서 JPA와 번갈아 실행한다. 입력·목록은 각 구현 3회 워밍업 후 10회 측정한다. MyBatis 세션 캐시를 비우고 기존 JDBC 관측 도구로 실제 SQL 실행 수를 검사한다. UPSERT와 계약별 처리의 측정은 같은 업무키 재처리 경로를 포함한다.

2026-10-06 최종 JPQL 대상 테스트 실행에서 기록한 표본:

| 처리 | SQL 수 MyBatis/JPA | 평균 ms MyBatis/JPA |
|---|---:|---:|
| 입력 4종 조회 | 4 / 4 | 2.082 / 2.212 |
| 목록·요약·건수 | 3 / 3 | 2.696 / 2.271 |
| UPSERT | 1 / 1 | 1.042 / 1.145 |
| 계약별 입력·계산·저장·예외 처리 | 5 / 5 | 2.187 / 2.669 |

SQL 실행 횟수 증가가 없고 같은 입력의 계산 결과·JSON 스냅샷·식별자가 일치했다. 시간은 작은 demo 데이터의 로컬 호출 전체 시간으로 JDBC·JVM·매핑 비용을 포함하며 DB 서버 실행 시간이나 운영 부하 성능 보증이 아니다. 일부 경로의 JPA 평균이 높으므로 처리량 개선을 주장하지 않는다. 운영 규모에서의 배치 처리량·동시 부하 측정은 별도로 필요하다.

## 리뷰 전 남은 확인

- #380 제공자와 공용 예외 탐지 API 수렴 방식 확인 및 #366/#383의 공유 소비자 기록 갱신.
- 사용자 확인 후 commit/push/PR 생성, GitHub CI 및 팀 리뷰.
- 이슈 완료·종료는 공유 계약과 리뷰 기준을 충족한 뒤 결정한다.
