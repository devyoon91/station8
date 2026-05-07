#!/usr/bin/env bash
# 05: DAG 검증 거부 (사이클 / 미등록 액티비티)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

step "Case 1: 사이클 DAG (A → B → A)"
NM="CycleFlow-$(date +%s)"
PAYLOAD=$(cat <<EOF
{
  "definitionNm": "$NM",
  "nodes": [
    {"nodeId": "ca", "nodeNm": "A", "activityNm": "MIGRATION_WRITE", "posX": 0, "posY": 0},
    {"nodeId": "cb", "nodeNm": "B", "activityNm": "MIGRATION_WRITE", "posX": 0, "posY": 0}
  ],
  "edges": [
    {"edgeId": "ce1", "fromNodeId": "ca", "toNodeId": "cb"},
    {"edgeId": "ce2", "fromNodeId": "cb", "toNodeId": "ca"}
  ]
}
EOF
)
out=$(http POST "/api/workflow/definitions" "$PAYLOAD")
status=$(echo "$out" | head -1)
body=$(echo "$out" | tail -n +2)
if [[ "$status" == "400" ]]; then
  if echo "$body" | grep -q "WF-E305"; then
    pass "Cycle rejected with WF-E305"
  else
    fail "Status 400 but WF-E305 not in body: $body"
    exit 1
  fi
else
  fail "Cycle DAG should be rejected (got $status). Body: $body"
  exit 1
fi

step "Case 2: 미등록 activityName"
NM2="UnknownAct-$(date +%s)"
PAYLOAD2="{\"definitionNm\":\"$NM2\",\"nodes\":[{\"nodeId\":\"u1\",\"nodeNm\":\"X\",\"activityNm\":\"NO_SUCH_ACTIVITY\",\"posX\":0,\"posY\":0}],\"edges\":[]}"
out=$(http POST "/api/workflow/definitions" "$PAYLOAD2")
status=$(echo "$out" | head -1)
body=$(echo "$out" | tail -n +2)
if [[ "$status" == "400" ]] && echo "$body" | grep -q "WF-E307"; then
  pass "Unknown activity rejected with WF-E307"
else
  fail "Expected WF-E307, got status=$status body=$body"
  exit 1
fi

step "Case 3: 정상 DAG는 통과"
NM3="OK-$(date +%s)"
PAYLOAD3="{\"definitionNm\":\"$NM3\",\"nodes\":[{\"nodeId\":\"ok1\",\"nodeNm\":\"A\",\"activityNm\":\"MIGRATION_WRITE\",\"posX\":0,\"posY\":0}],\"edges\":[]}"
out=$(http POST "/api/workflow/definitions" "$PAYLOAD3")
status=$(echo "$out" | head -1)
[[ "$status" == "201" ]] && pass "Valid DAG accepted (201)" || { fail "Valid DAG rejected: $status"; exit 1; }
