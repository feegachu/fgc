# FGC 성능시험 결과서 v1.0

| 항목 | 내용 |
|---|---|
| 요구사항 | FGC-TER-008 성능·부하시험 (요구사항명세서 v3.0 `TER_테스트_요구사항`), 함께 증명: PER-001, PER-003, PER-004, PER-005, PER-007 |
| 추적 이슈 | #673 (출시 전 트래커 #755) |
| 상태 | **RC 전 사전 측정 (2026-11-04).** 동결 RC(11/6~11/10)에서 같은 스크립트로 다시 재서 표를 바꾼다. 지금 값을 RC 결과로 인용하지 않는다 |
| 근거 | 인수조건은 요구사항명세서 v3.0 원문. PER-003, 004, 005, TER-008 출처 SRC-005("최종 릴리스는 10만 건 이상 정산 성능시험을 수행한다. 처리시간·처리량·오류율 결과 제출"), PER-007 출처 SRC-004, SRC-005. PER-001 출처 SRC-006은 근거대장에서 "누락"이라 원문 대조를 못 한다(근거 없음, 확인 필요). 데이터는 DAR-012 [SRC-054, SRC-027 D-05], 격리 DB 전용은 PM 결정(2026-11-04, 팀 공유 전) [SRC-076, SRC-065, SRC-067]. 측정 도구 선택은 기술 판단 [SRC-028] |
| 공개 범위 | 공개 저장소 문서다. 대상 주소, 비밀번호, 토큰은 넣지 않는다 |

**인수조건 원문 (v3.0):**

- TER-008: "성능 요구사항의 대상·데이터량·측정값·판정이 결과서에 포함된다."
- PER-001: "주요 API 10종의 p95가 2초 이내이며 기준 초과 API는 개선결과를 제시한다."
- PER-003: "100,000건 정산이 목표시간 이내 완료되고 CPU·메모리·DB 병목지표를 제시한다." 세부내용 "계약 또는 스케줄 100,000건 이상 정산을 10분 이내 완료하는 목표를 둔다."
- PER-004: "대표 대시보드 조회의 p95가 3초 이내이고 집계값이 상세 합계와 일치한다." 세부내용 "100,000건 기준 핵심 KPI 조회를 3초 이내 제공한다."
- PER-005: "동시사용자 50명 부하시 오류율 1% 미만, 주요 API p95 3초 이내를 충족한다." 세부내용 "동시사용자 50명, 10분 시나리오".
- PER-007: "동일 요청 재실행 시 신규 생성 0건이며 성능 저하가 합의 기준 이내이다." 합의 기준의 값이 원문에 없어 측정값만 남긴다.

원문에 없는 기준으로 합격을 선언하지 않는다. 10만 건에 못 미치는 측정은 외삽하지 않고 "미검증"으로 적는다(#673).

## 1. 판정 요약

| 요구사항 | 대상 | 데이터량 | 조건 | 반복 | 측정값 | 목표 | 판정 |
|---|---|---|---|---|---|---|---|
| PER-003 | 월 통합검증 `MonthlyValidationJob`, 2026-08 | DAR-012 1,000계약: 대상 1,007, 스케줄 28,168행, 분개 4,378, 대사 3,871, 예외 4,228 | 2절 | 1회 | 184.6초 | 10만 건 10분 | **미검증** (10만 건 미만). 3,600계약 측정 대기 |
| PER-004 | `GET /api/v1/dashboard/summary?month=2026-08` | 위와 같음 | VU 5, 예열 뒤 | 300회 | p95 328ms, KPI 6종 홈과 상세 일치 | 10만 건 기준 p95 3초, 집계 일치 | **미검증** (10만 건 미만). 집계 일치는 1,000계약에서 확인 |
| PER-005 | 주요 API 14개 | 대기 | VU 50, 10분, 생각 시간 1~3초 | 미실행 | 대기 | 오류율 1% 미만, API별 p95 3초 | **미확인** (큰 데이터로 실행 예정) |
| PER-001 | PER-005와 같은 실행의 API별 p95 | 대기 | 위와 같음 | 미실행 | 대기 | API별 p95 2초 | **미확인** |
| PER-007 | 같은 월 재실행 (IF-API-45, 48), 멱등 조회 10종 EXPLAIN | 1,000계약 | API 경유 | 재실행 1회 | 업무 키 표 신규 0건. Seq Scan 0건. 첫 실행 184.6초, 재실행 45.7초 | 신규 0건, 저하는 합의 기준(값 없음) | 신규 0건 **충족**, 저하는 측정값만 |

이 결과서는 "600명 동시 접속 가능"을 주장하지 않는다. 600은 설계사 레코드 수이고(DAR-012, OI-024 프로젝트 가정) 동시 사용자는 데이터 규모와 분리해 50 VU로만 잰다(#673 시나리오 6).

## 2. 대상과 조건

| 항목 | 값 |
|---|---|
| 코드 기준 | 사전 측정: develop `25626562` + PR #872 `081465fb`(생성기) + PR #873 `10e0b799`(분개 상한 99,999, 채번 인덱스 V82)를 합친 임시 커밋 `40efe174`. 저장소에 올리지 않았다 |
| RC | `r2.0.0-rc1` 미발행. RC는 digest로 고정한 이미지로 잰다(`scripts/performance/README.md`) |
| 데이터 | `Dar012Runner`가 새 DB에서 적재, seed 20261118, 기준일 2026-09-10 [시드 명세 §50]. 정답표 일치 76/76, 집계 일치(`D12-1000-verification.json`) |
| PC | Windows 11, Docker Desktop 29.9(WSL2) CPU 16개, 메모리 7.6GiB. **다른 작업 동시 실행.** Gradle 빌드 2개, Testcontainers 시험, 다른 격리 PostgreSQL 컨테이너가 함께 돌았고 호스트 사용 가능 메모리가 0.9~3.3GB를 오갔다. 절대값으로 인용하지 않는다 |
| 네트워크 | k6, API, DB가 같은 Docker 네트워크에 있다. 호스트에 포트를 열지 않는다. SSH 터널과 외부 네트워크 지연이 없으므로 응답시간은 서버 처리와 컨테이너 사이 통신만 담는다 |
| 공유 개발계, 신한 서버 | **부하를 걸지 않았고 데이터를 넣지 않았다.** 스크립트에 대상 주소를 바꾸는 설정이 없다 |

### 신한 compose와 다른 점

힙과 메모리는 `infra/shinhan/compose.yaml`과 같다: API `-Xms256m -Xmx1024m -XX:MaxMetaspaceSize=384m`, `mem_limit 2g`, DB `postgres:17-alpine`, `mem_limit 1g`, `TZ=Asia/Seoul`, `SPRING_PROFILES_ACTIVE=prod`. 다른 점은 아래뿐이다.

| 항목 | compose | 이 측정 | 이유 |
|---|---|---|---|
| Flyway 위치 | `db/migration`, `db/demo`, `db/demo-agent` | `db/migration`, `db/demo` | 생성기 DB는 `db/demo-agent`를 읽지 않는다 [시드 명세 §50]. `agent01`은 격리 DB에서만 스크립트가 켠다 |
| 웹 계층 | nginx(web) 앞단 | 없음 | API 처리 시간만 잰다. 정적 파일과 프록시 지연은 포함하지 않는다 |
| 앱 형태 | RC 이미지(Tomcat WAR) | 사전 측정은 bootJar(내장 Tomcat), RC는 이미지 | 게시 이미지 `sha-2c460fd3`로 같은 스크립트의 smoke를 돌려 동작을 확인했다 |
| JVM 옵션 | 위 3개 | 위 3개 + `-Xlog:gc:file=/tmp/gc.log` | 힙 표본 |
| PostgreSQL | 기본값 | 기본값 + `pg_stat_statements`, `track_io_timing`, `track_wal_io_timing`, trust 인증 | DB 병목 지표. 포트를 열지 않아 trust로 둔다 |
| 일 배치 | 02:00 켜짐 | 꺼짐 | 측정 중 끼어들지 않게 한다 |
| AI 키, 파일 볼륨 | 있음 | 없음 | 대상 API에 쓰이지 않는다 |

## 3. 배치 (PER-003)

### 3-1. 측정표

| 계약 | 대상 | 스케줄 행 | 분개 | 분개 행 | 대사 | 예외 | 1,200% | 차익 | 전체 | 판정 | 비고 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| 1,000 | 1,007 | 28,168 | 4,378 | 8,756 | 3,871 | 4,228 | 2,012 | 1,006 | 184.6초 | 미검증 | 1회, RC 전 |

### 3-2. Step별 시간 (1,000계약, 초)

| Step | 시간 | 건수(write_count) |
|---|---:|---:|
| regenerateScheduleStep | 55.1 | 1,006 |
| capCheckStep (GA_TO_FC 16.6, INSURER_TO_GA 12.8) | 29.4 | 2,012 |
| arbitrageCheckStep | 10.8 | 0 |
| journalPostingStep | 61.4 | 4,378 |
| reconciliationStep | 21.5 | 3,871 |
| exceptionGenerationStep | 5.7 | 3,814 |
| 나머지 3개 Step | 0.2 | |
| **합계** | **184.6** | |

### 3-3. CPU, 메모리, DB 병목 (1,000계약)

| 지표 | 값 |
|---|---|
| API 컨테이너 | 메모리 최대 1,049MiB / 2GiB. CPU는 기동 뒤 배치 구간에서 대체로 30~70%(코어 기준 100%가 1개) |
| JVM | 힙 사용 최대 238MiB, 확보 257MiB, GC 60회(Full 0), 멈춤 합계 0.8초. OOM 없음 |
| DB 컨테이너 | 메모리 최대 135MiB / 1GiB. CPU 40~60% |
| SQL 실행 | 1,028종, 호출 136,467회, 실행 합계 64.9초(평균 0.48ms). **배치 184.6초의 약 1/3** |
| 커밋, WAL | 커밋 9,375, fsync 9,126회 합계 24.9초(평균 2.7ms). WAL 93MB. 적재 포함 누계 |
| 캐시 | 적중 99.98%, 임시 파일 0, deadlock 0 |

상위 SQL(적재와 검증 조회 포함 누계): `journal_line` INSERT 8,756회 6.7초, `schedule_line` INSERT 두 형태 합계 8.5초, 대사 예외 집계 1회 4.7초, `reconciliation_match` INSERT 4,380회 4.7초, `cap_check_detail` INSERT 두 형태 합계 5.7초. 분개 채번은 4,378회 0.3초(평균 0.08ms, PR #873 적용 뒤)로 상위권 밖이다. 전문은 `report.txt`.

## 4. API (PER-001, 004, 005)

### 4-1. 대시보드 (PER-004)

| 계약 | 스케줄 행 | VU | 횟수 | 평균 | p95 | 최대 | 오류 | KPI |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| 1,000 | 28,168 | 5 | 300 | 181ms | 328ms | 662ms | 0 | 6종 일치 |

집계 일치는 k6 `setup()`이 홈 KPI를 목록 API 건수와 비교한다(임계값 `kpi_consistent rate==1`). 1,000계약 값: `capViolation` 394, `capWarning` 411, `arbitrageCandidate` 12, `reconMismatch` 3,420, `journalImbalance` 0, `openException` 4,228. 원천 표 대조는 `Dar012Verifier.checkAggregateConsistency`가 한다. 대시보드 쿼리 중 `vw_journal_imbalance` 전체 집계가 가장 무겁다(1회 평균 65ms, 월과 무관한 전체 집계).

### 4-2. 동시 사용자 (PER-005, PER-001)

미실행. 큰 데이터셋에서 10분을 돈 뒤 API별 표를 넣는다. 대상 14개: 대시보드, 계약 목록과 상세, 지급 목록과 상세, 1,200% 목록, 실행 목록과 상세, 예외 목록, 분개 목록, 대사 실행 목록과 결과 목록, 설계사 홈과 명세 목록.

### 4-3. 한계

- **읽기 API만 잰다.** PER-001 세부내용의 "등록·수정"은 측정하지 않았다. 쓰기는 PER-007 재실행 1건뿐이다.
- 계정은 내부 1개(`settle01`), 설계사 1개(`agent01`)를 VU가 나눠 쓴다. 토큰은 `setup()`에서 계정당 1회 발급한다. 로그인 부하는 재지 않는다.
- 사용자 구성(내부 9 : 설계사 1), 생각 시간 1~3초, 대시보드 VU 5는 원문에 없는 값이다. 프로젝트 가정이며 합격 기준이 아니라 조건이다. 업무 규칙 아님, 가상 GA 정책으로 정함. 실제 사용 패턴 자료가 나오면 다시 정한다.
- DAR-012 데이터는 GA→설계사 지급을 만들지 않아 설계사 명세 행이 적고 그 단계 대사가 전부 `ACTUAL_MISSING`이다 [시드 명세 §50]. 예외와 위반 건수는 데이터 구성에서 나온 값이고 업무 사례가 아니다.
- PC 값이다. 신한 서버 성능으로 인용하지 않는다.

## 5. 재실행 멱등 (PER-007, #673 시나리오 3)

`run-k6.sh rerun`이 같은 월 통합검증을 실제 API(IF-API-45 생성, IF-API-48 기동)로 한 번 더 돌리고 fgc 스키마 모든 표의 행 수를 전후로 비교한다. 저장소 계층 멱등은 `BatchWriterIdempotencyIntegrationTest`(같은 실행, 같은 계약의 예외 중복 0)가 지킨다.

| 표 구분 | 표 | 1,000계약 재실행 증가 | 판정 |
|---|---|---:|---|
| 업무 키 (스케줄, 정산, 지급 건) | `schedule_header`, `schedule_line`, `journal_header`, `journal_line`, `commission_transaction`, `exception_case` | 0 | 충족 |
| 실행 이력 (실행마다 쌓이는 것이 설계) | `cap_check` +2,012, `cap_check_detail` +21,950, `arbitrage_check` +1,006, `reconciliation_run` +10, `reconciliation_result` +3,871, `reconciliation_match` +4,380, `exception_occurrence` +4,228, `validation_target` +1,007, `validation_run` +1, `audit_log` +14, `batch_*` | 있음 | 해당 없음. 유니크 키에 `validation_run_id`, `reconciliation_run_id`가 들어 있다 |

멱등 조회 실행 계획 (1,000계약, `per007-explain.txt`): 10개 중 Seq Scan 0건.

| 조회 | 실행 계획 |
|---|---|
| 스케줄 활성 운영본 | Index Scan `uq_schedule_header_active_operational` |
| 스케줄 회차 업무키 | Index Scan `uq_schedule_line_business` |
| 분개 원천키 (`findBySourceKey`) | Index Scan `uq_journal_source_revision` |
| 현재 기표 존재 확인 | Index Only Scan `uq_journal_current_posted_source` |
| 지급 건 원천키 | Index Scan `uq_commission_transaction_source` |
| 지급 확정 멱등키 | Index Scan `uq_commission_transaction_confirm_idempotency` |
| 1,200% UPSERT 키 | Index Scan `ix_cap_check_contract`(플래너 선택. 충돌 판정은 `uq_cap_check_monthly`) |
| 차익 UPSERT 키 | Index Scan `ix_arbitrage_contract`(플래너 선택. 충돌 판정은 `uq_arbitrage_check`) |
| 대사 결과 키 | Index Scan `uq_reconciliation_result` |
| 예외 업무키 | Index Scan `uq_exception_key` |

성능 저하는 "합의 기준"의 값이 원문에 없어 판정하지 않는다. 1,000계약 측정값: 첫 실행 184.6초, 재실행 45.7초. 재실행에서 분개 Step은 61.4초에서 0.8초(이미 기표된 원천을 건너뜀), 예외 Step은 5.7초에서 2.0초(새 예외 0건)다. 재실행은 JVM이 예열된 뒤라 직접 비율로 쓰지 않는다.

## 6. 목표 초과 항목, 원인, 개선안

지금까지 목표를 넘은 항목은 없다. 10만 건 측정 뒤 채운다(#673 시나리오 4).

## 7. RC 때 다시 할 일

`scripts/performance/README.md` 순서대로 1천 → 3천 → 3,600 → 1만2천. 담당은 #673 표(배치 @C4t4ddict, k6 @gayo73). 결과는 이 문서 표를 바꾸고 "RC 전 사전 측정" 표시를 지운다. DAR-012 인수조건의 단계별 시간과 조회 p95도 같은 표로 기록한다.
