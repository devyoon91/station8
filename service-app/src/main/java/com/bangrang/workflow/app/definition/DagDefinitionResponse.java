package com.bangrang.workflow.app.definition;

import java.util.List;

public record DagDefinitionResponse(
        String definitionId,
        String definitionNm,
        String description,
        int versionNo,
        String activeFl,
        List<DagDefinitionRequest.NodeDef> nodes,
        List<DagDefinitionRequest.EdgeDef> edges
) {
}
