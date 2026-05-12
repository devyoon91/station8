/*
 * Touch long-press → synthetic contextmenu (#205 PR-3).
 *
 * touch에는 contextmenu 이벤트가 없거나 브라우저별 편차가 큼. 500ms 이상 누르면
 * 합성 contextmenu 이벤트를 노드/엣지 element에서 dispatch — ctx-menu.js의 capture
 * 단계 핸들러가 그대로 처리. 코드 중복 zero.
 *
 * #181 PR-4: index.js에서 분리. 의존 — DOM(#drawflow) only. defer 스크립트라
 * 파싱 완료 후 실행되므로 element 항상 존재.
 */

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
