#!/usr/bin/env bash
# 10: M17 (#248, #273) — credential vault 평문 무노출 audit.
#
# 검증 경로:
#   1) POST /api/line/credentials 응답 → 입력한 평문이 없는지
#   2) GET  /api/line/credentials       (list)  → 평문 없음
#   3) GET  /api/line/credentials/{id}  (single) → 평문 없음
#   4) 활동 실행 (REQUIRE_FIELD_ID + $credentials.<name>.value) 성공 케이스 →
#      outputData / errorMessage에 평문 없음 (정상 활동이 secret을 echo하지 않음을 회귀 가드)
#   5) 표현식 실패 케이스 ($credentials.<unknown>.value) → errorMessage가 평문/ciphertext 포함 안 함
#
# 의존:
#   - STATION8_CREDENTIAL_KEY 가 앱에 주입되어 vault 활성 (없으면 POST 자체가 500)
#   - examples/plugin-starter 의 REQUIRE_FIELD_ID 활동 등록 (없으면 4-5 [SKIP])
#   - ADMIN 자격: STATION8_ADMIN_USER (default admin) + STATION8_ADMIN_PASS (필수)
#
# 비범위:
#   - 앱 stdout/파일 로그 직접 scan — 컨테이너/실행 형태마다 위치가 달라 docs/SECRETS.md 운영 가이드로
#   - inputData 노출 — 표현식이 평문을 inputData에 materialize 하는 것은 설계 의도 (#272 보안 노트 참조)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/_lib.sh"

ADMIN_USER="${STATION8_ADMIN_USER:-admin}"
ADMIN_PASS="${STATION8_ADMIN_PASS:-}"
COOKIE_JAR="/tmp/swe_audit_jar.$$"
trap 'rm -f "$COOKIE_JAR" /tmp/swe_audit_*.$$' EXIT

# Cleanup + summary — early-exit / final-exit 양쪽에서 호출.
# DELETE는 CRED_ID가 set된 경우에만 시도.
finish() {
  local exit_code="${1:-0}"
  if [[ -n "${CRED_ID:-}" ]]; then
    step "Cleanup — soft delete sentinel credential"
    local del_status
    del_status=$(curl -sS -b "$COOKIE_JAR" -o /dev/null -w "%{http_code}" \
      -X DELETE "$BASE_URL/api/line/credentials/$CRED_ID")
    if [[ "$del_status" == "200" ]]; then
      pass "Deleted credential id=$CRED_ID"
    else
      note "DELETE → HTTP $del_status (cleanup 실패해도 audit 결과엔 영향 없음)"
    fi
  fi
  echo ""
  echo "${CYAN}=== Audit summary ===${NC}"
  echo "  PASS: $PASS_COUNT"
  echo "  FAIL: $FAIL_COUNT"
  exit "$exit_code"
}

if [[ -z "$ADMIN_PASS" ]]; then
  fail "STATION8_ADMIN_PASS 미설정 — credential 등록 API는 ADMIN 인증 필요"
  note "  export STATION8_ADMIN_PASS='<초기 admin 비밀번호>' 후 재실행"
  note "  비밀번호를 모르면: docker compose logs app 에서 'InitialAdmin:' 라인 확인 (#121)"
  exit 2
fi

ensure_app_up

# ---- 0. ADMIN 로그인 (form auth + JSESSIONID cookie) ----
step "Login as $ADMIN_USER (form auth)"
# /login GET → CSRF token 추출 (Spring Security form 기본 hidden input)
login_html=$(curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" "$BASE_URL/login")
csrf_token=$(echo "$login_html" | grep -oE 'name="_csrf"[^>]*value="[^"]+"' \
  | head -1 | sed -E 's/.*value="([^"]+)".*/\1/')
if [[ -z "$csrf_token" ]]; then
  fail "CSRF token을 /login 폼에서 추출 못함 — 보안 설정 변경됨?"
  finish 1
fi

login_status=$(curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -o /dev/null -w "%{http_code}" \
  -X POST "$BASE_URL/login" \
  --data-urlencode "username=$ADMIN_USER" \
  --data-urlencode "password=$ADMIN_PASS" \
  --data-urlencode "_csrf=$csrf_token")
# Spring formLogin: 성공/실패 모두 302 — 보호 경로 hit으로 실제 인증 검증
if [[ "$login_status" != "302" ]]; then
  fail "POST /login → HTTP $login_status (302 기대)"
  finish 1
fi
me_status=$(curl -sS -b "$COOKIE_JAR" -o /dev/null -w "%{http_code}" "$BASE_URL/me/password")
if [[ "$me_status" != "200" ]]; then
  fail "로그인 후 /me/password → HTTP $me_status (200 기대) — 비밀번호 오류 가능성"
  finish 1
fi
pass "Logged in as $ADMIN_USER"

# ---- audit secret 생성 ----
# 이름은 valid JS identifier만 사용 — $credentials.<name>으로 dot 액세스 가능하게.
STAMP=$(date +%s)
SECRET_VALUE="auditPlaintextSentinel_${STAMP}_$RANDOM"
CRED_NAME="auditTest${STAMP}"
note "Sentinel plaintext: $SECRET_VALUE"
note "Sentinel name:      $CRED_NAME"

# ---- 1. POST /api/line/credentials — 응답에 평문 부재 검증 ----
step "POST /api/line/credentials — plaintext leak check"
post_body=$(jq -n -c \
  --arg name "$CRED_NAME" \
  --arg value "$SECRET_VALUE" \
  '{name: $name, type: "http_bearer", value: $value}')

post_out="/tmp/swe_audit_post.$$"
post_status=$(curl -sS -b "$COOKIE_JAR" -o "$post_out" -w "%{http_code}" \
  -X POST "$BASE_URL/api/line/credentials" \
  -H "Content-Type: application/json" \
  -d "$post_body")

if [[ "$post_status" != "201" ]]; then
  fail "POST /api/line/credentials → HTTP $post_status (201 기대)"
  cat "$post_out" >&2
  finish 1
fi
CRED_ID=$(jq -r '.id' < "$post_out")
pass "Created credential id=$CRED_ID"

if grep -qF "$SECRET_VALUE" "$post_out"; then
  fail "POST 응답에 평문이 노출됨 — 본 응답에서 sentinel 검출"
  cat "$post_out" >&2
  finish 1
fi
pass "POST 응답에 평문 부재 (sentinel 미검출)"

# ---- 2. GET /api/line/credentials (list) — 평문 부재 ----
step "GET /api/line/credentials (list) — plaintext leak check"
list_out="/tmp/swe_audit_list.$$"
list_status=$(curl -sS -b "$COOKIE_JAR" -o "$list_out" -w "%{http_code}" \
  "$BASE_URL/api/line/credentials")
if [[ "$list_status" != "200" ]]; then
  fail "GET list → HTTP $list_status"
  finish 1
fi
if grep -qF "$SECRET_VALUE" "$list_out"; then
  fail "GET list 응답에 평문 노출"
  cat "$list_out" >&2
  finish 1
fi
pass "GET list 응답에 평문 부재"

# ---- 3. GET /api/line/credentials/{id} (single) — 평문 부재 ----
step "GET /api/line/credentials/$CRED_ID (single) — plaintext leak check"
single_out="/tmp/swe_audit_single.$$"
single_status=$(curl -sS -b "$COOKIE_JAR" -o "$single_out" -w "%{http_code}" \
  "$BASE_URL/api/line/credentials/$CRED_ID")
if [[ "$single_status" != "200" ]]; then
  fail "GET single → HTTP $single_status"
  finish 1
fi
if grep -qF "$SECRET_VALUE" "$single_out"; then
  fail "GET single 응답에 평문 노출"
  cat "$single_out" >&2
  finish 1
fi
pass "GET single 응답에 평문 부재"

# ---- 4. 활동 실행 (REQUIRE_FIELD_ID + $credentials) — outputData/errorMessage 평문 부재 ----
step "활동 실행 — REQUIRE_FIELD_ID + \$credentials.${CRED_NAME}.value"
acts=$(curl -sS -b "$COOKIE_JAR" "$BASE_URL/api/line/activities" 2>/dev/null || echo "[]")
if ! echo "$acts" | grep -q '"REQUIRE_FIELD_ID"'; then
  note "REQUIRE_FIELD_ID 미등록 — plugin-starter jar 업로드 후 재실행하면 step 4-5 실행"
  note "[SKIP] step 4 / 5 — credential CRUD audit (1-3) 통과"
  finish 0
fi
pass "REQUIRE_FIELD_ID registered — 활동 실행 audit 진입"

NM="CredAudit-${STAMP}"
# JS dot 액세스: $credentials.auditTestNNN.value
INPUT_PARAMS=$(jq -n -c --arg cn "$CRED_NAME" \
  '{id: ("{{ $credentials." + $cn + ".value }}")}')
PAYLOAD=$(jq -c -n \
  --arg nm "$NM" \
  --arg input "$INPUT_PARAMS" \
  '{
    definitionNm: $nm,
    description: "Scenario 10 — credential vault audit (success path)",
    nodes: [{nodeId: "n1", nodeNm: "Validate", activityNm: "REQUIRE_FIELD_ID", inputParams: $input, posX: 100, posY: 100}],
    edges: []
  }')

def_out="/tmp/swe_audit_def.$$"
def_status=$(curl -sS -b "$COOKIE_JAR" -o "$def_out" -w "%{http_code}" \
  -X POST "$BASE_URL/api/line/definitions" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD")
if [[ "$def_status" != "201" ]]; then
  fail "POST definitions → HTTP $def_status — $(cat "$def_out")"
  finish 1
fi
DEF_ID=$(jq -r '.definitionId' < "$def_out")
pass "Created definitionId=$DEF_ID"

run_out="/tmp/swe_audit_run.$$"
run_status=$(curl -sS -b "$COOKIE_JAR" -o "$run_out" -w "%{http_code}" \
  -X POST "$BASE_URL/api/line/definitions/$DEF_ID/run" \
  -H "Content-Type: application/json" \
  -d '{"input":"{}"}')
if [[ "$run_status" != "201" ]]; then
  fail "POST run → HTTP $run_status — $(cat "$run_out")"
  finish 1
fi
INST_ID=$(jq -r '.instanceId' < "$run_out")
pass "Started instanceId=$INST_ID"

# COMPLETED 까지 폴링
step "Polling state until COMPLETED"
elapsed=0
OUTPUT_DATA=""
ERR_MSG=""
inst_status=""
while (( elapsed < 30 )); do
  state=$(curl -sS -b "$COOKIE_JAR" "$BASE_URL/api/line/instances/$INST_ID/state")
  inst_status=$(echo "$state" | jq -r '.instance.statusSt // ""')
  if [[ "$inst_status" == "COMPLETED" ]]; then
    OUTPUT_DATA=$(echo "$state" | jq -r '.activities[0].outputData // ""')
    ERR_MSG=$(echo "$state" | jq -r '.activities[0].errorMessage // ""')
    pass "instance COMPLETED after ${elapsed}s"
    break
  fi
  if [[ "$inst_status" == "FAILED" || "$inst_status" == "TERMINATED" ]]; then
    ERR_MSG=$(echo "$state" | jq -r '.activities[0].errorMessage // "(no message)"')
    fail "instance terminal $inst_status — error: $ERR_MSG"
    finish 1
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done

if [[ "$inst_status" != "COMPLETED" ]]; then
  fail "Polling timeout — last status=$inst_status"
  finish 1
fi

# outputData / errorMessage 무누출 — REQUIRE_FIELD_ID 는 secret을 echo하지 않음
if echo "$OUTPUT_DATA" | grep -qF "$SECRET_VALUE"; then
  fail "outputData에 평문 노출 — REQUIRE_FIELD_ID이 secret을 echo하지 않음을 회귀 가드"
  echo "  outputData: $OUTPUT_DATA" >&2
  finish 1
fi
pass "성공 case outputData에 평문 부재"

if [[ -n "$ERR_MSG" ]] && echo "$ERR_MSG" | grep -qF "$SECRET_VALUE"; then
  fail "성공 case errorMessage에 평문 노출"
  finish 1
fi
pass "성공 case errorMessage에 평문 부재"

# ---- 5. 실패 케이스 — $credentials.<unknown>.value → TypeError, errorMessage 무누출 ----
step "표현식 실패 케이스 — \$credentials.unknown.value (등록 안 된 이름)"
UNK="notRegistered${STAMP}"
FAIL_INPUT=$(jq -n -c --arg unk "$UNK" \
  '{id: ("{{ $credentials." + $unk + ".value }}")}')
NM2="CredAuditFail-${STAMP}"
FAIL_PAYLOAD=$(jq -c -n \
  --arg nm "$NM2" \
  --arg input "$FAIL_INPUT" \
  '{
    definitionNm: $nm,
    description: "Scenario 10 — credential failure path (unknown name)",
    nodes: [{nodeId: "n1", nodeNm: "Validate", activityNm: "REQUIRE_FIELD_ID", inputParams: $input, posX: 100, posY: 100}],
    edges: []
  }')

def2_status=$(curl -sS -b "$COOKIE_JAR" -o "$def_out" -w "%{http_code}" \
  -X POST "$BASE_URL/api/line/definitions" \
  -H "Content-Type: application/json" \
  -d "$FAIL_PAYLOAD")
if [[ "$def2_status" != "201" ]]; then
  fail "POST failure-case definitions → HTTP $def2_status"
  finish 1
fi
DEF2_ID=$(jq -r '.definitionId' < "$def_out")
pass "Created failure-case definitionId=$DEF2_ID"

run2_status=$(curl -sS -b "$COOKIE_JAR" -o "$run_out" -w "%{http_code}" \
  -X POST "$BASE_URL/api/line/definitions/$DEF2_ID/run" \
  -H "Content-Type: application/json" \
  -d '{"input":"{}"}')
if [[ "$run2_status" != "201" ]]; then
  fail "POST failure-case run → HTTP $run2_status"
  finish 1
fi
INST2_ID=$(jq -r '.instanceId' < "$run_out")
pass "Started failure-case instanceId=$INST2_ID"

# FAILED 폴링
elapsed=0
FAIL_ERR=""
st=""
while (( elapsed < 30 )); do
  state=$(curl -sS -b "$COOKIE_JAR" "$BASE_URL/api/line/instances/$INST2_ID/state")
  st=$(echo "$state" | jq -r '.activities[0].statusSt // ""')
  if [[ "$st" == "FAILED" ]]; then
    FAIL_ERR=$(echo "$state" | jq -r '.activities[0].errorMessage // ""')
    pass "failure-case activity FAILED after ${elapsed}s"
    break
  fi
  if [[ "$st" == "COMPLETED" ]]; then
    fail "표현식 실패가 무시됨 (\$credentials.unknown.value 가 통과) — sandbox 회귀"
    finish 1
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done

if [[ "$st" != "FAILED" ]]; then
  fail "failure case polling timeout — last status=$st"
  finish 1
fi
if [[ -z "$FAIL_ERR" ]]; then
  fail "failure case errorMessage 비어있음 — 평가 실패가 트래킹 안 됨"
  finish 1
fi

# errorMessage에 등록된 평문은 없어야 함 (애초에 unknown 이름이라 decrypt 안 됨)
if echo "$FAIL_ERR" | grep -qF "$SECRET_VALUE"; then
  fail "실패 errorMessage에 등록된 평문 노출"
  echo "  errorMessage: $FAIL_ERR" >&2
  finish 1
fi
pass "실패 errorMessage에 평문 부재 — '$FAIL_ERR'"

finish 0
