#!/usr/bin/env bash
# 정적 사이트 빌드 → site/. 폐쇄망에서는 site/를 정적 서버로 서빙.
# --strict: nav 누락/깨진 링크가 있으면 빌드 실패 (CI 게이트용).
set -euo pipefail
cd "$(dirname "$0")/../.."
exec python -m mkdocs build --strict "$@"
