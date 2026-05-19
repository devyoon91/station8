#!/usr/bin/env bash
# 12: M19 (#250, #298) — file.read / file.write 데모 라인 회귀 가드.
#
# DemoSeedRunner (demo 프로파일)가 시드한 두 라인을 run-now → COMPLETED 확인 + outbox에
# 결과 파일이 생겼는지 검증.
#   - DemoFileInbound:  file.read (inbox/order.json) → MIGRATION_WRITE
#   - DemoFileOutbound: NOOP → file.write (outbox/result-<run.id>.json)
#
# Skip 조건:
#   - 데모 라인이 시드 안 됨 → 앱이 demo 프로파일로 안 떴거나 시드 실패 — [SKIP]
#
# 정상 PASS 조건:
#   - 두 라인 모두 instance COMPLETED
#   - DemoFileInbound: read 노드 outputData에 content.orderId 있음, write 노드 정상 종료
#   - DemoFileOutbound: write 노드 outputData에 sizeBytes > 0, uri는 outbox 안
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ensure_app_up

# ---- 데모 라인 시드 여부 확인 ----
step "Check demo seed presence (DemoFileInbound / DemoFileOutbound)"
defs_json=$(curl -sS "$BASE_URL/api/line/definitions" 2>/dev/null || echo "[]")
if ! echo "$defs_json" | grep -q '"DemoFileInbound"' || ! echo "$defs_json" | grep -q '"DemoFileOutbound"'; then
  note "데모 라인이 시드 안 됨 — app이 demo 프로파일로 떠 있는지 + station8.file.local.allowed-roots 설정 확인"
  note "[SKIP] scenario 12"
  exit 0
fi
pass "두 파일 데모 라인 모두 등록 확인"

INBOUND_ID=$(echo "$defs_json" | jq -r '.[] | select(.definitionNm=="DemoFileInbound") | .definitionId' | head -1)
OUTBOUND_ID=$(echo "$defs_json" | jq -r '.[] | select(.definitionNm=="DemoFileOutbound") | .definitionId' | head -1)
if [[ -z "$INBOUND_ID" || -z "$OUTBOUND_ID" ]]; then
  fail "데모 라인 ID 추출 실패"
  echo "$defs_json" | jq '.[0:2]' >&2
  exit 1
fi
note "DemoFileInbound  id=$INBOUND_ID"
note "DemoFileOutbound id=$OUTBOUND_ID"

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

# ============ Demo 1: file.read → MIGRATION_WRITE ============

step "Demo 1 — DemoFileInbound 실행 (inbox/order.json → DB insert)"
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
  fail "DemoFileInbound 폴링 timeout"
  exit 1
fi
inst1_status=$(echo "$state1" | jq -r '.instance.statusSt')
if [[ "$inst1_status" != "COMPLETED" ]]; then
  fail "DemoFileInbound terminal=$inst1_status"
  echo "$state1" | jq '.activities' >&2
  exit 1
fi
pass "DemoFileInbound COMPLETED"

read_output=$(echo "$state1" | jq -r '.activities[] | select(.activityName=="file.read") | .outputData' | head -1)
read_format=$(echo "$read_output" | jq -r '.format // empty')
read_orderid=$(echo "$read_output" | jq -r '.content.orderId // empty')
if [[ "$read_format" == "json" ]]; then
  pass "read 노드 format=json"
else
  fail "read 노드 format expected 'json', got '$read_format'"
fi
if [[ "$read_orderid" == "demo-1" ]]; then
  pass "read 응답 content.orderId 정상 — '$read_orderid' (inbox 샘플 파일)"
else
  fail "read content.orderId expected 'demo-1', got '$read_orderid'"
fi

write_status=$(echo "$state1" | jq -r '.activities[] | select(.activityName=="MIGRATION_WRITE") | .statusSt' | head -1)
if [[ "$write_status" == "COMPLETED" ]]; then
  pass "write 노드 (MIGRATION_WRITE) COMPLETED"
else
  fail "write 노드 status=$write_status"
fi

# ============ Demo 2: NOOP → file.write ============

step "Demo 2 — DemoFileOutbound 실행 (input.user=alice)"
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
  fail "DemoFileOutbound 폴링 timeout"
  exit 1
fi
inst2_status=$(echo "$state2" | jq -r '.instance.statusSt')
if [[ "$inst2_status" != "COMPLETED" ]]; then
  fail "DemoFileOutbound terminal=$inst2_status"
  echo "$state2" | jq '.activities' >&2
  exit 1
fi
pass "DemoFileOutbound COMPLETED"

write_output=$(echo "$state2" | jq -r '.activities[] | select(.activityName=="file.write") | .outputData' | head -1)
write_uri=$(echo "$write_output" | jq -r '.uri // empty')
write_size=$(echo "$write_output" | jq -r '.sizeBytes // 0')

if echo "$write_uri" | grep -q "outbox"; then
  pass "write 노드 uri가 outbox 안 — '$write_uri'"
else
  fail "write uri expected 'outbox' 포함, got '$write_uri'"
fi
if [[ "$write_size" -gt 0 ]]; then
  pass "write 노드 sizeBytes=$write_size (> 0)"
else
  fail "write sizeBytes expected > 0, got '$write_size'"
fi

# 인스턴스 ID가 파일명에 들어가는지 확인 — {{ $ctx.run.id }} 표현식 동작 검증
if echo "$write_uri" | grep -q "$INST2"; then
  pass "write uri에 인스턴스 ID 인터폴레이션 정상 동작 (\$ctx.run.id)"
else
  fail "write uri에 인스턴스 ID 없음 — \$ctx.run.id 평가 실패 가능 — '$write_uri'"
fi

# ============ Summary ============
echo ""
echo "${CYAN}=== Scenario 12 summary ===${NC}"
echo "  PASS: $PASS_COUNT"
echo "  FAIL: $FAIL_COUNT"
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
exit 0
