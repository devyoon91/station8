package com.station8.app.expressions;

import com.station8.engine.core.ExpressionEvaluator;
import com.station8.engine.core.ExpressionEvaluator.ExpressionEvaluationException;
import com.station8.engine.core.LineContextBindings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * M21 (#306) — 표현식 dry-run endpoint. 빌더의 "Test inputParams" UX가 호출.
 *
 * <p>운영 평가 경로({@link com.station8.engine.core.InputParamsEvaluator})와 같은
 * {@link ExpressionEvaluator}를 사용하되, 사용자 입력 dummy {@code $prev} / {@code $ctx} /
 * {@code $credentials}로 평가 — 빌더에서 라인 실행 안 해도 표현식 결과를 미리 볼 수 있게.</p>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>USER 인증 필요 — 임의 사용자가 GraalVM JS를 평가하게 두지 않음</li>
 *   <li>5초 wall-clock timeout — 무한 루프 표현식이 워커 스레드를 점유하지 못하게</li>
 *   <li>Sandbox는 {@link ExpressionEvaluator}가 적용 — {@code HostAccess.NONE} 등</li>
 *   <li>{@link LineContextBindings#toJsExposable}로 사용자 JSON을 ProxyObject로 wrap —
 *       Java reflection escape 차단</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/line/expressions")
public class ExpressionEvaluationController {

    private static final Logger log = LoggerFactory.getLogger(ExpressionEvaluationController.class);

    /** wall-clock timeout. 무한 루프 / heavy computation 방어. */
    private static final Duration EVAL_TIMEOUT = Duration.ofSeconds(5);

    private final ExpressionEvaluator evaluator;
    private final LineContextBindings bindingsHelper;
    private final ScheduledExecutorService timeoutExecutor;

    public ExpressionEvaluationController(
            ExpressionEvaluator evaluator,
            LineContextBindings bindingsHelper) {
        this.evaluator = evaluator;
        this.bindingsHelper = bindingsHelper;
        // 평가 1건당 짧게 굴리고 끝 — daemon 단일 스레드면 충분.
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "expression-dryrun-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 표현식 dry-run.
     *
     * <p>요청은 {@code expression} 외에 {@code $prev} / {@code $ctx} / {@code $credentials}를
     * dummy로 받아 같은 키로 binding에 박는다. 안 주면 빈 객체.</p>
     */
    @PostMapping("/_evaluate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EvaluateResponse> evaluate(@RequestBody EvaluateRequest req) {
        if (req == null || req.expression() == null || req.expression().isBlank()) {
            return ResponseEntity.badRequest().body(
                    EvaluateResponse.failure("expression is required"));
        }

        long start = System.nanoTime();
        try {
            Object result = runWithTimeout(() -> {
                Map<String, Object> bindings = buildBindings(req);
                return evaluator.evaluate(req.expression(), bindings);
            }, EVAL_TIMEOUT);

            long durationMs = (System.nanoTime() - start) / 1_000_000;
            return ResponseEntity.ok(EvaluateResponse.success(
                    result, describeType(result), durationMs));
        } catch (TimeoutException ex) {
            log.warn("expression dry-run timeout (>{}ms): {}",
                    EVAL_TIMEOUT.toMillis(), truncate(req.expression()));
            return ResponseEntity.ok(EvaluateResponse.failure(
                    "평가 시간 초과 (>" + EVAL_TIMEOUT.toMillis() + "ms) — 무한 루프 또는 heavy computation"));
        } catch (ExpressionEvaluationException ex) {
            return ResponseEntity.ok(EvaluateResponse.failure(ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(EvaluateResponse.failure("interrupted"));
        } catch (Exception ex) {
            return ResponseEntity.ok(EvaluateResponse.failure(
                    ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    /** 사용자 dummy {@code $prev} / {@code $ctx} / {@code $credentials}를 ProxyObject로 감싸 binding 구성. */
    private Map<String, Object> buildBindings(EvaluateRequest req) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("$prev", bindingsHelper.toJsExposable(
                req.prev() != null ? req.prev() : Map.of("json", Map.of())));
        bindings.put("$ctx", bindingsHelper.toJsExposable(
                req.ctx() != null ? req.ctx() : Map.of(
                        "input", Map.of(),
                        "run", Map.of("id", "test-instance", "attempt", 1L),
                        "line", Map.of("name", "TestLine", "activity", "test"),
                        "runtime", Map.of())));
        bindings.put("$credentials", bindingsHelper.toJsExposable(
                req.credentials() != null ? req.credentials() : Map.of()));
        return bindings;
    }

    /**
     * 콜백을 별도 스레드에서 굴리고 timeout 도달 시 future cancel. GraalVM Context.interrupt가
     * 없으면 cancel만으로 평가가 즉시 멈추지 않을 수 있지만, 호출자 입장에서는 응답이 즉시 돌아옴
     * (background 평가는 결국 GC된다).
     */
    private <T> T runWithTimeout(Callable<T> task, Duration timeout) throws Exception {
        Future<T> future = timeoutExecutor.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            // unwrap — 사용자가 originally throw한 예외를 그대로
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) throw e;
            throw new RuntimeException(cause);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        }
    }

    private static String describeType(Object result) {
        if (result == null) return "null";
        if (result instanceof Boolean) return "boolean";
        if (result instanceof Number) return "number";
        if (result instanceof String) return "string";
        if (result instanceof Map) return "object";
        if (result instanceof java.util.List) return "array";
        return result.getClass().getSimpleName();
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    /**
     * 요청 DTO.
     *
     * @param expression 평가할 템플릿 (예: {@code "{{ $prev.json.id }}"})
     * @param prev       dummy $prev 객체. {@code {json: ..., binary: ...}} shape 권장
     * @param ctx        dummy $ctx 객체. {@code {input, run, line, runtime}} shape 권장
     * @param credentials dummy $credentials 객체. {@code {<name>: {value, type, ...}}}
     */
    public record EvaluateRequest(
            String expression,
            Object prev,
            Object ctx,
            Object credentials
    ) {}

    /**
     * 응답 DTO.
     *
     * @param ok          true면 정상 평가, false면 error 채워짐
     * @param result      평가 결과 (raw value)
     * @param resultType  결과 타입 힌트 (null/boolean/number/string/object/array)
     * @param error       에러 메시지 (ok=false일 때만)
     * @param durationMs  평가 소요 ms
     */
    public record EvaluateResponse(
            boolean ok,
            Object result,
            String resultType,
            String error,
            Long durationMs
    ) {
        static EvaluateResponse success(Object result, String type, long durationMs) {
            return new EvaluateResponse(true, result, type, null, durationMs);
        }

        static EvaluateResponse failure(String error) {
            return new EvaluateResponse(false, null, null, error, null);
        }
    }
}
