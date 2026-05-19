package com.station8.engine.entity;

import java.time.LocalDateTime;

/**
 * M20 (#310) — U_LINE_TRIGGER row.
 *
 * <p>cron 외 trigger 타입. 현재는 {@code webhook} 한 종류. 향후 {@code kafka} / {@code mq} 등
 * 추가될 때 같은 row shape 재사용.</p>
 *
 * @param id            UUID
 * @param definitionId  대상 라인 정의 ID
 * @param triggerType   {@code webhook} 등
 * @param triggerKey    type별 lookup 키 (webhook은 URL path)
 * @param configJson    type별 config — webhook이면 {@code {hmacSecret, allowedMethods}}
 * @param activeFl      {@code Y}/{@code N}
 * @param delFl         {@code Y}/{@code N}
 * @param regDt         생성 시각
 * @param regId         생성자
 * @param editDt        수정 시각 (없으면 null)
 * @param editId        수정자
 */
public record LineTrigger(
        String id,
        String definitionId,
        String triggerType,
        String triggerKey,
        String configJson,
        String activeFl,
        String delFl,
        LocalDateTime regDt,
        String regId,
        LocalDateTime editDt,
        String editId
) {
    public boolean isActive() {
        return "Y".equals(activeFl) && !"Y".equals(delFl);
    }
}
