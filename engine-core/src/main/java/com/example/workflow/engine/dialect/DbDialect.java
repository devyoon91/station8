package com.example.workflow.engine.dialect;

public interface DbDialect {
    /**
     * SKIP LOCKED와 함께 사용할 LIMIT 쿼리 조각을 반환합니다.
     */
    String limit(int limit);

    /**
     * 현재 시간 함수를 반환합니다.
     */
    String currentTimestamp();
}
