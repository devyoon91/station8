package com.station8.app.triggers;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #312 — token bucket 단위 테스트. Spring 컨텍스트 없이 시계만 mock.
 */
class WebhookRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);

    private WebhookRateLimiter limiter() {
        return new WebhookRateLimiter(true, now::get);
    }

    @Test
    void disabledLimiter_alwaysPasses() {
        WebhookRateLimiter rl = new WebhookRateLimiter(false, now::get);
        for (int i = 0; i < 100; i++) {
            assertThat(rl.tryAcquire("k", 1, 1)).isTrue();
        }
    }

    @Test
    void zeroMaxPerMinute_treatedAsUnlimited() {
        WebhookRateLimiter rl = limiter();
        for (int i = 0; i < 50; i++) {
            assertThat(rl.tryAcquire("k", 0, 0)).isTrue();
        }
    }

    @Test
    void burstSize_capsInitialTokens() {
        WebhookRateLimiter rl = limiter();
        // burst 3, refill 60/min = 1/sec — 첫 호출에서 3개까지 즉시 통과
        assertThat(rl.tryAcquire("k", 60, 3)).isTrue();
        assertThat(rl.tryAcquire("k", 60, 3)).isTrue();
        assertThat(rl.tryAcquire("k", 60, 3)).isTrue();
        // 시계 안 움직이면 4번째 거절
        assertThat(rl.tryAcquire("k", 60, 3)).isFalse();
    }

    @Test
    void refill_overTime_restoresTokens() {
        WebhookRateLimiter rl = limiter();
        // burst 1, refill 60/min = 1/sec
        assertThat(rl.tryAcquire("k", 60, 1)).isTrue();
        assertThat(rl.tryAcquire("k", 60, 1)).isFalse();

        // 1초 경과 → 1개 refill
        now.addAndGet(1000);
        assertThat(rl.tryAcquire("k", 60, 1)).isTrue();
        assertThat(rl.tryAcquire("k", 60, 1)).isFalse();
    }

    @Test
    void refill_doesNotExceedBurstCap() {
        WebhookRateLimiter rl = limiter();
        // burst 2, refill 60/min = 1/sec
        // 시계를 1시간 점프 — 무한 토큰 누적하지 않아야 함
        now.addAndGet(3600_000);
        assertThat(rl.tryAcquire("k", 60, 2)).isTrue();
        assertThat(rl.tryAcquire("k", 60, 2)).isTrue();
        // 2개로 cap — 3번째 거절
        assertThat(rl.tryAcquire("k", 60, 2)).isFalse();
    }

    @Test
    void separateKeys_haveIndependentBuckets() {
        WebhookRateLimiter rl = limiter();
        // 키 A 소진
        assertThat(rl.tryAcquire("a", 60, 1)).isTrue();
        assertThat(rl.tryAcquire("a", 60, 1)).isFalse();
        // 키 B는 영향 없음
        assertThat(rl.tryAcquire("b", 60, 1)).isTrue();
    }

    @Test
    void burstSizeZero_fallsBackToMaxPerMinute() {
        WebhookRateLimiter rl = limiter();
        // burstSize=0 → maxPerMinute(5)이 capacity
        for (int i = 0; i < 5; i++) {
            assertThat(rl.tryAcquire("k", 5, 0)).isTrue();
        }
        assertThat(rl.tryAcquire("k", 5, 0)).isFalse();
    }
}
