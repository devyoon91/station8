package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RunOptions의 단일 파싱/직렬화 진입점.
 *
 * <p>이전에는 같은 변환 로직이 4곳에 산재 — {@code LineWorker.parseRunOptionsSafely},
 * {@code LineDefinitionService.serializeRunOptions}, {@code LineDefinitionController.parseOptions},
 * {@code RunOptions.parse} 정적 메서드. 본 codec은 이 모두를 단일 컴포넌트로 통합 (DRY 충족).</p>
 *
 * <h3>책임</h3>
 * <ul>
 *   <li>{@link #parseFromClob(String)} — DB CLOB({@code U_LINE_INSTANCE.RUN_OPTIONS}) → {@link RunOptions}.
 *       파싱 실패 시 {@link RunOptions#defaults()}로 안전 fallback (운영 멈춤 방지).</li>
 *   <li>{@link #parseFromOptionsMap(Map)} — REST body의 {@code options} 서브맵 → {@link RunOptions}.
 *       알 수 없는 필드는 무시 (후방 호환).</li>
 *   <li>{@link #serializeToClob(RunOptions)} — {@link RunOptions} → CLOB JSON.
 *       모두 default면 null 반환 (DB 컬럼 비움).</li>
 * </ul>
 *
 * <p>Spring {@code @Component} bean — `JsonUtil` 의존. value object는 아니지만 stateless이므로
 * thread-safe.</p>
 */
@Component
public class RunOptionsCodec {

    private static final Logger log = LoggerFactory.getLogger(RunOptionsCodec.class);

    private final JsonUtil jsonUtil;

    public RunOptionsCodec(JsonUtil jsonUtil) {
        this.jsonUtil = jsonUtil;
    }

    /**
     * DB CLOB JSON을 RunOptions로 파싱. null/빈 문자열/잘못된 JSON 모두 안전하게 default로 fallback.
     *
     * @param json {@code U_LINE_INSTANCE.RUN_OPTIONS} CLOB 값 (null 허용)
     * @return 파싱된 옵션 또는 default
     */
    public RunOptions parseFromClob(String json) {
        if (json == null || json.isBlank()) {
            return RunOptions.defaults();
        }
        try {
            return parseFromMap(asMap(jsonUtil.fromJson(json, Map.class)));
        } catch (Exception ex) {
            // JSON 파싱 실패는 운영 가시성을 위해 WARN — 인스턴스 진행은 default로 계속
            log.warn("RunOptions CLOB 파싱 실패 — fallback to defaults ({}: {})",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return RunOptions.defaults();
        }
    }

    /**
     * REST 컨트롤러가 받은 body의 {@code options} 서브맵을 RunOptions로 변환.
     * null / 비어있음 / Map 아님 — 모두 default 반환.
     *
     * @param optionsMap 호출자가 추출한 서브맵 (예: {@code body.get("options")} 결과)
     * @return 파싱된 옵션 또는 default
     */
    public RunOptions parseFromOptionsMap(Map<String, Object> optionsMap) {
        if (optionsMap == null || optionsMap.isEmpty()) {
            return RunOptions.defaults();
        }
        return parseFromMap(optionsMap);
    }

    /**
     * RunOptions를 DB CLOB JSON으로 직렬화. 모든 필드가 default면 null 반환 — DB 컬럼 비움.
     *
     * @param opt 직렬화할 옵션 (null이면 default 취급 → null 반환)
     * @return JSON 문자열 또는 null (모두 default)
     */
    public String serializeToClob(RunOptions opt) {
        if (opt == null) {
            return null;
        }
        // 모든 필드 default 검사 — 빈 옵션은 DB 컬럼을 null로 비워 저장 공간/노이즈 절감
        boolean isDefault = opt.onFailure() == RunOptions.OnFailure.CONTINUE
                && (opt.runtimeParams() == null || opt.runtimeParams().isEmpty())
                && (opt.notificationWebhookUrl() == null || opt.notificationWebhookUrl().isBlank())
                && opt.slaSeconds() == null
                && opt.slaAction() == null
                && opt.concurrencyPolicy() == null;
        if (isDefault) {
            return null;
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("onFailure", opt.onFailure().name());
        if (opt.runtimeParams() != null && !opt.runtimeParams().isEmpty()) {
            map.put("runtimeParams", opt.runtimeParams());
        }
        if (opt.notificationWebhookUrl() != null && !opt.notificationWebhookUrl().isBlank()) {
            map.put("notificationWebhookUrl", opt.notificationWebhookUrl());
        }
        if (opt.slaSeconds() != null) {
            map.put("slaSeconds", opt.slaSeconds());
        }
        if (opt.slaAction() != null) {
            map.put("slaAction", opt.slaAction().name());
        }
        if (opt.concurrencyPolicy() != null) {
            map.put("concurrencyPolicy", opt.concurrencyPolicy().name());
        }
        return jsonUtil.toJson(map);
    }

    /**
     * Map → RunOptions 핵심 변환 로직. {@link #parseFromClob}/{@link #parseFromOptionsMap}이 공유.
     *
     * <p>알 수 없는 필드는 무시 (후방 호환). 타입 변환 실패는 해당 필드만 null로 떨어뜨리고 진행.</p>
     */
    private RunOptions parseFromMap(Map<String, Object> raw) {
        if (raw == null) {
            return RunOptions.defaults();
        }
        RunOptions.OnFailure onFailure = RunOptions.OnFailure.parse(asString(raw.get("onFailure")));

        Map<String, String> params = new LinkedHashMap<>();
        Object pRaw = raw.get("runtimeParams");
        if (pRaw instanceof Map<?, ?> pm) {
            // key는 String 강제, value는 null이면 그대로 null 저장 (호출자가 .get(k)에서 null 처리)
            pm.forEach((k, v) -> params.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }

        String webhook = asString(raw.get("notificationWebhookUrl"));

        // slaSeconds — Number 또는 String 모두 허용 (controller body는 JSON Number, DB CLOB도 Number)
        Long slaSeconds = null;
        Object slaRaw = raw.get("slaSeconds");
        if (slaRaw instanceof Number sn) {
            slaSeconds = sn.longValue();
        } else if (slaRaw instanceof String ss && !ss.isBlank()) {
            try {
                slaSeconds = Long.parseLong(ss.trim());
            } catch (NumberFormatException ignore) {
                // 숫자 파싱 실패는 옵션 미설정과 동일 처리 — 정의 default로 fallback
            }
        }

        SlaAction slaAction = null;
        String slaActStr = asString(raw.get("slaAction"));
        if (slaActStr != null && !slaActStr.isBlank()) {
            slaAction = SlaAction.parse(slaActStr);
        }

        // #165 — concurrency policy override (정의 default와 다를 때만 비-null)
        ConcurrencyPolicy concurrencyPolicy = null;
        String cpStr = asString(raw.get("concurrencyPolicy"));
        if (cpStr != null && !cpStr.isBlank()) {
            concurrencyPolicy = ConcurrencyPolicy.parse(cpStr);
        }

        return new RunOptions(onFailure, params, webhook, slaSeconds, slaAction, concurrencyPolicy);
    }

    /** Object → Map 안전 캐스팅 (Map 아니면 null). raw cast 경고 한곳에 격리. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
    }

    /** Object → String 안전 변환 (null이면 null, 그 외엔 toString). */
    private static String asString(Object o) {
        return o == null ? null : (o instanceof String s ? s : String.valueOf(o));
    }
}
