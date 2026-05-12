package com.station8.app.definition;

import com.station8.engine.core.RunOptions;
import com.station8.engine.core.RunOptionsCodec;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * <p>검증 실패는 {@code 400 Bad Request} + {@code GlobalRestExceptionHandler}가 표준
 * {@code ErrorResponse} 포맷으로 응답.</p>
 */
@RestController
@RequestMapping("/api/line/definitions")
public class LineDefinitionController {

    private final LineDefinitionService service;
    private final RunOptionsCodec runOptionsCodec;
    private final DefinitionImportService importService;

    public LineDefinitionController(LineDefinitionService service,
                                    RunOptionsCodec runOptionsCodec,
                                    DefinitionImportService importService) {
        this.service = service;
        this.runOptionsCodec = runOptionsCodec;
        this.importService = importService;
    }

    /**
     * 신규 정의 생성. 인증된 USER만 가능.
     *
     * <p>#140 — 생성 후 {@link LineDefinitionService}가 생성자에게 ADMIN 권한 자동 부여.</p>
     * <p>#175 — Bean Validation으로 1차 입력 검증, 그래프 위상 검증은 {@code DagValidator}가 별도 수행.</p>
     *
     * @param req 정의 등록 요청 — {@code @Valid}로 필드 검증
     * @return 201 + {@code {definitionId}}
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> create(@Valid @RequestBody DagDefinitionRequest req) {
        String id = service.createDefinition(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("definitionId", id));
    }

    @GetMapping("/{id}")
    public DagDefinitionResponse get(@PathVariable("id") String id) {
        return service.getDefinition(id);
    }

    /**
     * #193 — 정의를 JSON으로 export. {@code Content-Disposition: attachment}로 download 트리거.
     *
     * <p>파일명은 {@code <definitionNm>-v<versionNo>.json} (영숫자/하이픈/언더스코어/점 외 문자는 ``_``로 sanitize).
     * 응답 본문은 {@link DefinitionExportPayload} — {@code schemaVersion} 명시.</p>
     *
     * <p>읽기 권한자만 호출 가능 (#140 — ACL READ).</p>
     */
    @GetMapping("/{id}/export")
    @PreAuthorize("@lineAcl.canRead(#id)")
    public ResponseEntity<DefinitionExportPayload> export(@PathVariable("id") String id) {
        DagDefinitionResponse def = service.getDefinition(id);
        DefinitionExportPayload payload = DefinitionExportPayload.from(def);
        String filename = sanitizeFilename(def.definitionNm() + "-v" + def.versionNo() + ".json");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }

    /** 파일명 안전화 — 영숫자/하이픈/언더스코어/점 외 문자는 ``_``로 치환. 길이 100자 제한. */
    private static String sanitizeFilename(String name) {
        String cleaned = (name == null ? "definition" : name).replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    /**
     * #193 — 정의 import. {@link DefinitionExportPayload}를 받아 새 정의로 저장.
     *
     * <p>인증된 사용자만 호출 가능 (#140 — create 권한과 동일). 충돌 정책은 query param
     * {@code onConflict=newVersion|rename|reject} (default: newVersion).</p>
     *
     * <p>응답 — 201 Created + {@code {definitionId, definitionNm, appliedPolicy}}.</p>
     */
    @PostMapping("/import")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> importDefinition(
            @RequestBody DefinitionExportPayload payload,
            @RequestParam(value = "onConflict", required = false) String onConflict) {
        DefinitionImportService.ConflictPolicy policy =
                DefinitionImportService.ConflictPolicy.fromQuery(onConflict);
        DefinitionImportService.ImportResult result = importService.importDefinition(payload, policy);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionId", result.definitionId());
        body.put("definitionNm", result.definitionNm());
        body.put("appliedPolicy", result.appliedPolicy().name());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * 정의의 노드/엣지 + 메타를 통째로 교체. WRITE 권한 필요 (#140).
     *
     * @param id  교체 대상 정의 ID
     * @param req 교체 요청 — {@code @Valid}로 필드 검증 (#175)
     * @return 204 No Content
     */
    @PutMapping("/{id}")
    @PreAuthorize("@lineAcl.canWrite(#id)")
    public ResponseEntity<Void> replace(@PathVariable("id") String id,
                                        @Valid @RequestBody DagDefinitionRequest req) {
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
        // RunOptionsCodec — body의 options 서브맵을 RunOptions로 변환. body/options 없으면 default 반환.
        @SuppressWarnings("unchecked")
        Map<String, Object> optionsMap = body == null
                ? null
                : (body.get("options") instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
        RunOptions options = runOptionsCodec.parseFromOptionsMap(optionsMap);
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

    // 예외 매핑은 GlobalRestExceptionHandler로 통합 — controller-level 핸들러 제거.
    // RunOptions 파싱/직렬화는 RunOptionsCodec(engine.core)으로 통합 (#147).
}
