package com.example.workflow.engine.entity;

import java.time.LocalDateTime;

/**
 * H_WF_ACTIVITY_EXECUTION 테이블에 대응하는 엔티티 (Record 활용)
 * 
 * Oracle의 CLOB과 MariaDB의 LONGTEXT는 Java String으로 매핑됩니다.
 * 공통 컬럼 규칙(DATABASE_RULE.md)을 준수합니다.
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

    // 공통 컬럼
    String useFl,             // USE_FL
    String viewFl,            // VIEW_FL
    String delFl,             // DEL_FL
    LocalDateTime regDt,      // REG_DT
    String regId,             // REG_ID
    LocalDateTime editDt,     // EDIT_DT
    String editId             // EDIT_ID
) {
}
