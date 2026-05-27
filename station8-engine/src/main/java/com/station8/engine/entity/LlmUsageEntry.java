package com.station8.engine.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * H_LINE_LLM_USAGE 테이블 대응 엔티티 (#339). LLM 호출 1건의 토큰/비용 기록.
 *
 * <p>활동 실행과의 연결은 {@code instanceId} + {@code nodeId} + {@code activityName}로 한다.
 * 0.1.0 SDK의 {@code LineContext}가 activity_execution_id를 노출하지 않아 FK 대신 instance 단위로
 * 키잉 — "어느 라인이 얼마 썼나"는 instance → 라인 정의 조인으로 충분. 정밀한 실행 단위 귀속이
 * 필요해지면 후속에서 {@code LineContext.executionId()} 도입 후 컬럼 추가.</p>
 *
 * <p>공통 컬럼 규칙(DATABASE_RULE.md) 준수 — REG_DT가 생성 시각.</p>
 */
public record LlmUsageEntry(
        String id,                  // ID (PK)
        String instanceId,          // INSTANCE_ID
        String nodeId,              // NODE_ID (nullable: 레거시/선형 모드)
        String activityName,        // ACTIVITY_NAME
        String provider,            // PROVIDER (예: openai-compatible)
        String model,               // MODEL
        int inputTokens,            // INPUT_TOKENS
        int outputTokens,           // OUTPUT_TOKENS
        BigDecimal estimatedCostUsd, // ESTIMATED_COST_USD (nullable: 단가 미상 모델)
        String promptHash,          // PROMPT_HASH

        // 공통 컬럼
        String delFl,               // DEL_FL
        LocalDateTime regDt,        // REG_DT (생성 시각)
        String regId,               // REG_ID
        LocalDateTime editDt,       // EDIT_DT
        String editId               // EDIT_ID
) {}
