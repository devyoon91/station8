package com.bangrang.workflow.engine.core;

import com.bangrang.workflow.engine.entity.DlqEntry;

/**
 * DLQ 적재 시 외부 알림을 발송하는 SPI(Service Provider Interface).
 * 기본 구현체로 WebhookDlqNotifier를 제공하며, 필요에 따라 콘솔/이메일 등으로 교체 가능합니다.
 */
public interface DlqNotifier {

    /**
     * DLQ에 새 항목이 적재되었을 때 호출됩니다.
     *
     * @param entry 적재된 DLQ 엔트리
     */
    void notify(DlqEntry entry);
}
