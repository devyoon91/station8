// k6 smoke test — 핵심 경로 1회 (정의 등록 → 실행 → 대시보드 응답)
// 실행: k6 run smoke.js
// 참고: 동일 시나리오의 bash 버전은 scripts/scenarios/01-create-dag.sh + 02-run-now.sh
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    http_req_duration: ['p(95)<1000'],     // smoke는 캐시/워밍 후라 1s 이내
    http_req_failed: ['rate<0.01'],        // 1건이라 0이어야 정상
  },
};

export default function () {
  // 1) DAG 정의 등록
  const defPayload = JSON.stringify({
    definitionNm: `k6-smoke-${Date.now()}`,
    description: 'k6 smoke',
    nodes: [
      { nodeId: 's-n', nodeNm: 'Only', activityNm: 'NOOP', inputParams: '{}', posX: 0, posY: 0 },
    ],
    edges: [],
  });

  const defRes = http.post(`${BASE_URL}/api/workflow/definitions`, defPayload, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(defRes, {
    'definition created (201)': (r) => r.status === 201,
  });
  const definitionId = defRes.json('definitionId');

  // 2) 즉시 실행
  const runRes = http.post(`${BASE_URL}/api/workflow/definitions/${definitionId}/run`,
    JSON.stringify({ input: 'smoke' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(runRes, {
    'instance started (201)': (r) => r.status === 201,
    'instanceId returned': (r) => r.json('instanceId') !== undefined,
  });

  // 3) 대시보드 페이지 응답
  const dashRes = http.get(`${BASE_URL}/workflow/dashboard`);
  check(dashRes, {
    'dashboard 200': (r) => r.status === 200,
  });

  sleep(0.5);
}
