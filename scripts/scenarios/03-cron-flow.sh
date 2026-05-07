#!/usr/bin/env bash
# 03: cron 스케줄 등록 → run-now로 트리거 → 인스턴스 가시화
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

step "신규 정의 등록 (cron 등록 대상)"
NM="CronFlow-$(date +%s)"
PAYLOAD="{\"definitionNm\":\"$NM\",\"nodes\":[{\"nodeId\":\"c-n\",\"nodeNm\":\"Only\",\"activityNm\":\"MIGRATION_WRITE\",\"posX\":0,\"posY\":0}],\"edges\":[]}"
out=$(http POST "/api/workflow/definitions" "$PAYLOAD")
status=$(echo "$out" | head -1)
[[ "$status" != "201" ]] && { fail "Definition create status $status"; exit 1; }
DEF_ID=$(echo "$out" | tail -n +2 | jq -r '.definitionId')
pass "definitionId=$DEF_ID"

step "POST /api/workflow/schedules (cron='0 */5 * * * *')"
SCHED_PAYLOAD="{\"definitionId\":\"$DEF_ID\",\"cronExpr\":\"0 */5 * * * *\",\"inputData\":null}"
out=$(http POST "/api/workflow/schedules" "$SCHED_PAYLOAD")
status=$(echo "$out" | head -1)
[[ "$status" != "201" ]] && { fail "Schedule create status $status"; exit 1; }
SCH_ID=$(echo "$out" | tail -n +2 | jq -r '.scheduleId')
pass "scheduleId=$SCH_ID"

step "GET /api/workflow/schedules/$SCH_ID — nextRunDt 미래 검증"
out=$(http GET "/api/workflow/schedules/$SCH_ID")
nextRun=$(echo "$out" | tail -n +2 | jq -r '.nextRunDt // empty')
if [[ -n "$nextRun" ]]; then
  pass "nextRunDt=$nextRun"
else
  fail "nextRunDt empty"
  exit 1
fi

step "POST /api/workflow/schedules/$SCH_ID/run-now (즉시 트리거)"
out=$(http POST "/api/workflow/schedules/$SCH_ID/run-now")
status=$(echo "$out" | head -1)
[[ "$status" != "201" ]] && { fail "run-now status $status"; exit 1; }
INST_ID=$(echo "$out" | tail -n +2 | jq -r '.instanceId')
pass "Triggered instanceId=$INST_ID"

step "Cleanup: DELETE /api/workflow/schedules/$SCH_ID"
http DELETE "/api/workflow/schedules/$SCH_ID" > /dev/null
note "스케줄 삭제 완료 (테스트 격리)"
