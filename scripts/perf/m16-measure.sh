#!/usr/bin/env bash
#
# M16 (#261) — Expression engine 측정 스크립트.
#
# 1) Latency 마이크로벤치 (./gradlew :station8-engine:perfTest)
# 2) bootJar 크기
# 3) GraalVM 의존성 jar 합계
# 4) Docker 이미지 크기 (전 vs 후 비교는 별도 commit checkout 필요)
# 5) (옵션) Docker compose 부팅 시간
#
# 결과는 stdout으로 출력. CI에선 안 돌림 — 운영자가 RFC fallback 트리거 결정 데이터를 만들 때 1회.
#
# Usage:
#   bash scripts/perf/m16-measure.sh                # latency + jar + bootJar + docker image size
#   bash scripts/perf/m16-measure.sh --boot         # + docker compose 부팅 측정
#   bash scripts/perf/m16-measure.sh --baseline SHA # 이전 commit과 bootJar 크기 비교

set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

INCLUDE_BOOT=false
BASELINE_SHA=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --boot) INCLUDE_BOOT=true; shift ;;
    --baseline) BASELINE_SHA="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

print_section() { printf "\n=== %s ===\n" "$1"; }

# ----------------------------------------------------------------------
# 1. Latency 마이크로벤치
# ----------------------------------------------------------------------
print_section "1. Latency benchmark (perfTest)"
./gradlew :station8-engine:perfTest --console=plain 2>&1 \
  | grep -E "(per-op|cold-start|static-skip|single expr|binding access|nested|string interp|inputParams)" \
  || echo "No benchmark output captured"

# ----------------------------------------------------------------------
# 2. bootJar 크기 (현재)
# ----------------------------------------------------------------------
print_section "2. bootJar size (current)"
./gradlew :station8-app:bootJar --console=plain >/dev/null
ls -la station8-app/build/libs/*.jar | awk '{ printf "Current: %.1f MB  %s\n", $5/1024/1024, $NF }'

# ----------------------------------------------------------------------
# 3. GraalVM 의존성 jar 합계
# ----------------------------------------------------------------------
print_section "3. GraalVM dependency jar sizes"
GRAALVM_DIR="$HOME/.gradle/caches/modules-2/files-2.1"
if [[ -d "$GRAALVM_DIR" ]]; then
  find "$GRAALVM_DIR/org.graalvm."* -name "*.jar" 2>/dev/null \
    | xargs -I{} ls -la {} 2>/dev/null \
    | awk '{ size+=$5; printf "  %.2f MB  %s\n", $5/1024/1024, $NF } END { printf "  ---\n  TOTAL:  %.1f MB\n", size/1024/1024 }' \
    | sort -k2 -n
else
  echo "Gradle cache not found at $GRAALVM_DIR — run a build first"
fi

# ----------------------------------------------------------------------
# 4. Baseline 비교 (옵션)
# ----------------------------------------------------------------------
if [[ -n "$BASELINE_SHA" ]]; then
  print_section "4. Baseline comparison ($BASELINE_SHA)"
  CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
  CURRENT_SIZE=$(ls -la station8-app/build/libs/*.jar | awk '{ print $5 }')

  git stash push -u -m "m16-perf-baseline-temp" >/dev/null 2>&1 || true
  git checkout "$BASELINE_SHA" >/dev/null 2>&1
  ./gradlew :station8-app:bootJar --console=plain >/dev/null
  BASELINE_SIZE=$(ls -la station8-app/build/libs/*.jar | awk '{ print $5 }')
  git checkout "$CURRENT_BRANCH" >/dev/null 2>&1
  git stash pop >/dev/null 2>&1 || true

  awk -v c="$CURRENT_SIZE" -v b="$BASELINE_SIZE" 'BEGIN {
    printf "  baseline:  %.1f MB\n", b/1024/1024
    printf "  current:   %.1f MB\n", c/1024/1024
    printf "  delta:    +%.1f MB (%.1f%%)\n", (c-b)/1024/1024, ((c-b)/b)*100
  }'
fi

# ----------------------------------------------------------------------
# 5. Docker 이미지 크기
# ----------------------------------------------------------------------
print_section "5. Docker image size"
if command -v docker >/dev/null 2>&1; then
  docker build -t station8-app:perf-measure -f docker/Dockerfile . >/dev/null 2>&1 \
    && docker images station8-app:perf-measure --format "  {{.Size}}  {{.Repository}}:{{.Tag}}" \
    || echo "  docker build failed — skip"
else
  echo "  docker not installed — skip"
fi

# ----------------------------------------------------------------------
# 6. (옵션) Docker compose 부팅 시간
# ----------------------------------------------------------------------
if [[ "$INCLUDE_BOOT" == "true" ]]; then
  print_section "6. Docker compose boot time (3-run avg)"
  TOTAL=0
  for i in 1 2 3; do
    docker compose -f docker/docker-compose.yml down -v >/dev/null 2>&1 || true
    START=$(date +%s)
    docker compose -f docker/docker-compose.yml up -d --build >/dev/null 2>&1
    until curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; do
      sleep 1
      if (( $(date +%s) - START > 180 )); then
        echo "  run $i: TIMEOUT (>180s)"
        continue 2
      fi
    done
    ELAPSED=$(( $(date +%s) - START ))
    echo "  run $i: ${ELAPSED}s"
    TOTAL=$(( TOTAL + ELAPSED ))
  done
  echo "  avg: $(( TOTAL / 3 ))s"
  docker compose -f docker/docker-compose.yml down -v >/dev/null 2>&1 || true
fi

print_section "Done"
echo "Update docs/decisions/m16-expression-engine.md '측정 결과' section with the numbers above."
