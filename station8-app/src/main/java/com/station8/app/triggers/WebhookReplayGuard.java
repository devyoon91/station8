package com.station8.app.triggers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * M20 (#312) — webhook endpoint replay 방어.
 *
 * <p>HMAC만으로는 같은 (body, signature) 쌍을 재전송하면 라인이 다시 시작된다. 이를 막기 위해
 * 발신자가 매 호출에 {@code X-Timestamp} 헤더 (epoch millis)를 박고, signature 계산에 timestamp를
 * 포함하도록 강제. 본 guard는 두 가지를 검증:</p>
 *
 * <ol>
 *   <li><b>Window</b> — timestamp가 현재 시각 ± {@code station8.webhook.replay-window-seconds} 안인지</li>
 *   <li><b>Dedup</b> — 같은 (triggerKey, timestamp, signature) 가 window 안에서 1회만 통과</li>
 * </ol>
 *
 * <p>전역 토글 {@code station8.webhook.replay-defense.enabled} 가 false면 비활성 — 기존 HMAC(body)만
 * 동작. 마이그레이션/legacy 발신자 호환용. 기본값은 true (보안 baseline).</p>
 *
 * <p>Dedup cache는 ConcurrentHashMap — 메모리 내, 단일 노드. window 외 entry는 접근 시점에 정리.</p>
 */
@Component
public class WebhookReplayGuard {

    private final boolean enabled;
    private final long windowMillis;
    private final ConcurrentMap<String, Long> seen = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public WebhookReplayGuard(
            @Value("${station8.webhook.replay-defense.enabled:true}") boolean enabled,
            @Value("${station8.webhook.replay-window-seconds:300}") long windowSeconds) {
        this(enabled, windowSeconds, System::currentTimeMillis);
    }

    WebhookReplayGuard(boolean enabled, long windowSeconds, Clock clock) {
        this.enabled = enabled;
        this.windowMillis = windowSeconds * 1000L;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long windowMillis() {
        return windowMillis;
    }

    /**
     * timestamp 헤더 파싱 + window 검증.
     */
    public TimestampCheck checkTimestamp(String header) {
        if (header == null || header.isBlank()) return TimestampCheck.MISSING;
        long ts;
        try {
            ts = Long.parseLong(header.trim());
        } catch (NumberFormatException ex) {
            return TimestampCheck.INVALID;
        }
        long now = clock.now();
        if (Math.abs(now - ts) > windowMillis) return TimestampCheck.OUT_OF_WINDOW;
        return TimestampCheck.OK;
    }

    /**
     * (triggerKey, timestamp, signature) 튜플을 처음 보는 것이면 기록 후 true, 이미 본 적 있으면 false.
     * 호출 시점에 window 외 entry 정리.
     */
    public boolean recordOnce(String triggerKey, String timestamp, String signature) {
        long now = clock.now();
        evictExpired(now);
        String dedupKey = triggerKey + "|" + timestamp + "|" + signature;
        return seen.putIfAbsent(dedupKey, now) == null;
    }

    private void evictExpired(long now) {
        long cutoff = now - windowMillis;
        seen.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    /** 테스트용 — 상태 초기화. */
    void reset() {
        seen.clear();
    }

    @FunctionalInterface
    interface Clock {
        long now();
    }

    public enum TimestampCheck {
        OK,
        MISSING,
        INVALID,
        OUT_OF_WINDOW
    }
}
