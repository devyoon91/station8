package com.bangrang.workflow.engine.entity;

import java.time.LocalDateTime;

/**
 * H_WF_ACTIVITY_EXECUTION ?뚯씠釉붿뿉 ??묓븯???뷀떚??(Record ?쒖슜)
 * 
 * Oracle??CLOB怨?MariaDB??LONGTEXT??Java String?쇰줈 留ㅽ븨?⑸땲??
 * 怨듯넻 而щ읆 洹쒖튃(DATABASE_RULE.md)??以?섑빀?덈떎.
 */
public record ActivityExecution(
    String id,                // ID (PK)
    String instanceId,        // INSTANCE_ID (FK)
    String activityName,      // ACTIVITY_NAME
    String statusSt,          // STATUS_ST (PENDING, RUNNING, COMPLETED, FAILED)
    String inputData,         // INPUT_DATA (CLOB/LONGTEXT - JSON)
    String outputData,        // OUTPUT_DATA (CLOB/LONGTEXT - JSON)
    String errorMessage,      // ERROR_MESSAGE (CLOB/LONGTEXT)
    String stackTrace,        // STACK_TRACE (CLOB/LONGTEXT)
    int retryCnt,             // RETRY_CNT
    LocalDateTime nextRetryDt, // NEXT_RETRY_DT
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
}

