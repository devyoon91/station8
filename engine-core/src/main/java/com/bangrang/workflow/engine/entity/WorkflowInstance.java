package com.bangrang.workflow.engine.entity;

import java.time.LocalDateTime;

/**
 * U_WF_INSTANCE ?뚯씠釉붿뿉 ??묓븯???뷀떚??(Record ?쒖슜)
 * 
 * Oracle??CLOB怨?MariaDB??LONGTEXT??Java String?쇰줈 留ㅽ븨?⑸땲??
 * 怨듯넻 而щ읆 洹쒖튃(DATABASE_RULE.md)??以?섑빀?덈떎.
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
    
    // 怨듯넻 而щ읆
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

