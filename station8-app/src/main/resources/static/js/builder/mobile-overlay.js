/*
 * Mobile overlay (#205 PR-2) — 모바일(<768px) viewport에서 palette/properties를
 * fixed slide-in 오버레이로 노출. 좌하단/우하단 FAB로 toggle.
 *
 * #181 PR-4: index.js에서 분리. 의존 globals — `canvas`, `editor`, `setStatus` (index.js).
 * 함수 본문이 호출 시점에 lookup하므로 load order는 index.js 보다 먼저여도 안전.
 *
 * 데스크톱/태블릿에선 CSS @media로 FAB/backdrop이 숨겨져 아래 함수는 호출되지 않음.
 */

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

/**
 * 모바일 tap-to-add — palette item 클릭 시 canvas 중앙에 노드 생성 + 패널 자동 닫힘.
 * Drawflow의 HTML5 drag는 touch에서 안정적이지 않아 #205 PR-2의 우회 경로.
 */
function addActivityNodeAtCenter(activity) {
    const rect = canvas.getBoundingClientRect();
    const pos_x = rect.width / 2;
    const pos_y = rect.height / 2;
    const html = '<div><strong>' + activity + '</strong>' +
        '<div data-label-id style="font-size:11px; color:var(--body); margin-top:4px; line-height:1; min-height:11px;">&#160;</div></div>';
    const nodeId = editor.addNode(activity, 1, 1, pos_x, pos_y, activity,
        {activityNm: activity, inputParams: '', datasourceBindings: '', streamMode: 'NONE'}, html);
    const newNodeEl = document.getElementById('node-' + nodeId);
    if (newNodeEl) {
        const labelEl = newNodeEl.querySelector('[data-label-id]');
        if (labelEl) labelEl.textContent = '#' + nodeId;
    }
    setStatus('Added node #' + nodeId + ' (' + activity + ')');
}
