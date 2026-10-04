# #377 정산 대사 JPA 전환

- 담당: C4t4ddict
- 상위 이슈: [#366](https://github.com/feegachu/fgc/issues/366)
- 작업 이슈: [#377](https://github.com/feegachu/fgc/issues/377)
- 기준: develop `b5b4692`, 2026-10-05 확인
- 단계: 대사 소유 영역 전환 구현, PostgreSQL 실행 검증 및 공유 API 후속 연결 대기

## 변경 내용과 계약

ReconciliationRunMapper, ReconciliationResultMapper, ReconciliationRunHistoryMapper,
InsurerGaReconciliationMapper, GaFcReconciliationMapper와 운영 XML을 삭제하고
동일 이름의 Repository로 서비스 호출부를 전환했다. 컨트롤러·응답 DTO·매칭 알고리즘·허용오차·
사유 분류·수취인 짝짓기·금액 반올림 및 기존 DB/Flyway 이력은 변경하지 않았다.

소유 테이블은 `reconciliation_run`, `reconciliation_result`, `reconciliation_match`다.
엔티티와 JpaRepository를 제공하며 다른 영역의 계약·스케줄·지급·원장 엔티티를 재정의하지 않는다.
외부 참조는 기존 식별자로 매핑한다. 실행 생성은 JpaRepository의 identity 생성키를 사용한다.
기본 보험사 존재 확인은 공유 Insurer 엔티티를 사용하는 JPQL이다.

공개 소비 계약:

| 제공 클래스 | 주요 메서드 | 반환/조건 |
|---|---|---|
| ReconciliationRunRepository | insert, findById, findByNaturalKey | 기존 InsertRow/RunRow, 생성 ID 반영, 미조회 null |
| ReconciliationRunRepository | transitionToRunning/Completed/Failed | 기존 상태 조건과 영향 행 수, DB clock_timestamp 사용 |
| InsurerGaReconciliationRepository | findExpectedSources, findActualSources | 기존 원천 Row 목록·순서·코드 매핑 건수 |
| GaFcReconciliationRepository | findExpectedSources, findActualSources | 기존 원천 Row 목록, 선택 원장 참조는 nullable |
| ReconciliationResultRepository | insertResult, insertMatch | 충돌 시 0, 신규 결과만 ID 반영·상세 원천 저장 |
| ReconciliationResultRepository | findClassificationContext, findResultId, countByRunId | 기존 분류 신호, 식별자 및 실행별 건수 |
| ReconciliationResultRepository | findResults/countResults/findSummary/findDetail/findMatches | 기존 Row projection·정렬·금액·null·snapshot |
| ReconciliationRunHistoryRepository | search, count | 월·방향·보험사 조건, created_at/id 동률 정렬 |

## 네이티브 SQL을 유지하는 이유

- 예상/실제 원천: 미전환 스케줄 및 다영역 테이블, 선택 원장 참조, 원수사 코드의 LATERAL 매핑을 유지한다.
- 상태 전이: 조건부 UPDATE의 영향 행 수, DB 생성시각, 기존 상태 전이 트리거를 보존한다.
- 결과/매칭 저장: ON CONFLICT DO NOTHING으로 중복을 정상 처리한다. 유니크 충돌 예외를 잡아
  트랜잭션을 rollback-only로 만드는 방식은 사용하지 않는다. 신규 결과 ID는 RETURNING으로 받는다.
- 분류 및 상세: 원장 불균형 뷰·JSONB·text[]·기존 원천 추적 조인을 유지한다.
- 이력 및 요약: vw_reconciliation_summary의 결과 0건 실행 포함, 집계와 nullable 참조를 유지한다.
- 선택 대상/검증 실행 잠금: #379 제공 계약 연결 전까지 기존 조건과 잠금 SQL을 보존한다.

모든 사용자 값은 바인딩한다. 정렬 키워드는 코드의 ASC/DESC 중에서만 선택한다.
옵션 필터의 null은 타입 지정 CAST로 처리하여 PostgreSQL 파라미터 타입 추론 오류를 피한다.
배열은 JSON 문자열 바인딩 후 jsonb_array_elements_text로 text[]를 구성하여 따옴표·쉼표·한글을 보존한다.

## 트랜잭션 및 가시성

- API 실행 생성과 RUNNING 전이·실행 요청 등록은 기존 서비스 트랜잭션에 참여한다.
- 배치 준비는 기존 REQUIRES_NEW이고, validation_run 잠금은 호출자 트랜잭션이 필수다.
- 결과·매칭 저장과 COMPLETED 전이는 기존 실행 서비스의 REQUIRES_NEW에 참여한다.
- 저장 오류 시 전체 결과를 롤백한 뒤 기존 실패 기록 서비스가 별도 REQUIRES_NEW로 FAILED를 남긴다.
- native 변경 전 flush하고 상태 변경 후 해당 ReconciliationRun만 detach하여 재조회 시 오래된
  관리 엔티티가 반환되지 않게 한다. 다른 영역의 관리 엔티티를 전부 clear하지 않는다.
- 결과·매칭 충돌은 기존 금액·snapshot·순서를 수정하지 않는다.
- 확정 실행의 불변 트리거는 그대로 적용된다. JPA ddl-auto=validate를 유지한다.

## 공유 API 후속 연결 추적

2026-10-05 원격 확인 당시 PR #396/#397은 미병합이다. 해당 브랜치를 임의로 병합하지 않았다.

| 남은 경로 | 제공 이슈/PR | 소비 전환 담당 | 삭제/완료 조건 |
|---|---|---|---|
| ReconciliationRunServiceImpl의 ValidationRunMapper.findByIdForUpdate | #379 / #396 | #377, C4t4ddict | 공유 ValidationRunRepository.findByIdForUpdate 병합 후 엔티티/Optional 응답 연결 및 잠금·월·FINALIZED 회귀 통과 |
| ReconciliationRunRepository.lockValidationRun | #379 / #396 | #377, C4t4ddict | 공유 잠금 API로 배치 준비 호출부 전환 및 동시 소유권 검증 후 임시 메서드 제거 |
| findSelectedInsurerIds/findSelectedContractIds | #379 / 후속 공유 계약 | #377, C4t4ddict | 보험사·활성 보험사 조건을 포함한 공유 조회 계약 제공 후 전환; 현재 selectSelectedContractIds만으로 기존 조건을 대체하지 않음 |
| ReconciliationExceptionService의 ExceptionCaseMapper.bulkCreateFromReconciliationResultsByRun | #380 / #397 및 후속 제공 PR | #377, C4t4ddict | 후보/생성 건수의 동일 SQL snapshot과 중복 집계 계약을 제공한 뒤 소비 호출부 전환 |

주의: #397 현재 ExceptionCaseRepository는 `bulkCreateFromReconciliationResultsByRun`을 제공하지 않고
소비자가 아직 구 Mapper를 사용한다고 명시한다. #397 병합만으로 이 경로의 전환 조건이 충족되지 않는다.
#379의 선택 계약 API도 보험사별 조회와 활성 보험사 조건의 추가 공유 계약 확인이 필요하다.
각 제공 PR이 준비되면 본 이슈 후속 작업으로 연결한다. 공유 Mapper 자체의 최종 삭제는 다른 소비자의
참조도 0인 시점에 소유 영역/#383과 확인한다. #377과 #366은 이 단계에서 종료하지 않는다.

## 검증

기존 대사 서비스·Controller·adapter 테스트와 PostgreSQL 통합 테스트를 그대로 재사용했다.
실행 이력 통합 테스트는 repository 패키지로 이동했다.

신규 ReconciliationRepositoryCompatibilityIntegrationTest는 테스트 전용으로 격리한 기준 SQL과
같은 DB·트랜잭션에서 비교한다. XML은 `src/test/resources/reconciliation-baseline`에만 있으며
운영 mapper-locations에 포함되지 않는다. 테스트 내부의 별도 SqlSessionFactory로만 로딩한다.

- 양방향 예상/실제 원천, 빈 결과의 Row·금액·null·순서 동일성.
- 결과/매칭 엔티티 매핑, JSON·text[] 보존, nullable 참조 및 DB 생성시각.
- 동일 키 재저장 시 기존 금액·snapshot·원천 상세 보존.
- CREATED/RUNNING/FAILED/COMPLETED 전이 및 영향 행 수, 관리 엔티티 갱신 가시성.
- 결과·이력의 null/빈/SQL 형태 필터, 목록·count·summary·detail 동일성.
- 빈/비어 있지 않은 원장 ID 목록과 null 계약/설계사 분류 문맥.
- 같은 트랜잭션에서 MyBatis 쓰기→JPA 읽기와 JPA 쓰기→MyBatis 읽기.
- 예열 후 각 projection의 실제 JDBC 실행 횟수 1회 및 JDBC 호출 시간 로그.

이 시간 로그는 DB 서버 시간이나 대표 규모 전체 배치 벤치마크가 아니다. 시간 임계값을 CI에서 강제하지
않는다. 대표 데이터의 반복·확장 규모 성능과 애플리케이션 기동은 추가 실행 검증이 필요하다.

실행 명령:

```powershell
.\gradlew.bat test --tests 'com.susukkang.fgc.reconciliation.*' --no-daemon
.\gradlew.bat test --tests 'com.susukkang.fgc.common.persistence.*' --tests 'com.susukkang.fgc.validation.FinalizedValidationRunImmutabilityIntegrationTest' --no-daemon
.\gradlew.bat build --no-daemon
```

최초 대사 범위 실행은 127개 중 98개 통과, PostgreSQL 통합 29개는 Docker 환경 초기화 실패였다.
추가 호환성 테스트 4개도 실제 PostgreSQL 실행 검증이 필요하다. 테스트 코드는 컴파일 검증 대상에 포함한다.
Docker Desktop 실행을 시도했으나 엔진 pipe가 생성되지 않았고 서비스 기동 권한이 없어 현재 PC에서
통합 실행·기동·성능 결과는 확인하지 못했다. 개발자가 쓰는 로컬 DB로 테스트 대상을 바꾸지 않았다.
전체 통합 테스트/CI 통과와 공유 소비 호출부 전환 전에는 완료 또는 병합 가능 상태로 표시하지 않는다.

## PR #398 CI 디버깅 (2026-10-05)

- 최초 CI는 전체 1,442개 중 신규 호환성 테스트 2개 실패, 5개 건너뜀이었다.
  스케줄이 없는 시드에서 예상 원천을 비어 있지 않다고 가정했고, null 스케줄 ID로 매칭을
  저장하여 `ck_reconciliation_match_source`를 위반했다. 테스트 트랜잭션에서 양방향 스케줄,
  실제 지급·귀속, 균형 분개를 직접 생성하고 롤백하도록 변경했다.
- 양방향 예상 원천의 공통 조회를 추출했다. 보험사→GA는 POSTED 예상 분개가 필수이고,
  GA→FC는 없어도 조회되며 수취인도 각각 계약 설계사/스케줄 수취인으로 유지한다.
- 실행·결과의 명시적 scalar 타입/속성 매핑을 공통화했다. DTO 필드/API는 변경하지 않았다.
- 정렬 방향과 빈 원장 ID 목록의 분류 신호를 바인딩으로 처리하여 동적 SQL 조합을 제거했다.
  품질 게이트 설정이나 보안 판정을 우회하지 않고 코드 중복과 문자열 조합을 수정했다.
- 수정 후 PostgreSQL 전체 검증 결과는 PR CI에서 별도로 확인한다.
