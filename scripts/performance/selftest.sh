#!/usr/bin/env bash
# 판정과 요약 함수 자체 시험. Docker 없이 돈다. 사용: scripts/performance/selftest.sh
source "$(dirname "$0")/common.sh"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fails=0
expect() { # expect <기대 문자열> <실제>
  case "$2" in
    *"$1"*) ;;
    *) echo "실패: [$1] 없음: $2"; fails=$((fails + 1)) ;;
  esac
}

# PER-003 경계: 10만 건과 600초
expect 'PER-003: 미검증' "$(per003_verdict 99999 3000 100 COMPLETED)"
expect 'PER-003: 충족' "$(per003_verdict 100000 3000 600 COMPLETED)"
expect 'PER-003: 충족' "$(per003_verdict 90000 100000 600 COMPLETED)"
expect 'PER-003: 미충족' "$(per003_verdict 100000 3000 601 COMPLETED)"
expect 'PER-003: 실패' "$(per003_verdict 500000 12000 10 FAILED)"

cat >"$tmp/stats.csv" <<'CSV'
10:00:00,fgc-perf-app,392.96%,1.5GiB / 2GiB
10:00:05,fgc-perf-app,7.04%,512MiB / 2GiB
10:00:05,fgc-perf-db,12.5%,102400KiB / 1GiB
CSV
expect 'fgc-perf-app: 표본 2개, CPU 평균 200%, 최대 393%, 메모리 최대 1536 MiB' "$(stats_summary "$tmp/stats.csv")"
expect 'fgc-perf-db: 표본 1개, CPU 평균 12%, 최대 12%, 메모리 최대 100 MiB' "$(stats_summary "$tmp/stats.csv")"

cat >"$tmp/gc.log" <<'LOG'
[0.026s][info][gc] Using G1
[0.485s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 23M->3M(257M) 3.5ms
[9.000s][info][gc] GC(1) Pause Full (G1 Compaction Pause) 900M->300M(1024M) 96.5ms
[9.500s][info][gc] GC(2) Concurrent Mark Cycle 12.0ms
LOG
expect 'JVM GC: 2회(Full 1회), 멈춤 합계 0.1초, 힙 최대 사용 900 MiB, 최대 확보 1024 MiB' "$(gc_summary "$tmp/gc.log")"

cat >"$tmp/explain.txt" <<'TXT'
== uq_a 설명 ==
 Index Scan using uq_a on a  (cost=0.27..8.29 rows=1 width=8) (actual time=0.1..0.1 rows=1 loops=1)
== uq_b 설명 ==
 Limit  (cost=0.27..4.29 rows=1 width=4)
   ->  Index Only Scan using uq_b on b  (cost=0.27..4.29 rows=1 width=4)
== uq_c 설명 ==
 Seq Scan on c  (cost=0.00..6.59 rows=1 width=8)
TXT
expect 'uq_b: Index Only Scan using uq_b on b' "$(per007_explain_verdict "$tmp/explain.txt")"
expect 'uq_c: Seq Scan on c' "$(per007_explain_verdict "$tmp/explain.txt")"
expect 'PER-007 인덱스: 3개 조회 중 Seq Scan 1건' "$(per007_explain_verdict "$tmp/explain.txt")"

printf 'schedule_line,10\ncap_check,5\nagent,3\n' >"$tmp/before.csv"
printf 'schedule_line,10\ncap_check,8\nagent,3\n' >"$tmp/after.csv"
expect '늘어난 표: cap_check 5 -> 8 (+3)' "$(rerun_verdict "$tmp/before.csv" "$tmp/after.csv")"
expect 'PER-007 신규 생성: 업무 키 표 0건 (충족)' "$(rerun_verdict "$tmp/before.csv" "$tmp/after.csv")"
printf 'schedule_line,11\ncap_check,5\nagent,3\n' >"$tmp/after.csv"
expect 'PER-007 신규 생성: 업무 키 표 1건 (미충족)' "$(rerun_verdict "$tmp/before.csv" "$tmp/after.csv")"

[ "$fails" -eq 0 ] && echo "selftest 통과" || { echo "selftest 실패 $fails건"; exit 1; }
