#!/usr/bin/env bash
# 09: M16 (#247, #267) — $prev.json 표현식이 직전 노드 output을 실제로 끌어오는지 검증.
#     선형 체인 PRODUCE → CONSUME 에서 PRODUCE의 outputData가 CONSUME의 inputParams 평가에 노출되는지 확인.
#
# 의존: plugin-starter의 ECHO_INPUT (echo) + TRANSFORM_JSON ({echoed: <input>}) 활동.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

step "Checking ECHO_INPUT + TRANSFORM_JSON activities"
acts=$(curl -sS "$BASE_URL/api/line/activities" 2>/dev/null || echo "[]")
if ! echo "$acts" | grep -q '"ECHO_INPUT"' || ! echo "$acts" | grep -q '"TRANSFORM_JSON"'; then
  note "ECHO_INPUT 또는 TRANSFORM_JSON 미등록 — examples/plugin-starter jar 업로드 후 재실행."
  note "[SKIP] scenario 09"
  exit 0
fi
pass "Plugin activities registered"

# ---- 라인 정의: PRODUCE (TRANSFORM_JSON) → CONSUME (ECHO_INPUT) ----
# - PRODUCE: input "{}" → outputData '{"echoed":{}}'... 잠깐, TRANSFORM_JSON은 {echoed: input} wrap
#   PRODUCE inputParams = '{"orderId": 42}' (정적) → output '{"echoed":{"orderId":42}}'
# - CONSUME: inputParams = '{"fromPrev": "{{ $prev.json.echoed.orderId }}"}'
#   → 평가 결과: '{"fromPrev": 42}'  ← 정상 wiring 확인
NM="ExprPrevChain-$(date +%s)"
PRODUCE_INPUT='{"orderId":42,"label":"foo"}'
CONSUME_INPUT='{"fromPrev":"{{ $prev.json.echoed.orderId }}","echoedLabel":"{{ $prev.json.echoed.label }}"}'
PAYLOAD=$(jq -c -n \
  --arg nm "$NM" \
  --arg p_input "$PRODUCE_INPUT" \
  --arg c_input "$CONSUME_INPUT" \
  '{
    definitionNm: $nm,
    description: "Scenario 09 — $prev wiring (#267)",
    nodes: [
      {nodeId: "produce", nodeNm: "Produce", activityNm: "TRANSFORM_JSON", inputParams: $p_input, posX: 100, posY: 100},
      {nodeId: "consume", nodeNm: "Consume", activityNm: "ECHO_INPUT", inputParams: $c_input, posX: 300, posY: 100}
    ],
    edges: [{edgeId: "e1", fromNodeId: "produce", toNodeId: "consume"}]
  }')

step "POST /api/line/definitions ($NM)"
out=$(http POST "/api/line/definitions" "$PAYLOAD")
status=$(echo "$out" | head -1)
if [[ "$status" != "201" ]]; then
  fail "create status $status — $(echo "$out" | tail -n +2)"
  exit 1
fi
DEF_ID=$(echo "$out" | tail -n +2 | jq -r '.definitionId')
pass "Created definitionId=$DEF_ID"

step "Run instance"
out=$(http POST "/api/line/definitions/$DEF_ID/run" '{"input":"{}"}')
INST_ID=$(echo "$out" | tail -n +2 | jq -r '.instanceId')
pass "instanceId=$INST_ID"

# ---- 양 노드 COMPLETED 까지 폴링 ----
step "Polling state until both nodes COMPLETED"
elapsed=0
PRODUCE_OUTPUT=""
CONSUME_INPUT_DATA=""
while (( elapsed < 30 )); do
  state=$(curl -sS "$BASE_URL/api/line/instances/$INST_ID/state")
  inst_status=$(echo "$state" | jq -r '.instance.statusSt // ""')
  if [[ "$inst_status" == "COMPLETED" ]]; then
    PRODUCE_OUTPUT=$(echo "$state" | jq -r '.activities[] | select(.nodeId=="produce") | .outputData')
    CONSUME_INPUT_DATA=$(echo "$state" | jq -r '.activities[] | select(.nodeId=="consume") | .inputData')
    pass "instance COMPLETED after ${elapsed}s"
    break
  fi
  if [[ "$inst_status" == "FAILED" || "$inst_status" == "TERMINATED" ]]; then
    err=$(echo "$state" | jq -r '.activities[].errorMessage // ""' | grep -v '^$' | head -1)
    fail "instance terminal $inst_status — error: $err"
    exit 1
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done
[[ -z "$CONSUME_INPUT_DATA" ]] && { fail "Polling timeout"; exit 1; }

# ---- 검증 ----
step "Verify chain"
echo "  produce.outputData: $PRODUCE_OUTPUT"
echo "  consume.inputData (post-evaluation): $CONSUME_INPUT_DATA"

# PRODUCE: TRANSFORM_JSON should wrap input as {"echoed": <input>}
expected_produce_echoed=$(echo "$PRODUCE_OUTPUT" | jq -r '.echoed.orderId // ""')
if [[ "$expected_produce_echoed" == "42" ]]; then
  pass "produce.outputData wrapped input correctly (echoed.orderId=42)"
else
  fail "produce.outputData unexpected — echoed.orderId=$expected_produce_echoed"
fi

# CONSUME: $prev.json.echoed.orderId should evaluate to 42 (number preserved)
fp_type=$(echo "$CONSUME_INPUT_DATA" | jq -r '.fromPrev | type')
fp_val=$(echo "$CONSUME_INPUT_DATA" | jq -r '.fromPrev')
if [[ "$fp_type" == "number" && "$fp_val" == "42" ]]; then
  pass "{{ \$prev.json.echoed.orderId }} → number 42 (직전 활동 output 정확히 wiring)"
else
  fail "fromPrev expected number 42, got $fp_type $fp_val"
fi

el=$(echo "$CONSUME_INPUT_DATA" | jq -r '.echoedLabel')
if [[ "$el" == "foo" ]]; then
  pass "{{ \$prev.json.echoed.label }} → 'foo' (string field 접근)"
else
  fail "echoedLabel expected 'foo', got '$el'"
fi
