#!/usr/bin/env bash
# 로컬 미리보기 서버 (http://127.0.0.1:8000). 저장 시 자동 리로드.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec python -m mkdocs serve "$@"
