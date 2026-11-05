#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
phase=$(python3 -c 'import json; print(json.load(open("deployment.json"))["phase"])')
override=()
case "$phase" in
  health-only) override=(-f shinhan/health-only.override.yaml) ;;
  full) ;;
  *) echo "Unknown deployment phase; inspect deployment.json" >&2; exit 1 ;;
esac
if [[ $# == 0 ]]; then set -- ps; fi
exec docker compose -p fgc-monitoring -f compose.yaml -f shinhan/compose.yaml "${override[@]}" "$@"
