/*
 * Right-click context menu (#136) — 노드/엣지 우클릭 메뉴 (touch에선 long-press가 합성 dispatch).
 *
 * #181 PR-4: index.js에서 분리. 의존:
 *   - DOM: #ctx-menu element (mustache 마크업)
 *   - 글로벌 함수 — populateProps, startConnect, deleteNode (index.js),
 *     disconnectAllEdges, disconnectEdge, parseConnectionFromElement, edgeConditions,
 *     edgeKey, openEdgeCondModal (edge-condition-modal.js)
 *
 * 본 파일은 함수 선언 + DOM 리스너 등록(canvas contextmenu, document click/wheel/blur)만 한다.
 * 모든 호출은 사용자 액션(우클릭/터치) 이후 발생하므로 globals lookup은 항상 성공.
 */

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

// canvas 우클릭 인터셉트 (capture로 Drawflow 자체 메뉴 선점) — touch에선 long-press가 synth dispatch
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
