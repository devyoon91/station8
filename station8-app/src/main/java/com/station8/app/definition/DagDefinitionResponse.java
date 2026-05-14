package com.station8.app.definition;

import java.util.List;

public record DagDefinitionResponse(
        String definitionId,
        String definitionNm,
        String description,
        int versionNo,
        String activeFl,
        /** #138 — SLA 시간 임계치 (초). null이면 비활성. */
        Long slaSeconds,
        /** #138 — SLA 위반 시 액션 (`ALERT_ONLY` / `AUTO_TERMINATE`). */
        String slaAction,
        /** #141 — 동시 실행 정책 (`CONCURRENT` 기본 / `SKIP_IF_RUNNING`). */
        String concurrencyPolicy,
        /** #168 — 소속 프로젝트 ID. Phase 1에서는 보통 default project ID. */
        String projectId,
        /** #142 — 라인 정의 태그 (alphabetic 정렬). 빈 리스트면 태그 없음. */
        List<String> tags,
        List<DagDefinitionRequest.NodeDef> nodes,
        List<DagDefinitionRequest.EdgeDef> edges
) {
}
