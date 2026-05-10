package com.station8.engine.core;

import com.station8.engine.entity.DlqEntry;

/**
 * DLQ 적재 시 외부 알림을 발송하는 SPI(Service Provider Interface).
 * 기본 구현체로 WebhookDlqNotifier를 제공하며, 필요에 따라 콘솔/이메일 등으로 교체 가능합니다.
 */
public interface DlqNotifier {

    /**
     * DLQ에 새 항목이 적재되었을 때 호출됩니다 — 전역 webhook URL 사용.
     *
     * @param entry 적재된 DLQ 엔트리
     */
    void notify(DlqEntry entry);

    /**
     * #134 D8 — 인스턴스 단위 webhook URL override.
     *
     * <p>{@code overrideUrl}이 non-null/non-blank면 그 URL로 알림을 보내고,
     * 그렇지 않으면 {@link #notify(DlqEntry)}와 동일하게 전역 설정을 사용한다.
     * 후방 호환을 위해 default 메서드로 제공 — 기본 동작은 override 무시.</p>
     *
     * @param entry        적재된 DLQ 엔트리
     * @param overrideUrl  인스턴스 RunOptions의 notificationWebhookUrl (null이면 전역)
     */
    default void notify(DlqEntry entry, String overrideUrl) {
        notify(entry);
    }
}
