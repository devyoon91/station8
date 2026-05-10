package com.station8.engine.core;

import java.time.LocalDateTime;

/**
 * #138 — SLA 위반 이벤트 페이로드.
 *
 * <p>{@link SlaNotifier}가 webhook / 콘솔 등으로 발송하는 알림 데이터.</p>
 */
public record SlaViolation(
        String instanceId,
        String workflowName,
        LocalDateTime startedAt,
        long elapsedSeconds,
        long thresholdSeconds,
        SlaAction action
) {
}
