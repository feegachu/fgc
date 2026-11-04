# 성능, 부하 시험 스크립트 (TER-008, #673)

PER-001, PER-003, PER-004, PER-005, PER-007을 격리 Docker 환경에서 잰다. 결과는 `docs/FGC_성능시험_결과서_v1_0.md`에 옮긴다.

**공유 개발계와 신한 서버에 부하를 걸지 않는다.** 대상 주소를 바꾸는 설정이 없고, DB와 API는 호스트에 포트를 열지 않는다. DAR-012 데이터는 그 DB들에 적재하지 않는다(시드 명세 §50).

## 준비

- Docker, bash(Windows는 Git Bash). k6, PostgreSQL, JRE는 digest로 고정한 이미지를 쓰므로 따로 설치하지 않는다. 고정 값은 `common.sh`에 있다.
- 계정은 환경 변수로만 넘긴다. **비밀번호를 저장소, 이슈, PR에 쓰지 않는다.** 값은 `src/main/resources/db/demo/V3__seed_demo_data.sql` 머리 주석에 있고 이 격리 DB에만 쓴다.

```bash
export FGC_PERF_USER=settle01 FGC_PERF_AGENT=agent01
export FGC_PERF_PASSWORD='<시드 비밀번호>'
```

스크립트가 `OPENAI_API_KEY`를 지우고 컨테이너에는 필요한 값만 넘긴다. 필요한 생성기(`dataset` 패키지)는 PR #872, 분개 상한과 채번 인덱스는 `fix/673-journal-monthly-capacity`에 있으므로 **둘이 들어간 커밋에서** 돌린다.

## RC 실행 순서 (11/6~11/10)

1. **대상 커밋을 고정한다.** RC 태그로 worktree를 만들어 그 안에서 돌린다. `build/performance/`가 커밋별로 나뉜다.

   ```bash
   git fetch origin --tags && git worktree add ../fgc-rc <RC 태그>
   cd ../fgc-rc
   ```

2. **앱을 고른다.** RC 이미지로 재려면 digest를 준다. 없으면 현재 커밋의 bootJar를 빌드해 쓴다. 어느 쪽이든 힙, 메모리 한도, 프로필은 같다.

   ```bash
   export API_IMAGE='ghcr.io/feegachu/fgc/api@sha256:<RC digest>'
   ```

3. **단계마다 아래를 한 벌 돌린다.** `run-batch.sh`가 DB를 새로 만들므로 단계를 건너뛰어도 된다. 1천 → 3천 → 3,600(스케줄 10만 행 이상) → 1만2천 순서다.

   ```bash
   scripts/performance/run-batch.sh 1000            # 데이터 적재 + 월 통합검증. PER-003 판정, PER-007 EXPLAIN
   scripts/performance/run-k6.sh smoke              # 14개 API 한 번씩
   scripts/performance/run-k6.sh dashboard          # PER-004
   scripts/performance/run-k6.sh concurrent         # PER-005, PER-001. 50명 10분
   scripts/performance/run-k6.sh rerun              # PER-007. 같은 월 재실행. 마지막에 돌린다
   ```

   `run-k6.sh`는 재기 전에 smoke로 한 바퀴 예열한다. 시나리오를 돌릴 때마다 API를 새로 띄우고 끝나면 내린다. DB는 남긴다. 임계값을 넘으면 k6가 종료 코드 99로 끝난다.

4. **결과서를 갱신한다.** `build/performance/<시각>-<종류>/`의 값을 옮기고, 표의 "RC 전 사전 측정"을 "RC"로 바꾼다. `conditions.txt`의 커밋, 이미지, 다른 컨테이너와 `resources.txt`의 호스트 CPU, 메모리를 조건 칸에 적는다. 다른 작업과 겹치면 값이 크게 흔들리므로 RC는 PC에서 다른 작업을 멈추고 잰다.

5. **정리한다.**

   ```bash
   docker rm -fv fgc-perf-app fgc-perf-db fgc-perf-k6
   docker network rm fgc-perf
   ```

## 결과 파일

| 파일 | 내용 |
|---|---|
| `conditions.txt` | 커밋, 앱, 힙, 메모리 한도, Docker 자원, 시작 시 다른 컨테이너와 사용 가능 메모리 |
| `report.txt` | 전체와 Step별 시간, 처리 행 수, `pg_stat_statements` 상위, 커밋과 WAL, 캐시 적중률, 순차 스캔 상위 |
| `resources.txt`, `stats.csv`, `host.csv`, `gc.log` | `docker stats` 5초 표본 요약, 호스트 CPU와 사용 가능 메모리 30초 표본, 메모리 초과 여부, JVM GC 요약 |
| `verdict.txt` | PER-003 판정, PER-007 인덱스 사용과 신규 생성 건수 |
| `summary.json`, `k6.log` | k6 요약과 API별 p95, PER-001, 004, 005 판정 |
| `D12-*.json`, `D12-*-answers.csv`, `D12-*-verification.json` | 생성기 메타데이터, 정답표, 정답표 비교와 집계 일치 |
| `per007-explain.txt`, `counts-*.csv` | 멱등 조회 실행 계획, 재실행 전후 행 수 |

## 판정 규칙

원문 값만 쓴다. 원문에 없는 기준은 합격을 선언하지 않고 측정값만 남긴다.

| 요구사항 | 기준 | 스크립트가 내는 판정 |
|---|---|---|
| PER-003 | 계약 또는 스케줄 10만 건 이상, 10분 이내 | 10만 건 미만이면 "미검증"(외삽하지 않음), 그 이상이면 600초로 판정 |
| PER-004 | 10만 건 기준, 대시보드 p95 3초, 집계값과 상세 합계 일치 | 10만 건 미만이면 "미검증". KPI 6종을 목록 API 건수와 비교 |
| PER-005 | 동시 50명 10분, 오류율 1% 미만, API p95 3초 | 임계값과 판정 줄 |
| PER-001 | 주요 API p95 2초 | 같은 실행의 API별 p95. 읽기 API만 잰다 |
| PER-007 | 재실행 신규 생성 0건, 인덱스 사용 | 업무 키 표 증가 건수와 조회별 실행 계획. 성능 저하 "합의 기준"은 원문에 값이 없어 측정값만 남긴다 |

실행마다 늘어나는 표(`cap_check`, `arbitrage_check`, `reconciliation_*`, `exception_occurrence`, `validation_*`, `batch_*`)는 실행 이력이라 업무 키 표에서 뺐다. 전부 `verdict.txt`에 건수로 나온다. 재실행 시나리오는 실제 API(IF-API-45, 48)를 쓴다. 저장소 계층의 멱등 시험은 `BatchWriterIdempotencyIntegrationTest`다.

## 개발 중 확인

`scripts/performance/selftest.sh`가 판정과 요약 함수를 Docker 없이 시험한다. Gradle을 다른 방식으로 부르거나(동시 실행을 제한한 PC) 이미 빌드한 jar를 쓰려면 `GRADLE_CMD`를 준다(`GRADLE_CMD=true`는 빌드를 건너뛴다).
