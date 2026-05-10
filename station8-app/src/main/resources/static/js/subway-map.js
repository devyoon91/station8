/* Station8 — Subway Map Renderer (#87 M1·M2·M3)
 *
 * 입력:
 *   {
 *     definitionId, definitionNm,
 *     nodes:[{id,name,activity,x,y}],
 *     edges:[{id,from,to}],
 *     statusByNode?: { [nodeId]: 'pending'|'running'|'completed'|'failed'|'untouched' }
 *   }
 * 옵션(opts):
 *   {
 *     interactive?: boolean   // M3: 클릭/hover 인터랙션 활성화 (default false)
 *     onStationClick?: (node) => void   // 호출자 콜백(또는 'subway:station-click' 이벤트 수신)
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
 * 인터랙션(M3, ``interactive: true``일 때만):
 *  - 역 hover    → 본인+의존 트랙·이웃 역 강조 ('hover' 클래스)
 *  - 역 click    → ``onStationClick(node)`` 호출 + ``subway:station-click`` 이벤트 디스패치
 *  - 트랙 hover  → 본인+양 끝 역 강조
 *
 * 모든 SVG 노드에 ``data-node-id`` / ``data-edge-id`` / ``data-from`` / ``data-to``를
 * 부착해 호출자 측 추가 처리(스크롤/플래시/inline 패널)를 가능하게 한다.
 *
 * 외부 트리거 API (#135 D5=a):
 *  - ``render()``는 컨트롤러 객체를 반환한다 — ``{focusStation(id), clearFocus()}``.
 *  - 외부 패널(좌측 stations list)에서 호출자가 명시적으로 노드 강조/스크롤/콜백 트리거를 일으킬 수 있다.
 *
 * 위상정렬 헬퍼:
 *  - ``Station8SubwayMap.topologicalOrder(graph)`` — 노드를 위상정렬 레이어 → 인덱스 순서로 정렬한 배열 반환.
 *    빌더/프리뷰의 stations list 정렬에 공통 사용.
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
    const options = opts || {};
    const interactive = !!options.interactive;

    target.innerHTML = '';
    if (graph.nodes.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'swe-subway-empty';
      empty.textContent = '역(Station)이 없습니다 — 빌더에서 추가해주세요.';
      target.appendChild(empty);
      // 빈 그래프도 호출자가 안전하게 컨트롤러 호출하도록 no-op 반환 (#135 D5=a)
      return { focusStation: function () {}, clearFocus: function () {} };
    }

    const layout = computeLayout(graph);
    const svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', `0 0 ${layout.width} ${layout.height}`);
    svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', `Subway map: ${graph.definitionNm || 'line'}`);
    if (interactive) svg.classList.add('interactive');

    const positionsById = layout.positions;
    const nodeById = {};
    graph.nodes.forEach(n => { nodeById[n.id] = n; });

    // 1) 트랙(엣지)
    (graph.edges || []).forEach(e => {
      const a = positionsById[e.from];
      const b = positionsById[e.to];
      if (!a || !b) return;
      const path = document.createElementNS(NS, 'path');
      path.setAttribute('d', cubicBetween(a, b));
      path.setAttribute('class', 'swe-subway-track');
      path.setAttribute('data-edge-id', e.id);
      path.setAttribute('data-from', e.from);
      path.setAttribute('data-to', e.to);
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
      circle.setAttribute('data-node-id', n.id);
      svg.appendChild(circle);

      // 클릭 적중률 향상용 투명 hit area (interactive 모드에서만 추가)
      if (interactive) {
        const hit = document.createElementNS(NS, 'circle');
        hit.setAttribute('cx', p.x);
        hit.setAttribute('cy', p.y);
        hit.setAttribute('r', STATION_R + 8);
        hit.setAttribute('class', 'swe-subway-station-hit');
        hit.setAttribute('data-node-id', n.id);
        svg.appendChild(hit);
      }

      const label = document.createElementNS(NS, 'text');
      label.setAttribute('x', p.x);
      label.setAttribute('y', p.y + STATION_R + LABEL_GAP);
      label.setAttribute('class', 'swe-subway-station-label');
      label.setAttribute('data-node-id', n.id);
      label.textContent = n.name || n.activity || n.id;
      svg.appendChild(label);

      if (n.activity && n.activity !== n.name) {
        const sub = document.createElementNS(NS, 'text');
        sub.setAttribute('x', p.x);
        sub.setAttribute('y', p.y + STATION_R + LABEL_GAP + 18);
        sub.setAttribute('class', 'swe-subway-station-sublabel');
        sub.setAttribute('data-node-id', n.id);
        sub.textContent = n.activity;
        svg.appendChild(sub);
      }
    });

    target.appendChild(svg);

    if (interactive) attachInteractions(svg, target, nodeById, graph, options);

    // 컨트롤러 객체 반환 — 외부 트리거 API (#135 D5=a)
    return {
      /**
       * 특정 노드를 강조하고 viewport 중앙으로 스크롤한 뒤 onStationClick 콜백을 호출한다.
       * 호출자는 이걸 통해 "좌측 list 클릭 → 노선도 강조 + detail 패널 동시 노출"을 한 줄로 처리.
       */
      focusStation: function (nodeId) {
        if (!nodeId || !nodeById[nodeId]) return;
        // 1) 기존 .focused 제거
        svg.querySelectorAll('.focused').forEach(function (el) { el.classList.remove('focused'); });
        // 2) 새 노드 강조 (모든 data-node-id 매칭 요소 — circle / hit / label)
        const els = svg.querySelectorAll('[data-node-id="' + cssEscape(nodeId) + '"]');
        els.forEach(function (el) { el.classList.add('focused'); });
        // 3) viewport에 보이게 스크롤 (큰 DAG 대응)
        const circle = svg.querySelector('circle[data-node-id="' + cssEscape(nodeId) + '"]');
        if (circle && typeof circle.scrollIntoView === 'function') {
          circle.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' });
        }
        // 4) 사용자 클릭과 동일한 콜백 호출 — detail 패널 노출 등
        if (typeof options.onStationClick === 'function') {
          options.onStationClick(nodeById[nodeId]);
        }
        target.dispatchEvent(new CustomEvent('subway:station-click', {
          detail: { node: nodeById[nodeId], source: 'focus' }, bubbles: true
        }));
      },
      clearFocus: function () {
        svg.querySelectorAll('.focused').forEach(function (el) { el.classList.remove('focused'); });
      }
    };
  }

  /**
   * 호버/클릭 이벤트를 SVG 위임으로 처리한다. 어떤 SVG 자식이든
   * ``data-node-id`` 또는 ``data-edge-id``를 들고 있으면 단일 origin으로 묶여
   * hover/click 강조가 일관되게 동작한다.
   */
  function attachInteractions(svg, target, nodeById, graph, options) {
    const hoverNode = (id) => setHover(svg, id ? { type: 'node', id } : null, graph);
    const hoverEdge = (id) => setHover(svg, id ? { type: 'edge', id } : null, graph);

    svg.addEventListener('mouseover', (ev) => {
      const t = ev.target;
      if (!(t instanceof Element)) return;
      const nodeId = t.getAttribute('data-node-id');
      const edgeId = t.getAttribute('data-edge-id');
      if (nodeId) hoverNode(nodeId);
      else if (edgeId) hoverEdge(edgeId);
    });
    svg.addEventListener('mouseout', (ev) => {
      const related = ev.relatedTarget;
      if (related && svg.contains(related)) return; // SVG 안에서의 이동은 무시
      hoverNode(null);
    });

    svg.addEventListener('click', (ev) => {
      const t = ev.target;
      if (!(t instanceof Element)) return;
      const nodeId = t.getAttribute('data-node-id');
      if (!nodeId) return;
      const node = nodeById[nodeId];
      if (!node) return;
      if (typeof options.onStationClick === 'function') {
        options.onStationClick(node);
      }
      target.dispatchEvent(new CustomEvent('subway:station-click', {
        detail: { node }, bubbles: true
      }));
    });
  }

  function setHover(svg, target, graph) {
    // 기존 highlight 제거
    svg.querySelectorAll('.hover').forEach(el => el.classList.remove('hover'));
    if (!target) return;

    if (target.type === 'node') {
      // 본인 + 인접 트랙 + 인접 역 강조
      const nodeEls = svg.querySelectorAll(`[data-node-id="${cssEscape(target.id)}"]`);
      nodeEls.forEach(el => el.classList.add('hover'));
      (graph.edges || []).forEach(e => {
        if (e.from === target.id || e.to === target.id) {
          const edgeEl = svg.querySelector(`[data-edge-id="${cssEscape(e.id)}"]`);
          if (edgeEl) edgeEl.classList.add('hover');
          const otherId = e.from === target.id ? e.to : e.from;
          svg.querySelectorAll(`[data-node-id="${cssEscape(otherId)}"]`)
              .forEach(el => el.classList.add('hover'));
        }
      });
    } else if (target.type === 'edge') {
      const edgeEl = svg.querySelector(`[data-edge-id="${cssEscape(target.id)}"]`);
      if (edgeEl) edgeEl.classList.add('hover');
      const edge = (graph.edges || []).find(e => e.id === target.id);
      if (edge) {
        svg.querySelectorAll(`[data-node-id="${cssEscape(edge.from)}"]`)
            .forEach(el => el.classList.add('hover'));
        svg.querySelectorAll(`[data-node-id="${cssEscape(edge.to)}"]`)
            .forEach(el => el.classList.add('hover'));
      }
    }
  }

  function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(String(s));
    return String(s).replace(/["\\]/g, '\\$&');
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

  /**
   * 그래프를 위상정렬 레이어 → 같은 레이어 내 입력순으로 정렬한 노드 배열을 반환한다 (#135).
   * stations list 정렬에 사용. 사이클이 있으면 그 노드는 레이어 0으로 fallback (layerByTopology와 동일).
   *
   * @param {object} graph - { nodes, edges }
   * @returns {Array} 정렬된 노드 배열 (graph.nodes 원소 그대로). 위상 순서 + 같은 레이어 내 원본 순서 유지.
   */
  function topologicalOrder(graph) {
    if (!graph || !Array.isArray(graph.nodes) || graph.nodes.length === 0) return [];
    const inDeg = {};
    const adj = {};
    graph.nodes.forEach(function (n) { inDeg[n.id] = 0; adj[n.id] = []; });
    (graph.edges || []).forEach(function (e) {
      if (adj[e.from] && inDeg[e.to] !== undefined) {
        adj[e.from].push(e.to);
        inDeg[e.to] += 1;
      }
    });
    const layer = {};
    const queue = graph.nodes.filter(function (n) { return inDeg[n.id] === 0; }).map(function (n) { return n.id; });
    queue.forEach(function (id) { layer[id] = 0; });
    while (queue.length) {
      const id = queue.shift();
      (adj[id] || []).forEach(function (to) {
        const next = (layer[id] || 0) + 1;
        if (next > (layer[to] || 0)) layer[to] = next;
        inDeg[to] -= 1;
        if (inDeg[to] === 0) queue.push(to);
      });
    }
    graph.nodes.forEach(function (n) { if (layer[n.id] === undefined) layer[n.id] = 0; });
    // 같은 레이어 내에선 graph.nodes 원본 순서 유지
    const indexById = {};
    graph.nodes.forEach(function (n, i) { indexById[n.id] = i; });
    return graph.nodes.slice().sort(function (a, b) {
      const la = layer[a.id], lb = layer[b.id];
      if (la !== lb) return la - lb;
      return indexById[a.id] - indexById[b.id];
    });
  }

  global.Station8SubwayMap = {
    render: renderSubwayMap,
    topologicalOrder: topologicalOrder
  };
})(window);
