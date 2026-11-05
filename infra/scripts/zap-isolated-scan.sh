#!/usr/bin/env bash
# OWASP ZAP 스캔. 격리 로컬 전용 (TER-009, #677).
# 공유 개발계, 신한 서버, 외부 주소에는 돌리지 않는다. 대상이 이 PC 의 loopback 이 아니면 docker 를 부르기 전에 거부한다.
#
#   up        자체 PostgreSQL + API + web 을 전용 네트워크로 띄우고 web 만 127.0.0.1:$ZAP_PORT 에 연다. 이전 스택이 남아 있으면 지우지 않고 실패하니 먼저 down 한다
#   baseline  수동 관찰 스캔(공격 요청 없음)
#   api       /v3/api-docs 기반 능동 스캔(공격 요청을 보낸다). 로그인 토큰을 붙여 업무 API 까지 본다
#   down      컨테이너, 네트워크, 볼륨 삭제. 능동 스캔이 쓴 자료도 함께 사라진다
#
#   API_IMAGE=ghcr.io/feegachu/fgc/api:sha-<SHA> WEB_IMAGE=ghcr.io/feegachu/fgc/web:sha-<SHA> bash infra/scripts/zap-isolated-scan.sh up
#   bash infra/scripts/zap-isolated-scan.sh baseline
#   ZAP_LOGIN_PASSWORD=<시드 계정 비밀번호> bash infra/scripts/zap-isolated-scan.sh api
#   bash infra/scripts/zap-isolated-scan.sh down
#
# 환경변수: ZAP_TARGET(이미 띄운 로컬 서버를 볼 때), ZAP_PORT(기본 18770), ZAP_IMAGE, ZAP_LOGIN_ID(기본 settle01), ZAP_ACTIVE_MINUTES(기본 25)
# 보고서는 build/zap/ 에 쌓인다(gitignore). 공개 PR, 이슈에는 결과서의 요약만 옮기고 원본은 올리지 않는다.
set -Eeuo pipefail

command=${1:-}
port=${ZAP_PORT:-18770}
target=${ZAP_TARGET:-http://127.0.0.1:$port}

# 사용자 정보(@), 다른 도메인의 접두어로 우회하지 못하게 전체 형식을 고정한다.
if [[ ! "$target" =~ ^http://(127\.0\.0\.1|localhost):[0-9]{1,5}$ ]]; then
    echo "거부: 대상은 http://127.0.0.1:<포트> 또는 http://localhost:<포트> 만 허용합니다." >&2
    exit 2
fi

# 스캔이 실제 LLM 을 부르거나 키가 격리 환경으로 들어가지 않게 지운다. 아래 docker run 도 -e 로 적은 값만 넘긴다.
unset OPENAI_API_KEY SPRING_AI_OPENAI_API_KEY
export MSYS_NO_PATHCONV=1 # Git Bash 가 컨테이너 안 경로(/zap/wrk)를 Windows 경로로 바꾸지 않게 한다
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
root=$(pwd -W 2>/dev/null || pwd) # Windows 의 docker 는 C:/ 형식 경로를 받는다
out=$root/build/zap
zap_image=${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:stable}
network=fgc-zap
python=$(command -v python || command -v python3) # Windows 의 python3 별칭은 Python Install Manager 를 불러 저장소 안에 Python/ 을 만든다

up() {
    : "${API_IMAGE:?API_IMAGE 를 지정하세요}" "${WEB_IMAGE:?WEB_IMAGE 를 지정하세요}"
    local db_password jwt_secret
    db_password=$(openssl rand -hex 16)
    jwt_secret=$(openssl rand -hex 32)
    docker network create "$network" >/dev/null
    # 뜨다 실패하면 남은 자원을 지운다. 네트워크를 만든 뒤에 걸어 이미 떠 있는 스택을 지우지 않는다.
    trap down ERR
    docker run -d --name fgc-zap-db --network "$network" --memory=512m \
        -e POSTGRES_DB=fgc -e POSTGRES_USER=fgc -e "POSTGRES_PASSWORD=$db_password" postgres:17-alpine >/dev/null
    for _ in $(seq 1 30); do
        docker exec fgc-zap-db pg_isready -U fgc -d fgc >/dev/null 2>&1 && break
        sleep 2
    done
    # OPENAI 키를 넘기지 않는다. 스캔이 외부 LLM 비용을 쓰지 않는다.
    docker run -d --name fgc-zap-api --network "$network" --memory=1g \
        -e SPRING_PROFILES_ACTIVE=prod \
        -e "SPRING_DATASOURCE_URL=jdbc:postgresql://fgc-zap-db:5432/fgc?currentSchema=fgc" \
        -e SPRING_DATASOURCE_USERNAME=fgc -e "SPRING_DATASOURCE_PASSWORD=$db_password" \
        -e "SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/demo,classpath:db/demo-agent" \
        -e "FGC_JWT_SECRET=$jwt_secret" -e FGC_STORAGE_ROOT=/tmp/fgc-files \
        -e "JAVA_TOOL_OPTIONS=-Xmx512m -XX:MaxMetaspaceSize=256m" "$API_IMAGE" >/dev/null
    # 신한 서버와 같은 guard(/actuator 404)를 붙여 실제 공개면을 본다.
    docker run -d --name fgc-zap-web --network "$network" --memory=128m -p "127.0.0.1:$port:80" \
        -e API_UPSTREAM=fgc-zap-api:8080 -v "$root/infra/shinhan/nginx-guard.conf:/etc/nginx/fgc.d/guard.conf:ro" \
        "$WEB_IMAGE" >/dev/null
    for _ in $(seq 1 90); do
        # 미인증 401 이 API 까지 연결됐다는 뜻이다. 뜨는 중에는 nginx 가 502 를 준다.
        if [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$target/api/v1/auth/me" || true)" == 401 ]]; then
            trap - ERR
            echo "준비됨: $target"
            return 0
        fi
        sleep 4
    done
    docker logs --tail 40 fgc-zap-api >&2
    echo "API 가 뜨지 않았습니다." >&2
    # 이 함수 안에서 건 ERR trap 은 return 1 로 끝날 때 돌지 않으니 직접 지운다.
    trap - ERR
    down
    return 1
}

down() {
    docker rm -fv fgc-zap-web fgc-zap-api fgc-zap-db >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
}

summarize() {
    "$python" - "$out/$1.json" <<'PY'
import collections, json, sys
alerts = [alert for site in json.load(open(sys.argv[1], encoding="utf-8"))["site"] for alert in site.get("alerts", [])]
print(dict(collections.Counter(alert["riskdesc"].split(" ")[0] for alert in alerts)))
for alert in sorted(alerts, key=lambda a: -int(a["riskcode"])):
    print(f'{alert["riskdesc"]} | {alert["count"]} | {alert["alertRef"]} | {alert["name"]}')
PY
}

scan() {
    local name=$1 status=0
    shift
    mkdir -p "$out"
    # Docker Desktop 컨테이너의 127.0.0.1 은 컨테이너 자신이라 호스트 loopback 으로 가는 이름을 쓴다.
    local zap_network=(--network host) zap_url=$target
    if [[ "$(docker info --format '{{.OperatingSystem}}')" == *"Docker Desktop"* ]]; then
        zap_network=()
        zap_url=$(sed -E 's#//(127\.0\.0\.1|localhost):#//host.docker.internal:#' <<<"$target")
    fi
    # API scan 은 문서의 servers 주소로 공격 요청을 보낸다. 문서가 무엇을 적든 대상에 고정한다.
    [[ "$name" == zap-api-scan ]] && set -- "$@" -O "$zap_url"
    trap "chmod go-rwx '$out'; exit 130" HUP INT TERM # 중단돼도 보고서 폴더를 열어 두지 않는다
    chmod a+rwx "$out" # 컨테이너의 zap 사용자가 보고서를 쓰는 동안만 연다
    docker run --rm ${zap_network[@]+"${zap_network[@]}"} --memory=1500m -v "$out:/zap/wrk:rw" \
        -e "ZAP_AUTH_HEADER_VALUE=${ZAP_AUTH_HEADER_VALUE:-}" "$zap_image" \
        "$name.py" -t "$zap_url${SCAN_PATH:-}" -I -J "$name.json" -r "$name.html" "$@" || status=$?
    trap - HUP INT TERM
    chmod go-rwx "$out"
    summarize "$name"
    return "$status"
}

login_token() {
    : "${ZAP_LOGIN_PASSWORD:?ZAP_LOGIN_PASSWORD 를 지정하세요}"
    "$python" -c 'import json, os; print(json.dumps({"loginId": os.environ.get("ZAP_LOGIN_ID", "settle01"), "password": os.environ["ZAP_LOGIN_PASSWORD"]}))' |
        curl -fsS -X POST "$target/api/v1/auth/login" -H "Origin: $target" -H 'X-FGC-Client: web' \
            -H 'Content-Type: application/json' --data-binary @- |
        "$python" -c 'import json, sys; print(json.load(sys.stdin)["data"]["accessToken"])'
}

case "$command" in
    up) up ;;
    down) down ;;
    # 호출자 환경의 SCAN_PATH 가 대상 뒤에 붙으면 @evil.example 처럼 loopback 검사를 우회한다. api 는 아래에서 값을 정한다.
    baseline) SCAN_PATH= scan zap-baseline -m 2 -z "-Xmx768m -silent" ;;
    api)
        # 대입을 나눠야 set -e 가 로그인 실패에서 멈춘다. 빈 토큰으로 스캔하면 인증 API 를 못 본 채 통과로 보인다.
        token=$(login_token)
        [[ -n "$token" ]] || { echo "로그인 토큰을 받지 못했습니다." >&2; exit 1; }
        # shortcut: 토큰을 갱신하지 않고 수명(30분)에 5분 여유만 둔다. 기동과 문서 가져오기는 제한 밖이고 측정한 전체 시간은 7분이다. 30분에 닿는 규모가 되면 갱신을 넣는다.
        ZAP_AUTH_HEADER_VALUE="Bearer $token" SCAN_PATH=/v3/api-docs \
            scan zap-api-scan -f openapi -z "-Xmx1g -silent -config scanner.maxScanDurationInMins=${ZAP_ACTIVE_MINUTES:-25}"
        ;;
    *)
        echo "사용법: $0 up|baseline|api|down" >&2
        exit 64
        ;;
esac
