package com.station8.app.controller;

import com.station8.engine.core.LineExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 라인 인스턴스 운영 REST API.
 *
 * <ul>
 *   <li>POST /api/line/instances/{id}/terminate — 강제 종료 (#101)</li>
 * </ul>
 *
 * <p>UI 액션({@code POST /line/instance/{id}/terminate})과 별개 — 자동화/스크립트용.</p>
 */
@RestController
@RequestMapping("/api/line/instances")
public class LineInstanceController {

    private final LineExecutor lineExecutor;

    public LineInstanceController(LineExecutor lineExecutor) {
        this.lineExecutor = lineExecutor;
    }

    @PostMapping("/{id}/terminate")
    public ResponseEntity<Map<String, String>> terminate(@PathVariable("id") String instanceId) {
        try {
            lineExecutor.terminateLine(instanceId);
            return ResponseEntity.ok(Map.of("status", "TERMINATED", "instanceId", instanceId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", ex.getMessage()));
        }
    }
}
