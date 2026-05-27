#!/usr/bin/env bash
# 13: M23 (#341, #345) — llm.agent (AI agent 루프) 데모 라인 회귀 가드.
#
# DemoSeedRunner(demo 프로파일)가 시드한 DemoLlmAgent를 run-now → COMPLETED 확인.
#   - 단일 llm.agent 노드 + get_weather 도구 (allowlist)
#   - DemoChatController(자체 LLM endpoint)가 "도구 호출 → 결과 받은 뒤 최종 답변"을 흉내냄
#   - 외부 LLM / API 키 불필요 (self-contained)
#
# Skip 조건:
#   - DemoLlmAgent 미시드 → 앱이 demo 프로파일로 안 떴거나 credential 키 미설정 — [SKIP]
#
# 정상 PASS 조건:
#   - instance COMPLETED
#   - agent 노드 outputData: stopReason=stop, content 존재, steps에 get_weather(error=false)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

step "Check demo seed presence (DemoLlmAgent)"
defs_json=$(curl -sS "$BASE_URL/api/line/definitions" 2>/dev/null || echo "[]")
if ! echo "$defs_json" | grep -q '"DemoLlmAgent"'; then
  note "DemoLlmAgent 미시드 — SPRING_PROFILES_ACTIVE=docker,demo + STATION8_CREDENTIAL_KEY 확인"
  note "[SKIP] scenario 13"
  exit 0
fi
pass "DemoLlmAgent 등록 확인"

AGENT_ID=$(echo "$defs_json" | jq -r '.[] | select(.definitionNm=="DemoLlmAgent") | .definitionId' | head -1)
if [[ -z "$AGENT_ID" ]]; then
  fail "DemoLlmAgent ID 추출 실패"
  exit 1
fi
note "DemoLlmAgent id=$AGENT_ID"

poll_instance() {
  local inst_id="$1" timeout="${2:-40}" elapsed=0 state inst_status
  while (( elapsed < timeout )); do
    state=$(curl -sS "$BASE_URL/api/line/instances/$inst_id/state")
    inst_status=$(echo "$state" | jq -r '.instance.statusSt // ""')
    if [[ "$inst_status" == "COMPLETED" || "$inst_status" == "FAILED" || "$inst_status" == "TERMINATED" ]]; then
      echo "$state"
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "TIMEOUT"
  return 1
}

step "DemoLlmAgent 실행"
out=$(http POST "/api/line/definitions/$AGENT_ID/run" '{"input":"{}"}')
status=$(echo "$out" | head -1)
if [[ "$status" != "201" ]]; then
  fail "run status $status — $(echo "$out" | tail -n +2)"
  exit 1
fi
INST=$(echo "$out" | tail -n +2 | jq -r '.instanceId')
pass "instance 시작: $INST"

step "Polling until COMPLETED (timeout 40s)"
state=$(poll_instance "$INST" 40)
if [[ "$state" == "TIMEOUT" ]]; then
  fail "DemoLlmAgent 폴링 timeout"
  exit 1
fi
inst_status=$(echo "$state" | jq -r '.instance.statusSt')
if [[ "$inst_status" != "COMPLETED" ]]; then
  fail "DemoLlmAgent terminal=$inst_status"
  echo "$state" | jq '.activities' >&2
  exit 1
fi
pass "DemoLlmAgent COMPLETED"

# agent 노드 outputData 검증
agent_output=$(echo "$state" | jq -r '.activities[] | select(.activityName=="llm.agent") | .outputData' | head -1)
if [[ -z "$agent_output" ]]; then
  fail "agent 노드 outputData 비어있음"
  exit 1
fi

stop_reason=$(echo "$agent_output" | jq -r '.stopReason // empty')
content=$(echo "$agent_output" | jq -r '.content // empty')
tool_used=$(echo "$agent_output" | jq -r '.steps[0].tool // empty')
tool_error=$(echo "$agent_output" | jq -r '.steps[0].error // empty')

if [[ "$stop_reason" == "stop" ]]; then
  pass "agent stopReason=stop (모델이 도구 없이 종료)"
else
  fail "agent stopReason expected 'stop', got '$stop_reason'"
fi
if [[ -n "$content" ]]; then
  pass "agent 최종 응답 존재: '$content'"
else
  fail "agent content 비어있음"
fi
if [[ "$tool_used" == "get_weather" && "$tool_error" == "false" ]]; then
  pass "get_weather 도구 실행 성공 (error=false)"
else
  fail "get_weather step expected (tool=get_weather, error=false), got (tool='$tool_used', error='$tool_error')"
fi

echo ""
echo "${CYAN}=== Scenario 13 summary ===${NC}"
echo "  PASS: $PASS_COUNT"
echo "  FAIL: $FAIL_COUNT"
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
exit 0
