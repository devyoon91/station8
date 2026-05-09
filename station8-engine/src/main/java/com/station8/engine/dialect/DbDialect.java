package com.station8.engine.dialect;

public interface DbDialect {
    /**
     * SKIP LOCKED와 함께 사용할 LIMIT 쿼리 조각을 반환합니다.
     */
    String limit(int limit);

    /**
     * 페이징용 ``OFFSET ... LIMIT/FETCH ... ROWS`` 절을 반환합니다.
     * ``ORDER BY ... <offsetLimit(...)>`` 형태로 SQL 끝에 붙여서 사용한다.
     * SKIP LOCKED와 무관하므로 ``FOR UPDATE``가 없는 평범한 SELECT 페이지 조회에만 쓴다.
     */
    String offsetLimit(int offset, int limit);

    /**
     * 현재 시간 함수를 반환합니다.
     */
    String currentTimestamp();
}

