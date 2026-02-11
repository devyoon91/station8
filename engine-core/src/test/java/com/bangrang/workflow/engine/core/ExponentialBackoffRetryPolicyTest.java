package com.bangrang.workflow.engine.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ExponentialBackoffRetryPolicyTest {

    private ExponentialBackoffRetryPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ExponentialBackoffRetryPolicy();
    }

    @Test
    @DisplayName("첫 번째 시도(attempt=1)는 baseBackoffSeconds를 그대로 반환한다")
    void calculateNextBackoff_firstAttempt() {
        Duration result = policy.calculateNextBackoff(1, 10);
        assertEquals(Duration.ofSeconds(10), result);
    }

    @Test
    @DisplayName("attempt <= 0 일 때도 baseBackoffSeconds를 반환한다")
    void calculateNextBackoff_zeroOrNegativeAttempt() {
        assertEquals(Duration.ofSeconds(5), policy.calculateNextBackoff(0, 5));
        assertEquals(Duration.ofSeconds(5), policy.calculateNextBackoff(-1, 5));
    }

    @Test
    @DisplayName("지수 백오프 공식: base * 2^(attempt-1)")
    void calculateNextBackoff_exponentialGrowth() {
        // attempt=2 → 10 * 2^1 = 20
        assertEquals(Duration.ofSeconds(20), policy.calculateNextBackoff(2, 10));
        // attempt=3 → 10 * 2^2 = 40
        assertEquals(Duration.ofSeconds(40), policy.calculateNextBackoff(3, 10));
        // attempt=4 → 10 * 2^3 = 80
        assertEquals(Duration.ofSeconds(80), policy.calculateNextBackoff(4, 10));
    }

    @Test
    @DisplayName("최대 지연 시간은 3600초(1시간)로 제한된다")
    void calculateNextBackoff_cappedAtMaxDelay() {
        // attempt=20, base=10 → 10 * 2^19 = 5,242,880 → capped to 3600
        Duration result = policy.calculateNextBackoff(20, 10);
        assertEquals(Duration.ofSeconds(3600), result);
    }

    @Test
    @DisplayName("isExceeded: 시도 횟수가 최대 재시도 횟수를 초과하면 true")
    void isExceeded_true() {
        assertTrue(policy.isExceeded(4, 3));
        assertTrue(policy.isExceeded(10, 5));
    }

    @Test
    @DisplayName("isExceeded: 시도 횟수가 최대 재시도 횟수 이하이면 false")
    void isExceeded_false() {
        assertFalse(policy.isExceeded(3, 3));
        assertFalse(policy.isExceeded(1, 5));
    }
}
