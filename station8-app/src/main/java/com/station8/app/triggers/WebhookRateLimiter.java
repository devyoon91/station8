package com.station8.app.triggers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * M20 (#312) — webhook endpoint per-trigger rate limiter.
 *
 * <p>Token bucket — 트리거 키별 메모리 내 카운터. 폐쇄망/단일 노드 가정.
 * 다중 노드 분산 환경은 비범위(Redis bucket 등).</p>
 *
 * <p>버킷 capacity = {@code burstSize}, refill rate = {@code maxPerMinute / 60s}.
 * 호출 시점에 경과 시간 기반 refill 후 토큰 1개 차감. 부족하면 거절.</p>
 *
 * <p>전역 토글 {@code station8.webhook.rate-limit.enabled} 가 false면 항상 통과.
 * 트리거 config에 {@code rateLimit}이 없으면 (또는 maxPerMinute &lt;= 0) 무제한 통과 —
 * 외부 노출 webhook이라 default-deny가 아니라 default-allow로 유지. 운영자가 명시적으로
 * 보호하려는 트리거에만 적용.</p>
 */
@Component
public class WebhookRateLimiter {

    private final boolean enabled;
    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public WebhookRateLimiter(
            @Value("${station8.webhook.rate-limit.enabled:true}") boolean enabled) {
        this(enabled, System::currentTimeMillis);
    }

    WebhookRateLimiter(boolean enabled, Clock clock) {
        this.enabled = enabled;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 토큰 1개 차감 시도. {@code maxPerMinute &lt;= 0} 이면 무제한으로 간주.
     *
     * @param triggerKey    트리거 키 — 버킷 단위
     * @param maxPerMinute  refill rate (분당)
     * @param burstSize     버킷 capacity. 0/음수면 maxPerMinute로 대체
     * @return 통과 시 true, 초과 시 false
     */
    public boolean tryAcquire(String triggerKey, int maxPerMinute, int burstSize) {
        if (!enabled) return true;
        if (maxPerMinute <= 0) return true;
        int effectiveBurst = burstSize > 0 ? burstSize : maxPerMinute;

        TokenBucket bucket = buckets.computeIfAbsent(triggerKey,
                k -> new TokenBucket(effectiveBurst, clock.now()));
        return bucket.tryAcquire(clock.now(), maxPerMinute, effectiveBurst);
    }

    /** 테스트용 — 상태 초기화. */
    void reset() {
        buckets.clear();
    }

    @FunctionalInterface
    interface Clock {
        long now();
    }

    private static final class TokenBucket {
        double tokens;
        long lastRefillMillis;

        TokenBucket(int initialTokens, long now) {
            this.tokens = initialTokens;
            this.lastRefillMillis = now;
        }

        synchronized boolean tryAcquire(long now, int maxPerMinute, int burstSize) {
            double refillPerMillis = maxPerMinute / 60_000.0;
            long elapsed = now - lastRefillMillis;
            double refilled = tokens + Math.max(0, elapsed) * refillPerMillis;
            if (refilled > burstSize) refilled = burstSize;
            lastRefillMillis = now;
            if (refilled >= 1.0) {
                tokens = refilled - 1.0;
                return true;
            }
            tokens = refilled;
            return false;
        }
    }
}
