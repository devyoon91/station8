package com.bangrang.workflow.engine.core;

import java.time.Duration;

/**
 * 지수 백오프 기반의 재시도 지연 시간을 계산하는 클래스.
 */
public class ExponentialBackoffRetryPolicy {

    /**
     * 시도 횟수에 따른 다음 지연 시간을 계산합니다.
     * 공식: baseBackoffSeconds * (2 ^ (attempt - 1))
     *
     * @param attempt 현재 시도 횟수 (1부터 시작)
     * @param baseBackoffSeconds 기본 백오프 초 단위
     * @return 계산된 지연 시간
     */
    public Duration calculateNextBackoff(int attempt, long baseBackoffSeconds) {
        if (attempt <= 1) {
            return Duration.ofSeconds(baseBackoffSeconds);
        }
        
        // 지수 계산 (2의 n제곱)
        long exponentialFactor = (long) Math.pow(2, attempt - 1);
        long delaySeconds = baseBackoffSeconds * exponentialFactor;
        
        // 최대 지연 시간 제한 (예: 1시간) - 필요 시 파라미터화 가능
        long maxDelaySeconds = 3600;
        return Duration.ofSeconds(Math.min(delaySeconds, maxDelaySeconds));
    }

    /**
     * 최대 재시도 횟수를 초과했는지 확인합니다.
     *
     * @param attempt 현재 시도 횟수
     * @param maxRetryCount 최대 허용 재시도 횟수
     * @return 초과 여부
     */
    public boolean isExceeded(int attempt, int maxRetryCount) {
        return attempt > maxRetryCount;
    }
}

