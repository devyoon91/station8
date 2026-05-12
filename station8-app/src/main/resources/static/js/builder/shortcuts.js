/*
 * Keyboard shortcuts (#151) — Delete / Backspace / Esc / F / ? / Ctrl+S 핸들러 + cheatsheet 모달.
 *
 * #181 PR-4: index.js에서 분리. 의존 — selectedNodeId, connectMode, editor, switchTab,
 * deleteNode, cancelConnect, saveDefinition, closeMobilePanels. 모두 globals이며 keydown은
 * 사용자 액션이라 호출 시점에 lookup 항상 성공.
 *
 * input/textarea/select/contenteditable 포커스 중이면 단축키 무시 (텍스트 입력 우선).
 */

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
 * #defNm / #tagsInput / #stations-search / #paramsInput / #bindingsInput 입력 시 Del/F 등이
 * 단축키로 가로채이지 않고 정상 텍스트 편집으로 동작하도록 가드.
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
            // edge-cond-modal Escape는 edge-condition-modal.js의 별도 핸들러가 닫음
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
