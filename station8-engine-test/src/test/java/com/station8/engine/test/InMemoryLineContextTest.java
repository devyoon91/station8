package com.station8.engine.test;

import com.station8.engine.core.LineContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryLineContextTest {

    @Test
    void builder_default_fillsReasonableValues() {
        InMemoryLineContext ctx = LineContextBuilder.create().build();

        assertNotNull(ctx.instanceId());
        assertTrue(ctx.instanceId().startsWith("test-"));
        assertEquals("test-workflow", ctx.workflowName());
        assertEquals("test-activity", ctx.currentActivityName());
        assertNull(ctx.nodeId());
        assertEquals(1, ctx.attempt());
        assertTrue(ctx.previousOutput().isEmpty());
        assertTrue(ctx.runtimeParams().isEmpty());
        assertNotNull(ctx.now());
        assertTrue(ctx.savedState().isEmpty());
        assertTrue(ctx.capturedNextActivityName().isEmpty());
    }

    @Test
    void builder_chain_overridesAllFields() {
        Instant fixed = Instant.parse("2026-05-22T00:00:00Z");
        InMemoryLineContext ctx = LineContextBuilder.create()
                .instanceId("inst-1")
                .workflowName("OrderFlow")
                .currentActivityName("VALIDATE")
                .nodeId("node-A")
                .attempt(3)
                .input("hello")
                .previousOutput("prev")
                .attribute("k1", "v1")
                .runtimeParam("date", "2026-05-22")
                .now(fixed)
                .build();

        assertEquals("inst-1", ctx.instanceId());
        assertEquals("OrderFlow", ctx.workflowName());
        assertEquals("VALIDATE", ctx.currentActivityName());
        assertEquals("node-A", ctx.nodeId());
        assertEquals(3, ctx.attempt());
        assertEquals("hello", ctx.input());
        assertEquals("prev", ctx.previousOutput().orElseThrow());
        assertEquals("v1", ctx.attributes().get("k1"));
        assertEquals("2026-05-22", ctx.runtimeParams().get("date"));
        assertEquals(fixed, ctx.now());
    }

    @Test
    void setNext_capturedAndReadable() {
        InMemoryLineContext ctx = LineContextBuilder.create().build();
        assertTrue(ctx.nextActivityName().isEmpty());

        ctx.setNext("SEND_EMAIL", Map.of("to", "alice@example.com"));

        assertEquals("SEND_EMAIL", ctx.nextActivityName().orElseThrow());
        assertEquals("SEND_EMAIL", ctx.capturedNextActivityName().orElseThrow());
        assertEquals(Map.of("to", "alice@example.com"), ctx.capturedNextActivityInput().orElseThrow());
    }

    @Test
    void saveState_capturedViaSavedStateAndLoadState() {
        InMemoryLineContext ctx = LineContextBuilder.create().build();
        ctx.saveState(Map.of("processed", 42));

        assertEquals(Map.of("processed", 42), ctx.savedState().orElseThrow());
        assertEquals(Map.of("processed", 42), ctx.loadState().orElseThrow());
    }

    @Test
    void reset_clearsCapturedSideEffectsButKeepsBuilderValues() {
        InMemoryLineContext ctx = LineContextBuilder.create()
                .input("orig-input")
                .build();
        ctx.setNext("X", "y");
        ctx.saveState("z");

        ctx.resetCapturedSideEffects();

        assertTrue(ctx.capturedNextActivityName().isEmpty());
        assertTrue(ctx.savedState().isEmpty());
        assertEquals("orig-input", ctx.input(), "빌더 설정값은 reset에 영향받지 않아야 한다");
    }

    @Test
    void attempt_zeroOrNegative_throws() {
        LineContextBuilder b = LineContextBuilder.create();
        assertThrows(IllegalArgumentException.class, () -> b.attempt(0));
        assertThrows(IllegalArgumentException.class, () -> b.attempt(-1));
    }

    @Test
    void withInput_shortcut_fillsInputOnly() {
        InMemoryLineContext ctx = InMemoryLineContext.withInput("quick");
        assertEquals("quick", ctx.input());
        assertEquals("test-workflow", ctx.workflowName());
    }

    @Test
    void implementsLineContextInterface() {
        // 플러그인 메서드 시그니처가 LineContext 받을 때 InMemoryLineContext가 그대로 전달 가능해야 한다
        LineContext asInterface = LineContextBuilder.create().build();
        assertNotNull(asInterface.instanceId());
    }
}
