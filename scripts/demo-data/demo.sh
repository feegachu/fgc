#!/usr/bin/env bash
# 신한 EC2: bash scripts/demo-data/demo.sh rebuild (reset/load/backup/restore)
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 -X utf8 "$script_dir/demo.py" ec2 "$@"
