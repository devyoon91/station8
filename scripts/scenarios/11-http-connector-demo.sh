#!/usr/bin/env bash
# 11: M18 (#249, #290) — http.request 데모 라인 회귀 가드.
#
# DemoSeedRunner (demo 프로파일)가 시드한 두 라인을 run-now → COMPLETED 확인.
#   - DemoHttpInbound:  http.request GET → MIGRATION_WRITE
#   - DemoHttpOutbound: NOOP → http.request POST
#
# 자체 endpoint (/api/demo/echo/...) 를 부르므로 외부 인터넷 불필요. demo 프로파일에
# allowlist=localhost가 박혀있어 NetworkPolicy 기본 차단을 우회.
#
# Skip 조건:
#   - 데모 라인이 시드 안 됨 → 앱이 demo 프로파일로 안 떴거나 시드 실패 — [SKIP]
#
# 정상 PASS 조건:
#   - 두 라인 모두 instance COMPLETED
#   - DemoHttpInbound: fetch 노드 outputData에 status=200 + body 있음, write 노드 정상 종료
#   - DemoHttpOutbound: prep 노드 통과, post 노드 outputData에 status=200 + body.echo 있음
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

# ---- 데모 라인 시드 여부 확인 ----
step "Check demo seed presence (DemoHttpInbound / DemoHttpOutbound)"
defs_json=$(curl -sS "$BASE_URL/api/line/definitions" 2>/dev/null || echo "[]")
if ! echo "$defs_json" | grep -q '"DemoHttpInbound"' || ! echo "$defs_json" | grep -q '"DemoHttpOutbound"'; then
  note "데모 라인이 시드 안 됨 — app이 demo 프로파일로 떠 있는지 확인 (SPRING_PROFILES_ACTIVE=docker,demo)"
  note "[SKIP] scenario 11"
  exit 0
fi
pass "두 데모 라인 모두 등록 확인"

# 라인별 ID 추출 — definitionNm 매칭
INBOUND_ID=$(echo "$defs_json" | jq -r '.[] | select(.definitionNm=="DemoHttpInbound") | .definitionId' | head -1)
OUTBOUND_ID=$(echo "$defs_json" | jq -r '.[] | select(.definitionNm=="DemoHttpOutbound") | .definitionId' | head -1)
if [[ -z "$INBOUND_ID" || -z "$OUTBOUND_ID" ]]; then
  fail "데모 라인 ID 추출 실패 — definitions API 응답 형식 확인 필요"
  echo "$defs_json" | jq '.[0:2]' >&2
  exit 1
fi
note "DemoHttpInbound  id=$INBOUND_ID"
note "DemoHttpOutbound id=$OUTBOUND_ID"

# ---- 공통 — 인스턴스 폴링 (COMPLETED 또는 FAILED 까지) ----
poll_instance() {
  local inst_id="$1"
  local timeout="${2:-30}"
  local elapsed=0
  local state inst_status
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

# ============ Demo 1: HTTP → MIGRATION_WRITE ============

step "Demo 1 — DemoHttpInbound 실행"
out=$(http POST "/api/line/definitions/$INBOUND_ID/run" '{"input":"{}"}')
status=$(echo "$out" | head -1)
if [[ "$status" != "201" ]]; then
  fail "run status $status — $(echo "$out" | tail -n +2)"
  exit 1
fi
INST1=$(echo "$out" | tail -n +2 | jq -r '.instanceId')
pass "instance 시작: $INST1"

step "Polling until COMPLETED (timeout 30s)"
state1=$(poll_instance "$INST1" 30)
if [[ "$state1" == "TIMEOUT" ]]; then
  fail "DemoHttpInbound 폴링 timeout"
  exit 1
fi
inst1_status=$(echo "$state1" | jq -r '.instance.statusSt')
if [[ "$inst1_status" != "COMPLETED" ]]; then
  fail "DemoHttpInbound terminal=$inst1_status"
  echo "$state1" | jq '.activities' >&2
  exit 1
fi
pass "DemoHttpInbound COMPLETED"

# fetch 노드 outputData 검증 — status 200 + body.title 존재
fetch_output=$(echo "$state1" | jq -r '.activities[] | select(.activityName=="http.request") | .outputData' | head -1)
if [[ -z "$fetch_output" ]]; then
  fail "fetch 노드 outputData 비어있음"
  exit 1
fi
fetch_status=$(echo "$fetch_output" | jq -r '.status // empty')
fetch_title=$(echo "$fetch_output" | jq -r '.body.title // empty')
if [[ "$fetch_status" == "200" ]]; then
  pass "fetch 노드 응답 status=200"
else
  fail "fetch 노드 status expected 200, got '$fetch_status'"
fi
if [[ "$fetch_title" == "sample-from-demo-echo" ]]; then
  pass "fetch 응답 body.title 정상 — '$fetch_title'"
else
  fail "fetch body.title expected 'sample-from-demo-echo', got '$fetch_title'"
fi

# write 노드도 COMPLETED 확인 (MIGRATION_WRITE가 표현식 치환 후 DB insert 시도)
write_status=$(echo "$state1" | jq -r '.activities[] | select(.activityName=="MIGRATION_WRITE") | .statusSt' | head -1)
if [[ "$write_status" == "COMPLETED" ]]; then
  pass "write 노드 (MIGRATION_WRITE) COMPLETED"
else
  fail "write 노드 status=$write_status"
fi

# ============ Demo 2: NOOP → HTTP POST ============

step "Demo 2 — DemoHttpOutbound 실행 (input.user=alice)"
out=$(http POST "/api/line/definitions/$OUTBOUND_ID/run" '{"input":"{\"user\":\"alice\"}"}')
status=$(echo "$out" | head -1)
if [[ "$status" != "201" ]]; then
  fail "run status $status — $(echo "$out" | tail -n +2)"
  exit 1
fi
INST2=$(echo "$out" | tail -n +2 | jq -r '.instanceId')
pass "instance 시작: $INST2"

step "Polling until COMPLETED (timeout 30s)"
state2=$(poll_instance "$INST2" 30)
if [[ "$state2" == "TIMEOUT" ]]; then
  fail "DemoHttpOutbound 폴링 timeout"
  exit 1
fi
inst2_status=$(echo "$state2" | jq -r '.instance.statusSt')
if [[ "$inst2_status" != "COMPLETED" ]]; then
  fail "DemoHttpOutbound terminal=$inst2_status"
  echo "$state2" | jq '.activities' >&2
  exit 1
fi
pass "DemoHttpOutbound COMPLETED"

# post 노드 — status 200 + body.echo.user == 'alice' (round-trip 검증)
post_output=$(echo "$state2" | jq -r '.activities[] | select(.activityName=="http.request") | .outputData' | head -1)
post_status=$(echo "$post_output" | jq -r '.status // empty')
post_echo_user=$(echo "$post_output" | jq -r '.body.echo.user // empty')

if [[ "$post_status" == "200" ]]; then
  pass "post 노드 응답 status=200"
else
  fail "post 노드 status expected 200, got '$post_status'"
fi
if [[ "$post_echo_user" == "alice" ]]; then
  pass "post 응답 body.echo.user='alice' — round-trip 정상"
else
  fail "post body.echo.user expected 'alice', got '$post_echo_user'"
fi

# ============ Summary ============
echo ""
echo "${CYAN}=== Scenario 11 summary ===${NC}"
echo "  PASS: $PASS_COUNT"
echo "  FAIL: $FAIL_COUNT"
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
exit 0
