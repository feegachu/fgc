# run-batch.sh, run-k6.sh가 함께 쓴다. fgc-perf-* 컨테이너만 만들고 지운다.
# DB와 API는 호스트에 포트를 열지 않고, 공유 개발계나 신한 서버를 가리키는 설정값을 받지 않는다(#673).
set -euo pipefail
unset OPENAI_API_KEY SPRING_AI_OPENAI_API_KEY
export MSYS_NO_PATHCONV=1 # Git Bash가 /app.jar 같은 컨테이너 경로를 Windows 경로로 바꾸지 않게 한다

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

NET=fgc-perf
DB=fgc-perf-db
APP=fgc-perf-app
K6=fgc-perf-k6
PG_IMAGE=postgres:17-alpine@sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b
JRE_IMAGE=eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
K6_IMAGE=grafana/k6:2.2.0@sha256:9bd01d6941fca969cb61bb57d2da5ee9b385fe2aa8881df3798c196564d6ace6
JAR=build/libs/fgc-0.0.1-SNAPSHOT.jar
JVM_OPTS="-Xms256m -Xmx1024m -XX:MaxMetaspaceSize=384m" # infra/shinhan/compose.yaml의 api와 같은 값
# PER-007 "지급 건, 정산, 스케줄"의 업무 키 표. 같은 월을 다시 돌려도 늘면 안 된다.
PER007_KEYED_TABLES="schedule_header schedule_line journal_header journal_line commission_transaction exception_case"

psql_db() { docker exec -i "$DB" psql -h 127.0.0.1 -U fgc -d fgc -v ON_ERROR_STOP=1 "$@"; }

host_memory() {
  if command -v powershell.exe >/dev/null; then
    powershell.exe -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -d '\r'
  else
    free -m | awk '/^Mem:/{print $7}'
  fi
}

new_run_dir() {
  RUN_DIR="build/performance/$(date +%Y%m%d-%H%M%S)-$1"
  mkdir -p "$RUN_DIR"
}

# 빌드를 기다린 시간이 섞이지 않게 재기 직전에 남긴다.
record_conditions() {
  {
    echo "시각: $(date '+%F %T %z')"
    echo "커밋: $(git rev-parse HEAD)$(git diff --quiet HEAD || echo ' (커밋 안 된 수정 있음)')"
    echo "앱: ${API_IMAGE:-$JAR (현재 커밋 bootJar)}"
    echo "API: $JVM_OPTS, 메모리 2g. DB: $PG_IMAGE, 메모리 1g"
    docker info --format 'Docker: CPU {{.NCPU}}개, 메모리 {{.MemTotal}} 바이트, 서버 {{.ServerVersion}}'
    echo "시작 시 호스트 사용 가능 메모리: $(host_memory) MB"
    echo "시작 시 떠 있는 컨테이너:"
    docker stats --no-stream --format '  {{.Name}} CPU {{.CPUPerc}} 메모리 {{.MemUsage}}'
  } >"$RUN_DIR/conditions.txt"
}

# API_IMAGE를 주면 그 이미지(RC는 Tomcat WAR)로, 없으면 현재 커밋의 bootJar로 띄운다.
build_app() {
  if [ -n "${API_IMAGE:-}" ]; then
    [[ "$API_IMAGE" == *@sha256:* ]] || { echo "API_IMAGE는 digest로 고정한다: $API_IMAGE" >&2; exit 2; }
    docker pull -q "$API_IMAGE" >/dev/null
  else
    ${GRADLE_CMD:-./gradlew --no-daemon} bootJar >"$RUN_DIR/gradle.log" 2>&1 || { tail -20 "$RUN_DIR/gradle.log"; exit 1; }
  fi
}

# 단계마다 새 DB에서 처음부터 만든다(시드명세 §50). 호스트에 포트를 열지 않으므로 인증은 trust로 둔다.
fresh_db() {
  docker rm -fv "$APP" "$DB" "$K6" >/dev/null 2>&1 || true
  docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
  docker run -d --name "$DB" --network "$NET" --memory 1g \
    -e POSTGRES_DB=fgc -e POSTGRES_USER=fgc -e POSTGRES_HOST_AUTH_METHOD=trust -e TZ=Asia/Seoul \
    "$PG_IMAGE" -c shared_preload_libraries=pg_stat_statements -c track_io_timing=on -c track_wal_io_timing=on >/dev/null
  until psql_db -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements' >/dev/null 2>&1; do sleep 1; done
}

# start_app <환경변수=값>... compose와 다른 점은 Flyway 위치(db/demo-agent 없음, 시드명세 §50)와 GC 로그 뿐이다.
start_app() {
  local args=(--name "$APP" --network "$NET" --memory 2g
    -e "SPRING_DATASOURCE_URL=jdbc:postgresql://$DB:5432/fgc?currentSchema=fgc"
    -e SPRING_DATASOURCE_USERNAME=fgc
    -e SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo
    -e "JAVA_TOOL_OPTIONS=$JVM_OPTS -Xlog:gc:file=/tmp/gc.log"
    -e TZ=Asia/Seoul)
  local kv
  for kv in "$@"; do args+=(-e "$kv"); done
  docker rm -f "$APP" >/dev/null 2>&1 || true
  if [ -n "${API_IMAGE:-}" ]; then
    docker create "${args[@]}" "$API_IMAGE" >/dev/null
  else
    docker create "${args[@]}" "$JRE_IMAGE" java -jar /app.jar --server.port=8080 >/dev/null
    docker cp -q "$JAR" "$APP:/app.jar"
  fi
  docker start "$APP" >/dev/null
}

start_stats() {
  (while :; do
    docker stats --no-stream --format "$(date +%T),{{.Name}},{{.CPUPerc}},{{.MemUsage}}" "$APP" "$DB" 2>/dev/null || true
    sleep 5
  done) >"$RUN_DIR/stats.csv" &
  STATS_PID=$!
}

stop_stats() {
  [ -n "${STATS_PID:-}" ] || return 0
  kill "$STATS_PID" 2>/dev/null || true
  wait "$STATS_PID" 2>/dev/null || true
  STATS_PID=
}

# docker stats 표본 CSV(시각,이름,CPU%,사용량 / 한도)를 컨테이너별로 요약한다.
stats_summary() {
  awk -F, '{
    gsub(/%/, "", $3); split($4, m, " "); v = m[1]
    mib = v ~ /GiB/ ? v * 1024 : v ~ /KiB/ ? v / 1024 : v + 0
    n[$2]++; c[$2] += $3; if ($3 + 0 > cmax[$2]) cmax[$2] = $3 + 0; if (mib > mmax[$2]) mmax[$2] = mib
  } END {
    for (k in n) printf "%s: 표본 %d개, CPU 평균 %.0f%%, 최대 %.0f%%, 메모리 최대 %.0f MiB\n", k, n[k], c[k] / n[k], cmax[k], mmax[k]
  }' "$1" | sort
}

# -Xlog:gc 줄의 "123M->45M(256M) 5.1ms"를 요약한다.
gc_summary() {
  awk 'match($0, /[0-9]+M->[0-9]+M\([0-9]+M\) [0-9.]+ms/) {
    split(substr($0, RSTART, RLENGTH), a, /M->|M\(|M\) |ms/)
    n++; p += a[4]; if (a[1] + 0 > hi) hi = a[1] + 0; if (a[3] + 0 > cap) cap = a[3] + 0; if (/Pause Full/) full++
  } END {
    printf "JVM GC: %d회(Full %d회), 멈춤 합계 %.1f초, 힙 최대 사용 %d MiB, 최대 확보 %d MiB\n", n, full, p / 1000, hi, cap
  }' "$1"
}

# 앱 컨테이너의 로그와 GC 기록을 남기고 메모리 초과 흔적을 알린다(#673 시나리오 5).
collect_app() {
  docker logs "$APP" >"$RUN_DIR/app.log" 2>&1 || true
  docker cp -q "$APP:/tmp/gc.log" "$RUN_DIR/gc.log" 2>/dev/null || : >"$RUN_DIR/gc.log"
  {
    stats_summary "$RUN_DIR/stats.csv"
    gc_summary "$RUN_DIR/gc.log"
    echo "메모리 초과: 컨테이너 OOMKilled=$(docker inspect -f '{{.State.OOMKilled}}' "$APP"), 로그 OutOfMemoryError $(grep -c OutOfMemoryError "$RUN_DIR/app.log" || true)건"
    echo "끝날 때 호스트 사용 가능 메모리: $(host_memory) MB"
  } | tee "$RUN_DIR/resources.txt"
}

# per003_verdict <스케줄 행> <처리 계약> <배치 초> <배치 상태>. 부족하면 외삽하지 않고 미검증으로 적는다(PER-003, #673).
per003_verdict() {
  local rows=$1 contracts=$2 seconds=$3 status=$4
  if [ "$status" != COMPLETED ]; then
    echo "PER-003: 실패 (배치 $status, 스케줄 $rows행, 계약 $contracts건, ${seconds}초)"
  elif [ "$rows" -lt 100000 ] && [ "$contracts" -lt 100000 ]; then
    echo "PER-003: 미검증 (스케줄 $rows행, 계약 $contracts건으로 10만 건 미만, ${seconds}초. 외삽하지 않음)"
  elif [ "$seconds" -le 600 ]; then
    echo "PER-003: 충족 (스케줄 $rows행, 계약 $contracts건, ${seconds}초 <= 600초)"
  else
    echo "PER-003: 미충족 (스케줄 $rows행, 계약 $contracts건, ${seconds}초 > 600초)"
  fi
}

# per007-explain.sql 결과에서 절마다 어떤 인덱스를 탔는지 적는다. 행이 아주 적으면 플래너가 Seq Scan을 고르므로 큰 데이터로 판정한다.
per007_explain_verdict() {
  awk '/^== / { name = $2; order[++total] = name; next }
    /Scan/ && !(name in plan) { sub(/^[ ->]*/, ""); sub(/ +\(cost.*/, ""); plan[name] = $0; if (/^Seq Scan/) seq++ }
    END {
      for (i = 1; i <= total; i++) printf "  %s: %s\n", order[i], plan[order[i]]
      printf "PER-007 인덱스: %d개 조회 중 Seq Scan %d건\n", total, seq
    }' "$1"
}

# 재실행 전후 행 수 비교. 늘어난 표를 모두 적고, 업무 키 표가 늘었으면 미충족으로 적는다(PER-007, #673 시나리오 3).
rerun_verdict() {
  awk -F, -v keyed="$PER007_KEYED_TABLES" '
    BEGIN { n = split(keyed, k, " "); for (i = 1; i <= n; i++) iskey[k[i]] = 1 }
    NR == FNR { before[$1] = $2; next }
    { d = $2 - before[$1]; if (d != 0) { printf "늘어난 표: %s %d -> %d (+%d)%s\n", $1, before[$1], $2, d, $1 in iskey ? " [업무 키 표]" : ""; if ($1 in iskey) bad += d } }
    END { printf "PER-007 신규 생성: 업무 키 표 %d건 (%s)\n", bad, bad == 0 ? "충족" : "미충족" }
  ' "$1" "$2"
}
