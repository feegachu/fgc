#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
[[ $(id -un) == student ]] || { echo 'Run as student, without sudo bash'; exit 1; }
command -v docker >/dev/null
command -v python3 >/dev/null
docker info >/dev/null
sudo -v
runner="$HOME/actions-runner-shinhan"
mkdir -p "$runner"
cd "$runner"
if [[ ! -f .runner ]]; then
    archive=actions-runner-linux-x64-2.338.0.tar.gz
    curl -fL --connect-timeout 10 --max-time 600 \
        "https://github.com/actions/runner/releases/download/v2.338.0/$archive" -o "$archive"
    printf '%s  %s\n' af4b794c1bc41d73d40535e3fe092a39f9679cd8d965954c2aca25a05ca41d32 "$archive" | sha256sum -c -
    tar -xzf "$archive"
    sudo ./bin/installdependencies.sh
    echo 'Open https://github.com/feegachu/fgc/settings/actions/runners/new (Linux / x64).'
    echo 'Copy only the token after --token from the Configure command; do not send it in chat.'
    read -r -s -p 'GitHub runner registration token: ' token </dev/tty
    printf '\n'
    trap 'unset token' EXIT
    ./config.sh --unattended --url https://github.com/feegachu/fgc --token "$token" \
        --name shinhan-fgc --labels shinhan-fgc --work _work
    unset token
fi
if [[ ! -f .service ]]; then sudo ./svc.sh install student; fi
sudo ./svc.sh start
sudo ./svc.sh status
echo 'Runner registered. Confirm shinhan-fgc is Idle in GitHub Settings > Actions > Runners.'
