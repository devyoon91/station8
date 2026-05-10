package com.station8.app.definition;

/**
 * #141 — {@link LineDefinitionService#runDefinitionWithResult} 결과.
 *
 * <p>SKIP_IF_RUNNING 정책에 의해 새 인스턴스 시작이 무시됐을 때, 호출자(REST controller / cron poller)는
 * {@code skipped=true}를 보고 200 OK + 메시지로 응답할 수 있다 — 에러가 아니라 정상 흐름.</p>
 */
public record RunResult(
        String instanceId,
        boolean skipped,
        String reason,
        String conflictingInstanceId
) {
    public static RunResult started(String instanceId) {
        return new RunResult(instanceId, false, null, null);
    }

    public static RunResult skipped(String reason, String conflictingInstanceId) {
        return new RunResult(null, true, reason, conflictingInstanceId);
    }
}
