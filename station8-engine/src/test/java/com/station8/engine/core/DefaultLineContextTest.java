package com.station8.engine.core;

import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultLineContextTest {

    private JsonUtil jsonUtil;
    private DefaultLineContext context;

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        context = new DefaultLineContext(
            "inst-001", "TestLine", "step1", 1, "inputData", null, jsonUtil
        );
    }

    @Test
    @DisplayName("기본 속성이 올바르게 반환된다")
    void basicProperties() {
        assertEquals("inst-001", context.instanceId());
        assertEquals("TestLine", context.workflowName());
        assertEquals("step1", context.currentActivityName());
        assertEquals(1, context.attempt());
        assertEquals("inputData", context.input());
        assertTrue(context.previousOutput().isEmpty());
    }

    @Test
    @DisplayName("previousOutput이 주입되면 Optional로 반환된다")
    void previousOutput_present() {
        var ctx = new DefaultLineContext(
            "inst-002", "WF", "step2", 2, null, "prevResult", jsonUtil
        );
        assertTrue(ctx.previousOutput().isPresent());
        assertEquals("prevResult", ctx.previousOutput().get());
    }

    @Test
    @DisplayName("setNext/nextActivityName/nextActivityInput 동작 확인")
    void setNextAndRetrieve() {
        assertTrue(context.nextActivityName().isEmpty());
        assertTrue(context.nextActivityInput().isEmpty());

        context.setNext("step2", Map.of("key", "value"));

        assertEquals("step2", context.nextActivityName().orElse(null));
        assertEquals(Map.of("key", "value"), context.nextActivityInput().orElse(null));
    }

    @Test
    @DisplayName("attributes 맵에 값을 추가하고 조회할 수 있다")
    void attributes() {
        context.attributes().put("executionId", "exec-001");
        assertEquals("exec-001", context.attributes().get("executionId"));
    }

    @Test
    @DisplayName("saveState/loadState로 상태 스냅샷을 저장하고 조회한다")
    void saveAndLoadState() {
        assertTrue(context.loadState().isEmpty());

        context.saveState(Map.of("progress", 50));

        assertTrue(context.loadState().isPresent());
        // saveState는 JSON 문자열로 저장되므로 String으로 반환됨
        String json = (String) context.loadState().get();
        assertTrue(json.contains("\"progress\""));
        assertTrue(json.contains("50"));
    }

    @Test
    @DisplayName("setStateData로 외부 상태 데이터를 주입할 수 있다")
    void setStateData() {
        context.setStateData("{\"restored\":true}");
        assertTrue(context.loadState().isPresent());
        assertEquals("{\"restored\":true}", context.loadState().get());
    }

    @Test
    @DisplayName("getStateSnapshotJson은 저장된 JSON 문자열을 반환한다")
    void getStateSnapshotJson() {
        assertNull(context.getStateSnapshotJson());
        context.saveState("snapshot");
        assertNotNull(context.getStateSnapshotJson());
    }
}
