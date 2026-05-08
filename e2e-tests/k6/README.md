# k6 부하 시나리오

[k6](https://k6.io/) 기반 부하/시나리오 테스트.

## 사전 조건

1. k6 설치: `winget install k6.k6` (Windows) / `brew install k6` (macOS) / `apt install k6` (Linux)
2. station8-app 가동: `./gradlew composeUpApp`

## 시나리오

| 파일 | 목적 | 실행 명령 |
|------|------|----------|
| `smoke.js` | 핵심 경로 1회 — 정의 등록 → 실행 → 대시보드 응답 | `k6 run smoke.js` |
| `load.js` | 100 VU × 30초 — 인스턴스 시작 처리량 측정 | `k6 run load.js` |
| `stress.js` | VU를 50→200으로 ramp — 폴러 backpressure 한계점 | `k6 run stress.js` |

기본 호스트: `http://localhost:8080`. 다른 호스트는 `BASE_URL` 환경변수로 override:

```bash
k6 run -e BASE_URL=http://staging.local:8080 smoke.js
```

## Thresholds

각 스크립트에 미리 정의된 thresholds:

- **smoke**: 모든 요청 < 1s (p95)
- **load**: p95 < 2s, error rate < 1%
- **stress**: p95 < 5s, error rate < 5% (backpressure 허용)

threshold 실패 시 k6는 exit 99로 종료 → CI에서 fail로 처리.

## 출력

기본은 stdout 요약. JSON 결과 저장:

```bash
k6 run --out json=load-result.json load.js
```

Grafana k6 dashboard 연동:

```bash
k6 run --out cloud load.js
```

## 관련 문서

- [scripts/scenarios/](../../scripts/scenarios/) — bash 회귀 시나리오 (기능 검증)
- [e2e-tests](../) — REST Assured 회귀 테스트 (기능 검증)
