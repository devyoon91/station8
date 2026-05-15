#!/usr/bin/env bash
# 08: M16 (#247) — 표현식 평가 실패 → 활동 FAILED + errorMessage에 실패 사유 명시.
#     ECHO_INPUT은 retryCount=0이라 첫 평가 실패가 곧 최종 FAILED.
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
  note "[SKIP] scenario 08"
  exit 0
fi
pass "ECHO_INPUT registered"

# ---- 라인 정의: 평가 시 ReferenceError가 나는 표현식 ----
NM="ExprFailure-$(date +%s)"
INPUT_PARAMS='{"v":"{{ unknownVarThatDoesNotExist }}"}'
PAYLOAD=$(jq -c -n \
  --arg nm "$NM" \
  --arg input "$INPUT_PARAMS" \
  '{
    definitionNm: $nm,
    description: "Scenario 08 — expression failure → activity FAILED",
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

step "Run instance — 평가 실패 예상"
out=$(http POST "/api/line/definitions/$DEF_ID/run" '{"input":"{}"}')
INST_ID=$(echo "$out" | tail -n +2 | jq -r '.instanceId')
pass "instanceId=$INST_ID"

# ---- 활동 FAILED 까지 폴링 ----
step "Polling state until activity FAILED"
elapsed=0
ACT_STATUS=""
ERR_MSG=""
while (( elapsed < 30 )); do
  state=$(curl -sS "$BASE_URL/api/line/instances/$INST_ID/state")
  ACT_STATUS=$(echo "$state" | jq -r '.activities[0].statusSt // ""')
  if [[ "$ACT_STATUS" == "FAILED" ]]; then
    ERR_MSG=$(echo "$state" | jq -r '.activities[0].errorMessage // ""')
    pass "activity FAILED after ${elapsed}s"
    break
  fi
  if [[ "$ACT_STATUS" == "COMPLETED" ]]; then
    fail "activity COMPLETED — 평가 실패가 무시된 것? inputData=$(echo "$state" | jq -r '.activities[0].inputData')"
    exit 1
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done
[[ "$ACT_STATUS" != "FAILED" ]] && { fail "polling timeout — last status=$ACT_STATUS"; exit 1; }

# ---- errorMessage 검증 — 표현식/평가 관련 키워드 포함되어야 ----
step "Verify errorMessage references the expression failure"
echo "  errorMessage: $ERR_MSG"

# expression eval 실패 경로 — message는 'unknownVarThatDoesNotExist' 또는 '표현식'을 포함해야 함
if echo "$ERR_MSG" | grep -qiE "unknownVarThatDoesNotExist|표현식|ReferenceError|expression"; then
  pass "errorMessage가 평가 실패 사유를 명시 (포함: 표현식 키워드)"
else
  fail "errorMessage에 평가 실패 단서 없음 — '$ERR_MSG'"
fi

# 워커는 살아있어야 함 — 후속 호출 가능 (다른 endpoint ping)
step "Verify worker still alive (다른 호출 가능)"
status=$(curl -sS -o /dev/null -w "%{http_code}" "$BASE_URL/api/line/activities")
if [[ "$status" == "200" ]]; then
  pass "Worker / app 정상 — 평가 실패가 워커 죽이지 않음"
else
  fail "App 응답 안 함 — status $status"
fi
