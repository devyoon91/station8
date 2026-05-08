/* Station8 — Subway Map Renderer (#87 M1·M2)
 *
 * 입력:
 *   {
 *     definitionId, definitionNm,
 *     nodes:[{id,name,activity,x,y}],
 *     edges:[{id,from,to}],
 *     statusByNode?: { [nodeId]: 'pending'|'running'|'completed'|'failed'|'untouched' }
 *   }
 *
 * 출력: 컨테이너 안에 인라인 SVG로 노선도 한 장.
 *
 * 좌표 처리:
 *  - posX/posY가 있으면 그대로 쓴다 (Drawflow가 빌더 단계에서 채워둔 값).
 *  - 모두 0이면 단순 위상정렬 + 가로 레이어드 폴백(현재 시드 라인 대응).
 *
 * 트랙 곡선:
 *  - 두 역 사이를 cubic bezier로 잇는다. 제어점은 두 역 간 dx의 절반을 수평 오프셋으로.
 *
 * 상태 표시(M2):
 *  - running:   외곽 후광(halo)이 SMIL ``<animate>``로 점멸
 *  - completed: 채워진 원 (트랙 색)
 *  - failed:    채워진 원 (적색)
 *  - pending:   가벼운 펄스
 *  - untouched: 기본 외곽선만
 *
 * M3(클릭 → 액티비티 상세, 트랙 hover → 의존성)는 후속.
 */
(function (global) {
  'use strict';

  const NS = 'http://www.w3.org/2000/svg';
  const STATION_R = 12;
  const PADDING = 60;
  const LABEL_GAP = 16;

  /**
   * @param {HTMLElement} target
   * @param {object} graph
   * @param {object} [opts]
   */
  function renderSubwayMap(target, graph, opts) {
    if (!target) throw new Error('subway-map: target 필수');
    if (!graph || !Array.isArray(graph.nodes)) throw new Error('subway-map: graph.nodes 필수');

    target.innerHTML = '';
    if (graph.nodes.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'swe-subway-empty';
      empty.textContent = '역(Station)이 없습니다 — 빌더에서 추가해주세요.';
      target.appendChild(empty);
      return;
    }

    const layout = computeLayout(graph);
    const svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', `0 0 ${layout.width} ${layout.height}`);
    svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', `Subway map: ${graph.definitionNm || 'line'}`);

    const positionsById = layout.positions;

    // 1) 트랙(엣지)
    (graph.edges || []).forEach(e => {
      const a = positionsById[e.from];
      const b = positionsById[e.to];
      if (!a || !b) return;
      const path = document.createElementNS(NS, 'path');
      path.setAttribute('d', cubicBetween(a, b));
      path.setAttribute('class', 'swe-subway-track');
      svg.appendChild(path);
    });

    // 2) 역(노드)
    const statusByNode = (graph.statusByNode || {});
    graph.nodes.forEach(n => {
      const p = positionsById[n.id];
      if (!p) return;
      const status = statusByNode[n.id] || 'untouched';

      // running: 외곽 후광(halo) — SMIL <animate>로 r 펄스
      if (status === 'running') {
        const halo = document.createElementNS(NS, 'circle');
        halo.setAttribute('cx', p.x);
        halo.setAttribute('cy', p.y);
        halo.setAttribute('r', STATION_R + 4);
        halo.setAttribute('class', 'swe-subway-station-halo');
        const anim = document.createElementNS(NS, 'animate');
        anim.setAttribute('attributeName', 'r');
        anim.setAttribute('values', `${STATION_R + 2};${STATION_R + 12};${STATION_R + 2}`);
        anim.setAttribute('dur', '1.4s');
        anim.setAttribute('repeatCount', 'indefinite');
        halo.appendChild(anim);
        const animO = document.createElementNS(NS, 'animate');
        animO.setAttribute('attributeName', 'opacity');
        animO.setAttribute('values', '0.55;0;0.55');
        animO.setAttribute('dur', '1.4s');
        animO.setAttribute('repeatCount', 'indefinite');
        halo.appendChild(animO);
        svg.appendChild(halo);
      }

      const circle = document.createElementNS(NS, 'circle');
      circle.setAttribute('cx', p.x);
      circle.setAttribute('cy', p.y);
      circle.setAttribute('r', STATION_R);
      circle.setAttribute('class', `swe-subway-station-circle ${status}`);
      svg.appendChild(circle);

      const label = document.createElementNS(NS, 'text');
      label.setAttribute('x', p.x);
      label.setAttribute('y', p.y + STATION_R + LABEL_GAP);
      label.setAttribute('class', 'swe-subway-station-label');
      label.textContent = n.name || n.activity || n.id;
      svg.appendChild(label);

      if (n.activity && n.activity !== n.name) {
        const sub = document.createElementNS(NS, 'text');
        sub.setAttribute('x', p.x);
        sub.setAttribute('y', p.y + STATION_R + LABEL_GAP + 18);
        sub.setAttribute('class', 'swe-subway-station-sublabel');
        sub.textContent = n.activity;
        svg.appendChild(sub);
      }
    });

    target.appendChild(svg);
  }

  function cubicBetween(a, b) {
    const dx = b.x - a.x;
    const cx1 = a.x + dx * 0.5;
    const cx2 = b.x - dx * 0.5;
    return `M ${a.x} ${a.y} C ${cx1} ${a.y}, ${cx2} ${b.y}, ${b.x} ${b.y}`;
  }

  /**
   * posX/posY가 모두 채워져 있으면 그대로 사용 (단, viewBox에 맞게 정규화).
   * 하나라도 비어 있거나 전부 0이면 위상정렬 기반 가로 레이어드 폴백.
   */
  function computeLayout(graph) {
    const usePersisted = hasPersistedPositions(graph.nodes);
    const raw = {};
    if (usePersisted) {
      graph.nodes.forEach(n => { raw[n.id] = { x: n.x, y: n.y }; });
    } else {
      const layered = layerByTopology(graph);
      Object.assign(raw, layered);
    }
    return normalize(raw, graph.nodes);
  }

  function hasPersistedPositions(nodes) {
    if (nodes.length < 2) return false;
    let nonZero = 0;
    for (const n of nodes) {
      if ((n.x | 0) !== 0 || (n.y | 0) !== 0) nonZero++;
    }
    return nonZero >= Math.ceil(nodes.length * 0.5);
  }

  function layerByTopology(graph) {
    const inDeg = {};
    const adj = {};
    graph.nodes.forEach(n => { inDeg[n.id] = 0; adj[n.id] = []; });
    (graph.edges || []).forEach(e => {
      if (adj[e.from] && inDeg[e.to] !== undefined) {
        adj[e.from].push(e.to);
        inDeg[e.to] += 1;
      }
    });

    const layer = {};
    const queue = graph.nodes.filter(n => inDeg[n.id] === 0).map(n => n.id);
    queue.forEach(id => { layer[id] = 0; });
    while (queue.length) {
      const id = queue.shift();
      (adj[id] || []).forEach(to => {
        const next = (layer[id] || 0) + 1;
        if (next > (layer[to] || 0)) layer[to] = next;
        inDeg[to] -= 1;
        if (inDeg[to] === 0) queue.push(to);
      });
    }
    // 사이클이 남아 있으면 임의 레이어 0으로
    graph.nodes.forEach(n => { if (layer[n.id] === undefined) layer[n.id] = 0; });

    // 같은 레이어 내 분기는 세로로 분산
    const buckets = {};
    graph.nodes.forEach(n => {
      const l = layer[n.id];
      (buckets[l] = buckets[l] || []).push(n.id);
    });

    const positions = {};
    const xStep = 200;
    const yStep = 140;
    Object.keys(buckets).forEach(l => {
      const ids = buckets[l];
      ids.forEach((id, idx) => {
        positions[id] = {
          x: (l | 0) * xStep,
          y: (idx - (ids.length - 1) / 2) * yStep
        };
      });
    });
    return positions;
  }

  function normalize(raw, nodes) {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    nodes.forEach(n => {
      const p = raw[n.id];
      if (!p) return;
      if (p.x < minX) minX = p.x;
      if (p.y < minY) minY = p.y;
      if (p.x > maxX) maxX = p.x;
      if (p.y > maxY) maxY = p.y;
    });
    if (!isFinite(minX)) { minX = 0; minY = 0; maxX = 0; maxY = 0; }

    const positions = {};
    nodes.forEach(n => {
      const p = raw[n.id];
      if (!p) return;
      positions[n.id] = {
        x: PADDING + (p.x - minX),
        y: PADDING + (p.y - minY)
      };
    });

    const width = (maxX - minX) + PADDING * 2;
    const height = (maxY - minY) + PADDING * 2 + 40; // 라벨 여유
    return { positions, width: Math.max(width, 320), height: Math.max(height, 240) };
  }

  global.Station8SubwayMap = { render: renderSubwayMap };
})(window);
