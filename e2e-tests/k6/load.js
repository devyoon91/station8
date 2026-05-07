// k6 load test — 100 VU × 30초로 인스턴스 시작 처리량 측정
// 실행: k6 run load.js
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    constant_load: {
      executor: 'constant-vus',
      vus: 100,
      duration: '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000'],     // 부하 중에도 p95 < 2s
    http_req_failed: ['rate<0.01'],        // 에러율 1% 미만
    'http_req_duration{group:::run-now}': ['p(99)<3000'],
  },
};

// 사전: 셋업 단계에서 정의 1개 등록 → 모든 VU가 공유
export function setup() {
  const defPayload = JSON.stringify({
    definitionNm: `k6-load-${Date.now()}`,
    description: 'k6 load shared definition',
    nodes: [
      { nodeId: 'l-n', nodeNm: 'Only', activityNm: 'NOOP', inputParams: '{}', posX: 0, posY: 0 },
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
  const payload = JSON.stringify({ input: `vu-${__VU}-iter-${__ITER}` });

  const res = http.post(url, payload, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /run', group: 'run-now' },
  });

  check(res, {
    'run-now 201': (r) => r.status === 201,
  });
}
