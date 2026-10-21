#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
commit=${1:?Successful CI commit required}
ci_url=${2:?CI run URL required}
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit'; exit 1; }
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
state="$HOME/fgc-stg"
[[ -s "$state/.env" && -s "$state/compose.yaml" ]] || { echo 'Existing Shinhan deployment and .env required'; exit 1; }
exec 9>"$state/deploy.lock"
flock -n 9 || { echo 'Another Shinhan deployment is running'; exit 1; }
release="$state/releases/$(date -u +%Y%m%dT%H%M%SZ)-${commit:0:12}-${GITHUB_RUN_ID:-manual}-${GITHUB_RUN_ATTEMPT:-1}"
mkdir -p "$release/nginx" "$release/previous" "$state/backups"
trap 'echo "Shinhan deployment failed; inspect DB/API/web state. Release: $release. DB is not automatically rolled back." >&2' ERR

for name in api web; do
    image="ghcr.io/feegachu/fgc/$name:sha-$commit"
    docker pull "$image"
    docker image inspect "$image" > "$release/$name-inspect.json"
done
docker run --rm --network none --entrypoint cat \
    "ghcr.io/feegachu/fgc/web:sha-$commit" \
    /etc/nginx/templates/default.conf.template > "$release/nginx/default.conf.template"
python3 "$root/infra/scripts/shinhan-release.py" "$commit" "$ci_url" "$release"
cp "$root/infra/shinhan/compose.yaml" "$release/compose.yaml"
cp "$root/infra/shinhan/nginx-guard.conf" "$release/nginx/guard.conf"

# Candidate config is validated without printing interpolated secrets.
compose=(docker compose --project-directory "$state" --env-file "$state/.env" --env-file "$release/release.env" -f "$release/compose.yaml")
"${compose[@]}" config --quiet

# Save the current deployment metadata/config and a consistent DB dump before Flyway runs.
cp "$state/compose.yaml" "$release/previous/compose.yaml"
if [[ -f "$state/release.env" ]]; then cp "$state/release.env" "$release/previous/"; fi
if [[ -f "$state/current.json" ]]; then cp "$state/current.json" "$release/previous/"; fi
cp -R "$state/nginx" "$release/previous/nginx"
backup="$state/backups/$(basename "$release").dump"
"${compose[@]}" exec -T db pg_dump -U fgc -d fgc -Fc > "$backup.partial"
test -s "$backup.partial"
mv "$backup.partial" "$backup"
printf '%s\n' "$backup" > "$release/db-backup-path.txt"

# Keep the existing project/volume names and host-only API port; do not run down -v.
cp "$release/compose.yaml" "$state/compose.yaml"
cp "$release/release.env" "$state/release.env"
cp "$release/nginx/"* "$state/nginx/"
compose=(docker compose --project-directory "$state" --env-file "$state/.env" --env-file "$state/release.env" -f "$state/compose.yaml")
"${compose[@]}" up -d db api
ready=0
for n in $(seq 1 90); do
    health=$(curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:18081/actuator/health 2>/dev/null || true)
    if grep -Eq '\"status\"[[:space:]]*:[[:space:]]*\"UP\"' <<< "$health"; then ready=1; break; fi
    sleep 2
done
if [[ "$ready" != 1 ]]; then
    "${compose[@]}" logs --tail=120 api
    echo 'API readiness failed; web was not upgraded'
    exit 1
fi
"${compose[@]}" run --rm -T --no-deps web nginx -t
"${compose[@]}" up -d --force-recreate --no-deps web
base=http://127.0.0.1
for n in $(seq 1 20); do
    code=$(curl --connect-timeout 2 --max-time 5 -sS -o /dev/null -w '%{http_code}' "$base/app/login" || true)
    [[ "$code" == 200 ]] && break
    sleep 2
done
[[ "$code" == 200 ]] || { echo "React HTTP=$code"; exit 1; }
domain=xn--v42bn2m21nmmd.shinhanacademy.co.kr
code=$(curl --connect-timeout 3 --max-time 10 -sS -o /dev/null -w '%{http_code}' \
    -X POST "$base/api/v1/auth/login" -H "Host: $domain" -H "Origin: https://$domain" \
    -H 'Content-Type: application/json' -H 'X-FGC-Client: web' \
    -d '{"loginId":"__shinhan_deploy_probe__","password":"x"}')
[[ "$code" == 401 ]] || { echo "Token login route HTTP=$code (expected 401)"; exit 1; }
cp "$release/release.json" "$state/current.json"
"${compose[@]}" ps
echo "SUCCESS Shinhan $commit"
