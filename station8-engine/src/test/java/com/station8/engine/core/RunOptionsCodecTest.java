package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RunOptionsCodec 단위 테스트 — 단일 진입점이 4개 호출측의 동작을 모두 보존하는지 검증.
 *
 * <p>이전에 분산되어 있던 파싱/직렬화 로직이 codec으로 통합된 후, 동일한 입력이
 * 동일한 RunOptions를 생성하는지 회귀 검증.</p>
 */
class RunOptionsCodecTest {

    private RunOptionsCodec codec;
    private JsonUtil jsonUtil;

    @BeforeEach
    void setup() {
        jsonUtil = new JsonUtil();
        codec = new RunOptionsCodec(jsonUtil);
    }

    // ===== parseFromClob =====

    @Test
    void parseFromClob_null_returnsDefaults() {
        RunOptions opts = codec.parseFromClob(null);
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(opts.runtimeParams()).isEmpty();
        assertThat(opts.notificationWebhookUrl()).isNull();
    }

    @Test
    void parseFromClob_blank_returnsDefaults() {
        assertThat(codec.parseFromClob("").onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(codec.parseFromClob("   ").onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
    }

    @Test
    void parseFromClob_invalidJson_safeFallbackToDefaults() {
        // 잘못된 JSON — fallback이 핵심 (운영 멈춤 방지)
        RunOptions opts = codec.parseFromClob("{not valid json");
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.CONTINUE);
        assertThat(opts.runtimeParams()).isEmpty();
    }

    @Test
    void parseFromClob_fullPayload_parsesAllFields() {
        String json = """
                {
                  "onFailure": "ABORT",
                  "runtimeParams": {"k1": "v1", "k2": "v2"},
                  "notificationWebhookUrl": "https://hook",
                  "slaSeconds": 3600,
                  "slaAction": "AUTO_TERMINATE"
                }
                """;
        RunOptions opts = codec.parseFromClob(json);
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.ABORT);
        assertThat(opts.runtimeParams()).containsEntry("k1", "v1").containsEntry("k2", "v2");
        assertThat(opts.notificationWebhookUrl()).isEqualTo("https://hook");
        assertThat(opts.slaSeconds()).isEqualTo(3600L);
        assertThat(opts.slaAction()).isEqualTo(SlaAction.AUTO_TERMINATE);
    }

    @Test
    void parseFromClob_unknownFields_ignored() {
        // 알 수 없는 필드는 무시 — 후방 호환
        String json = "{\"onFailure\":\"ABORT\",\"futureField\":\"x\",\"randomKey\":42}";
        RunOptions opts = codec.parseFromClob(json);
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.ABORT);
    }

    @Test
    void parseFromClob_slaSecondsAsString_convertsToLong() {
        // CLOB에 숫자가 String으로 저장된 경우도 허용
        RunOptions opts = codec.parseFromClob("{\"slaSeconds\":\"7200\"}");
        assertThat(opts.slaSeconds()).isEqualTo(7200L);
    }

    @Test
    void parseFromClob_slaSecondsInvalidString_silentlyIgnored() {
        // 숫자 파싱 실패는 옵션 미설정과 동일 처리
        RunOptions opts = codec.parseFromClob("{\"slaSeconds\":\"not-a-number\"}");
        assertThat(opts.slaSeconds()).isNull();
    }

    // ===== parseFromOptionsMap =====

    @Test
    void parseFromOptionsMap_null_returnsDefaults() {
        assertThat(codec.parseFromOptionsMap(null).onFailure())
                .isEqualTo(RunOptions.OnFailure.CONTINUE);
    }

    @Test
    void parseFromOptionsMap_empty_returnsDefaults() {
        assertThat(codec.parseFromOptionsMap(Map.of()).onFailure())
                .isEqualTo(RunOptions.OnFailure.CONTINUE);
    }

    @Test
    void parseFromOptionsMap_fullPayload_parsesAllFields() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("onFailure", "PAUSE_ON_FAILURE");
        map.put("runtimeParams", Map.of("env", "prod"));
        map.put("notificationWebhookUrl", "https://wh");
        map.put("slaSeconds", 1800);  // Number
        map.put("slaAction", "ALERT_ONLY");

        RunOptions opts = codec.parseFromOptionsMap(map);
        assertThat(opts.onFailure()).isEqualTo(RunOptions.OnFailure.PAUSE_ON_FAILURE);
        assertThat(opts.runtimeParams()).containsEntry("env", "prod");
        assertThat(opts.notificationWebhookUrl()).isEqualTo("https://wh");
        assertThat(opts.slaSeconds()).isEqualTo(1800L);
        assertThat(opts.slaAction()).isEqualTo(SlaAction.ALERT_ONLY);
    }

    // ===== serializeToClob =====

    @Test
    void serializeToClob_null_returnsNull() {
        assertThat(codec.serializeToClob(null)).isNull();
    }

    @Test
    void serializeToClob_allDefaults_returnsNull() {
        // 모두 default — DB 컬럼 비움
        assertThat(codec.serializeToClob(RunOptions.defaults())).isNull();
    }

    @Test
    void serializeToClob_someFieldsSet_returnsJson() {
        RunOptions opts = new RunOptions(
                RunOptions.OnFailure.ABORT,
                Map.of("k", "v"),
                "https://wh",
                3600L,
                SlaAction.AUTO_TERMINATE
        );
        String json = codec.serializeToClob(opts);
        assertThat(json).isNotNull();
        assertThat(json).contains("\"onFailure\":\"ABORT\"");
        assertThat(json).contains("\"notificationWebhookUrl\":\"https://wh\"");
        assertThat(json).contains("\"slaSeconds\":3600");
        assertThat(json).contains("\"slaAction\":\"AUTO_TERMINATE\"");
        assertThat(json).contains("\"k\":\"v\"");
    }

    @Test
    void roundTrip_parseAfterSerialize_preservesValues() {
        // 직렬화 → 파싱 라운드트립이 손실 없는지
        RunOptions original = new RunOptions(
                RunOptions.OnFailure.PAUSE_ON_FAILURE,
                Map.of("region", "us-west", "shard", "3"),
                "https://hook.example",
                7200L,
                SlaAction.ALERT_ONLY
        );
        String json = codec.serializeToClob(original);
        RunOptions parsed = codec.parseFromClob(json);
        assertThat(parsed.onFailure()).isEqualTo(original.onFailure());
        assertThat(parsed.runtimeParams()).containsAllEntriesOf(original.runtimeParams());
        assertThat(parsed.notificationWebhookUrl()).isEqualTo(original.notificationWebhookUrl());
        assertThat(parsed.slaSeconds()).isEqualTo(original.slaSeconds());
        assertThat(parsed.slaAction()).isEqualTo(original.slaAction());
    }

    // ===== #165 concurrencyPolicy override =====

    @Test
    void parseFromClob_concurrencyPolicy_parsed() {
        RunOptions opts = codec.parseFromClob("{\"concurrencyPolicy\":\"SKIP_IF_RUNNING\"}");
        assertThat(opts.concurrencyPolicy()).isEqualTo(ConcurrencyPolicy.SKIP_IF_RUNNING);
    }

    @Test
    void parseFromOptionsMap_concurrencyPolicy_parsed() {
        RunOptions opts = codec.parseFromOptionsMap(Map.of("concurrencyPolicy", "PIPELINE_2"));
        assertThat(opts.concurrencyPolicy()).isEqualTo(ConcurrencyPolicy.PIPELINE_2);
    }

    @Test
    void parseFromClob_concurrencyPolicyBlank_isNull() {
        // blank/missing은 null — 정의 default 사용 의미
        assertThat(codec.parseFromClob("{\"concurrencyPolicy\":\"\"}").concurrencyPolicy()).isNull();
        assertThat(codec.parseFromClob("{}").concurrencyPolicy()).isNull();
    }

    @Test
    void serializeToClob_onlyConcurrencyPolicySet_includedInJson() {
        // concurrencyPolicy만 비-default여도 옵션 직렬화됨 (default 검사 통과)
        RunOptions opts = new RunOptions(
                RunOptions.OnFailure.CONTINUE, Map.of(), null, null, null,
                ConcurrencyPolicy.CONCURRENT);
        String json = codec.serializeToClob(opts);
        assertThat(json).isNotNull().contains("\"concurrencyPolicy\":\"CONCURRENT\"");
    }

    @Test
    void roundTrip_concurrencyPolicy_preserved() {
        RunOptions original = new RunOptions(
                RunOptions.OnFailure.CONTINUE, Map.of(), null, null, null,
                ConcurrencyPolicy.PIPELINE_3);
        RunOptions parsed = codec.parseFromClob(codec.serializeToClob(original));
        assertThat(parsed.concurrencyPolicy()).isEqualTo(ConcurrencyPolicy.PIPELINE_3);
    }
}
