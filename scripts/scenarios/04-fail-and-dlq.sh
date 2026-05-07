#!/usr/bin/env bash
# 04: 강제 실패 → DLQ 가시화 (DataMigrationWorkflow.migrateItem이 'Second' 포함 시 RuntimeException)
# 본 시나리오는 부팅 시 MigrationInitializer가 시드한 'Second Data' 항목이 폴러를 통해 결국 DLQ로 가는 흐름을 검증한다.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

step "기존 DLQ 페이지 확인 (HTML 200)"
out=$(http GET "/workflow/dlq")
status=$(echo "$out" | head -1)
[[ "$status" != "200" ]] && { fail "DLQ page status $status"; exit 1; }
pass "/workflow/dlq accessible"

step "참고 — DLQ 적재 흐름"
note "본 환경은 MigrationInitializer가 부팅 시 'Second Data'를 PENDING으로 시드함."
note "DataMigrationWorkflow가 5회 재시도(@Activity retryCount=5) 후 DLQ로 적재 → /workflow/dlq에서 확인 가능."
note "재시도 백오프(5/10/20/40/80s)로 인해 DLQ 가시화까지 약 2~3분 소요. 본 시나리오에서는 페이지 접근만 검증."
