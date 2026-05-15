package com.example.station8plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M16 시나리오 플러그인 단위 테스트. 호스트 없이 활동 메서드를 직접 검증.
 */
class ExpressionTestPluginTest {

    private final ExpressionTestPlugin plugin = new ExpressionTestPlugin();

    // ---- ECHO_INPUT ----

    @Test
    void echoInput_returnsInputAsIs() {
        assertEquals("{\"x\":1}", plugin.echoInput("{\"x\":1}"));
        assertEquals("plain text", plugin.echoInput("plain text"));
    }

    @Test
    void echoInput_nullReturnsLiteralNullString() {
        assertEquals("null", plugin.echoInput(null));
    }

    // ---- REQUIRE_FIELD_ID ----

    @Test
    void requireFieldId_jsonWithId_passes() {
        String out = plugin.requireFieldId("{\"id\":42,\"name\":\"foo\"}");
        assertEquals("{\"validated\":true}", out);
    }

    @Test
    void requireFieldId_jsonWithoutId_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> plugin.requireFieldId("{\"name\":\"foo\"}"));
        assertTrue(ex.getMessage().contains("expected JSON with \"id\" key"));
    }

    @Test
    void requireFieldId_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> plugin.requireFieldId(null));
    }

    // ---- TRANSFORM_JSON ----

    @Test
    void transformJson_wrapsInputAsEchoed() {
        assertEquals("{\"echoed\":{\"a\":1}}", plugin.transformJson("{\"a\":1}"));
    }

    @Test
    void transformJson_nullInputWrappedAsLiteralNull() {
        assertEquals("{\"echoed\":null}", plugin.transformJson(null));
    }
}
