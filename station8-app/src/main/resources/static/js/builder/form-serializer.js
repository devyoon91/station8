/*
 * Form serializer — UI/Drawflow 상태 ↔ DagDefinitionRequest JSON 직렬화/복원.
 *
 * #181 PR-3: index.js에서 saveDefinition / restoreExistingDefinition 두 함수를 분리.
 * 두 함수는 classic-script 전역 의존 (`editor`, `edgeConditions`, `EDIT_MODE`,
 * `EDIT_DEFINITION_ID`, `setStatus`) — index.js 보다 먼저 로드되지만 함수 본문은
 * 호출 시점에 globals를 lookup하므로 ordering 안전.
 *
 *   bootstrap call (`restoreExistingDefinition()`) 위치: index.js 하단.
 *   save 트리거: Save 버튼 / Ctrl+S 단축키 — 둘 다 index.js 내부 핸들러.
 */

// ===== Save → POST /api/line/definitions =====
async function saveDefinition() {
    const definitionNm = document.getElementById('defNm').value.trim();
    if (!definitionNm) { alert('Definition name 필수'); return; }

    const data = editor.export().drawflow.Home.data;
    const nodes = [];
    const edges = [];
    const idMap = {};

    Object.keys(data).forEach(k => {
        const n = data[k];
        const nodeId = 'n-' + n.id;
        idMap[n.id] = nodeId;
        // datasourceBindings: 빈 문자열 → null, JSON 문자열 → 파싱해서 Map으로 전송
        let bindingsMap = null;
        const bindingsRaw = (n.data.datasourceBindings || '').trim();
        if (bindingsRaw) {
            try { bindingsMap = JSON.parse(bindingsRaw); }
            catch (e) {
                alert('Node ' + nodeId + '의 DataSource bindings JSON 오류: ' + e.message);
                throw e;
            }
        }
        nodes.push({
            nodeId: nodeId,
            nodeNm: n.name,
            activityNm: n.data.activityNm,
            inputParams: n.data.inputParams || null,
            posX: Math.round(n.pos_x),
            posY: Math.round(n.pos_y),
            datasourceBindings: bindingsMap
        });
    });

    let edgeCounter = 0;
    Object.keys(data).forEach(k => {
        const n = data[k];
        Object.keys(n.outputs || {}).forEach(outKey => {
            (n.outputs[outKey].connections || []).forEach(c => {
                // #152 — 엣지 조건식: edgeConditions Map에서 lookup (Drawflow 내부 ID 기준)
                const condKey = n.id + '|' + c.node + '|' + outKey + '|' + c.output;
                const conditionExpr = edgeConditions.get(condKey) || null;
                edges.push({
                    edgeId: 'e-' + (++edgeCounter),
                    fromNodeId: idMap[n.id],
                    toNodeId: idMap[c.node],
                    conditionExpr: conditionExpr
                });
            });
        });
    });

    // 편집 모드면 PUT, 아니면 POST (#99)
    const url = EDIT_MODE
        ? '/api/line/definitions/' + EDIT_DEFINITION_ID
        : '/api/line/definitions';
    const method = EDIT_MODE ? 'PUT' : 'POST';

    // #138 — SLA settings (선택)
    const slaSecondsRaw = document.getElementById('slaSeconds').value.trim();
    const slaActionRaw = document.getElementById('slaAction').value;
    const slaSeconds = slaSecondsRaw ? parseInt(slaSecondsRaw, 10) : null;
    const slaAction = slaActionRaw || null;
    // #141 — 동시 실행 정책 (선택)
    const concurrencyPolicyRaw = document.getElementById('concurrencyPolicy').value;
    const concurrencyPolicy = concurrencyPolicyRaw || null;
    // #142 — 태그 (쉼표 구분, free-form)
    const tagsRaw = document.getElementById('tagsInput').value;
    const tags = tagsRaw.split(',').map(t => t.trim()).filter(t => t.length > 0);

    const payload = EDIT_MODE
        ? {definitionNm, description: 'Edited via Builder', slaSeconds, slaAction, concurrencyPolicy, tags, nodes, edges}
        : {definitionNm, description: 'Created via Builder', slaSeconds, slaAction, concurrencyPolicy, tags, nodes, edges};

    setStatus(EDIT_MODE ? 'Updating…' : 'Saving…');
    const res = await fetch(url, {
        method: method,
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(payload)
    });
    const validation = document.getElementById('validation');
    if (res.ok) {
        validation.style.display = 'none';
        if (EDIT_MODE) {
            setStatus('Updated! definitionId=' + EDIT_DEFINITION_ID);
            alert('Updated. ID: ' + EDIT_DEFINITION_ID + '\n\nNodes/edges replaced in place.');
            window.location.href = '/line/definitions/' + EDIT_DEFINITION_ID;
        } else {
            const j = await res.json();
            setStatus('Saved! definitionId=' + j.definitionId);
            alert('Saved. ID: ' + j.definitionId + '\n\nGo to Schedules to register a cron, or run via REST: POST /api/line/definitions/' + j.definitionId + '/run');
        }
    } else {
        const j = await res.json().catch(() => ({}));
        validation.style.display = 'block';
        validation.innerHTML = '<strong>' + (EDIT_MODE ? 'Update' : 'Validation') + ' failed:</strong><br>' +
            (j.errorCode ? '<code>' + j.errorCode + '</code> — ' : '') +
            (j.message || res.statusText);
        setStatus('Save failed (HTTP ' + res.status + ')');
    }
}

// ===== 편집 모드: 기존 정의를 Drawflow에 복원 (#99) =====
function restoreExistingDefinition() {
    const el = document.getElementById('existing-definition-json');
    if (!el) return;
    let def;
    try {
        def = JSON.parse(el.textContent);
    } catch (e) {
        setStatus('Failed to parse existing definition: ' + e.message);
        return;
    }

    // node 외부 ID(예: "n-validate") → Drawflow 내부 숫자 ID 매핑
    const idMap = {};

    (def.nodes || []).forEach(n => {
        const html = '<div><strong>' + n.activityNm + '</strong>' +
            '<div data-label-id style="font-size:11px; color:var(--body); margin-top:4px; line-height:1; min-height:11px;">&#160;</div></div>';
        const bindingsJson = (n.datasourceBindings && Object.keys(n.datasourceBindings).length > 0)
            ? JSON.stringify(n.datasourceBindings)
            : '';
        const drawflowId = editor.addNode(
            n.activityNm,
            1, 1,
            n.posX || 100, n.posY || 100,
            n.activityNm,
            {
                activityNm: n.activityNm,
                inputParams: n.inputParams || '',
                datasourceBindings: bindingsJson,
                originalNodeId: n.nodeId  // 편집 시 reset 후 같은 외부 ID로 다시 저장하기 위함은 아님 (replace는 새로 ID 발급도 OK)
            },
            html
        );
        // 동기적으로 라벨 채움 — placeholder와 final 모두 1줄 텍스트라 height 변동 zero
        const newNodeEl = document.getElementById('node-' + drawflowId);
        if (newNodeEl) {
            const labelEl = newNodeEl.querySelector('[data-label-id]');
            if (labelEl) labelEl.textContent = '#' + drawflowId;
        }
        idMap[n.nodeId] = drawflowId;
    });

    (def.edges || []).forEach(e => {
        const fromId = idMap[e.fromNodeId];
        const toId = idMap[e.toNodeId];
        if (fromId == null || toId == null) {
            console.warn('Edge skipped — node not found', e);
            return;
        }
        try {
            editor.addConnection(fromId, toId, 'output_1', 'input_1');
            // #152 — 기존 정의의 conditionExpr을 Map에 복원 (Drawflow는 default output_1/input_1 사용)
            if (e.conditionExpr) {
                edgeConditions.set(fromId + '|' + toId + '|output_1|input_1', e.conditionExpr);
            }
        } catch (err) {
            console.warn('Edge restore failed:', err);
        }
    });

    setStatus('Loaded ' + (def.nodes || []).length + ' stations · ' + (def.edges || []).length + ' tracks');
}
