#!/usr/bin/env bash
# 06: M16 (#247) — $ctx.input / $ctx.run / $ctx.line / $ctx.runtime 표현식이
#     활동 inputData에 평가된 값으로 치환되는지 검증.
#
# 의존: plugin-starter의 ECHO_INPUT 활동이 등록되어 있어야 함 (입력을 그대로 출력으로 echo).
# 미등록이면 [SKIP] 후 종료.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

# ---- ECHO_INPUT 등록 여부 확인 (없으면 skip) ----
step "Checking ECHO_INPUT activity is registered"
acts=$(curl -sS "$BASE_URL/api/line/activities" 2>/dev/null || echo "[]")
if ! echo "$acts" | grep -q '"ECHO_INPUT"'; then
  note "ECHO_INPUT not found — examples/plugin-starter jar를 /admin/plugins에 업로드 + Reload 후 다시 실행."
  note "[SKIP] scenario 06"
  exit 0
fi
pass "ECHO_INPUT registered"

# ---- 라인 정의: 단일 ECHO_INPUT 노드, inputParams에 $ctx.* 표현식 ----
NM="ExprCtxBindings-$(date +%s)"
INPUT_PARAMS=$(jq -n -c '{
  runId: "{{ $ctx.run.id }}",
  lineName: "{{ $ctx.line.name }}",
  activity: "{{ $ctx.line.activity }}",
  userInput: "{{ $ctx.input.user }}",
  attempt: "{{ $ctx.run.attempt }}"
}')
PAYLOAD=$(jq -c -n \
  --arg nm "$NM" \
  --arg input "$INPUT_PARAMS" \
  '{
    definitionNm: $nm,
    description: "Scenario 06 — $ctx bindings",
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

# ---- run-now (input.user='alice') ----
step "POST /api/line/definitions/$DEF_ID/run (input.user=alice)"
RUN_BODY='{"input":"{\"user\":\"alice\"}"}'
out=$(http POST "/api/line/definitions/$DEF_ID/run" "$RUN_BODY")
status=$(echo "$out" | head -1)
if [[ "$status" != "201" ]]; then
  fail "run status $status — $(echo "$out" | tail -n +2)"
  exit 1
fi
INST_ID=$(echo "$out" | tail -n +2 | jq -r '.instanceId')
pass "Started instanceId=$INST_ID"

# ---- COMPLETED 까지 폴링 (최대 30s) ----
step "Polling /api/line/instances/$INST_ID/state until COMPLETED"
elapsed=0
INPUT_DATA=""
while (( elapsed < 30 )); do
  state=$(curl -sS "$BASE_URL/api/line/instances/$INST_ID/state" 2>/dev/null)
  inst_status=$(echo "$state" | jq -r '.instance.statusSt // ""')
  if [[ "$inst_status" == "COMPLETED" ]]; then
    INPUT_DATA=$(echo "$state" | jq -r '.activities[0].inputData // ""')
    OUTPUT_DATA=$(echo "$state" | jq -r '.activities[0].outputData // ""')
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
if [[ -z "$INPUT_DATA" ]]; then
  fail "Polling timeout after ${elapsed}s — instance not COMPLETED"
  exit 1
fi

# ---- 평가 결과 검증: inputData가 표현식 치환된 형태여야 함 ----
step "Verify inputData = post-evaluation form"
echo "  inputData: $INPUT_DATA"

run_id=$(echo "$INPUT_DATA" | jq -r '.runId // ""')
line_nm=$(echo "$INPUT_DATA" | jq -r '.lineName // ""')
act_nm=$(echo "$INPUT_DATA" | jq -r '.activity // ""')
user=$(echo "$INPUT_DATA" | jq -r '.userInput // ""')
attempt=$(echo "$INPUT_DATA" | jq -r '.attempt // ""')

if [[ "$run_id" == "$INST_ID" ]]; then
  pass "{{ \$ctx.run.id }} → instanceId 치환 OK ($run_id)"
else
  fail "{{ \$ctx.run.id }} expected $INST_ID, got '$run_id'"
fi

if [[ "$line_nm" == "$NM" ]]; then
  pass "{{ \$ctx.line.name }} → 라인 이름 치환 OK"
else
  fail "{{ \$ctx.line.name }} expected $NM, got '$line_nm'"
fi

if [[ "$act_nm" == "ECHO_INPUT" ]]; then
  pass "{{ \$ctx.line.activity }} → 활동 이름 치환 OK"
else
  fail "{{ \$ctx.line.activity }} expected ECHO_INPUT, got '$act_nm'"
fi

if [[ "$user" == "alice" ]]; then
  pass "{{ \$ctx.input.user }} → 인스턴스 input 치환 OK"
else
  fail "{{ \$ctx.input.user }} expected 'alice', got '$user'"
fi

# attempt 는 평가 결과가 number → JSON 모드에서 "1"로 들어오면 string-coerced (M16 평가가 number 보존하면 1)
# JSON 모드는 단일 표현식 단독이면 raw value로 박힘 → number 1
if [[ "$attempt" == "1" ]]; then
  pass "{{ \$ctx.run.attempt }} → 시도 횟수 (number 1 보존) OK"
else
  fail "{{ \$ctx.run.attempt }} expected 1, got '$attempt'"
fi

# ---- ECHO_INPUT은 입력을 그대로 echo — outputData == inputData 확인 ----
if [[ "$OUTPUT_DATA" == "$INPUT_DATA" ]]; then
  pass "ECHO_INPUT outputData == inputData (활동이 평가된 입력을 그대로 받았음)"
else
  fail "outputData != inputData — output=$OUTPUT_DATA"
fi
