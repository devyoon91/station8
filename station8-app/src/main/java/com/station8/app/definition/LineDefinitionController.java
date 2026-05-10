package com.station8.app.definition;

import com.station8.engine.core.RunOptions;
import com.station8.engine.exception.LineEngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DAG 정의 등록/조회/수정/삭제 + 즉시 실행 REST API.
 *
 * <ul>
 *   <li>POST   /api/line/definitions          — 신규 등록 (검증 + 저장)</li>
 *   <li>GET    /api/line/definitions/{id}     — 단건 조회</li>
 *   <li>PUT    /api/line/definitions/{id}     — 역/엣지 교체 (메타 + 그래프)</li>
 *   <li>DELETE /api/line/definitions/{id}     — 소프트 삭제</li>
 *   <li>POST   /api/line/definitions/{id}/run — 즉시 실행 (인스턴스 생성)</li>
 * </ul>
 *
 * 검증 실패는 {@code 400 Bad Request} + {@code LineEngineException} 메시지로 응답.
 */
@RestController
@RequestMapping("/api/line/definitions")
public class LineDefinitionController {

    private final LineDefinitionService service;

    public LineDefinitionController(LineDefinitionService service) {
        this.service = service;
    }

    /**
     * #140 — 신규 정의 생성. 인증된 USER만 가능.
     * 생성 후 LineDefinitionService가 생성자에게 ADMIN 권한 자동 부여.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> create(@RequestBody DagDefinitionRequest req) {
        String id = service.createDefinition(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("definitionId", id));
    }

    @GetMapping("/{id}")
    public DagDefinitionResponse get(@PathVariable("id") String id) {
        return service.getDefinition(id);
    }

    /** #140 — WRITE 권한 필요. */
    @PutMapping("/{id}")
    @PreAuthorize("@lineAcl.canWrite(#id)")
    public ResponseEntity<Void> replace(@PathVariable("id") String id,
                                        @RequestBody DagDefinitionRequest req) {
        service.replaceDefinition(id, req);
        return ResponseEntity.noContent().build();
    }

    /** #140 — WRITE 권한 필요 (소프트 삭제). */
    @DeleteMapping("/{id}")
    @PreAuthorize("@lineAcl.canWrite(#id)")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.deleteDefinition(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 즉시 실행 (#134) — body shape:
     * <pre>
     * {
     *   "input": "...",                    // optional
     *   "options": {                       // optional — 미지정 시 default(continue/빈맵/전역 webhook)
     *     "onFailure": "CONTINUE|ABORT",   // default CONTINUE
     *     "runtimeParams": { "k": "v" },   // default 빈맵
     *     "notificationWebhookUrl": "..."  // default null (전역 사용)
     *   }
     * }
     * </pre>
     * 후방 호환 — body 없거나 options 없으면 default 적용.
     */
    /**
     * #140 — EXECUTE 권한 필요.
     * #141 — SKIP_IF_RUNNING 정책에서 skip 시 200 OK + {@code skipped:true} 본문 (에러 아님).
     */
    @PostMapping("/{id}/run")
    @PreAuthorize("@lineAcl.canExecute(#id)")
    public ResponseEntity<Map<String, Object>> run(@PathVariable("id") String id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        String inputData = (body == null || body.get("input") == null) ? null : String.valueOf(body.get("input"));
        RunOptions options = parseOptions(body);
        RunResult result = service.runDefinitionWithResult(id, inputData, options);
        if (result.skipped()) {
            // #141 — SKIP은 정상 흐름 — 200 OK + 메시지
            Map<String, Object> body2 = new LinkedHashMap<>();
            body2.put("skipped", true);
            body2.put("reason", result.reason());
            body2.put("conflictingInstanceId", result.conflictingInstanceId());
            return ResponseEntity.ok(body2);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("instanceId", result.instanceId()));
    }

    /**
     * Body의 {@code options} 서브맵을 {@link RunOptions}로 변환. 없거나 비어있으면 default.
     * 알 수 없는 필드는 무시 (후방 호환).
     */
    @SuppressWarnings("unchecked")
    private RunOptions parseOptions(Map<String, Object> body) {
        if (body == null) return RunOptions.defaults();
        Object raw = body.get("options");
        if (!(raw instanceof Map<?, ?> optMap) || optMap.isEmpty()) {
            return RunOptions.defaults();
        }
        RunOptions.OnFailure onFailure = RunOptions.OnFailure.parse(
                optMap.get("onFailure") == null ? null : String.valueOf(optMap.get("onFailure")));

        Map<String, String> params = new LinkedHashMap<>();
        Object pRaw = optMap.get("runtimeParams");
        if (pRaw instanceof Map<?, ?> pm) {
            pm.forEach((k, v) -> params.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }

        String webhook = optMap.get("notificationWebhookUrl") == null
                ? null : String.valueOf(optMap.get("notificationWebhookUrl"));

        // #138 — SLA override (선택)
        Long slaSeconds = null;
        Object slaRaw = optMap.get("slaSeconds");
        if (slaRaw instanceof Number sn) slaSeconds = sn.longValue();
        else if (slaRaw instanceof String ss && !ss.isBlank()) {
            try { slaSeconds = Long.parseLong(ss.trim()); } catch (NumberFormatException ignore) {}
        }
        com.station8.engine.core.SlaAction slaAction = null;
        Object slaActRaw = optMap.get("slaAction");
        if (slaActRaw instanceof String sas && !sas.isBlank()) {
            slaAction = com.station8.engine.core.SlaAction.parse(sas);
        }

        return new RunOptions(onFailure, params, webhook, slaSeconds, slaAction);
    }

    // === 예외 매핑 ===

    @ExceptionHandler(LineEngineException.class)
    public ResponseEntity<Map<String, String>> handleEngineException(LineEngineException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("errorCode", e.getErrorCode(), "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("message", e.getMessage()));
    }
}
