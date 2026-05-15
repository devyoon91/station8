package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * H_LINE_ACTIVITY_EXECUTION 테이블에 대응하는 엔티티 (Record 사용)
 * 
 * Oracle의 CLOB과 MariaDB의 LONGTEXT를 Java String으로 매핑합니다.
 * 공통 컬럼 규칙(DATABASE_RULE.md)을 준수합니다.
 */
public record ActivityExecution(
    String id,                // ID (PK)
    String instanceId,        // INSTANCE_ID (FK)
    String nodeId,            // NODE_ID (FK to U_LINE_STATION, nullable: 레거시 모드는 null)
    String activityName,      // ACTIVITY_NAME
    String statusSt,          // STATUS_ST (WAITING_DEPENDENCIES, PENDING, RUNNING, COMPLETED, FAILED)
    String inputData,         // INPUT_DATA (CLOB/LONGTEXT - JSON)
    String outputData,        // OUTPUT_DATA (CLOB/LONGTEXT - JSON)
    String errorMessage,      // ERROR_MESSAGE (CLOB/LONGTEXT)
    String stackTrace,        // STACK_TRACE (CLOB/LONGTEXT)
    int retryCnt,             // RETRY_CNT
    LocalDateTime nextRetryDt, // NEXT_RETRY_DT
    LocalDateTime startDt,    // START_DT
    LocalDateTime endDt,      // END_DT

    // 공통 컬럼
    String delFl,             // DEL_FL
    LocalDateTime regDt,      // REG_DT
    String regId,             // REG_ID
    LocalDateTime editDt,     // EDIT_DT
    String editId             // EDIT_ID
) {
    /** 상태만 바꾼 새 인스턴스를 반환합니다 (record는 불변이므로 재구성). */
    public ActivityExecution withStatus(String newStatus) {
        return new ActivityExecution(
            id, instanceId, nodeId, activityName, newStatus,
            inputData, outputData, errorMessage, stackTrace,
            retryCnt, nextRetryDt, startDt, endDt,
            delFl, regDt, regId, editDt, editId
        );
    }
}

