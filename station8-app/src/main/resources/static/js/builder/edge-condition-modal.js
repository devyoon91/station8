/*
 * Edge utilities + SpEL condition modal (#152).
 *
 * #181 PR-4: index.js에서 분리. 엣지 관련 4종 결합:
 *   1) 파싱/키 — parseConnectionFromElement, edgeKey
 *   2) 액션   — disconnectAllEdges, disconnectEdge
 *   3) 상태   — edgeConditions Map, editingEdgeInfo
 *   4) 모달   — open/close/clear/saveEdgeCond + 시각화 refreshEdgeConditionVisualization
 *
 * 의존 globals — `editor`, `setStatus`. 본 파일은 index.js 보다 먼저 로드 가능하도록
 * top-level `editor.on(...)` 바인딩을 `bindEdgeConditionEditorEvents()` 함수로 래핑.
 * index.js 부트스트랩(`editor` 생성 후)에서 명시적으로 호출한다.
 */

/**
 * Drawflow 연결(SVG)의 클래스명에서 from/to/output_class/input_class 추출.
 * Drawflow 0.0.59는 ``<svg class="connection node_in_node-X node_out_node-Y input_M output_N">`` 형태.
 */
function parseConnectionFromElement(el) {
    if (!el) return null;
    const conn = el.closest('.connection');
    if (!conn) return null;
    let from = null, to = null, inputClass = null, outputClass = null;
    conn.classList.forEach(c => {
        let m;
        if ((m = c.match(/^node_in_node-(\d+)$/))) to = m[1];
        else if ((m = c.match(/^node_out_node-(\d+)$/))) from = m[1];
        else if (/^input_\d+$/.test(c)) inputClass = c;
        else if (/^output_\d+$/.test(c)) outputClass = c;
    });
    if (from && to && inputClass && outputClass) {
        return { from: parseInt(from, 10), to: parseInt(to, 10), inputClass, outputClass };
    }
    return null;
}

/** 노드의 모든 in/out 엣지를 일괄 제거 (#136 D1=b). */
function disconnectAllEdges(id) {
    const data = editor.export().drawflow.Home.data;
    const node = data[id];
    if (!node) return;
    let count = 0;
    // outgoing — node.outputs[outputClass].connections = [{node:targetId, output:'input_N'}, ...]
    Object.keys(node.outputs || {}).forEach(outClass => {
        ((node.outputs[outClass].connections) || []).slice().forEach(c => {
            try { editor.removeSingleConnection(id, c.node, outClass, c.output); count++; }
            catch (e) { console.warn('removeSingleConnection (out) failed', e); }
        });
    });
    // incoming — node.inputs[inputClass].connections = [{node:sourceId, input:'output_N'}, ...]
    Object.keys(node.inputs || {}).forEach(inClass => {
        ((node.inputs[inClass].connections) || []).slice().forEach(c => {
            try { editor.removeSingleConnection(c.node, id, c.input, inClass); count++; }
            catch (e) { console.warn('removeSingleConnection (in) failed', e); }
        });
    });
    setStatus('Disconnected ' + count + ' edges from #' + id);
}

function disconnectEdge(info) {
    try {
        edgeConditions.delete(edgeKey(info));
        editor.removeSingleConnection(info.from, info.to, info.outputClass, info.inputClass);
        setStatus('Disconnected #' + info.from + ' → #' + info.to);
    } catch (e) {
        setStatus('Disconnect failed: ' + e.message);
    }
}

// ===== #152 — 엣지 조건식 (SpEL) =====

// Drawflow는 엣지에 임의 데이터 attach API가 없어서 외부 Map으로 관리.
// 키: "from|to|outputClass|inputClass" — 한 노드 쌍에 같은 포트 조합 1개만.
// form-serializer.js (save/restore) 가 이 Map을 읽고 씀.
const edgeConditions = new Map();
let editingEdgeInfo = null;

function edgeKey(info) {
    return info.from + '|' + info.to + '|' + info.outputClass + '|' + info.inputClass;
}

window.openEdgeCondModal = function (info) {
    editingEdgeInfo = info;
    const existing = edgeConditions.get(edgeKey(info)) || '';
    document.getElementById('edge-cond-info').textContent =
        'Edge: #' + info.from + ' → #' + info.to + '  (' + info.outputClass + ' → ' + info.inputClass + ')';
    document.getElementById('edge-cond-input').value = existing;
    document.getElementById('edge-cond-error').style.display = 'none';
    document.getElementById('edge-cond-backdrop').style.display = 'block';
    document.getElementById('edge-cond-modal').style.display = 'block';
    setTimeout(() => document.getElementById('edge-cond-input').focus(), 50);
};

window.closeEdgeCondModal = function () {
    editingEdgeInfo = null;
    document.getElementById('edge-cond-backdrop').style.display = 'none';
    document.getElementById('edge-cond-modal').style.display = 'none';
};

window.clearEdgeCond = function () {
    if (!editingEdgeInfo) return;
    edgeConditions.delete(edgeKey(editingEdgeInfo));
    setStatus('Cleared condition: #' + editingEdgeInfo.from + ' → #' + editingEdgeInfo.to);
    closeEdgeCondModal();
    refreshEdgeConditionVisualization();
};

window.saveEdgeCond = function () {
    if (!editingEdgeInfo) return;
    const expr = document.getElementById('edge-cond-input').value.trim();
    if (!expr) {
        // 빈 값은 condition 제거와 동일
        edgeConditions.delete(edgeKey(editingEdgeInfo));
    } else {
        edgeConditions.set(edgeKey(editingEdgeInfo), expr);
    }
    setStatus(expr
        ? 'Saved condition on #' + editingEdgeInfo.from + ' → #' + editingEdgeInfo.to
        : 'Cleared condition: #' + editingEdgeInfo.from + ' → #' + editingEdgeInfo.to);
    closeEdgeCondModal();
    refreshEdgeConditionVisualization();
};

document.getElementById('edge-cond-backdrop').addEventListener('click', closeEdgeCondModal);
document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && document.getElementById('edge-cond-modal').style.display === 'block') {
        closeEdgeCondModal();
    }
});

/**
 * 모든 엣지를 walk하면서 조건식이 있으면 .has-condition 클래스 + 라벨 추가.
 * Drawflow는 connection을 다시 그릴 때마다 클래스가 reset되므로 connection 이벤트마다 재적용.
 */
function refreshEdgeConditionVisualization() {
    // 기존 라벨 모두 제거
    document.querySelectorAll('.swe-edge-cond-label').forEach(el => el.remove());
    document.querySelectorAll('.connection.has-condition').forEach(el => el.classList.remove('has-condition'));

    const drawflowEl = document.getElementById('drawflow');
    document.querySelectorAll('.drawflow .connection').forEach(conn => {
        const info = parseConnectionFromElement(conn);
        if (!info) return;
        const expr = edgeConditions.get(edgeKey(info));
        if (!expr) return;
        conn.classList.add('has-condition');

        // 엣지 중간에 라벨 위치 — path bbox 중앙 (대략값)
        const path = conn.querySelector('.main-path');
        if (path) {
            try {
                const bbox = path.getBBox();
                const cx = bbox.x + bbox.width / 2;
                const cy = bbox.y + bbox.height / 2;
                const label = document.createElement('div');
                label.className = 'swe-edge-cond-label';
                label.textContent = expr.length > 30 ? expr.substring(0, 28) + '…' : expr;
                label.title = 'SpEL: ' + expr;
                label.style.left = cx + 'px';
                label.style.top = cy + 'px';
                drawflowEl.appendChild(label);
            } catch (e) {
                // SVG 미렌더 상태 — 그냥 dashed line만
            }
        }
    });
}

/**
 * editor 이벤트 바인딩 — connection 변경 시점마다 시각화 재적용.
 * top-level이 아닌 함수에 두어 본 모듈을 index.js 보다 먼저 로드해도 안전 (editor 미존재 시 즉시 실패 없음).
 * index.js 부트스트랩에서 명시 호출.
 */
function bindEdgeConditionEditorEvents() {
    editor.on('connectionCreated', () => setTimeout(refreshEdgeConditionVisualization, 0));
    editor.on('connectionRemoved', () => setTimeout(refreshEdgeConditionVisualization, 0));
    editor.on('translate', () => setTimeout(refreshEdgeConditionVisualization, 0));
    editor.on('zoom', () => setTimeout(refreshEdgeConditionVisualization, 0));
}
