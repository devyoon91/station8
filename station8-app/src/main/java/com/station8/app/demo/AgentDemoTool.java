package com.station8.app.demo;

import com.station8.engine.annotation.Activity;
import com.station8.engine.annotation.ActivityParam;
import com.station8.engine.annotation.ActivityParam.Kind;
import com.station8.engine.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M23 (#345) — AI agent 데모용 도구 활동 {@code @Activity("get_weather")}.
 *
 * <p>AgenticLoop({@code llm.agent})이 도구로 호출하는 대상. 외부 의존 0 — 도시 이름을 받아 고정
 * 날씨를 돌려준다. 데모/E2E가 "LLM이 도구를 호출 → 실행 → 결과 되먹임" 흐름을 self-contained로
 * 증명하게 한다.</p>
 *
 * <p>이름에 점({@code .})을 쓰지 않는 이유: OpenAI/Anthropic tool name은 {@code [a-zA-Z0-9_-]}만
 * 허용한다. {@code http.request}처럼 점 있는 빌트인 활동을 agent 도구로 직접 노출하려면 이름
 * 정규화가 필요 — 별도 과제. 데모는 점 없는 이름으로 그 제약을 피한다.</p>
 */
@Component
public class AgentDemoTool {

    private final JsonUtil jsonUtil;

    public AgentDemoTool(JsonUtil jsonUtil) {
        this.jsonUtil = jsonUtil;
    }

    @Activity(value = "get_weather", retryCount = 0,
            description = "데모 도구 — 도시의 (고정) 현재 날씨 반환. agent 도구 호출 데모용.",
            params = {
                @ActivityParam(name = "city", kind = Kind.STRING, required = true,
                        description = "날씨를 조회할 도시 이름.")
            })
    public String getWeather(String inputJson) {
        String city = "Unknown";
        if (inputJson != null && !inputJson.isBlank()) {
            Map<String, Object> in = jsonUtil.fromJson(inputJson, Map.class);
            if (in != null && in.get("city") != null) {
                city = String.valueOf(in.get("city"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("city", city);
        out.put("condition", "Sunny");
        out.put("tempC", 22);
        return jsonUtil.toJson(out);
    }
}
