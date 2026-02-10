package com.example.workflow.engine.entity;

import java.time.LocalDateTime;

/**
 * U_WF_INSTANCE 테이블에 대응하는 엔티티 (Record 활용)
 * 
 * Oracle의 CLOB과 MariaDB의 LONGTEXT는 Java String으로 매핑됩니다.
 * 공통 컬럼 규칙(DATABASE_RULE.md)을 준수합니다.
 */
public record WorkflowInstance(
    String id,                // ID (PK)
    String workflowName,      // WORKFLOW_NAME
    String statusSt,          // STATUS_ST (RUNNING, COMPLETED, FAILED, TERMINATED)
    String inputData,         // INPUT_DATA (CLOB/LONGTEXT - JSON)
    String outputData,        // OUTPUT_DATA (CLOB/LONGTEXT - JSON)
    String stateData,         // STATE_DATA (CLOB/LONGTEXT - JSON)
    LocalDateTime startDt,    // START_DT
    LocalDateTime endDt,      // END_DT
    
    // 공통 컬럼
    String useFl,             // USE_FL
    String viewFl,            // VIEW_FL
    String delFl,             // DEL_FL
    LocalDateTime regDt,      // REG_DT
    String regId,             // REG_ID
    LocalDateTime editDt,     // EDIT_DT
    String editId             // EDIT_ID
) {
    public static WorkflowInstance create(String id, String workflowName, String inputData) {
        return new WorkflowInstance(
            id,
            workflowName,
            "RUNNING",
            inputData,
            null,
            null,
            LocalDateTime.now(),
            null,
            "Y", "Y", "N",
            LocalDateTime.now(),
            "SYSTEM",
            null,
            null
        );
    }
}
