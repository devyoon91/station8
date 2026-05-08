package com.station8.app.definition;

import com.station8.engine.exception.LineEngineException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * DAG 정의 등록/조회/수정/삭제 + 즉시 실행 REST API.
 *
 * <ul>
 *   <li>POST   /api/workflow/definitions          — 신규 등록 (검증 + 저장)</li>
 *   <li>GET    /api/workflow/definitions/{id}     — 단건 조회</li>
 *   <li>PUT    /api/workflow/definitions/{id}     — 노드/엣지 교체 (메타 + 그래프)</li>
 *   <li>DELETE /api/workflow/definitions/{id}     — 소프트 삭제</li>
 *   <li>POST   /api/workflow/definitions/{id}/run — 즉시 실행 (인스턴스 생성)</li>
 * </ul>
 *
 * 검증 실패는 {@code 400 Bad Request} + {@code LineEngineException} 메시지로 응답.
 */
@RestController
@RequestMapping("/api/workflow/definitions")
public class LineDefinitionController {

    private final LineDefinitionService service;

    public LineDefinitionController(LineDefinitionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestBody DagDefinitionRequest req) {
        String id = service.createDefinition(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("definitionId", id));
    }

    @GetMapping("/{id}")
    public DagDefinitionResponse get(@PathVariable("id") String id) {
        return service.getDefinition(id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> replace(@PathVariable("id") String id,
                                        @RequestBody DagDefinitionRequest req) {
        service.replaceDefinition(id, req);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.deleteDefinition(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, String>> run(@PathVariable("id") String id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        String inputData = (body == null || body.get("input") == null) ? null : String.valueOf(body.get("input"));
        String instanceId = service.runDefinition(id, inputData);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("instanceId", instanceId));
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
