package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * U_LINE_INSTANCE 테이블에 대응하는 엔티티 (Record 사용)
 * 
 * Oracle의 CLOB과 MariaDB의 LONGTEXT를 Java String으로 매핑합니다.
 * 공통 컬럼 규칙(DATABASE_RULE.md)을 준수합니다.
 */
public record LineInstance(
    String id,                // ID (PK)
    String workflowName,      // WORKFLOW_NAME
    String definitionId,      // DEFINITION_ID (#364) — DAG 인스턴스의 소속 라인 정의. 레거시/선형(@Activity)은 null.
    String statusSt,          // STATUS_ST (RUNNING, COMPLETED, FAILED, TERMINATED)
    String inputData,         // INPUT_DATA (CLOB/LONGTEXT - JSON)
    String outputData,        // OUTPUT_DATA (CLOB/LONGTEXT - JSON)
    String stateData,         // STATE_DATA (CLOB/LONGTEXT - JSON)
    String runOptions,        // RUN_OPTIONS (#134) — 인스턴스 단위 실행 옵션 raw JSON
    LocalDateTime startDt,    // START_DT
    LocalDateTime endDt,      // END_DT

    // 공통 컬럼
    String delFl,             // DEL_FL
    LocalDateTime regDt,      // REG_DT
    String regId,             // REG_ID
    LocalDateTime editDt,     // EDIT_DT
    String editId             // EDIT_ID
) {
    public static LineInstance create(String id, String workflowName, String inputData) {
        return new LineInstance(
            id,
            workflowName,
            null,             // definitionId — 이 팩토리는 레거시/선형 경로용. DAG는 INSERT 시 직접 채운다.
            "RUNNING",
            inputData,
            null,
            null,
            null,
            LocalDateTime.now(),
            null,
            "N",
            LocalDateTime.now(),
            "SYSTEM",
            null,
            null
        );
    }
}

