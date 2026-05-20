package com.station8.app.triggers;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #312 — replay guard 단위 테스트. Spring 컨텍스트 없이 시계만 mock.
 */
class WebhookReplayGuardTest {

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private WebhookReplayGuard guard(long windowSeconds) {
        return new WebhookReplayGuard(true, windowSeconds, now::get);
    }

    @Test
    void disabledGuard_reportsEnabledFalse() {
        WebhookReplayGuard g = new WebhookReplayGuard(false, 300, now::get);
        assertThat(g.isEnabled()).isFalse();
    }

    @Test
    void missingHeader_returnsMissing() {
        assertThat(guard(300).checkTimestamp(null))
                .isEqualTo(WebhookReplayGuard.TimestampCheck.MISSING);
        assertThat(guard(300).checkTimestamp("  "))
                .isEqualTo(WebhookReplayGuard.TimestampCheck.MISSING);
    }

    @Test
    void nonNumeric_returnsInvalid() {
        assertThat(guard(300).checkTimestamp("hello"))
                .isEqualTo(WebhookReplayGuard.TimestampCheck.INVALID);
    }

    @Test
    void withinWindow_returnsOk() {
        WebhookReplayGuard g = guard(300);
        // 정확히 현재 시각
        assertThat(g.checkTimestamp(Long.toString(now.get())))
                .isEqualTo(WebhookReplayGuard.TimestampCheck.OK);
        // 4분 전
        assertThat(g.checkTimestamp(Long.toString(now.get() - 240_000)))
                .isEqualTo(WebhookReplayGuard.TimestampCheck.OK);
        // 4분 후 (시계 어긋남 발신자)
        assertThat(g.checkTimestamp(Long.toString(now.get() + 240_000)))
                .isEqualTo(WebhookReplayGuard.TimestampCheck.OK);
    }

    @Test
    void outsideWindow_returnsOutOfWindow() {
        WebhookReplayGuard g = guard(300);
        // 6분 전
        assertThat(g.checkTimestamp(Long.toString(now.get() - 360_000)))
                .isEqualTo(WebhookReplayGuard.TimestampCheck.OUT_OF_WINDOW);
        // 6분 후
        assertThat(g.checkTimestamp(Long.toString(now.get() + 360_000)))
                .isEqualTo(WebhookReplayGuard.TimestampCheck.OUT_OF_WINDOW);
    }

    @Test
    void recordOnce_firstSeen_returnsTrue() {
        WebhookReplayGuard g = guard(300);
        assertThat(g.recordOnce("k", "1000", "sig-a")).isTrue();
    }

    @Test
    void recordOnce_duplicate_returnsFalse() {
        WebhookReplayGuard g = guard(300);
        assertThat(g.recordOnce("k", "1000", "sig-a")).isTrue();
        assertThat(g.recordOnce("k", "1000", "sig-a")).isFalse();
    }

    @Test
    void recordOnce_differentSignature_independentEntries() {
        WebhookReplayGuard g = guard(300);
        assertThat(g.recordOnce("k", "1000", "sig-a")).isTrue();
        assertThat(g.recordOnce("k", "1000", "sig-b")).isTrue();
        assertThat(g.recordOnce("k", "1001", "sig-a")).isTrue();
    }

    @Test
    void recordOnce_differentTrigger_independentEntries() {
        WebhookReplayGuard g = guard(300);
        assertThat(g.recordOnce("a", "1000", "sig")).isTrue();
        assertThat(g.recordOnce("b", "1000", "sig")).isTrue();
    }

    @Test
    void recordOnce_afterWindow_entryEvicted_allowsAgain() {
        WebhookReplayGuard g = guard(300); // 300s window
        long initialNow = now.get();
        assertThat(g.recordOnce("k", "1000", "sig")).isTrue();

        // 5분 + 1초 경과 — 첫 기록이 window 밖
        now.set(initialNow + 301_000);
        assertThat(g.recordOnce("k", "1000", "sig")).isTrue();
    }
}
