#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
export XDG_RUNTIME_DIR="/run/user/$(id -u)"
export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
[[ $(id -un) == deploy ]] || { echo 'deploy 계정에서 실행해야 합니다'; exit 1; }
release_json=$(realpath "${1:?release.json 경로가 필요합니다}")
bundle_dir=$(dirname "$release_json")
source "$HOME/.config/fgc/site.env"
case "$HOST_ROLE" in combined|was|web) ;; *) exit 1;; esac
case "$ENV_NAME" in dev|stage|prod) ;; *) exit 1;; esac
[[ "$DOMAIN" =~ ^[a-z0-9.-]+$ ]] || exit 1
if [[ "$HOST_ROLE" == web ]]; then
    [[ "${FGC_TLS_ENABLED:-0}" == 1 ]] || { echo '운영 WEB의 인증서 부트스트랩과 TLS 설정을 먼저 완료하세요(§14)'; exit 1; }
    test -s "$HOME/letsencrypt/live/$DOMAIN/fullchain.pem"
    test -s "$HOME/letsencrypt/live/$DOMAIN/privkey.pem"
fi
[[ "$API_UPSTREAM" =~ ^[0-9.]+:[0-9]+$ || "$HOST_ROLE" == was ]] || exit 1
[[ "$API_MEMORY" =~ ^[0-9]+Mi$ && "$WEB_MEMORY" =~ ^[0-9]+Mi$ ]] || exit 1
pod_template="$bundle_dir/podman/fgc-app-$HOST_ROLE.yaml"
test -s "$pod_template" || { echo "Pod yaml 없음: $pod_template"; exit 1; }
exec 9>"$HOME/fgc/deploy.lock"
flock -n 9 || { echo '다른 배포가 실행 중입니다'; exit 1; }

# JSON을 shell source/eval로 실행하지 않는다.
mapfile -t values < <(python3 - "$release_json" <<'PY'
import json, re, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
assert re.fullmatch(r'[0-9a-f]{40}', d['commit'])
for name in ('api', 'web'):
    assert re.fullmatch(r'ghcr\.io/feegachu/fgc/' + name + r'@sha256:[0-9a-f]{64}', d[name])
print(d['commit']); print(d['api']); print(d['web'])
PY
)
[[ ${#values[@]} == 3 ]] || { echo 'manifest 검증 실패'; exit 1; }
commit=${values[0]}; api_image=${values[1]}; web_image=${values[2]}
unitdir="$HOME/.config/containers/systemd"
release_dir="$HOME/fgc/releases/$(date -u +%Y%m%dT%H%M%SZ)-${commit:0:12}"
mkdir -p "$release_dir/nginx/fgc.d" "$HOME/certbot-www" "$HOME/letsencrypt"
cp "$release_json" "$release_dir/release.json"
cp "$HOME/.config/fgc/site.env" "$release_dir/site.env"
sha256sum "$release_dir/site.env" "$0" "$pod_template"
if [[ -f "$unitdir/fgc-app.kube" ]]; then cp "$unitdir/fgc-app.kube" "$release_dir/old-fgc-app.kube"; fi
trap 'echo "배포 실패: 실제 Pod 상태를 확인하세요(podman pod ps). 기록: $release_dir" >&2' ERR

if [[ "$HOST_ROLE" != web ]]; then
    systemctl --user is-active --quiet fgc-db.service
    podman secret inspect fgc-app >/dev/null
    podman pull "$api_image"
fi
if [[ "$HOST_ROLE" != was ]]; then
    podman pull "$web_image"
    # 로그인 시도 제한: http 문맥(conf.d)에 zone, server 문맥(fgc.d)에 location. 업스트림 주소는 site.env 값.
    cat > "$release_dir/nginx/fgc-http.conf" <<'NGINX'
limit_req_zone $binary_remote_addr zone=fgc_login:10m rate=30r/m;
# HTTP Basic 인증 헤더가 붙은 요청만 접속 IP별로 센다(키가 빈 문자열이면 세지 않는다). §2 운영 Basic 정리 전까지의 방어
map $http_authorization $fgc_basic_key { "~*^Basic " $binary_remote_addr; default ""; }
limit_req_zone $fgc_basic_key zone=fgc_basic:10m rate=30r/m;
limit_req_status 429;
NGINX
    cat > "$release_dir/nginx/fgc.d/10-guard.conf" <<NGINX
# actuator는 밖으로 내보내지 않는다. health는 서버 안에서 API 포트로 직접 확인한다.
location ^~ /actuator { return 404; }
# server 단계 제한: 자체 limit_req가 없는 모든 location(API 프록시 포함)에 적용된다
limit_req zone=fgc_basic burst=10 nodelay;
location = /login {
    limit_req zone=fgc_login burst=10 nodelay;
    proxy_pass http://$API_UPSTREAM;
    proxy_http_version 1.1;
    proxy_set_header Host              \$http_host;
    proxy_set_header X-Real-IP         \$remote_addr;
    proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_set_header X-Forwarded-Host  \$http_host;
    proxy_set_header X-Forwarded-Port  \$fgc_forwarded_port;
}
location = /api/v1/auth/login {
    limit_req zone=fgc_login burst=10 nodelay;
    proxy_pass http://$API_UPSTREAM;
    proxy_http_version 1.1;
    proxy_set_header Host              \$http_host;
    proxy_set_header X-Real-IP         \$remote_addr;
    proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_set_header X-Forwarded-Host  \$http_host;
    proxy_set_header X-Forwarded-Port  \$fgc_forwarded_port;
}
NGINX
    if [[ "$HOST_ROLE" == web ]]; then
        cat > "$release_dir/nginx/fgc.d/00-tls.conf" <<NGINX
listen 443 ssl;
ssl_certificate     /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
ssl_protocols TLSv1.2 TLSv1.3;
# 주의: location 안에 add_header가 있으면 그 location에서는 아래 헤더가 상속되지 않는다(nginx 규칙)
add_header Strict-Transport-Security "max-age=31536000" always;
add_header X-Content-Type-Options nosniff always;
add_header X-Frame-Options DENY always;
location ^~ /.well-known/acme-challenge/ { root /var/www/certbot; }
# 80으로 온 요청은 HTTPS로. 인증서 갱신 challenge도 리다이렉트를 따라 443에서 위 location이 응답한다
if (\$scheme = http) { return 301 https://\$host\$request_uri; }
NGINX
    fi
fi

# Pod yaml 빈칸 채우기. 남은 __빈칸__ 이 있으면 실패한다.
api_bind_ip=${API_BIND%:*}; api_bind_port=${API_BIND##*:}
web_bind_ip=${WEB_BIND%:*}; web_bind_port=${WEB_BIND##*:}
export FGC_T="$pod_template" FGC_OUT="$release_dir/fgc-app.yaml" \
    FGC_API_IMAGE="$api_image" FGC_WEB_IMAGE="$web_image" FGC_ENV_NAME="$ENV_NAME" \
    FGC_API_MEMORY="$API_MEMORY" FGC_WEB_MEMORY="$WEB_MEMORY" FGC_API_UPSTREAM="$API_UPSTREAM" \
    FGC_API_BIND_IP="$api_bind_ip" FGC_API_BIND_PORT="$api_bind_port" \
    FGC_WEB_BIND_IP="$web_bind_ip" FGC_WEB_BIND_PORT="$web_bind_port" \
    FGC_RELEASE_DIR="$release_dir" FGC_HOME="$HOME"
python3 - <<'PY'
import os, pathlib, re
s = pathlib.Path(os.environ['FGC_T']).read_text(encoding='utf-8')
for key in ('API_IMAGE', 'WEB_IMAGE', 'ENV_NAME', 'API_MEMORY', 'WEB_MEMORY', 'API_UPSTREAM',
            'API_BIND_IP', 'API_BIND_PORT', 'WEB_BIND_IP', 'WEB_BIND_PORT', 'RELEASE_DIR', 'HOME'):
    s = s.replace(f'__{key}__', os.environ['FGC_' + key])
left = re.findall(r'__[A-Z_]+__', s)
assert not left, f'채우지 못한 빈칸: {left}'
pathlib.Path(os.environ['FGC_OUT']).write_text(s, encoding='utf-8')
PY

# Pod yaml의 hostPath는 SELinux가 자동으로 라벨을 바꿔 주지 않는다. 컨테이너가 읽을 수 있게 직접 붙인다(:z와 같은 공유 라벨).
chcon -R -t container_file_t "$release_dir/nginx" "$HOME/letsencrypt" "$HOME/certbot-www"

if [[ "$HOST_ROLE" != was ]]; then
    # 이미지의 nginx 템플릿 + fgc.d 추가 설정을 실제 이미지로 문법 검사한 뒤 교체한다.
    podman run --rm --network none -e "API_UPSTREAM=$API_UPSTREAM" \
        -v "$release_dir/nginx/fgc.d:/etc/nginx/fgc.d:ro" \
        -v "$release_dir/nginx/fgc-http.conf:/etc/nginx/conf.d/fgc-http.conf:ro" \
        -v "$HOME/letsencrypt:/etc/letsencrypt:ro" \
        "$web_image" nginx -t
fi

pod_network='Network=fgc.network'
db_deps=$'Wants=fgc-db.service\nAfter=fgc-db.service'
if [[ "$HOST_ROLE" == web ]]; then
    # pasta는 접속자 IP를 그대로 넘긴다. bridge 네트워크의 rootlessport는 모든 접속을 127.0.0.1로 바꿔
    # 로그인 제한이 전원 합산되고 감사로그 client_ip가 전부 같아진다(Podman 문서).
    pod_network='Network=pasta'
    db_deps=''
fi
cat > "$release_dir/fgc-app.kube" <<EOF
[Unit]
Description=FGC app Pod $commit
$db_deps

[Kube]
Yaml=$release_dir/fgc-app.yaml
$pod_network
LogDriver=journald

[Service]
TimeoutStartSec=900

[Install]
WantedBy=default.target
EOF

check_http() {
    local url=$1 expected=$2 result
    for n in $(seq 1 60); do
        result=$(curl --connect-timeout 3 --max-time 5 -sS -o /dev/null -w '%{http_code}' "$url" || true)
        if [[ "$result" == "$expected" ]]; then return 0; fi
        sleep 3
    done
    echo "health 실패: $url HTTP=$result" >&2
    return 1
}

install -m 600 "$release_dir/fgc-app.kube" "$unitdir/fgc-app.kube"
systemctl --user daemon-reload
systemctl --user restart fgc-app.service
if [[ "$HOST_ROLE" != web ]]; then
    check_http "http://127.0.0.1:$api_bind_port/actuator/health" 200
    curl -fsS "http://127.0.0.1:$api_bind_port/actuator/health" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
fi
# 토큰 로그인 경로 검사: 없는 계정으로 시도해 401(아이디·비밀번호 불일치)이면 출처 검사(Origin·포트)는 통과한 것이다.
# 403이면 X-Forwarded-Port·RemoteIpValve 설정이 어긋났다(§9.2). 실제 계정을 쓰지 않으므로 계정 잠금·세션 무효화가 없다.
check_token_origin() {
    local base=$1 origin=$2 code
    shift 2
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@" -X POST "$base/api/v1/auth/login" \
        -H 'Content-Type: application/json' -H "Origin: $origin" -H 'X-FGC-Client: web' \
        -d '{"loginId":"__deploy_probe__","password":"x"}' || true)
    [[ "$code" == 401 ]] || { echo "토큰 로그인 경로 검사 실패: HTTP $code (403이면 §9.2 포트 전달 확인)" >&2; return 1; }
}
if [[ "$HOST_ROLE" == combined ]]; then
    check_http "http://127.0.0.1:$web_bind_port/login" 200
    check_token_origin "http://127.0.0.1:$web_bind_port" "http://127.0.0.1:$web_bind_port"
elif [[ "$HOST_ROLE" == web ]]; then
    # 공인 DNS 경로 시험은 Jenkins(§12)에서 별도로 수행한다. nginx가 막 떴을 수 있으므로 최대 3분 재시도한다
    for n in $(seq 1 60); do
        if curl --fail --silent --show-error --max-time 5 --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/login" >/dev/null; then break; fi
        (( n < 60 )) || { echo "health 실패: https://$DOMAIN/login" >&2; exit 1; }
        sleep 3
    done
    check_token_origin "https://$DOMAIN" "https://$DOMAIN" --resolve "$DOMAIN:443:127.0.0.1"
fi
if [[ -f "$HOME/fgc/current.json" ]]; then cp "$HOME/fgc/current.json" "$HOME/fgc/previous.json"; fi
cp "$release_json" "$HOME/fgc/current.json"
printf '%s\n' "$release_dir" > "$HOME/fgc/current-dir.txt"
# 디스크 정리: 릴리스 폴더는 최근 10개(현재·이전 포함), Jenkins 전송 폴더는 7일, 쓰지 않는 이미지는 7일 지난 것만 지운다.
# 롤백용 이전 이미지가 지워져도 bundle의 digest로 GHCR에서 다시 받는다(§17.4: 배포한 digest는 GHCR에서 지우지 않는다).
ls -1dt "$HOME"/fgc/releases/*/ | tail -n +11 | xargs -r rm -rf --
find "$HOME/fgc/incoming" -mindepth 1 -maxdepth 1 -mtime +7 -exec rm -rf -- {} + 2>/dev/null || true
podman image prune -a -f --filter until=168h >/dev/null || true
df -h "$HOME" | tail -1
podman pod ps
podman ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'
echo "SUCCESS $ENV_NAME $HOST_ROLE $commit"