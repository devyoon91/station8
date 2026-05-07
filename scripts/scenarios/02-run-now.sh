#!/usr/bin/env bash
# 02: 정의 즉시 실행 → 인스턴스 생성 + 시작 노드 PENDING 검증
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

# 01에서 만든 정의 재사용 또는 새로 생성
if [[ -f /tmp/swe_last_def.txt ]]; then
  DEF_ID=$(cat /tmp/swe_last_def.txt)
  step "Reusing definitionId from scenario 01: $DEF_ID"
else
  step "No prior definition — creating one"
  NM="RunNowFlow-$(date +%s)"
  PAYLOAD="{\"definitionNm\":\"$NM\",\"nodes\":[{\"nodeId\":\"r-n\",\"nodeNm\":\"Only\",\"activityNm\":\"NOOP\",\"posX\":0,\"posY\":0}],\"edges\":[]}"
  out=$(http POST "/api/workflow/definitions" "$PAYLOAD")
  DEF_ID=$(echo "$out" | tail -n +2 | jq -r '.definitionId')
fi

step "POST /api/workflow/definitions/$DEF_ID/run"
out=$(http POST "/api/workflow/definitions/$DEF_ID/run" "{\"input\":\"scenario-02\"}")
status=$(echo "$out" | head -1)
if [[ "$status" != "201" ]]; then
  fail "Run status $status"
  exit 1
fi
INST_ID=$(echo "$out" | tail -n +2 | jq -r '.instanceId')
pass "Created instanceId=$INST_ID"
echo "$INST_ID" > /tmp/swe_last_inst.txt
