#!/usr/bin/env bash
# Linux / Git Bash 入口，测试流程与 Windows 共用。
# python verification/run_verify.py 可直接在 Windows PowerShell 使用。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$HERE/run_verify.py" "$@"
