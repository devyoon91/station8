package com.station8.engine.core.builtin.llm;

import com.station8.engine.core.ActivityArgumentResolver;
import com.station8.engine.core.LineRegistry;
import com.station8.engine.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

/**
 * {@link AgentToolExecutor} 기본 구현 (#341) — 도구 이름을 등록된 {@code @Activity}로 매핑해 실행.
 *
 * <p>LLM의 도구 호출 인자를 활동의 String input(JSON)으로 넘겨 리플렉션 invoke하고, 반환값을
 * 결과 텍스트로 돌려준다. {@link LineRegistry}는 {@code ContextRefreshedEvent}에서 lazy 스캔하므로
 * 본 빈이 레지스트리를 주입해도 순환 의존이 생기지 않는다.</p>
 *
 * <h3>한계 (MVP)</h3>
 * {@link ActivityArgumentResolver}의 String-input 경로로 호출하므로 {@code LineContext}/
 * {@code @BoundDataSource}를 받는 활동은 그 인자가 null/primary로 들어간다. {@code http.request}처럼
 * String input만 받는 활동이 1급 도구. 더 풍부한 바인딩은 후속 과제.
 */
@Component
public class RegistryAgentToolExecutor implements AgentToolExecutor {

    private final LineRegistry registry;
    private final ActivityArgumentResolver argumentResolver;
    private final JsonUtil jsonUtil;

    public RegistryAgentToolExecutor(LineRegistry registry,
                                     ActivityArgumentResolver argumentResolver,
                                     JsonUtil jsonUtil) {
        this.registry = registry;
        this.argumentResolver = argumentResolver;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public String execute(String toolName, Map<String, Object> arguments) {
        LineRegistry.ActivityMetadata meta = registry.getActivity(toolName);
        if (meta == null) {
            throw new IllegalArgumentException("unknown tool (no registered activity): " + toolName);
        }
        String inputJson = jsonUtil.toJson(arguments == null ? Map.of() : arguments);
        Object[] args = argumentResolver.resolve(meta.method(), inputJson);
        try {
            Object result = meta.method().invoke(meta.bean(), args);
            return result == null ? "" : String.valueOf(result);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new RuntimeException(cause.getMessage(), cause);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException("tool invoke failed: " + ex.getMessage(), ex);
        }
    }
}
