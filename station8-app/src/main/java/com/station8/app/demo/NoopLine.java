package com.station8.app.demo;

import com.station8.engine.annotation.Activity;
import com.station8.engine.annotation.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 항상 성공하는 노옵(no-op) 액티비티 컬렉션.
 *
 * <p>e2e 테스트, 빌더 데모, 부하 테스트에서 사용한다. 입력은 임의의 문자열 (JSON 파싱 X), 출력은 입력 그대로 반환.
 * 부수 효과(DB 삽입, 외부 호출 등)가 없으므로 반복 실행이 안전하고 DLQ 적재도 발생하지 않는다.</p>
 *
 * <p>등록되는 액티비티:</p>
 * <ul>
 *   <li>{@code NOOP} — 입력을 그대로 출력으로 돌려준다.</li>
 *   <li>{@code NOOP_FAIL_ONCE} — 첫 시도에서 실패하고, 재시도 시 성공한다 (재시도 정책 시연용).</li>
 * </ul>
 *
 * <p>비주얼 빌더에서 노드 팔레트에 자동 노출됨 ({@code LineRegistry} 스캔).</p>
 */
@Component
@Line("NoopLine")
public class NoopLine {

    private static final Logger log = LoggerFactory.getLogger(NoopLine.class);

    private static final java.util.concurrent.ConcurrentMap<String, Integer> attemptCounter =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Activity(value = "NOOP", retryCount = 0, backoffSeconds = 0)
    public String noop(String input) {
        log.info("NOOP activity: input={}", input);
        return input == null ? "null" : input;
    }

    /**
     * 첫 호출은 실패, 두 번째 호출은 성공. 재시도 백오프 동작 시연용.
     * inputData를 키로 사용하므로 동일 input으로 두 번 호출하지 말 것.
     */
    @Activity(value = "NOOP_FAIL_ONCE", retryCount = 3, backoffSeconds = 2)
    public String noopFailOnce(String input) {
        String key = input == null ? "null" : input;
        int attempt = attemptCounter.merge(key, 1, Integer::sum);
        if (attempt == 1) {
            throw new RuntimeException("NOOP_FAIL_ONCE: simulated first-attempt failure (input=" + key + ")");
        }
        return "ok-after-retry-" + attempt;
    }
}
