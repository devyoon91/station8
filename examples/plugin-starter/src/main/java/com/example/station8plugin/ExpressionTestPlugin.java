package com.example.station8plugin;

import com.station8.engine.annotation.Activity;

/**
 * M16 (#247) 표현식 평가 통합 시나리오를 위한 테스트 플러그인.
 *
 * <p>{@link ExamplePlugin}는 플러그인 starter 사이클 검증용 dummy다. 본 클래스는
 * inputData가 표현식 평가를 거친 후 활동에 도달했는지 확인할 수 있도록 입력을 그대로
 * 출력으로 echo하거나, 입력 형태를 검증하는 역할을 모은 것이다.</p>
 *
 * <h3>등록되는 활동</h3>
 * <ul>
 *   <li>{@code ECHO_INPUT} — 입력을 그대로 출력으로 echo (활동 INPUT_DATA / OUTPUT_DATA 비교용)</li>
 *   <li>{@code REQUIRE_FIELD_ID} — 입력 JSON에 {@code "id"} 키가 있어야 통과 (M16 평가 결과 검증용)</li>
 *   <li>{@code TRANSFORM_JSON} — 입력 JSON을 파싱해서 {@code "echoed": ...} 래퍼로 감싸 반환 (체이닝 데모)</li>
 * </ul>
 *
 * <p>모든 활동은 {@code retryCount=0}이라 첫 실패가 곧 FAILED — 시나리오에서 평가 실패 ↔
 * 활동 실패의 1:1 매핑을 확인하기 쉽다.</p>
 */
public class ExpressionTestPlugin {

    public ExpressionTestPlugin() {}

    @Activity(
            value = "ECHO_INPUT",
            retryCount = 0,
            backoffSeconds = 0,
            description = "M16 평가 후의 INPUT_DATA를 OUTPUT_DATA로 그대로 echo (시나리오에서 표현식 치환 검증용)."
    )
    public String echoInput(String input) {
        return input == null ? "null" : input;
    }

    @Activity(
            value = "REQUIRE_FIELD_ID",
            retryCount = 0,
            backoffSeconds = 0,
            description = "입력 JSON에 \"id\" 키가 있어야 통과 — M16 평가 결과가 기대 형태인지 빠르게 fail-fast."
    )
    public String requireFieldId(String input) {
        if (input == null || !input.contains("\"id\"")) {
            throw new IllegalArgumentException(
                    "REQUIRE_FIELD_ID expected JSON with \"id\" key, got: " + input);
        }
        return "{\"validated\":true}";
    }

    @Activity(
            value = "TRANSFORM_JSON",
            retryCount = 0,
            backoffSeconds = 0,
            description = "입력 JSON을 {\"echoed\": <input>} 형태로 wrap (체이닝 데모용 — JSON 모드 평가 결과를 다음 노드로 흘림)."
    )
    public String transformJson(String input) {
        String body = (input == null || input.isBlank()) ? "null" : input;
        return "{\"echoed\":" + body + "}";
    }
}
