package com.station8.app.definition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #178 — {@link DagDefinitionRequest.Builder} + {@link DagDefinitionRequest.LineSettings} 단위 테스트.
 *
 * <p>빌더가 누적 후방 호환 생성자(4/6/7-arg)와 동등한 결과를 만드는지, settings/개별 setter 혼용
 * 시맨틱이 의도대로인지 검증.</p>
 */
class DagDefinitionRequestBuilderTest {

    private static DagDefinitionRequest.NodeDef simpleNode() {
        return new DagDefinitionRequest.NodeDef(
                "n-1", "Validate", "VALIDATE_ORDER", null, 0, 0, null);
    }

    @Test
    void builder_minimal_buildsValidRequest() {
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("MinimalFlow")
                .nodes(List.of(simpleNode()))
                .build();

        assertThat(req.definitionNm()).isEqualTo("MinimalFlow");
        assertThat(req.description()).isNull();
        assertThat(req.slaSeconds()).isNull();
        assertThat(req.slaAction()).isNull();
        assertThat(req.concurrencyPolicy()).isNull();
        assertThat(req.tags()).isNull();
        assertThat(req.nodes()).hasSize(1);
        assertThat(req.edges()).isNull();
    }

    @Test
    void builder_allFieldsIndividually_buildsExpectedRequest() {
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("FullFlow")
                .description("주문 처리")
                .slaSeconds(3600L)
                .slaAction("ALERT_ONLY")
                .concurrencyPolicy("SKIP_IF_RUNNING")
                .tags(List.of("prod", "core"))
                .nodes(List.of(simpleNode()))
                .edges(List.of())
                .build();

        assertThat(req.definitionNm()).isEqualTo("FullFlow");
        assertThat(req.description()).isEqualTo("주문 처리");
        assertThat(req.slaSeconds()).isEqualTo(3600L);
        assertThat(req.slaAction()).isEqualTo("ALERT_ONLY");
        assertThat(req.concurrencyPolicy()).isEqualTo("SKIP_IF_RUNNING");
        assertThat(req.tags()).containsExactly("prod", "core");
        assertThat(req.edges()).isEmpty();
    }

    @Test
    void builder_settingsBundle_unpacksIntoFields() {
        // LineSettings 묶음 주입이 4개 필드를 개별 호출과 동일하게 펼치는지
        DagDefinitionRequest.LineSettings settings = new DagDefinitionRequest.LineSettings(
                7200L, "AUTO_TERMINATE", "PIPELINE_2", List.of("dev"));
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("SettingsFlow")
                .settings(settings)
                .nodes(List.of(simpleNode()))
                .build();

        assertThat(req.slaSeconds()).isEqualTo(7200L);
        assertThat(req.slaAction()).isEqualTo("AUTO_TERMINATE");
        assertThat(req.concurrencyPolicy()).isEqualTo("PIPELINE_2");
        assertThat(req.tags()).containsExactly("dev");
    }

    @Test
    void builder_settingsThenIndividualOverride_lastWins() {
        // settings(...) 호출 후 개별 setter — 가장 마지막 호출이 이김
        DagDefinitionRequest.LineSettings base = new DagDefinitionRequest.LineSettings(
                1000L, "ALERT_ONLY", "CONCURRENT", List.of("dev"));
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("OverrideFlow")
                .settings(base)
                .slaSeconds(9999L)  // override
                .concurrencyPolicy("SKIP_IF_RUNNING")  // override
                .nodes(List.of(simpleNode()))
                .build();

        assertThat(req.slaSeconds()).isEqualTo(9999L);
        assertThat(req.concurrencyPolicy()).isEqualTo("SKIP_IF_RUNNING");
        // settings로 들어왔지만 override 안 한 두 필드는 base 유지
        assertThat(req.slaAction()).isEqualTo("ALERT_ONLY");
        assertThat(req.tags()).containsExactly("dev");
    }

    @Test
    void builder_individualThenSettingsBundle_settingsWins() {
        // 개별 setter 후 settings(...) — settings가 마지막이므로 4개 모두 덮어씀
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("ReverseOrderFlow")
                .slaSeconds(100L)
                .concurrencyPolicy("CONCURRENT")
                .settings(new DagDefinitionRequest.LineSettings(
                        500L, "AUTO_TERMINATE", "PIPELINE_1", List.of("staging")))
                .nodes(List.of(simpleNode()))
                .build();

        assertThat(req.slaSeconds()).isEqualTo(500L);
        assertThat(req.slaAction()).isEqualTo("AUTO_TERMINATE");
        assertThat(req.concurrencyPolicy()).isEqualTo("PIPELINE_1");
        assertThat(req.tags()).containsExactly("staging");
    }

    @Test
    void builder_settingsNull_isNoOp() {
        DagDefinitionRequest req = DagDefinitionRequest.builder()
                .definitionNm("NullSettingsFlow")
                .slaSeconds(123L)
                .settings(null)  // null 입력은 기존 값 유지
                .nodes(List.of(simpleNode()))
                .build();

        assertThat(req.slaSeconds()).isEqualTo(123L);
        assertThat(req.slaAction()).isNull();
    }

    @Test
    void lineSettings_empty_allNull() {
        DagDefinitionRequest.LineSettings empty = DagDefinitionRequest.LineSettings.empty();
        assertThat(empty.slaSeconds()).isNull();
        assertThat(empty.slaAction()).isNull();
        assertThat(empty.concurrencyPolicy()).isNull();
        assertThat(empty.tags()).isNull();
    }

    @Test
    @SuppressWarnings("removal")
    void builder_matchesDeprecated7ArgConstructor() {
        // 빌더 결과가 deprecated 7-arg 생성자(#141 시그니처)와 같은 record로 평가되는지 확인 —
        // 후방 호환성 회귀 방지.
        DagDefinitionRequest legacy = new DagDefinitionRequest(
                "Equivalence", "desc",
                3600L, "ALERT_ONLY", "SKIP_IF_RUNNING",
                List.of(simpleNode()), List.of());

        DagDefinitionRequest viaBuilder = DagDefinitionRequest.builder()
                .definitionNm("Equivalence")
                .description("desc")
                .slaSeconds(3600L)
                .slaAction("ALERT_ONLY")
                .concurrencyPolicy("SKIP_IF_RUNNING")
                .nodes(List.of(simpleNode()))
                .edges(List.of())
                .build();

        assertThat(viaBuilder).isEqualTo(legacy);
    }
}
