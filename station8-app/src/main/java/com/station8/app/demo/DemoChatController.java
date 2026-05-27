package com.station8.app.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 데모용 OpenAI 호환 LLM endpoint — {@code demo} 프로파일에서만 활성 (#345).
 *
 * <p>외부 LLM/API 키 없이 AI agent 데모를 self-contained로 돌리기 위한 가짜 모델. 상태를 안 들고
 * 요청 메시지로 턴을 판단한다:</p>
 * <ul>
 *   <li>대화에 {@code tool} 결과 메시지가 아직 없음 → {@code get_weather} 호출 요청 (tool_calls)</li>
 *   <li>tool 결과가 있음 → 최종 답변 (stop)</li>
 * </ul>
 *
 * <p>{@code DemoSeedRunner}가 시드하는 {@code DemoLlmAgent} 라인의 credential이 이 endpoint를
 * baseUrl로 가리킨다. 운영에선 같은 패턴으로 실제 OpenAI/Anthropic/Ollama URL을 박으면 된다.</p>
 */
@RestController
@Profile("demo")
@RequestMapping("/api/demo/llm/v1")
public class DemoChatController {

    /**
     * OpenAI Chat Completions 흉내. agent 루프가 이 endpoint를 반복 호출한다.
     *
     * @param body OpenAI 요청 (model/messages/tools...)
     * @return tool_call 또는 최종 답변 응답
     */
    @PostMapping("/chat/completions")
    public Map<String, Object> chatCompletions(@RequestBody Map<String, Object> body) {
        return hasToolResult(body) ? finalAnswer() : toolCall();
    }

    @SuppressWarnings("unchecked")
    private boolean hasToolResult(Map<String, Object> body) {
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list)) {
            return false;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && "tool".equals(m.get("role"))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> toolCall() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "get_weather");
        function.put("arguments", "{\"city\":\"Seoul\"}");
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call_demo_1");
        call.put("type", "function");
        call.put("function", function);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", List.of(call));

        return response(message, "tool_calls", 25, 10);
    }

    private Map<String, Object> finalAnswer() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", "서울은 맑고 기온은 22도입니다.");
        return response(message, "stop", 40, 12);
    }

    private Map<String, Object> response(Map<String, Object> message, String finishReason,
                                         int promptTokens, int completionTokens) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("message", message);
        choice.put("finish_reason", finishReason);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);

        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> choices = new ArrayList<>();
        choices.add(choice);
        root.put("choices", choices);
        root.put("usage", usage);
        return root;
    }
}
