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
        List<DagDefinitionRequest.NodeDef> nodes,
        List<DagDefinitionRequest.EdgeDef> edges
) {
}
