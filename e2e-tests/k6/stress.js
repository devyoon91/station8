// k6 stress test — VU 50 → 200으로 ramp하며 폴러 backpressure 한계점 측정
// 실행: k6 run stress.js
// CallerRunsPolicy 동작 시 throughput이 plateau에 도달하고 latency가 급증하는지 관찰.
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    ramp_stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '1m',  target: 100 },
        { duration: '1m',  target: 200 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<5000'],     // backpressure 시 p95 5s까지 허용
    http_req_failed: ['rate<0.05'],        // 에러율 5% 미만
  },
};

export function setup() {
  const defPayload = JSON.stringify({
    definitionNm: `k6-stress-${Date.now()}`,
    description: 'k6 stress shared definition',
    nodes: [
      { nodeId: 's-n', nodeNm: 'Only', activityNm: 'NOOP', inputParams: '{}', posX: 0, posY: 0 },
    ],
    edges: [],
  });
  const r = http.post(`${BASE_URL}/api/workflow/definitions`, defPayload, {
    headers: { 'Content-Type': 'application/json' },
  });
  if (r.status !== 201) {
    throw new Error(`setup: definition create failed (${r.status}): ${r.body}`);
  }
  return { definitionId: r.json('definitionId') };
}

export default function (data) {
  const url = `${BASE_URL}/api/workflow/definitions/${data.definitionId}/run`;
  const res = http.post(url, JSON.stringify({ input: `stress-${__VU}-${__ITER}` }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'run-now ok': (r) => r.status === 201 || r.status === 503 });
  // 503은 backpressure로 거절된 정상 케이스 — 통계에 포함되도록 분리하지 않음
}
