#!/usr/bin/env bash
# 01: DAG 정의 등록 → 조회 검증
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

NM="ScenarioFlow-$(date +%s)"
step "POST /api/line/definitions ($NM)"
# jq -c (compact)로 single-line JSON 생성 — Git Bash의 CRLF/한글 + 멀티라인 조합 함정 회피
INPUT_PARAMS='{"id":"sc1","content":"Scenario 1"}'
PAYLOAD=$(jq -c -n \
  --arg nm "$NM" \
  --arg desc "Scenario 01 - single node definition" \
  --arg input "$INPUT_PARAMS" \
  '{
    definitionNm: $nm,
    description: $desc,
    nodes: [{nodeId: "s1-n", nodeNm: "Only", activityNm: "MIGRATION_WRITE", inputParams: $input, posX: 100, posY: 100}],
    edges: []
  }')
out=$(http POST "/api/line/definitions" "$PAYLOAD")
status=$(echo "$out" | head -1)
body=$(echo "$out" | tail -n +2)
if [[ "$status" != "201" ]]; then
  fail "POST status $status (expected 201). Body: $body"
  exit 1
fi
DEF_ID=$(echo "$body" | jq -r '.definitionId')
pass "Created definitionId=$DEF_ID"

step "GET /api/line/definitions/$DEF_ID"
out=$(http GET "/api/line/definitions/$DEF_ID")
status=$(echo "$out" | head -1)
if [[ "$status" != "200" ]]; then
  fail "GET status $status"
  exit 1
fi
nodes=$(echo "$out" | tail -n +2 | jq -r '.nodes | length')
if [[ "$nodes" == "1" ]]; then
  pass "Definition has $nodes node(s)"
else
  fail "Expected 1 node, got $nodes"
  exit 1
fi

echo "$DEF_ID" > /tmp/swe_last_def.txt
note "definitionId saved → /tmp/swe_last_def.txt (다음 시나리오에서 재사용)"
