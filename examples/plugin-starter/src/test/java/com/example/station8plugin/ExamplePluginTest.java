package com.example.station8plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 플러그인은 평범한 Java 클래스 — Spring 없이 직접 인스턴스화 + 메서드 호출로 단위 테스트 가능 (#105).
 */
class ExamplePluginTest {

    @Test
    void echo_returnsUppercaseValue() {
        String out = new ExamplePlugin().echo("hello");
        assertEquals("{\"value\":\"HELLO\"}", out);
    }

    @Test
    void echo_nullInput_returnsEmpty() {
        String out = new ExamplePlugin().echo(null);
        assertEquals("{\"value\":\"\"}", out);
    }

    @Test
    void echo_escapesQuotesAndBackslashes() {
        String out = new ExamplePlugin().echo("he\"llo\\");
        // 대문자 변환 + " → \", \ → \\
        assertEquals("{\"value\":\"HE\\\"LLO\\\\\"}", out);
    }
}
