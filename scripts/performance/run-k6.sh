#!/usr/bin/env bash
# PER-001, 004, 005, 007 측정. run-batch.sh가 만든 격리 DB에 API를 띄워 k6를 같은 Docker 네트워크에서 돌린다.
# 터널이나 외부 네트워크를 지나지 않으므로 응답시간은 서버 처리 시간과 컨테이너 사이 통신만 담는다.
# 사용: run-k6.sh <smoke|dashboard|concurrent|rerun> [검증월 yyyy-MM, 기본 2026-08]. README.md 참고.
source "$(dirname "$0")/common.sh"

SCENARIO=${1:?사용: run-k6.sh <smoke|dashboard|concurrent|rerun> [검증월 yyyy-MM]}
case "$SCENARIO" in smoke | dashboard | concurrent | rerun) ;; *) echo "알 수 없는 시나리오: $SCENARIO" >&2; exit 2 ;; esac
export SCENARIO MONTH=${2:-2026-08}
export FGC_PERF_USER=${FGC_PERF_USER:?내부 계정을 FGC_PERF_USER로 준다}
export FGC_PERF_AGENT=${FGC_PERF_AGENT:?설계사 계정을 FGC_PERF_AGENT로 준다}
export FGC_PERF_PASSWORD=${FGC_PERF_PASSWORD:?비밀번호를 FGC_PERF_PASSWORD로 준다. 저장소에 쓰지 않는다}
[ "$(docker inspect -f '{{.State.Running}}' "$DB" 2>/dev/null)" = true ] || { echo "$DB가 없다. run-batch.sh를 먼저 돌린다." >&2; exit 2; }

new_run_dir "k6-$SCENARIO"
echo "결과 위치: $RUN_DIR"
build_app

# agent01은 db/demo-agent 없이는 DISABLED다(시드명세 §50). 이 격리 DB에서만 내부 계정과 같은 비밀번호로 켠다.
psql_db -q -v "user_id=$FGC_PERF_USER" -v "agent_id=$FGC_PERF_AGENT" -f - <<'SQL'
UPDATE fgc.app_user
   SET account_status = 'ACTIVE',
       password_hash = (SELECT password_hash FROM fgc.app_user WHERE login_id = :'user_id')
 WHERE login_id = :'agent_id' AND password_hash = '!';
SQL

record_conditions
# prod 프로필은 서명 키가 있어야 뜬다. 이 실행에서만 쓰는 임의 값이다.
start_app SPRING_PROFILES_ACTIVE=prod FGC_BATCH_DAILYCHANGEDCONTRACT_ENABLED=false \
  "FGC_JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '[:space:]')"
trap 'stop_stats; docker rm -fv "$APP" "$K6" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 150); do
  docker logs "$APP" 2>&1 | grep -q 'Started .* in ' && break
  [ "$(docker inspect -f '{{.State.Running}}' "$APP")" = true ] || { docker logs --tail 40 "$APP"; echo "API가 뜨지 않았다" >&2; exit 1; }
  sleep 2
done

run_k6() { # run_k6 <시나리오> <로그 파일>
  docker rm -f "$K6" >/dev/null 2>&1 || true
  docker create --name "$K6" --network "$NET" -e "SCENARIO=$1" -e MONTH -e FGC_PERF_USER -e FGC_PERF_AGENT -e FGC_PERF_PASSWORD \
    -e "SCHEDULE_ROWS=$SCHEDULE_ROWS" "$K6_IMAGE" run --quiet /home/k6/fgc-load.js >/dev/null
  docker cp -q scripts/performance/k6/fgc-load.js "$K6:/home/k6/fgc-load.js"
  local code=0
  docker start -a "$K6" >"$2" 2>&1 || code=$?
  return "$code"
}

SCHEDULE_ROWS=$(psql_db -At -c 'SELECT count(*) FROM fgc.schedule_line' | tr -d '\r')
# 콜드 스타트가 p95에 섞이지 않게 재기 전에 한 바퀴 돈다.
if [ "$SCENARIO" != smoke ]; then
  run_k6 smoke "$RUN_DIR/warmup.log" || { cat "$RUN_DIR/warmup.log"; echo "예열 smoke 실패" >&2; exit 1; }
fi

psql_db -q -c 'SELECT pg_stat_statements_reset()' >/dev/null
[ "$SCENARIO" != rerun ] || psql_db -At -f - <scripts/performance/sql/table-counts.sql >"$RUN_DIR/counts-before.csv"
start_stats
code=0
run_k6 "$SCENARIO" "$RUN_DIR/k6.log" || code=$?
stop_stats
cat "$RUN_DIR/k6.log"
docker cp -q "$K6:/home/k6/summary.json" "$RUN_DIR/summary.json" 2>/dev/null || true
collect_app

if [ "$SCENARIO" = rerun ]; then
  psql_db -At -f - <scripts/performance/sql/table-counts.sql >"$RUN_DIR/counts-after.csv"
  rerun_verdict "$RUN_DIR/counts-before.csv" "$RUN_DIR/counts-after.csv" | tee "$RUN_DIR/verdict.txt"
  psql_db -f - <scripts/performance/sql/batch-report.sql >"$RUN_DIR/report.txt"
  if grep -q '(미충족)' "$RUN_DIR/verdict.txt"; then code=1; fi
else
  psql_db -f - <<'SQL' >"$RUN_DIR/report.txt"
SELECT round((total_exec_time / 1000)::numeric, 1) AS total_s, calls, round(mean_exec_time::numeric, 2) AS mean_ms,
       rows, shared_blks_read AS blks_read, left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query
  FROM pg_stat_statements
 ORDER BY total_exec_time DESC
 LIMIT 10;
SQL
fi
echo "k6 종료 코드: $code (99면 임계값 초과). 결과: $RUN_DIR"
exit "$code"
