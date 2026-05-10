package com.station8.engine.core;

/**
 * #138 — SLA 위반 알림 SPI.
 *
 * <p>기본 구현체는 {@link WebhookSlaNotifier} (전역 + 인스턴스 override). 필요 시 콘솔 / 이메일 등으로 교체.</p>
 *
 * <p>DLQ webhook과 의미적으로 분리 — DLQ는 "활동 최종 실패", SLA는 "인스턴스 시간 초과".
 * 운영팀이 다른 채널로 받기 원할 가능성을 위해 별도 SPI.</p>
 */
public interface SlaNotifier {

    /**
     * SLA 위반 발생 시 호출.
     *
     * @param violation     위반 페이로드
     * @param overrideUrl   인스턴스 RunOptions의 notificationWebhookUrl override (null이면 전역)
     */
    void notify(SlaViolation violation, String overrideUrl);
}
