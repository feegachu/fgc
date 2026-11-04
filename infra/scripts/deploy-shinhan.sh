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
# A failure that can follow the migration ends with false, not exit: exit skips this trap and its rollback order.
trap 'echo "Shinhan production promotion failed; inspect DB/API/web state. Release: $release. DB is not automatically rolled back. To roll back after fgc.flyway_schema_history has 66, 72 or 73, restore the backup named in $release/db-backup-path.txt first, then start previous/compose.yaml." >&2' ERR
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
# SRC-065: this server is public and the demo seed passwords are published in the repository.
# Refuse to deploy while a demo account still accepts one, or while V66 would give agent01 one.
db_query() { "${compose[@]}" exec -T db psql -U fgc -d fgc -XAtq -v ON_ERROR_STOP=1; }
published_logins=$(python3 "$helper" published-logins "$root")
unprotected=$(python3 "$helper" unprotected-sql "$root" | db_query)
if [[ -n "$unprotected" ]]; then
    echo "Demo accounts still accept a published seed password: $unprotected. Change or lock them first (seed spec section 5, SRC-065)." >&2
    exit 1
fi
agent_unset=$(db_query <<< "SELECT EXISTS (SELECT 1 FROM fgc.app_user WHERE login_id = 'agent01' AND password_hash = '!') AND NOT EXISTS (SELECT 1 FROM fgc.flyway_schema_history WHERE version = '66' AND success);")
if [[ "$agent_unset" == t ]]; then
    echo "agent01 has no password yet and V66 would activate it with the published demo password. Set its Secret password first (seed spec section 5)." >&2
    exit 1
fi
# db/demo-agent (V66, V72, V73) joined this DB after V77: apply exactly those once out of order.
# Every later deploy keeps Flyway's ordering check, so a late lower-numbered migration still stops the API.
pending=$(db_query <<< "SELECT 3 - count(DISTINCT version) FROM fgc.flyway_schema_history WHERE version IN ('66', '72', '73') AND success;")
if [[ "$pending" == 0 ]]; then export FGC_FLYWAY_OUT_OF_ORDER=false; else export FGC_FLYWAY_OUT_OF_ORDER=true; fi

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
    false
fi
# The running web already proxies to the new API, so a failed recheck closes the public entry (SRC-065).
unprotected=$(python3 "$helper" unprotected-sql "$root" | db_query)
if [[ -n "$unprotected" ]]; then
    "${compose[@]}" stop web
    echo "Demo accounts accept a published seed password after migration: $unprotected. Public web stopped; protect them and rerun (SRC-065)." >&2
    false
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
unknown_login='{"loginId":"__shinhan_deploy_probe__","password":"x"}'
check_login() {
    local base=$1 body=$2 code
    code=$(curl --connect-timeout 3 --max-time 15 -sS -o /dev/null -w '%{http_code}' \
        -X POST "$base/api/v1/auth/login" -H "Host: $domain" -H "Origin: $origin" \
        -H 'Content-Type: application/json' -H 'X-FGC-Client: web' \
        -d @- <<< "$body")
    [[ "$code" == 401 ]] || { echo "Token login route HTTP=$code (expected 401)"; return 1; }
}
check_http http://127.0.0.1/login 200
check_login http://127.0.0.1 "$unknown_login"
# SRC-065: the hash check misses a published password hashed again, and an unknown account's 401 proves nothing.
# Each demo account must refuse it. Bodies go through stdin, so the password reaches neither argv nor this log.
while IFS=$'\t' read -r account body; do
    if ! check_login http://127.0.0.1 "$body"; then
        "${compose[@]}" stop web
        echo "Published seed password was not refused for $account. Public web stopped; protect it and rerun (SRC-065)." >&2
        false
    fi
done <<< "$published_logins"
check_http "$origin/login" 200
check_login "$origin" "$unknown_login"
cp "$release/release.json" "$state/current.json"
cp "$release/promotion.json" "$state/current-promotion.json"
"${compose[@]}" ps
echo "SUCCESS Shinhan production $commit"
