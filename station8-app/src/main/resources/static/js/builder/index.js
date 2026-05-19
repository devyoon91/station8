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
let credentials = [];   // #305 — vault 등록 credential 목록, CREDENTIAL kind dropdown 소스
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
            // #192 — description은 @Activity(description) 메타에서 옴. 빈 문자열이면 표시 안 함.
            const descHtml = a.description
                ? '<div class="desc">' + escapeHtmlBuilder(a.description) + '</div>'
                : '';
            div.innerHTML = '<strong>' + a.activityName + '</strong>' +
                descHtml +
                '<div class="meta">retry ' + a.retryCount + ' · backoff ' + a.backoffSeconds + 's</div>';
            if (a.description) div.title = a.description;
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

/**
 * #305 — vault에 등록된 credential을 fetch해서 글로벌에 캐싱. CREDENTIAL kind form 필드의
 * dropdown 소스. 부팅 시 1회 + 사용자가 properties 패널의 "↻" 버튼 누르면 재호출.
 *
 * 응답 권한: USER 인증 필요 (M17). 로그인 안 됐거나 권한 부족이면 빈 list — 빌더는 그대로
 * 동작하되 CREDENTIAL 필드는 text input fallback.
 */
async function loadCredentials() {
    try {
        const res = await fetch('/api/line/credentials');
        if (!res.ok) {
            credentials = [];
            return;
        }
        const data = await res.json();
        credentials = Array.isArray(data) ? data : [];
    } catch (_) {
        credentials = [];
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
    // #192 — activities catalog에서 description lookup (없으면 빈 문자열)
    const meta = (typeof activities !== 'undefined' && Array.isArray(activities))
        ? activities.find(x => x.activityName === node.data.activityNm)
        : null;
    const descHtml = (meta && meta.description)
        ? '<div class="swe-mute" style="font-size: 12px; padding: var(--space-sm) var(--space-md); background: var(--surface-card); border-radius: var(--radius-sm); margin-bottom: var(--space-sm);">'
            + escapeHtmlBuilder(meta.description) + '</div>'
        : '';
    // #304 — schema 있으면 form 렌더, 없으면 기존 textarea fallback
    const paramsBlock = (meta && Array.isArray(meta.params) && meta.params.length > 0)
        ? renderParamsForm(meta.params, node.data.inputParams)
        : renderParamsTextarea(node.data.inputParams);

    props.innerHTML = '<div class="swe-stack">' +
        descHtml +
        '<div><label>Node ID</label><input class="swe-input" value="' + id + '" disabled></div>' +
        '<div><label>Activity</label><input class="swe-input" value="' + node.data.activityNm + '" disabled></div>' +
        paramsBlock +
        '<div><label>DataSource bindings (JSON)</label><textarea class="swe-input" rows="3" id="bindingsInput" style="height: auto; padding: 8px;" placeholder=\'{"source":"oracle-prod","target":"mart"}\'>' + (node.data.datasourceBindings || '') + '</textarea><div class="swe-mute" style="font-size: 11px; margin-top: 4px;">role → DataSource 이름. 액티비티가 <code>@BoundDataSource("role") JdbcTemplate</code>로 받아씀. 비우면 모두 primary fallback.</div></div>' +
        '<button class="swe-btn-tertiary" onclick="updateParams(' + id + ')">Update params + bindings</button>' +
        '<button class="swe-btn-tertiary swe-btn-danger" onclick="deleteNode(' + id + ')">Delete node</button>' +
        '<hr style="border: none; border-top: 1px solid var(--hairline); margin: var(--space-md) 0;">' +
        '<button class="swe-btn-tertiary swe-w-full" onclick="startConnect(' + id + ')">Connect from this node</button>' +
        (connectMode ? '<button class="swe-btn-tertiary swe-w-full" onclick="cancelConnect()">Cancel connect</button>' : '') +
        '</div>';
}

/** #304 — 기존 free-form textarea (schema 없는 활동용 fallback). */
function renderParamsTextarea(existing) {
    return '<div><label>Input params (JSON)</label>' +
        '<textarea class="swe-input" rows="6" id="paramsInput" style="height: auto; padding: 8px;" placeholder="{}">' +
        escapeHtmlBuilder(existing || '') + '</textarea></div>';
}

/**
 * #304 — schema 기반 form 렌더. data-param-name 속성으로 직렬화 시 식별.
 * existing inputParams (JSON string)이 있으면 그 값으로 form 초기화 — 사용자가 이전 라인을
 * 열었을 때 손실 0.
 */
function renderParamsForm(params, existingJson) {
    let existing = {};
    if (existingJson && existingJson.trim()) {
        try { existing = JSON.parse(existingJson); } catch (_) { /* invalid JSON — defaultValue 사용 */ }
    }
    const fieldsHtml = params.map(p => renderParamField(p, existing[p.name])).join('');
    return '<div data-params-form="1">' +
        '<label style="font-weight: 600;">Input params</label>' +
        '<div class="swe-stack" style="gap: var(--space-sm);">' + fieldsHtml + '</div>' +
        '<details style="margin-top: var(--space-sm);"><summary class="swe-mute" style="font-size: 11px; cursor: pointer;">JSON raw 보기 / 직접 편집</summary>' +
        '<textarea class="swe-input" rows="6" id="paramsInput" style="height: auto; padding: 8px; margin-top: var(--space-xs);">' +
        escapeHtmlBuilder(existingJson || '') + '</textarea>' +
        '<div class="swe-mute" style="font-size: 11px; margin-top: var(--space-xs);">위 form과 동기화는 Update 시점에 form → JSON 으로 덮어씀. raw 편집은 form에 없는 키만 보존 용도.</div>' +
        '</details></div>';
}

/** 단일 ActivityParam → 입력 element. label + (input | select | textarea | checkbox). */
function renderParamField(p, currentValue) {
    const id = 'param-' + p.name;
    const reqMark = p.required ? ' <span style="color: var(--text-error);">*</span>' : '';
    const desc = p.description
        ? '<div class="swe-mute" style="font-size: 11px; margin-top: 2px;">' + escapeHtmlBuilder(p.description) + '</div>'
        : '';
    const value = currentValue !== undefined && currentValue !== null
        ? (typeof currentValue === 'string' ? currentValue : JSON.stringify(currentValue))
        : (p.defaultValue || '');

    let input;
    switch (p.kind) {
        case 'NUMBER':
            input = '<input type="number" class="swe-input" id="' + id + '" data-param-name="' + p.name +
                '" data-param-kind="NUMBER" value="' + escapeHtmlBuilder(value) + '">';
            break;
        case 'BOOLEAN':
            const checked = (value === true || value === 'true') ? ' checked' : '';
            input = '<label style="display: flex; align-items: center; gap: var(--space-xs);">' +
                '<input type="checkbox" id="' + id + '" data-param-name="' + p.name +
                '" data-param-kind="BOOLEAN"' + checked + '> ' + p.name + '</label>';
            break;
        case 'SELECT':
            const opts = (p.options || []).map(o =>
                '<option value="' + escapeHtmlBuilder(o) + '"' +
                (o === value ? ' selected' : '') + '>' + escapeHtmlBuilder(o) + '</option>').join('');
            input = '<select class="swe-input" id="' + id + '" data-param-name="' + p.name +
                '" data-param-kind="SELECT">' + opts + '</select>';
            break;
        case 'OBJECT':
            input = '<textarea class="swe-input" rows="3" id="' + id + '" data-param-name="' + p.name +
                '" data-param-kind="OBJECT" placeholder="{}" style="height: auto; padding: 8px;">' +
                escapeHtmlBuilder(value) + '</textarea>';
            break;
        case 'CREDENTIAL':
            // #305 — vault dropdown. options 비어있으면 모든 credential, 있으면 type 화이트리스트.
            input = renderCredentialPicker(id, p, value);
            break;
        case 'STRING':
        default:
            input = '<input type="text" class="swe-input" id="' + id + '" data-param-name="' + p.name +
                '" data-param-kind="STRING" placeholder="{{ \$prev.json.x }} 표현식 가능" value="' +
                escapeHtmlBuilder(value) + '">';
    }
    return '<div><label for="' + id + '">' + escapeHtmlBuilder(p.name) + reqMark + '</label>' +
        input + desc + '</div>';
}

/**
 * #305 — CREDENTIAL kind 전용 렌더러. vault dropdown + 새로고침 + 미등록 fallback.
 *
 * - p.options 비어있으면 모든 활성 credential을 보임
 * - p.options 명시 (예: ["http_bearer","http_basic"]) — type 화이트리스트로 필터
 * - 저장된 value가 vault에 없으면 ⚠ 경고 + 텍스트 입력 fallback
 * - "↻" 버튼으로 vault 재fetch (사용자가 mid-session에 credential 등록한 경우)
 */
function renderCredentialPicker(id, p, currentValue) {
    const allowedTypes = (p.options && p.options.length > 0) ? p.options : null;
    const matching = credentials.filter(c => !allowedTypes || allowedTypes.includes(c.type));
    const knownNames = new Set(matching.map(c => c.name));

    // dropdown options 구성. value="" 는 placeholder.
    let optionsHtml = '<option value="">— select credential —</option>';
    matching.forEach(c => {
        const sel = (c.name === currentValue) ? ' selected' : '';
        optionsHtml += '<option value="' + escapeHtmlBuilder(c.name) + '"' + sel + '>' +
            escapeHtmlBuilder(c.name) + ' <' + escapeHtmlBuilder(c.type) + '></option>';
    });

    // 저장된 값이 vault에 없으면 별도 옵션 + 경고
    const orphan = currentValue && !knownNames.has(currentValue);
    if (orphan) {
        optionsHtml += '<option value="' + escapeHtmlBuilder(currentValue) + '" selected>' +
            '⚠ ' + escapeHtmlBuilder(currentValue) + ' (vault 미등록)</option>';
    }

    const typeHint = allowedTypes
        ? '호환 type: ' + allowedTypes.join(' / ')
        : '모든 credential type';

    const orphanWarn = orphan
        ? '<div style="color: var(--text-error); font-size: 11px; margin-top: 2px;">' +
            "⚠ '" + escapeHtmlBuilder(currentValue) + "'은 vault에 없음 — 운영자가 삭제했거나 이름 오타. /admin/credentials에서 확인" +
            '</div>'
        : '';

    return '<div style="display: flex; gap: var(--space-xs); align-items: center;">' +
        '<select class="swe-input" id="' + id + '" data-param-name="' + p.name +
            '" data-param-kind="CREDENTIAL" style="flex: 1;">' + optionsHtml + '</select>' +
        '<button type="button" class="swe-btn-tertiary" title="vault 새로고침" ' +
            'onclick="refreshCredentialsAndRepaint()" style="padding: 4px 8px;">↻</button>' +
        '</div>' +
        '<div class="swe-mute" style="font-size: 10px; margin-top: 2px;">' +
            escapeHtmlBuilder(typeHint) + ' · ' + matching.length + '건 등록됨' +
        '</div>' +
        orphanWarn;
}

/** #305 — vault refetch 후 현재 선택된 노드 properties 다시 그림. */
async function refreshCredentialsAndRepaint() {
    await loadCredentials();
    if (selectedNodeId != null) {
        populateProps(selectedNodeId);
        setStatus('Credential list refreshed (' + credentials.length + ' entries)');
    }
}
editor.on('nodeSelected', id => { selectedNodeId = id; populateProps(id); });
editor.on('nodeUnselected', () => { selectedNodeId = null; });
editor.on('nodeRemoved', () => { selectedNodeId = null; });

// (extracted to separate module — see builder.mustache <script> load order) #181 PR-4

function updateParams(id) {
    const node = editor.getNodeFromId(id);
    // #304 — schema form이 렌더된 경우 form 필드를 우선, raw textarea는 form에 없는 키 보존용
    const formContainer = document.querySelector('[data-params-form="1"]');
    if (formContainer) {
        node.data.inputParams = serializeParamsForm(formContainer);
    } else {
        node.data.inputParams = document.getElementById('paramsInput').value;
    }
    const bindingsEl = document.getElementById('bindingsInput');
    if (bindingsEl) node.data.datasourceBindings = bindingsEl.value;
    editor.updateNodeDataFromId(id, node.data);
    setStatus('Updated node #' + id);
}

/**
 * #304 — schema form의 모든 [data-param-name] 필드를 모아 JSON object로. raw textarea의 추가 키는
 * 보존 (form에 없는 키만 merge — 점진 도입 시 호환).
 */
function serializeParamsForm(container) {
    const inputs = container.querySelectorAll('[data-param-name]');
    const obj = {};
    inputs.forEach(el => {
        const name = el.getAttribute('data-param-name');
        const kind = el.getAttribute('data-param-kind');
        let value;
        switch (kind) {
            case 'NUMBER':
                value = el.value === '' ? undefined : Number(el.value);
                if (isNaN(value)) value = el.value;  // 표현식 사용 시 그대로 둠
                break;
            case 'BOOLEAN':
                value = el.checked;
                break;
            case 'OBJECT':
                if (el.value.trim()) {
                    try { value = JSON.parse(el.value); } catch (_) { value = el.value; }
                }
                break;
            default:  // STRING / SELECT / CREDENTIAL
                if (el.value !== '') value = el.value;
        }
        if (value !== undefined) obj[name] = value;
    });
    // raw textarea에 form에 없는 키가 있으면 보존
    const raw = document.getElementById('paramsInput');
    if (raw && raw.value.trim()) {
        try {
            const rawObj = JSON.parse(raw.value);
            for (const k in rawObj) {
                if (!(k in obj)) obj[k] = rawObj[k];
            }
        } catch (_) { /* invalid raw — form 우선 */ }
    }
    return JSON.stringify(obj);
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

// #181 PR-4 — 아래 5개 모듈을 builder.mustache의 <script> load order로 분리:
//   ctx-menu.js · edge-condition-modal.js · shortcuts.js · touch-longpress.js · mobile-overlay.js

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

// #181 PR-4 — edge-condition-modal.js의 editor.on 바인딩은 함수로 래핑돼 있어 여기서 명시 호출
//   (그래야 본 모듈이 index.js 보다 먼저 로드돼도 editor 미존재 에러가 없음).
bindEdgeConditionEditorEvents();
loadActivities();
loadCredentials();
restoreExistingDefinition();
// #135 — stations list 초기 렌더 + 검색 입력 listener
refreshStationsList();
const _searchEl = document.getElementById('stations-search');
if (_searchEl) _searchEl.addEventListener('input', refreshStationsList);
// #130 — preload 직후 Drawflow 내부 상태가 늦게 업데이트되는 케이스 대비 한 tick 양보 후 재동기화
setTimeout(refreshNodeLabels, 50);
// #152 — preload된 엣지의 조건 시각화 적용
setTimeout(refreshEdgeConditionVisualization, 60);
