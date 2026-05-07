#!/usr/bin/env bash
# 시나리오 공통 헬퍼: 색상 출력 + curl 래퍼 + assert 함수
# Windows: Git Bash에서 실행

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

# Colors
if [[ -t 1 ]]; then
  GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; YELLOW=$'\033[1;33m'; CYAN=$'\033[0;36m'; NC=$'\033[0m'
else
  GREEN=""; RED=""; YELLOW=""; CYAN=""; NC=""
fi

# 카운터 (run-all.sh가 source 후 결과 집계)
PASS_COUNT=${PASS_COUNT:-0}
FAIL_COUNT=${FAIL_COUNT:-0}

step() { echo "${CYAN}[STEP]${NC} $*"; }
pass() { PASS_COUNT=$((PASS_COUNT + 1)); echo "${GREEN}[PASS]${NC} $*"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); echo "${RED}[FAIL]${NC} $*" >&2; }
note() { echo "${YELLOW}[NOTE]${NC} $*"; }

# curl JSON helper (status code + body 분리)
http() {
  local method="$1" path="$2" body="${3:-}"
  local resp
  if [[ -n "$body" ]]; then
    resp=$(curl -sS -o /tmp/swe_body.$$ -w "%{http_code}" -X "$method" \
      -H "Content-Type: application/json" -d "$body" "$BASE_URL$path")
  else
    resp=$(curl -sS -o /tmp/swe_body.$$ -w "%{http_code}" -X "$method" "$BASE_URL$path")
  fi
  echo "$resp"
  cat /tmp/swe_body.$$
  rm -f /tmp/swe_body.$$
}

# status_eq <expected_status> <method> <path> [body]
assert_status() {
  local expected="$1" method="$2" path="$3" body="${4:-}"
  local out status
  out=$(http "$method" "$path" "$body")
  status=$(echo "$out" | head -1)
  if [[ "$status" == "$expected" ]]; then
    pass "$method $path → HTTP $status"
    echo "$out" | tail -n +2
    return 0
  else
    fail "$method $path expected $expected, got $status"
    echo "$out" | tail -n +2 >&2
    return 1
  fi
}

# wait_for_url <method> <path> <expected_status> [timeout_sec=60]
wait_for_url() {
  local method="$1" path="$2" expected="$3" timeout="${4:-60}"
  local elapsed=0
  while (( elapsed < timeout )); do
    local code
    code=$(curl -sS -o /dev/null -w "%{http_code}" -X "$method" "$BASE_URL$path" 2>/dev/null || echo "000")
    if [[ "$code" == "$expected" ]]; then
      pass "wait_for_url $method $path → $code (after ${elapsed}s)"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  fail "wait_for_url $method $path timed out after ${timeout}s (last: $code)"
  return 1
}

# 시나리오 진입점에서 호출 — base 환경 헬스체크
ensure_app_up() {
  step "Pinging $BASE_URL"
  if ! wait_for_url GET /api/workflow/activities 200 30; then
    note "App not reachable. 'docker compose up --build -d' 또는 './gradlew composeUpApp' 먼저 실행하세요."
    exit 2
  fi
}
