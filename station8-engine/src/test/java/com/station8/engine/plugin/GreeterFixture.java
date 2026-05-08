package com.station8.engine.plugin;

import com.station8.engine.annotation.Activity;

/**
 * PluginLoaderTest를 위한 fixture 클래스.
 *
 * <p>test-classpath에 컴파일되어 있어야 jar로 추출 가능. 기본 생성자를 가지며 ``@Activity("GREET")``
 * 메서드 1개를 노출한다.</p>
 */
public class GreeterFixture {

    public GreeterFixture() {
    }

    @Activity("GREET")
    public String greet(String name) {
        return "Hello, " + name;
    }
}
