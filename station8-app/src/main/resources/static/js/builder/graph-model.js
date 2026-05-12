/*
 * Graph model — Drawflow 내부 데이터를 추출/정규화하는 순수 헬퍼.
 *
 * #181 PR-2: index.js에서 분리. classic script (non-module) 환경에서 top-level
 * function declaration은 global object property로 노출되므로, 본 파일을 index.js
 * 보다 먼저 로드하면 동일 호출 그래프로 동작 (전역 함수 호출).
 *
 * 단위 테스트는 #181 PR-5 (vitest 셋업)에서 도입 예정.
 */

/**
 * Drawflow 내부 데이터 → Station8SubwayMap.topologicalOrder 입력 형태로 변환.
 *
 * @param {Drawflow} editor — Drawflow 인스턴스 (editor.export().drawflow.Home.data 접근)
 * @returns {{nodes: Array<{id, name, activity}>, edges: Array<{id, from, to}>}}
 */
function buildBuilderGraph(editor) {
    const data = editor.export().drawflow.Home.data;
    const nodes = [];
    const edges = [];
    const idMap = {};  // 외부ID → 자기 자신 (1:1) — buildBuilderGraph는 Drawflow 숫자 ID를 그대로 nodeId로 씀
    Object.keys(data).forEach(k => {
        const n = data[k];
        const nid = String(n.id);
        idMap[n.id] = nid;
        nodes.push({
            id: nid,
            name: n.data.activityNm || n.name || nid,
            activity: n.data.activityNm || n.name || nid
        });
    });
    let edgeCounter = 0;
    Object.keys(data).forEach(k => {
        const n = data[k];
        Object.keys(n.outputs || {}).forEach(outKey => {
            (n.outputs[outKey].connections || []).forEach(c => {
                edges.push({
                    id: 'e-' + (++edgeCounter),
                    from: idMap[n.id],
                    to: idMap[c.node]
                });
            });
        });
    });
    return { nodes: nodes, edges: edges };
}

/**
 * 사용자 입력 텍스트를 innerHTML에 안전하게 삽입하기 위한 HTML escape.
 * 5가지 문자(`& < > " '`)만 처리 — XSS surface를 좁히고, builder UI 라벨/검색어 표시에 사용.
 */
function escapeHtmlBuilder(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]);
}
