package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RunOptions} 파싱/기본값 동작 검증 (#134).
 *
 * <p>RUN_OPTIONS CLOB JSON ↔ {@code RunOptions} 라운드트립 + 후방 호환:
 * null/빈 문자열/알 수 없는 필드 모두 default로 graceful fallback.</p>
 */
class RunOptionsTest {

    private final JsonUtil jsonUtil = new JsonUtil();

    @Test
    void defaults_continueAndEmptyParamsAndNullWebhook() {
        RunOptions opts = RunOptions.defaults();
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(opts.runtimeParams()).isEmpty();
        assertThat(opts.notificationWebhookUrl()).isNull();
    }

    @Test
    void parse_nullJson_returnsDefaults() {
        RunOptions opts = RunOptions.parse(null, jsonUtil);
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(opts.runtimeParams()).isEmpty();
    }

    @Test
    void parse_blankJson_returnsDefaults() {
        assertThat(RunOptions.parse("", jsonUtil).onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(RunOptions.parse("   ", jsonUtil).onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
    }

    @Test
    void parse_abortPolicyAndParamsAndWebhook_roundTrip() {
        String json = "{\"onFailure\":\"ABORT\",\"runtimeParams\":{\"region\":\"KR\",\"tier\":\"premium\"},"
                + "\"notificationWebhookUrl\":\"https://hooks.example.com/dlq\"}";
        RunOptions opts = RunOptions.parse(json, jsonUtil);

        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.ABORT);
        assertThat(opts.runtimeParams())
                .containsEntry("region", "KR")
                .containsEntry("tier", "premium");
        assertThat(opts.notificationWebhookUrl()).isEqualTo("https://hooks.example.com/dlq");
    }

    @Test
    void parse_unknownFields_areIgnored() {
        String json = "{\"onFailure\":\"CONTINUE\",\"unknownFuture\":42,\"runtimeParams\":{\"k\":\"v\"}}";
        RunOptions opts = RunOptions.parse(json, jsonUtil);
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(opts.runtimeParams()).containsEntry("k", "v");
    }

    @Test
    void parse_invalidOnFailure_fallsBackToContinue() {
        String json = "{\"onFailure\":\"NONSENSE\"}";
        RunOptions opts = RunOptions.parse(json, jsonUtil);
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
    }

    @Test
    void parse_runtimeParamsWithNonStringValues_areCoerced() {
        // JSON 숫자/불린이 문자열로 강제되는지 — RUN_OPTIONS는 string→string 맵
        String json = "{\"runtimeParams\":{\"count\":42,\"enabled\":true}}";
        RunOptions opts = RunOptions.parse(json, jsonUtil);
        assertThat(opts.runtimeParams())
                .containsEntry("count", "42")
                .containsEntry("enabled", "true");
    }

    @Test
    void onFailureParse_caseInsensitiveAndTrim() {
        assertThat(RunOptions.OnFailure.parse("abort")).isEqualTo(RunOptions.OnFailure.ABORT);
        assertThat(RunOptions.OnFailure.parse(" ABORT ")).isEqualTo(RunOptions.OnFailure.ABORT);
        assertThat(RunOptions.OnFailure.parse("Continue")).isEqualTo(RunOptions.OnFailure.CONTINUE);
    }

    @Test
    void onFailureParse_nullOrBlank_isContinue() {
        assertThat(RunOptions.OnFailure.parse(null)).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(RunOptions.OnFailure.parse("")).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(RunOptions.OnFailure.parse("   ")).isEqualTo(RunOptions.OnFailure.CONTINUE);
    }

    @Test
    void compactConstructor_normalizesNulls() {
        RunOptions opts = new RunOptions(null, null, null);
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(opts.runtimeParams()).isEmpty();
        assertThat(opts.notificationWebhookUrl()).isNull();
    }

    @Test
    void preservesParamInsertionOrder() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("a", "1");
        ordered.put("b", "2");
        ordered.put("c", "3");
        RunOptions opts = new RunOptions(RunOptions.OnFailure.CONTINUE, ordered, null);
        assertThat(opts.runtimeParams().keySet()).containsExactly("a", "b", "c");
    }

    // ---- #138 — SLA 필드 ----

    @Test
    void defaults_slaSecondsAndActionAreNull() {
        RunOptions opts = RunOptions.defaults();
        assertThat(opts.slaSeconds()).isNull();
        assertThat(opts.slaAction()).isNull();
    }

    @Test
    void parse_slaSecondsAndAction_roundTrip() {
        String json = "{\"slaSeconds\":3600,\"slaAction\":\"AUTO_TERMINATE\"}";
        RunOptions opts = RunOptions.parse(json, jsonUtil);
        assertThat(opts.slaSeconds()).isEqualTo(3600L);
        assertThat(opts.slaAction()).isEqualTo(SlaAction.AUTO_TERMINATE);
    }

    @Test
    void parse_slaSecondsAsString_isCoercedToLong() {
        // 일부 클라이언트는 number를 string으로 보낼 수 있음
        String json = "{\"slaSeconds\":\"7200\",\"slaAction\":\"alert_only\"}";
        RunOptions opts = RunOptions.parse(json, jsonUtil);
        assertThat(opts.slaSeconds()).isEqualTo(7200L);
        assertThat(opts.slaAction()).isEqualTo(SlaAction.ALERT_ONLY);
    }

    @Test
    void parse_invalidSlaAction_fallsBackToAlertOnly() {
        String json = "{\"slaAction\":\"NUKE_FROM_ORBIT\"}";
        RunOptions opts = RunOptions.parse(json, jsonUtil);
        assertThat(opts.slaAction()).isEqualTo(SlaAction.ALERT_ONLY);
    }

    @Test
    void parse_unknownFieldsIgnored_keepingSla() {
        String json = "{\"slaSeconds\":300,\"unknownFuture\":42}";
        RunOptions opts = RunOptions.parse(json, jsonUtil);
        assertThat(opts.slaSeconds()).isEqualTo(300L);
    }

    @Test
    void backwardCompat_threeArgConstructor_setsSlaNull() {
        RunOptions opts = new RunOptions(RunOptions.OnFailure.ABORT, Map.of(), "https://x");
        assertThat(opts.slaSeconds()).isNull();
        assertThat(opts.slaAction()).isNull();
    }

    @Test
    void slaActionParse_caseInsensitive() {
        assertThat(SlaAction.parse("auto_terminate")).isEqualTo(SlaAction.AUTO_TERMINATE);
        assertThat(SlaAction.parse(" ALERT_ONLY ")).isEqualTo(SlaAction.ALERT_ONLY);
    }

    @Test
    void slaActionParse_nullOrBlank_isAlertOnly() {
        assertThat(SlaAction.parse(null)).isEqualTo(SlaAction.ALERT_ONLY);
        assertThat(SlaAction.parse("")).isEqualTo(SlaAction.ALERT_ONLY);
        assertThat(SlaAction.parse("   ")).isEqualTo(SlaAction.ALERT_ONLY);
    }
}
