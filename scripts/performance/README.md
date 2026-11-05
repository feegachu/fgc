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

DB와 API 컨테이너는 `infra/shinhan/compose.yaml`과 같은 힙, 메모리 한도를 쓴다. DB `/dev/shm`은 256MB로 신한 compose와 같다(`common.sh`의 `DB_SHM`). 스크립트가 `OPENAI_API_KEY`를 지우고 컨테이너에는 필요한 값만 넘긴다.

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

3. **단계마다 아래를 한 벌 돌린다.** `run-batch.sh`가 DB를 새로 만들므로 단계를 건너뛰어도 된다. RC 이미지에서 처음이면 60계약으로 한 번 돌려 생성기와 API가 뜨는지 본 뒤 1천 → 3천 → 3,600(스케줄 10만 행 이상) → 1만2천 순서로 올린다.

   ```bash
   scripts/performance/run-batch.sh 1000            # 데이터 적재 + 월 통합검증. PER-003 판정, PER-007 EXPLAIN
   scripts/performance/run-k6.sh smoke              # 14개 API 한 번씩
   scripts/performance/run-k6.sh dashboard          # PER-004, FUN-080
   scripts/performance/run-k6.sh concurrent         # PER-005, PER-001. 50명 10분
   scripts/performance/run-k6.sh rerun              # PER-007. 같은 월 재실행. 실행이 하나 늘어 KPI 대조 대상이 바뀌므로 마지막에 돌린다
   ```

   배치 시간은 사전 측정 기준 1천 3분, 3천 6분, 3,600 6~9분, 1만2천 33분이고 적재와 기동에 1~2분이 더 든다. `concurrent`는 12분이다.

   `run-k6.sh`는 재기 전에 smoke로 한 바퀴 예열한다. 시나리오를 돌릴 때마다 API를 새로 띄우고 끝나면 내린다. DB는 남긴다. 임계값을 넘으면 k6가 종료 코드 99로 끝난다. `run-batch.sh`는 PER-003 원문 기준 미충족이면 99(사전 측정의 1만2천 단계가 여기에 해당한다), 생성기나 배치가 실패하면 1이다. `rerun`은 실행 이력 표 밖의 표가 늘면 1이다.

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
| `resources.txt`, `stats.csv`, `host.csv`, `gc.log` | `docker stats` 5초 표본 요약(`run-batch.sh`는 배치 구간 요약을 따로 적는다), 호스트 CPU와 사용 가능 메모리 30초 표본, 메모리 초과 여부, JVM GC 요약 |
| `verdict.txt` | PER-003 판정, PER-007 인덱스 사용과 신규 생성 건수 |
| `summary.json`, `k6.log` | k6 요약과 API별 p95, PER-001, 004, 005 판정 |
| `D12-*.json`, `D12-*-answers.csv`, `D12-*-verification.json` | 생성기 메타데이터, 정답표, 정답표 비교와 집계 일치 |
| `per007-explain.txt`, `counts-*.csv` | 멱등 조회 실행 계획, 재실행 전후 행 수 |

## 판정 규칙

원문 값만 쓴다. 원문에 없는 기준은 합격을 선언하지 않고 측정값만 남긴다.

| 요구사항 | 기준 | 스크립트가 내는 판정 |
|---|---|---|
| PER-003 | 계약 또는 스케줄 10만 건 이상, 10분 이내 | 판정 단위는 스케줄 행. 10만 행 미만이면 "미검증"(외삽하지 않음). 10만 행 이상이면 원문 기준 판정을 항상 적고, 10만 행 이상 15만 행 미만(3,600계약)은 RC 수용 단계 판정을 함께 적는다(PM 판단, 팀 공유 전). 상수는 `common.sh`의 `PER003_*`. 계약 10만 건은 재지 않는다 |
| PER-004 | 10만 건 기준, 대시보드 p95 3초, 집계값과 상세 합계 일치 | 데이터 규모 구분은 PER-005와 같다. KPI 5종을 목록 API 건수와 비교. `journalImbalance`는 홈이 전체, 상세가 실행 범위라 값만 남기고 일치 판정에 넣지 않는다 |
| FUN-080 | DAR-012에서 PER-004 기준(p95 3초)으로 관리자 홈 조회 측정, 홈과 예외함 건수 일치, 600명 모두 검색 | IF-API-68, 69, 07의 p95. 데이터 규모 구분은 PER-005와 같다. 600명 전원을 이름으로 검색해 코드가 나오는지 확인 |
| PER-005 | 동시 50명 10분, 오류율 1% 미만, API p95 3초 | 임계값과 판정 줄. 요청 수와 오류율은 setup을 뺀 시나리오 요청 기준. 10만 행 미만은 "미검증", 15만 행 이상은 "판정 단계 아님" |
| PER-001 | 주요 API p95 2초 | 같은 실행의 API별 p95. 읽기 API만 재므로 "부분 검증"으로 적는다. 데이터 규모 구분은 PER-005와 같다 |
| PER-007 | 재실행 신규 생성 0건, 인덱스 사용 | 실행 이력 표 밖의 증가 건수, 끝난 실행 재요청 거절, 조회별 실행 계획. 성능 저하 "합의 기준"은 원문에 값이 없어 측정값만 남긴다 |

실행마다 늘어나도 되는 표는 `common.sh`의 `PER007_HISTORY_TABLES`에 있고, 그 밖의 표는 늘면 미충족이다. 전부 `verdict.txt`에 건수로 나온다. 재실행 시나리오는 실제 API(IF-API-45, 48)를 쓴다. 저장소 계층의 멱등 시험은 `BatchWriterIdempotencyIntegrationTest`다.

## 개발 중 확인

`scripts/performance/selftest.sh`가 판정과 요약 함수를 Docker 없이 시험한다. Gradle을 다른 방식으로 부르거나(동시 실행을 제한한 PC) 이미 빌드한 jar를 쓰려면 `GRADLE_CMD`를 준다(`GRADLE_CMD=true`는 빌드를 건너뛴다).
