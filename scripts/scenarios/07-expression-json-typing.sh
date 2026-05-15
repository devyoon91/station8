#!/usr/bin/env bash
# 07: M16 (#247) — JSON 모드 평가의 타입 보존 검증.
#     {{ 1+1 }}이 string "2"가 아닌 number 2로, {{ [1,2,3] }}이 array로 박혀야 함.
#
# 의존: plugin-starter의 ECHO_INPUT 활동.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

step "Checking ECHO_INPUT activity is registered"
acts=$(curl -sS "$BASE_URL/api/line/activities" 2>/dev/null || echo "[]")
if ! echo "$acts" | grep -q '"ECHO_INPUT"'; then
  note "ECHO_INPUT not found — examples/plugin-starter jar 업로드 후 재실행."
  note "[SKIP] scenario 07"
  exit 0
fi
pass "ECHO_INPUT registered"

# ---- 라인 정의: 다양한 JS 타입을 inputParams에 박음 ----
NM="ExprJsonTyping-$(date +%s)"
INPUT_PARAMS=$(jq -n -c '{
  countNum: "{{ 1 + 1 }}",
  truthy: "{{ true || false }}",
  arr: "{{ [1, 2, 3] }}",
  obj: "{{ ({k: \"v\", n: 7}) }}",
  greeting: "Hello {{ $ctx.input.name }}!"
}')
PAYLOAD=$(jq -c -n \
  --arg nm "$NM" \
  --arg input "$INPUT_PARAMS" \
  '{
    definitionNm: $nm,
    description: "Scenario 07 — JSON-mode type preservation",
    nodes: [{nodeId: "n1", nodeNm: "Echo", activityNm: "ECHO_INPUT", inputParams: $input, posX: 100, posY: 100}],
    edges: []
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

step "Run with input.name=world"
out=$(http POST "/api/line/definitions/$DEF_ID/run" '{"input":"{\"name\":\"world\"}"}')
INST_ID=$(echo "$out" | tail -n +2 | jq -r '.instanceId')
pass "instanceId=$INST_ID"

# ---- COMPLETED 폴링 ----
step "Polling state until COMPLETED"
elapsed=0
INPUT_DATA=""
while (( elapsed < 30 )); do
  state=$(curl -sS "$BASE_URL/api/line/instances/$INST_ID/state")
  inst_status=$(echo "$state" | jq -r '.instance.statusSt // ""')
  if [[ "$inst_status" == "COMPLETED" ]]; then
    INPUT_DATA=$(echo "$state" | jq -r '.activities[0].inputData // ""')
    pass "instance COMPLETED after ${elapsed}s"
    break
  fi
  if [[ "$inst_status" == "FAILED" || "$inst_status" == "TERMINATED" ]]; then
    err=$(echo "$state" | jq -r '.activities[0].errorMessage // "(no message)"')
    fail "instance terminal $inst_status — error: $err"
    exit 1
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done
[[ -z "$INPUT_DATA" ]] && { fail "Polling timeout"; exit 1; }

# ---- 타입 검증 (jq 타입 체크) ----
step "Verify type preservation in INPUT_DATA"
echo "  inputData: $INPUT_DATA"

# countNum: number 2
ct_type=$(echo "$INPUT_DATA" | jq -r '.countNum | type')
ct_val=$(echo "$INPUT_DATA" | jq -r '.countNum')
if [[ "$ct_type" == "number" && "$ct_val" == "2" ]]; then
  pass "countNum: number 2 (타입 보존, string 강제 변환 X)"
else
  fail "countNum expected number 2, got $ct_type $ct_val"
fi

# truthy: boolean true
tr_type=$(echo "$INPUT_DATA" | jq -r '.truthy | type')
tr_val=$(echo "$INPUT_DATA" | jq -r '.truthy')
if [[ "$tr_type" == "boolean" && "$tr_val" == "true" ]]; then
  pass "truthy: boolean true (타입 보존)"
else
  fail "truthy expected boolean true, got $tr_type $tr_val"
fi

# arr: array [1,2,3]
arr_type=$(echo "$INPUT_DATA" | jq -r '.arr | type')
arr_len=$(echo "$INPUT_DATA" | jq -r '.arr | length')
if [[ "$arr_type" == "array" && "$arr_len" == "3" ]]; then
  pass "arr: array length 3 (타입 보존)"
else
  fail "arr expected array length 3, got $arr_type length $arr_len"
fi

# obj: object {k:'v', n:7}
obj_type=$(echo "$INPUT_DATA" | jq -r '.obj | type')
obj_k=$(echo "$INPUT_DATA" | jq -r '.obj.k // ""')
obj_n=$(echo "$INPUT_DATA" | jq -r '.obj.n // ""')
if [[ "$obj_type" == "object" && "$obj_k" == "v" && "$obj_n" == "7" ]]; then
  pass "obj: {k:v, n:7} (객체 보존)"
else
  fail "obj expected {k:v,n:7}, got type=$obj_type k=$obj_k n=$obj_n"
fi

# greeting: string "Hello world!" (정적 텍스트 + 표현식 혼재 → string join)
gr=$(echo "$INPUT_DATA" | jq -r '.greeting')
if [[ "$gr" == "Hello world!" ]]; then
  pass "greeting: 'Hello world!' (혼재 → string join)"
else
  fail "greeting expected 'Hello world!', got '$gr'"
fi
