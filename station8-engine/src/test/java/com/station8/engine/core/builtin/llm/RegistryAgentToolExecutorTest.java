package com.station8.engine.core.builtin.llm;

import com.station8.engine.core.ActivityArgumentResolver;
import com.station8.engine.core.LineRegistry;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #341 — {@link RegistryAgentToolExecutor} 가 도구 이름을 등록 활동으로 매핑해 실행하는지 검증.
 */
class RegistryAgentToolExecutorTest {

    private RegistryAgentToolExecutor executor;
    private final ToolBean toolBean = new ToolBean();

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        JsonUtil jsonUtil = new JsonUtil();
        Method echo = ToolBean.class.getMethod("echo", String.class);
        Method boom = ToolBean.class.getMethod("boom", String.class);

        LineRegistry registry = new LineRegistry() {
            @Override
            public ActivityMetadata getActivity(String name) {
                if ("echo".equals(name)) {
                    return new ActivityMetadata(toolBean, echo, null);
                }
                if ("boom".equals(name)) {
                    return new ActivityMetadata(toolBean, boom, null);
                }
                return null;
            }
        };
        // String-only 활동은 DataSourceRegistry를 안 건드리므로 null로 충분.
        ActivityArgumentResolver resolver = new ActivityArgumentResolver(null);
        executor = new RegistryAgentToolExecutor(registry, resolver, jsonUtil);
    }

    @Test
    void execute_invokesActivityWithArgumentsAsJsonInput() {
        String result = executor.execute("echo", Map.of("city", "Seoul"));
        assertThat(result).startsWith("echoed:");
        assertThat(result).contains("Seoul");
    }

    @Test
    void execute_unknownTool_throws() {
        assertThatThrownBy(() -> executor.execute("ghost", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown tool");
    }

    @Test
    void execute_activityThrows_propagatesCause() {
        assertThatThrownBy(() -> executor.execute("boom", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("kaboom");
    }

    /** 도구로 노출할 테스트 활동 — String input만 받는다. */
    public static final class ToolBean {
        public String echo(String input) {
            return "echoed:" + input;
        }

        public String boom(String input) {
            throw new RuntimeException("kaboom");
        }
    }
}
