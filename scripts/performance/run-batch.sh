#!/usr/bin/env bash
# PER-003, DAR-012 측정. 새 격리 DB에 DAR-012 데이터를 넣고 월 통합검증을 돌려 시간과 병목을 기록한다.
# 사용: run-batch.sh <계약 수> [검증월 yyyy-MM, 기본 2026-08]. README.md 참고.
source "$(dirname "$0")/common.sh"

CONTRACTS=${1:?사용: run-batch.sh <계약 수> [검증월 yyyy-MM]}
MONTH=${2:-2026-08}

new_run_dir "batch-$CONTRACTS"
echo "결과 위치: $RUN_DIR"
build_app
fresh_db
record_conditions
# generateDar012 태스크(build.gradle)와 같은 진입점이다. Gradle을 빼서 API와 같은 컨테이너, 메모리 한도, DB 경로로 잰다.
start_app SPRING_PROFILES_ACTIVE=dar012 \
  "FGC_DAR012_DATASETID=D12-$CONTRACTS" "FGC_DAR012_CONTRACTS=$CONTRACTS" \
  FGC_DAR012_RUNVALIDATION=true "FGC_DAR012_VALIDATIONMONTH=$MONTH-01" FGC_DAR012_OUTPUTDIR=/tmp/dar012
trap stop_stats EXIT
start_stats
code=$(docker wait "$APP")
stop_stats
echo "생성기 종료 코드: $code"
collect_app
docker cp -q "$APP:/tmp/dar012/." "$RUN_DIR/" 2>/dev/null || true
docker rm -f "$APP" >/dev/null

# 생성기나 배치가 실패해도 남은 표로 보고하고 판정한다(#673 시나리오 4, 5).
psql_db -f - <scripts/performance/sql/batch-report.sql 2>&1 | tee "$RUN_DIR/report.txt" || true
IFS='|' read -r rows contracts seconds status < <(psql_db -At -f - <scripts/performance/sql/batch-verdict.sql 2>/dev/null | tr -d '\r') || true
per003_verdict "${rows:-0}" "${contracts:-0}" "${seconds:-0}" "${status:-NONE}" | tee "$RUN_DIR/verdict.txt"
IFS='|' read -r from to < <(psql_db -At -c "SELECT to_char(start_time, 'HH24:MI:SS'), to_char(end_time, 'HH24:MI:SS') FROM fgc.batch_job_execution ORDER BY job_execution_id LIMIT 1" 2>/dev/null | tr -d '\r') || true
if [ -n "${to:-}" ]; then batch_window_summary "$RUN_DIR/stats.csv" "$RUN_DIR/host.csv" "$from" "$to" | tee -a "$RUN_DIR/resources.txt"; fi

if [ "$code" -eq 0 ]; then
  psql_db -f - <scripts/performance/sql/per007-explain.sql >"$RUN_DIR/per007-explain.txt"
  per007_explain_verdict "$RUN_DIR/per007-explain.txt" | tee -a "$RUN_DIR/verdict.txt"
  grep -q 'PER-003: 미충족' "$RUN_DIR/verdict.txt" && code=99 # 목표 초과는 k6 임계값 초과와 같은 코드로 알린다
fi
exit "$code"
