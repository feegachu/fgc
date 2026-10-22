#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
promotion=${1:?Verified stage-fgc promotion.json required}
public_url=${FGC_PUBLIC_URL:?Set the actual PRD_URL in the Jenkins job}
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
helper="$root/infra/scripts/shinhan_release.py"
commit=$(python3 "$helper" commit "$promotion")
# Preserve the existing directory/project/volume; this server now serves production.
state="$HOME/fgc-stg"
[[ -s "$state/.env" && -s "$state/compose.yaml" ]] || { echo 'Existing Shinhan deployment and .env required'; exit 1; }
exec 9>"$state/deploy.lock"
flock -n 9 || { echo 'Another Shinhan deployment is running'; exit 1; }
release="$state/releases/$(date -u +%Y%m%dT%H%M%SZ)-${commit:0:12}-${BUILD_NUMBER:-manual}"
mkdir -p "$release/nginx" "$release/previous" "$state/backups"
trap 'echo "Shinhan production promotion failed; inspect DB/API/web state. Release: $release. DB is not automatically rolled back." >&2' ERR
python3 "$helper" origin "$public_url" > "$release/origin.txt"
mapfile -t public < "$release/origin.txt"
origin=${public[0]}
domain=${public[1]}
python3 "$helper" prepare "$promotion" "$release"
mapfile -t images < <(sed -n 's/^[A-Z_]*=//p' "$release/release.env")
[[ ${#images[@]} == 2 ]] || { echo 'Both staging image digests required'; exit 1; }
for index in 0 1; do
    name=api; [[ "$index" == 0 ]] || name=web
    docker pull "${images[$index]}"
    docker image inspect "${images[$index]}" > "$release/$name-inspect.json"
done
docker run --rm --network none --entrypoint cat "${images[1]}" \
    /etc/nginx/templates/default.conf.template > "$release/nginx/default.conf.template"
python3 "$helper" verify "$release"
cp "$root/infra/shinhan/compose.yaml" "$release/compose.yaml"
cp "$root/infra/shinhan/nginx-guard.conf" "$release/nginx/guard.conf"
compose=(docker compose --project-directory "$state" --env-file "$state/.env" --env-file "$release/release.env" -f "$release/compose.yaml")
"${compose[@]}" config --quiet

# Back up the current database and deployment before applying any Flyway changes.
cp "$state/compose.yaml" "$release/previous/compose.yaml"
if [[ -f "$state/release.env" ]]; then cp "$state/release.env" "$release/previous/"; fi
if [[ -f "$state/current.json" ]]; then cp "$state/current.json" "$release/previous/"; fi
if [[ -f "$state/current-promotion.json" ]]; then cp "$state/current-promotion.json" "$release/previous/"; fi
cp -R "$state/nginx" "$release/previous/nginx"
backup="$state/backups/$(basename "$release").dump"
"${compose[@]}" exec -T db pg_dump -U fgc -d fgc -Fc > "$backup.partial"
test -s "$backup.partial"
mv "$backup.partial" "$backup"
printf '%s\n' "$backup" > "$release/db-backup-path.txt"
cp "$release/compose.yaml" "$state/compose.yaml"
cp "$release/release.env" "$state/release.env"
cp "$release/nginx/"* "$state/nginx/"
compose=(docker compose --project-directory "$state" --env-file "$state/.env" --env-file "$state/release.env" -f "$state/compose.yaml")
"${compose[@]}" up -d db api
ready=0
for n in $(seq 1 90); do
    health=$(curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:18081/actuator/health 2>/dev/null || true)
    if grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<< "$health"; then ready=1; break; fi
    sleep 2
done
if [[ "$ready" != 1 ]]; then
    "${compose[@]}" logs --tail=120 api
    echo 'API readiness failed; web was not upgraded'
    exit 1
fi
"${compose[@]}" run --rm -T --no-deps web nginx -t
"${compose[@]}" up -d --force-recreate --no-deps web
check_http() {
    local url=$1 expected=$2 code
    for n in $(seq 1 20); do
        code=$(curl --connect-timeout 3 --max-time 10 -sS -o /dev/null -w '%{http_code}' "$url" || true)
        [[ "$code" == "$expected" ]] && return 0
        sleep 2
    done
    echo "HTTP check failed: $url HTTP=$code (expected $expected)" >&2
    return 1
}
check_login() {
    local base=$1 code
    code=$(curl --connect-timeout 3 --max-time 15 -sS -o /dev/null -w '%{http_code}' \
        -X POST "$base/api/v1/auth/login" -H "Host: $domain" -H "Origin: $origin" \
        -H 'Content-Type: application/json' -H 'X-FGC-Client: web' \
        -d '{"loginId":"__shinhan_deploy_probe__","password":"x"}')
    [[ "$code" == 401 ]] || { echo "Token login route HTTP=$code (expected 401)"; return 1; }
}
check_http http://127.0.0.1/app/login 200
check_login http://127.0.0.1
check_http "$origin/app/login" 200
check_login "$origin"
cp "$release/release.json" "$state/current.json"
cp "$release/promotion.json" "$state/current-promotion.json"
"${compose[@]}" ps
echo "SUCCESS Shinhan production $commit"
