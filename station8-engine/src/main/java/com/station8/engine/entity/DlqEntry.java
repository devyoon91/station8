package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * H_LINE_DLQ 테이블에 대응하는 엔티티 (Record 사용)
 * 최대 재시도 초과 시 Dead Letter Queue에 적재되는 레코드.
 */
public record DlqEntry(
    String id,                // ID (PK)
    String instanceId,        // INSTANCE_ID (FK)
    String executionId,       // EXECUTION_ID (FK)
    String workflowName,      // WORKFLOW_NAME
    String activityName,      // ACTIVITY_NAME
    String dlqStatusSt,       // DLQ_STATUS_ST (NEW, REQUEUED, DISCARDED)
    String errorMessage,      // ERROR_MESSAGE (CLOB/LONGTEXT)
    String stackTrace,        // STACK_TRACE (CLOB/LONGTEXT)
    int retryCnt,             // RETRY_CNT
    Integer maxRetryCnt,      // MAX_RETRY_CNT
    LocalDateTime failedAtDt, // FAILED_AT_DT

    // 공통 컬럼
    String delFl,             // DEL_FL
    LocalDateTime regDt,      // REG_DT
    String regId,             // REG_ID
    LocalDateTime editDt,     // EDIT_DT
    String editId             // EDIT_ID
) {
    /**
     * Mustache 템플릿에서 NEW 상태 여부를 판별하기 위한 헬퍼.
     */
    public boolean isNew() {
        return "NEW".equals(dlqStatusSt);
    }
}
