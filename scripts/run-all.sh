#!/usr/bin/env bash
# 모든 시나리오를 순차 실행하고 결과를 집계한다.
# 사용: ./scripts/run-all.sh
# 환경변수: BASE_URL=http://localhost:8080 (기본)

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TOTAL_PASS=0
TOTAL_FAIL=0
FAILED_SCENARIOS=()

scenarios=(
  "01-create-dag.sh"
  "02-run-now.sh"
  "03-cron-flow.sh"
  "04-fail-and-dlq.sh"
  "05-validation-errors.sh"
  "06-expression-bindings.sh"
  "07-expression-json-typing.sh"
  "08-expression-failure.sh"
)

for s in "${scenarios[@]}"; do
  echo ""
  echo "==================== $s ===================="
  if bash "$SCRIPT_DIR/scenarios/$s"; then
    TOTAL_PASS=$((TOTAL_PASS + 1))
    echo "==> $s OK"
  else
    TOTAL_FAIL=$((TOTAL_FAIL + 1))
    FAILED_SCENARIOS+=("$s")
    echo "==> $s FAIL" >&2
  fi
done

echo ""
echo "============= SUMMARY ============="
echo "Passed: $TOTAL_PASS / ${#scenarios[@]}"
echo "Failed: $TOTAL_FAIL"
if (( TOTAL_FAIL > 0 )); then
  for f in "${FAILED_SCENARIOS[@]}"; do echo "  - $f"; done
  exit 1
fi
echo "All scenarios passed."
