package com.example.station8plugin;

import com.station8.engine.annotation.Activity;

/**
 * Station8 플러그인 최소 예제 (#105).
 *
 * <p>입력 문자열을 대문자로 변환해 JSON 페이로드로 반환. 외부 의존성 없음 — 빌드/업로드 사이클
 * 검증용 dummy.</p>
 *
 * <h3>호환 요구</h3>
 * <ul>
 *   <li>public no-arg 생성자 — {@link PluginLoader}가 newInstance()로 생성</li>
 *   <li>패키지는 코어와 충돌하지 않는 고유 네임스페이스 (com.example.*)</li>
 * </ul>
 *
 * @see <a href="../../../../../../docs/PLUGIN_DEVELOPMENT.md">PLUGIN_DEVELOPMENT.md</a>
 */
public class ExamplePlugin {

    /** PluginLoader가 호출하는 기본 생성자. */
    public ExamplePlugin() {}

    /**
     * 입력을 대문자로 변환.
     *
     * @param input raw 입력 페이로드 (대개 JSON, 본 예제는 plain text로 받음)
     * @return {@code {"value":"<UPPERCASED>"}} JSON 문자열
     */
    @Activity(
            value = "ECHO_UPPER",
            retryCount = 0,
            backoffSeconds = 0,
            description = "입력을 대문자로 변환 — 플러그인 사이클 검증용 dummy"
    )
    public String echo(String input) {
        String upper = input == null ? "" : input.toUpperCase();
        return "{\"value\":\"" + escape(upper) + "\"}";
    }

    /** 최소한의 JSON escape — `"`와 `\\`만 처리 (예제용). 실 사용 시 Jackson 권장. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
