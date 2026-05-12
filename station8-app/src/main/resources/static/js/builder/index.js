/*
 * Builder entrypoint — Drawflow + 우클릭 메뉴 + 단축키 + 엣지 조건 + 모바일 overlay + 저장/복원.
 *
 * #181 PR-1: builder.mustache의 inline <script> (~990줄) 전체를 이 파일로 통째 이전. 로직 변경 zero.
 * 모바일 분리(#181의 7개 모듈 split)는 후속 PR — 본 파일은 1차 추출(extract-monolith)이라
 * 함수 경계만 보전하고, 코드 단위 재배치 / state 캡슐화 등은 후속 PR에서 수행.
 *
 * Mustache로 주입되던 EDIT_MODE / EDIT_DEFINITION_ID 두 값은 페이지의
 * `<script id="builder-config" type="application/json">` data island에서 읽음.
 */

const __builderConfig = JSON.parse(document.getElementById('builder-config').textContent);

const editor = new Drawflow(document.getElementById('drawflow'));
// reroute 비활성 — 엣지 더블클릭으로 의도치 않게 분할되는 UX 방지.
// 라우팅 점이 필요하면 별도 토글로 추가 (현재는 깔끔한 그래프 유지가 우선).
editor.reroute = false;
editor.start();

// 편집 모드 식별 (#99) — data island에서 주입
const EDIT_MODE = __builderConfig.editMode;
const EDIT_DEFINITION_ID = __builderConfig.editDefinitionId;

let activities = [];
let connectMode = null;  // {from: nodeId} when waiting for second click
let selectedNodeId = null;  // #151 — Drawflow 선택 노드 추적 (Delete 단축키 대상)

// ===== Palette load =====
async function loadActivities() {
    const palette = document.getElementById('palette');
    try {
        const res = await fetch('/api/line/activities');
        activities = await res.json();
        palette.innerHTML = '';
        activities.forEach(a => {
            const div = document.createElement('div');
            div.className = 'swe-palette-item';
            div.draggable = true;
            div.dataset.activity = a.activityName;
            div.innerHTML = '<strong>' + a.activityName + '</strong>' +
                '<div class="meta">retry ' + a.retryCount + ' · backoff ' + a.backoffSeconds + 's</div>';
            div.addEventListener('dragstart', e => {
                e.dataTransfer.setData('activity', a.activityName);
            });
            // PR-2: 모바일 tap-to-add — drag 대신 클릭 1회로 캔버스 중앙에 노드 생성 후 패널 자동 닫힘.
            div.addEventListener('click', () => {
                if (!isMobile()) return;
                addActivityNodeAtCenter(a.activityName);
                closeMobilePanels();
            });
            palette.appendChild(div);
        });
    } catch (e) {
        palette.innerHTML = '<div style="color: var(--accent-red);">Failed to load activities: ' + e.message + '</div>';
    }
}

// ===== Drop on canvas =====
const canvas = document.getElementById('drawflow');
canvas.addEventListener('dragover', e => e.preventDefault());
canvas.addEventListener('drop', e => {
    e.preventDefault();
    const activity = e.dataTransfer.getData('activity');
    if (!activity) return;
    const rect = canvas.getBoundingClientRect();
    const pos_x = e.clientX - rect.left;
    const pos_y = e.clientY - rect.top;
    // 라벨 div는 placeholder(&#160;)로 미리 채워 박스 사이즈가 처음부터 최종 사이즈와 동일하게.
    // 빈 div와 채워진 div의 height 차이로 Drawflow가 포트 좌표를 잘못 계산하던 회귀 차단.
    const html = '<div><strong>' + activity + '</strong>' +
        '<div data-label-id style="font-size:11px; color:var(--body); margin-top:4px; line-height:1; min-height:11px;">&#160;</div></div>';
    const nodeId = editor.addNode(activity, 1, 1, pos_x, pos_y, activity, {activityNm: activity, inputParams: '', datasourceBindings: ''}, html);
    // 동기적으로 라벨 채움 — setTimeout race 회피 (placeholder와 final 모두 1줄 텍스트라 height 변동 zero)
    const newNodeEl = document.getElementById('node-' + nodeId);
    if (newNodeEl) {
        const labelEl = newNodeEl.querySelector('[data-label-id]');
        if (labelEl) labelEl.textContent = '#' + nodeId;
    }
    setStatus('Added node #' + nodeId + ' (' + activity + ')');
});

// ===== Node selection → properties panel =====
function populateProps(id) {
    const node = editor.getNodeFromId(id);
    if (!node) return;
    // #206 — 노드 선택 시 우측 패널을 Properties 탭으로 자동 전환
    if (typeof switchPropTab === 'function') switchPropTab('props');
    // #205 PR-2 — 모바일에선 properties overlay 자동 노출
    if (typeof openMobilePanel === 'function' && typeof isMobile === 'function' && isMobile()) {
        openMobilePanel('properties');
    }
    const props = document.getElementById('props');
    props.innerHTML = '<div class="swe-stack">' +
        '<div><label>Node ID</label><input class="swe-input" value="' + id + '" disabled></div>' +
        '<div><label>Activity</label><input class="swe-input" value="' + node.data.activityNm + '" disabled></div>' +
        '<div><label>Input params (JSON)</label><textarea class="swe-input" rows="6" id="paramsInput" style="height: auto; padding: 8px;" placeholder="{}">' + (node.data.inputParams || '') + '</textarea></div>' +
        '<div><label>DataSource bindings (JSON)</label><textarea class="swe-input" rows="3" id="bindingsInput" style="height: auto; padding: 8px;" placeholder=\'{"source":"oracle-prod","target":"mart"}\'>' + (node.data.datasourceBindings || '') + '</textarea><div class="swe-mute" style="font-size: 11px; margin-top: 4px;">role → DataSource 이름. 액티비티가 <code>@BoundDataSource("role") JdbcTemplate</code>로 받아씀. 비우면 모두 primary fallback (#113).</div></div>' +
        '<button class="swe-btn-tertiary" onclick="updateParams(' + id + ')">Update params + bindings</button>' +
        '<button class="swe-btn-tertiary swe-btn-danger" onclick="deleteNode(' + id + ')">Delete node</button>' +
        '<hr style="border: none; border-top: 1px solid var(--hairline); margin: var(--space-md) 0;">' +
        '<button class="swe-btn-tertiary swe-w-full" onclick="startConnect(' + id + ')">Connect from this node</button>' +
        (connectMode ? '<button class="swe-btn-tertiary swe-w-full" onclick="cancelConnect()">Cancel connect</button>' : '') +
        '</div>';
}
editor.on('nodeSelected', id => { selectedNodeId = id; populateProps(id); });
editor.on('nodeUnselected', () => { selectedNodeId = null; });
editor.on('nodeRemoved', () => { selectedNodeId = null; });

// ===== #205 PR-2 — 모바일 panel overlay 제어 =====
// 데스크톱/태블릿에선 CSS @media로 FAB/backdrop이 숨겨져 아래 함수는 호출되지 않음.
const MOBILE_MQ = window.matchMedia('(max-width: 767px)');
function isMobile() { return MOBILE_MQ.matches; }

function openMobilePanel(which) {
    closeMobilePanels();
    const target = which === 'palette'
        ? document.querySelector('.swe-palette')
        : document.querySelector('.swe-properties');
    target.classList.add('swe-mobile-open');
    document.getElementById('mobile-backdrop').classList.add('swe-mobile-open');
    document.getElementById('mobile-fab-group').classList.add(which + '-open');
}
function closeMobilePanels() {
    document.querySelectorAll('.swe-palette, .swe-properties, .swe-mobile-backdrop')
        .forEach(el => el.classList.remove('swe-mobile-open'));
    const fabGroup = document.getElementById('mobile-fab-group');
    if (fabGroup) fabGroup.classList.remove('palette-open', 'properties-open');
}
function toggleMobilePanel(which) {
    const target = which === 'palette'
        ? document.querySelector('.swe-palette')
        : document.querySelector('.swe-properties');
    if (target.classList.contains('swe-mobile-open')) {
        closeMobilePanels();
    } else {
        openMobilePanel(which);
    }
}

// 모바일에서 노드 선택 시 properties overlay 자동 노출은 populateProps 본문에 직접 hook (위 함수 참조).

// 모바일 tap-to-add — palette item 클릭 시 canvas 중앙에 노드 생성 + 패널 자동 닫힘.
// Drawflow의 HTML5 drag는 touch에서 안정적이지 않음 — PR-3 전까지의 mobile-only 추가 입력 경로.
function addActivityNodeAtCenter(activity) {
    const rect = canvas.getBoundingClientRect();
    const pos_x = rect.width / 2;
    const pos_y = rect.height / 2;
    const html = '<div><strong>' + activity + '</strong>' +
        '<div data-label-id style="font-size:11px; color:var(--body); margin-top:4px; line-height:1; min-height:11px;">&#160;</div></div>';
    const nodeId = editor.addNode(activity, 1, 1, pos_x, pos_y, activity,
        {activityNm: activity, inputParams: '', datasourceBindings: ''}, html);
    const newNodeEl = document.getElementById('node-' + nodeId);
    if (newNodeEl) {
        const labelEl = newNodeEl.querySelector('[data-label-id]');
        if (labelEl) labelEl.textContent = '#' + nodeId;
    }
    setStatus('Added node #' + nodeId + ' (' + activity + ')');
}

function updateParams(id) {
    const node = editor.getNodeFromId(id);
    node.data.inputParams = document.getElementById('paramsInput').value;
    const bindingsEl = document.getElementById('bindingsInput');
    if (bindingsEl) node.data.datasourceBindings = bindingsEl.value;
    editor.updateNodeDataFromId(id, node.data);
    setStatus('Updated node #' + id);
}

function deleteNode(id) {
    editor.removeNodeId('node-' + id);
    document.getElementById('props').innerHTML = '<div class="swe-mute" style="font-size: 12px;">Click a node to edit its inputParams</div>';
    setStatus('Deleted node #' + id);
}

function startConnect(fromId) {
    connectMode = {from: fromId};
    setStatus('Connect mode: click target node');
    canvas.style.cursor = 'crosshair';
}

function cancelConnect() {
    connectMode = null;
    setStatus('Cancelled connect');
    canvas.style.cursor = '';
}

editor.on('nodeSelected', id => {
    if (connectMode && connectMode.from !== id) {
        try {
            editor.addConnection(connectMode.from, id, 'output_1', 'input_1');
            setStatus('Connected #' + connectMode.from + ' → #' + id);
        } catch (e) { setStatus('Connect failed: ' + e.message); }
        connectMode = null;
        canvas.style.cursor = '';
    }
});

function setStatus(text) { document.getElementById('status').textContent = text; }

function clearAll() {
    if (!confirm('Clear all nodes?')) return;
    editor.clearModuleSelected();
    setStatus('Cleared');
}

// ===== #135 — 좌측 패널 탭 + Stations list =====

window.switchTab = function (name) {
    const isActivities = name === 'activities';
    document.getElementById('tab-activities').classList.toggle('active', isActivities);
    document.getElementById('tab-activities').setAttribute('aria-selected', isActivities);
    document.getElementById('tab-stations').classList.toggle('active', !isActivities);
    document.getElementById('tab-stations').setAttribute('aria-selected', !isActivities);
    document.getElementById('palette').style.display = isActivities ? '' : 'none';
    document.getElementById('stations-tab-body').style.display = isActivities ? 'none' : '';
    if (!isActivities) refreshStationsList();
};

// ===== #206 — 우측 패널 탭 (Properties / Line settings) =====
// 노드 선택 시 populateProps에서 자동으로 'props' 탭 활성. 비선택 시는 사용자가 직접 클릭.
window.switchPropTab = function (name) {
    const isProps = name === 'props';
    document.getElementById('tab-props').classList.toggle('active', isProps);
    document.getElementById('tab-props').setAttribute('aria-selected', isProps);
    document.getElementById('tab-line-settings').classList.toggle('active', !isProps);
    document.getElementById('tab-line-settings').setAttribute('aria-selected', !isProps);
    document.getElementById('props').style.display = isProps ? '' : 'none';
    document.getElementById('line-settings-body').style.display = isProps ? 'none' : '';
};

// #181 PR-2 — buildBuilderGraph / escapeHtmlBuilder 는 /js/builder/graph-model.js로 분리.
// classic script global scope를 통해 그대로 호출 (mustache가 graph-model.js를 index.js보다 먼저 로드).

function refreshStationsList() {
    const graph = buildBuilderGraph(editor);
    const sorted = Station8SubwayMap.topologicalOrder(graph);
    const listEl = document.getElementById('stations-list');
    const countEl = document.getElementById('stations-count');
    if (countEl) countEl.textContent = sorted.length > 0 ? '(' + sorted.length + ')' : '';
    const searchEl = document.getElementById('stations-search');
    const q = searchEl ? searchEl.value.trim().toLowerCase() : '';
    const filtered = sorted.filter(n => {
        if (!q) return true;
        const hay = (n.name || '').toLowerCase() + ' ' + (n.activity || '').toLowerCase();
        return hay.indexOf(q) !== -1;
    });
    if (filtered.length === 0) {
        listEl.innerHTML = '<div class="swe-station-list-empty">' +
            (q ? '"' + escapeHtmlBuilder(q) + '" 일치 없음' :
                 '아직 역이 없습니다 — Activities 탭에서 드래그하세요.') +
            '</div>';
        return;
    }
    listEl.innerHTML = '';
    filtered.forEach(n => {
        const item = document.createElement('div');
        item.className = 'swe-station-list-item';
        item.dataset.nodeId = n.id;
        item.innerHTML = '<div class="activity">' + escapeHtmlBuilder(n.activity) + '</div>' +
                        '<div class="meta">#' + escapeHtmlBuilder(n.id) + '</div>';
        item.addEventListener('click', () => focusBuilderNode(n.id));
        listEl.appendChild(item);
    });
}

/**
 * 좌측 list 클릭 → Drawflow 캔버스의 해당 노드를 강조 + 스크롤 + properties 패널 노출.
 * Drawflow가 programmatic select API를 노출하지 않아서 DOM 조작 + populateProps 직접 호출로 대체.
 */
function focusBuilderNode(idStr) {
    document.querySelectorAll('.drawflow-node.builder-focused').forEach(el => el.classList.remove('builder-focused'));
    document.querySelectorAll('.swe-station-list-item').forEach(el => {
        el.classList.toggle('active', el.dataset.nodeId === idStr);
    });
    const el = document.getElementById('node-' + idStr);
    if (!el) { setStatus('Node not found: ' + idStr); return; }
    el.classList.add('builder-focused');
    if (typeof el.scrollIntoView === 'function') {
        el.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' });
    }
    populateProps(parseInt(idStr, 10));
    setStatus('Focused #' + idStr);
}

// escapeHtmlBuilder 도 graph-model.js로 분리 (#181 PR-2).

// 노드/엣지 변경 시 stations list 자동 갱신 (현재 active일 때만 의미 있지만 항상 갱신해 카운트 동기화)
editor.on('nodeCreated', refreshStationsList);
editor.on('nodeRemoved', refreshStationsList);
editor.on('connectionCreated', refreshStationsList);
editor.on('connectionRemoved', refreshStationsList);

// ===== #197 — output 드래그 시 input 포트 drop-target 강조 =====
// Drawflow 0.0.59가 output → input 단방향 연결만 허용한다는 규칙을 시각적으로 드러내,
// 첫 사용자의 onboarding 마찰을 줄인다 (도착지가 어디인지 명시적으로 표시).
editor.on('connectionStart', payload => {
    const sourceId = payload && (payload.output_id != null ? payload.output_id : payload.output);
    document.querySelectorAll('.drawflow-node').forEach(nodeEl => {
        if (sourceId != null && nodeEl.id === 'node-' + sourceId) return;  // 자기 자신 제외
        nodeEl.querySelectorAll('.input').forEach(p => p.classList.add('drop-target'));
    });
});
function clearDropTargets() {
    document.querySelectorAll('.drawflow-node .input.drop-target')
        .forEach(p => p.classList.remove('drop-target'));
}
editor.on('connectionCancel', clearDropTargets);
editor.on('connectionCreated', clearDropTargets);

// ===== #136 — 우클릭 컨텍스트 메뉴 =====

const ctxMenu = document.getElementById('ctx-menu');
let ctxFocusedIndex = -1;

/**
 * 메뉴 표시. items 형태: [{label, action, danger?}, {divider:true}, ...].
 * 좌표는 클릭 위치 기준 — 우/하단 viewport overflow 시 자동 clamp.
 */
function showCtxMenu(x, y, items) {
    ctxMenu.innerHTML = '';
    items.forEach((it, idx) => {
        if (it.divider) {
            const li = document.createElement('li');
            li.className = 'swe-ctx-menu-divider';
            li.setAttribute('role', 'separator');
            ctxMenu.appendChild(li);
            return;
        }
        const li = document.createElement('li');
        li.className = 'swe-ctx-menu-item' + (it.danger ? ' danger' : '');
        li.setAttribute('role', 'menuitem');
        li.tabIndex = -1;
        li.dataset.idx = idx;
        // #151 — shortcut hint (예: 'Del') 있으면 라벨 우측에 작게 표기
        if (it.shortcut) {
            const labelSpan = document.createElement('span');
            labelSpan.textContent = it.label;
            const kbd = document.createElement('span');
            kbd.className = 'swe-shortcut-hint';
            kbd.textContent = it.shortcut;
            li.appendChild(labelSpan);
            li.appendChild(kbd);
        } else {
            li.textContent = it.label;
        }
        li.addEventListener('click', e => {
            e.stopPropagation();
            try { it.action(); } finally { closeCtxMenu(); }
        });
        ctxMenu.appendChild(li);
    });
    ctxMenu.style.display = 'block';
    // viewport clamp — 메뉴 사이즈 측정 후 위치 조정
    const rect = ctxMenu.getBoundingClientRect();
    const vw = window.innerWidth, vh = window.innerHeight;
    const margin = 8;
    if (x + rect.width > vw - margin) x = Math.max(margin, vw - rect.width - margin);
    if (y + rect.height > vh - margin) y = Math.max(margin, vh - rect.height - margin);
    ctxMenu.style.left = x + 'px';
    ctxMenu.style.top = y + 'px';
    ctxFocusedIndex = -1;
    ctxMenu.focus();
}

function closeCtxMenu() {
    ctxMenu.style.display = 'none';
    ctxMenu.innerHTML = '';
    ctxFocusedIndex = -1;
}

function ctxMoveFocus(delta) {
    const items = ctxMenu.querySelectorAll('.swe-ctx-menu-item');
    if (items.length === 0) return;
    let next = ctxFocusedIndex + delta;
    if (next < 0) next = items.length - 1;
    if (next >= items.length) next = 0;
    ctxFocusedIndex = next;
    items[next].focus();
}

ctxMenu.addEventListener('keydown', e => {
    if (e.key === 'Escape') { closeCtxMenu(); e.preventDefault(); }
    else if (e.key === 'ArrowDown') { ctxMoveFocus(1); e.preventDefault(); }
    else if (e.key === 'ArrowUp') { ctxMoveFocus(-1); e.preventDefault(); }
    else if (e.key === 'Home') {
        const items = ctxMenu.querySelectorAll('.swe-ctx-menu-item');
        if (items.length > 0) { ctxFocusedIndex = 0; items[0].focus(); e.preventDefault(); }
    } else if (e.key === 'End') {
        const items = ctxMenu.querySelectorAll('.swe-ctx-menu-item');
        if (items.length > 0) { ctxFocusedIndex = items.length - 1; items[items.length - 1].focus(); e.preventDefault(); }
    } else if (e.key === 'Enter' || e.key === ' ') {
        const items = ctxMenu.querySelectorAll('.swe-ctx-menu-item');
        if (ctxFocusedIndex >= 0 && items[ctxFocusedIndex]) {
            items[ctxFocusedIndex].click();
            e.preventDefault();
        }
    }
});

// 외부 클릭 / 휠 스크롤 / 윈도우 blur로 닫기
document.addEventListener('click', e => {
    if (ctxMenu.style.display === 'block' && !ctxMenu.contains(e.target)) closeCtxMenu();
});
document.addEventListener('wheel', () => {
    if (ctxMenu.style.display === 'block') closeCtxMenu();
}, { passive: true });
window.addEventListener('blur', () => {
    if (ctxMenu.style.display === 'block') closeCtxMenu();
});

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

// ===== #151 — 키보드 단축키 (Delete / Esc / F / ?) =====

window.openShortcutsModal = function () {
    document.getElementById('shortcuts-backdrop').style.display = 'block';
    document.getElementById('shortcuts-modal').style.display = 'block';
};
window.closeShortcutsModal = function () {
    document.getElementById('shortcuts-backdrop').style.display = 'none';
    document.getElementById('shortcuts-modal').style.display = 'none';
};
document.getElementById('shortcuts-backdrop').addEventListener('click', closeShortcutsModal);

/**
 * input/textarea/select/contenteditable 포커스 중이면 단축키 무시 (텍스트 입력 우선).
 * 즉 #defNm, #tagsInput, #stations-search, #paramsInput, #bindingsInput 입력 시에는 Del/F 등이 정상 텍스트 편집으로 동작.
 */
function shouldIgnoreShortcut(e) {
    const t = e.target;
    if (!t) return false;
    const tag = (t.tagName || '').toUpperCase();
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
    if (t.isContentEditable) return true;
    return false;
}

function isAnyModalOpen() {
    const ec = document.getElementById('edge-cond-modal');
    const sc = document.getElementById('shortcuts-modal');
    return (ec && ec.style.display === 'block') || (sc && sc.style.display === 'block');
}

document.addEventListener('keydown', e => {
    // 모달 열린 상태: Esc로만 닫기 (이미 위 핸들러가 처리하지만 shortcuts-modal도 닫기)
    if (isAnyModalOpen()) {
        if (e.key === 'Escape') {
            closeShortcutsModal();
            // edge-cond-modal Escape는 이미 위 핸들러가 닫음
        }
        return;
    }

    if (shouldIgnoreShortcut(e)) return;

    // ctx-menu가 열린 상태: ctx-menu의 자체 keydown(arrow/enter/esc)이 우선 — 무시.
    const ctx = document.getElementById('ctx-menu');
    if (ctx && ctx.style.display === 'block') return;

    // Delete / Backspace — 선택된 노드 삭제
    if ((e.key === 'Delete' || e.key === 'Backspace') && selectedNodeId != null) {
        e.preventDefault();
        deleteNode(selectedNodeId);
        return;
    }

    // Escape — connect mode 취소 + 노드 deselect (모바일 overlay panel 열려 있으면 우선 닫기)
    if (e.key === 'Escape') {
        const mobilePanelOpen = document.querySelector('.swe-palette.swe-mobile-open, .swe-properties.swe-mobile-open');
        if (mobilePanelOpen) {
            closeMobilePanels();
            e.preventDefault();
        } else if (connectMode) {
            cancelConnect();
            e.preventDefault();
        } else if (selectedNodeId != null) {
            try { editor.unselectNode && editor.unselectNode(); } catch (_) {}
            selectedNodeId = null;
            e.preventDefault();
        }
        return;
    }

    // F — Stations 탭 + 검색 input 포커스 (#135 연결)
    if (e.key === 'f' || e.key === 'F') {
        const search = document.getElementById('stations-search');
        if (search) {
            switchTab('stations');
            search.focus();
            search.select();
            e.preventDefault();
        }
        return;
    }

    // ? (Shift+/) — cheatsheet 토글
    if (e.key === '?') {
        openShortcutsModal();
        e.preventDefault();
        return;
    }

    // Ctrl/Cmd+S — Save (브라우저 기본 차단)
    if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
        e.preventDefault();
        saveDefinition();
        return;
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

// connection 변경 시점마다 재적용
editor.on('connectionCreated', () => setTimeout(refreshEdgeConditionVisualization, 0));
editor.on('connectionRemoved', () => setTimeout(refreshEdgeConditionVisualization, 0));
editor.on('translate', () => setTimeout(refreshEdgeConditionVisualization, 0));
editor.on('zoom', () => setTimeout(refreshEdgeConditionVisualization, 0));

// ===== #205 PR-3 — touch long-press 컨텍스트 메뉴 =====
// touch에는 contextmenu 이벤트가 없거나 브라우저별 편차가 큼. 500ms 이상 누르면
// 합성 contextmenu 이벤트를 노드/엣지 element에서 dispatch하여 아래 우클릭 핸들러
// (capture 단계) 가 그대로 처리하게 함 — 코드 중복 zero.
(function setupTouchLongPress() {
    const LONG_PRESS_MS = 500;
    const MOVE_TOLERANCE = 10; // 손가락이 10px 이상 움직이면 drag 의도로 판단 → 취소
    const drawflow = document.getElementById('drawflow');
    let pressTimer = null;
    let pressInfo = null;

    function cancel() {
        if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
        pressInfo = null;
    }

    drawflow.addEventListener('touchstart', e => {
        if (e.touches.length !== 1) { cancel(); return; }
        const t = e.touches[0];
        const conn = t.target.closest && t.target.closest('.connection');
        const node = t.target.closest && t.target.closest('.drawflow-node');
        if (!conn && !node) return;          // 빈 캔버스는 contextmenu 비활성과 동일

        pressInfo = { x: t.clientX, y: t.clientY, el: node || conn };
        pressTimer = setTimeout(() => {
            if (!pressInfo) return;
            const evt = new MouseEvent('contextmenu', {
                bubbles: true,
                cancelable: true,
                clientX: pressInfo.x,
                clientY: pressInfo.y,
                view: window
            });
            pressInfo.el.dispatchEvent(evt);
            pressInfo = null;
        }, LONG_PRESS_MS);
    }, { passive: true });

    drawflow.addEventListener('touchmove', e => {
        if (!pressInfo) return;
        const t = e.touches[0];
        if (Math.abs(t.clientX - pressInfo.x) > MOVE_TOLERANCE
            || Math.abs(t.clientY - pressInfo.y) > MOVE_TOLERANCE) {
            cancel();   // 손가락 이동 = drag 의도 → long-press 취소
        }
    }, { passive: true });

    drawflow.addEventListener('touchend', cancel, { passive: true });
    drawflow.addEventListener('touchcancel', cancel, { passive: true });
})();

// canvas 우클릭 인터셉트 (capture로 Drawflow 자체 메뉴 선점) — touch에선 위 long-press가 synth dispatch
document.getElementById('drawflow').addEventListener('contextmenu', e => {
    const conn = e.target.closest('.connection');
    const node = e.target.closest('.drawflow-node');

    // D3=a — 빈 캔버스에선 메뉴 안 띄우고 브라우저 기본도 차단 안 함 (개발자 inspect 가능)
    if (!conn && !node) return;

    e.preventDefault();
    e.stopImmediatePropagation();  // Drawflow 기본 contextmenu 핸들러 차단

    if (node) {
        // 노드 메뉴 — D1=b: Edit / Connect / [divider] / Disconnect-all / Delete
        const id = parseInt(node.id.replace('node-', ''), 10);
        showCtxMenu(e.clientX, e.clientY, [
            { label: 'Edit params + bindings', action: () => populateProps(id) },
            { label: 'Connect from this node', action: () => startConnect(id) },
            { divider: true },
            { label: 'Disconnect all edges', action: () => disconnectAllEdges(id) },
            { label: 'Delete node', shortcut: 'Del', danger: true, action: () => deleteNode(id) }
        ]);
    } else if (conn) {
        // 엣지 메뉴 — D2=b 메뉴 형태 + #152 Edit condition 추가
        const info = parseConnectionFromElement(conn);
        if (info) {
            const hasCond = edgeConditions.has(edgeKey(info));
            showCtxMenu(e.clientX, e.clientY, [
                { label: hasCond ? 'Edit condition…' : 'Add condition…',
                  action: () => openEdgeCondModal(info) },
                { divider: true },
                { label: 'Disconnect ' + info.from + ' → ' + info.to,
                  danger: true, action: () => disconnectEdge(info) }
            ]);
        }
    }
}, { capture: true });

// saveDefinition / restoreExistingDefinition 는 /js/builder/form-serializer.js로 분리 (#181 PR-3).
// 두 함수는 globals(editor, edgeConditions, EDIT_MODE, EDIT_DEFINITION_ID, setStatus) 의존 —
// 호출 시점에 lookup하므로 load order는 form-serializer가 index.js 앞이어도 안전.

// ===== 노드 라벨 (#nodeId) 갱신 + connection path 좌표 재동기화 =====
// 노드 내부의 빈 [data-label-id] div에 Drawflow 내부 ID(#1, #2, ...)를 채워 식별성 확보.
// 라벨 채움으로 노드 박스 높이가 변동되면 Drawflow가 캐시한 connection path 좌표가 stale되어
// 화살표가 포트와 어긋나 보이는 회귀가 발생 — 따라서 라벨 갱신 후 updateConnectionNodes로
// 해당 노드의 모든 path 좌표를 즉시 재계산한다.
function refreshNodeLabels() {
    const data = (editor.export().drawflow.Home || {}).data || {};
    Object.keys(data).forEach(id => {
        const nodeEl = document.getElementById('node-' + id);
        if (!nodeEl) return;
        const labelEl = nodeEl.querySelector('[data-label-id]');
        const expected = '#' + id;
        if (labelEl && labelEl.textContent !== expected) {
            labelEl.textContent = expected;
        }
        editor.updateConnectionNodes('node-' + id);
    });
}

// Drawflow 이벤트 — 그래프 변경 시점마다 라벨/연결 재동기화
['nodeCreated', 'nodeRemoved', 'connectionCreated', 'connectionRemoved'].forEach(ev => {
    editor.on(ev, () => {
        // setTimeout 0 — Drawflow 내부 상태 갱신 후 DOM 조회/업데이트 가능하도록 한 tick 양보
        setTimeout(refreshNodeLabels, 0);
    });
});

loadActivities();
restoreExistingDefinition();
// #135 — stations list 초기 렌더 + 검색 입력 listener
refreshStationsList();
const _searchEl = document.getElementById('stations-search');
if (_searchEl) _searchEl.addEventListener('input', refreshStationsList);
// #130 — preload 직후 Drawflow 내부 상태가 늦게 업데이트되는 케이스 대비 한 tick 양보 후 재동기화
setTimeout(refreshNodeLabels, 50);
// #152 — preload된 엣지의 조건 시각화 적용
setTimeout(refreshEdgeConditionVisualization, 60);
