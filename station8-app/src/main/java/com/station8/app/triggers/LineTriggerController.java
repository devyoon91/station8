package com.station8.app.triggers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.engine.entity.LineTrigger;
import com.station8.engine.repository.LineDefinitionRepository;
import com.station8.engine.repository.LineTriggerRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M20 (#311) — Trigger CRUD REST + Mustache 목록 페이지.
 *
 * <h3>REST</h3>
 * <ul>
 *   <li>{@code POST   /api/line/triggers}     — 신규 등록 (ADMIN)</li>
 *   <li>{@code GET    /api/line/triggers}     — 목록 (USER read-only)</li>
 *   <li>{@code GET    /api/line/triggers/{id}} — 단건</li>
 *   <li>{@code PUT    /api/line/triggers/{id}} — 갱신 (ADMIN)</li>
 *   <li>{@code DELETE /api/line/triggers/{id}} — soft delete (ADMIN)</li>
 * </ul>
 *
 * <h3>UI</h3>
 * {@code GET /line/triggers} — 등록 목록 + 신규 등록 form + 토글/삭제 버튼. Webhook trigger 등록 시
 * full URL preview + curl HMAC 예시까지.
 *
 * <h3>검증</h3>
 * <ul>
 *   <li>{@code triggerKey} — lowercase + alphanumeric + dash/underscore, 1~128자</li>
 *   <li>{@code triggerType} — 현재 {@code webhook}만 (향후 kafka 등)</li>
 *   <li>{@code configJson} — type별 schema 검증. webhook은 {@code hmacSecret} 필드 필수</li>
 *   <li>{@code definitionId} — U_LINE_DEFINITION에 존재해야</li>
 * </ul>
 */
@Controller
public class LineTriggerController {

    /** 지원 trigger type. 미지원 type 등록 시 400. */
    static final java.util.Set<String> SUPPORTED_TYPES = java.util.Set.of("webhook");

    private final LineTriggerRepository triggerRepository;
    private final LineDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;

    public LineTriggerController(LineTriggerRepository triggerRepository,
                                 LineDefinitionRepository definitionRepository,
                                 ObjectMapper objectMapper) {
        this.triggerRepository = triggerRepository;
        this.definitionRepository = definitionRepository;
        this.objectMapper = objectMapper;
    }

    // ====== UI ======

    /**
     * 목록 페이지. 등록된 trigger 전부 + 활성 정의 목록 (등록 form dropdown용).
     *
     * <p>webhook URL preview는 현재 request의 scheme/host로 구성 — 운영자가 그대로 curl 명령에
     * 복사할 수 있게.</p>
     */
    @GetMapping("/line/triggers")
    public String page(Model model, HttpServletRequest req) {
        List<LineTrigger> triggers = triggerRepository.findAllActive();
        String baseUrl = req.getScheme() + "://" + req.getServerName()
                + (req.getServerPort() == 80 || req.getServerPort() == 443
                        ? "" : ":" + req.getServerPort());

        // 라인 정의 dropdown용 — 활성 정의만
        List<com.station8.engine.entity.LineDefinition> defs =
                definitionRepository.findActiveDefinitionsPage(0, 1000);
        Map<String, String> defNameById = new LinkedHashMap<>();
        defs.forEach(d -> defNameById.put(d.id(), d.definitionNm()));

        List<Map<String, Object>> rows = triggers.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", t.id());
            m.put("definitionId", t.definitionId());
            m.put("definitionNm", defNameById.getOrDefault(t.definitionId(), "(deleted)"));
            m.put("triggerType", t.triggerType());
            m.put("triggerKey", t.triggerKey());
            m.put("activeFl", t.activeFl());
            m.put("isActive", "Y".equals(t.activeFl()));
            m.put("isWebhook", "webhook".equals(t.triggerType()));
            m.put("webhookUrl", "webhook".equals(t.triggerType())
                    ? baseUrl + "/api/triggers/webhook/" + t.triggerKey()
                    : null);
            return m;
        }).toList();

        List<Map<String, Object>> defView = defs.stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", d.id());
            m.put("definitionNm", d.definitionNm());
            return m;
        }).toList();

        model.addAttribute("triggers", rows);
        model.addAttribute("totalCount", rows.size());
        model.addAttribute("definitions", defView);
        model.addAttribute("baseUrl", baseUrl);
        model.addAttribute("navTriggers", true);
        return "triggers";
    }

    // ====== REST API ======

    @ResponseBody
    @PostMapping("/api/line/triggers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TriggerResponse> create(@Valid @RequestBody TriggerRequest req,
                                                  Authentication auth) {
        validateType(req.triggerType());
        validateDefinition(req.definitionId());
        validateConfig(req.triggerType(), req.configJson());

        String id = UUID.randomUUID().toString();
        LineTrigger t = new LineTrigger(
                id, req.definitionId(), req.triggerType(), req.triggerKey(),
                req.configJson(), req.activeFl() == null ? "Y" : req.activeFl(),
                "N",
                LocalDateTime.now(), auth != null ? auth.getName() : "system",
                null, null);
        try {
            triggerRepository.insert(t);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException(
                    "trigger key already exists: " + req.triggerKey());
        }
        LineTrigger saved = triggerRepository.findById(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(TriggerResponse.from(saved));
    }

    @ResponseBody
    @GetMapping("/api/line/triggers")
    @PreAuthorize("isAuthenticated()")
    public List<TriggerResponse> list() {
        return triggerRepository.findAllActive().stream()
                .map(TriggerResponse::from).toList();
    }

    @ResponseBody
    @GetMapping("/api/line/triggers/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TriggerResponse> get(@PathVariable("id") String id) {
        LineTrigger t = triggerRepository.findById(id);
        if (t == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TriggerResponse.from(t));
    }

    @ResponseBody
    @PutMapping("/api/line/triggers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TriggerResponse> update(@PathVariable("id") String id,
                                                  @Valid @RequestBody TriggerRequest req,
                                                  Authentication auth) {
        LineTrigger existing = triggerRepository.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        validateType(req.triggerType());
        validateDefinition(req.definitionId());
        validateConfig(req.triggerType(), req.configJson());

        LineTrigger updated = new LineTrigger(
                id, req.definitionId(), req.triggerType(), req.triggerKey(),
                req.configJson(), req.activeFl() == null ? existing.activeFl() : req.activeFl(),
                existing.delFl(),
                existing.regDt(), existing.regId(),
                LocalDateTime.now(), auth != null ? auth.getName() : "system");
        try {
            triggerRepository.update(updated);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("trigger key conflict: " + req.triggerKey());
        }
        return ResponseEntity.ok(TriggerResponse.from(triggerRepository.findById(id)));
    }

    @ResponseBody
    @DeleteMapping("/api/line/triggers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable("id") String id,
                                                      Authentication auth) {
        if (triggerRepository.findById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        triggerRepository.softDelete(id, auth != null ? auth.getName() : "system");
        return ResponseEntity.ok(Map.of("id", id, "status", "deleted"));
    }

    // ====== Validation ======

    private static void validateType(String type) {
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "unsupported trigger type: " + type +
                            " (supported: " + SUPPORTED_TYPES + ")");
        }
    }

    private void validateDefinition(String definitionId) {
        if (definitionRepository.findDefinitionById(definitionId) == null) {
            throw new IllegalArgumentException("definitionId not found: " + definitionId);
        }
    }

    /**
     * configJson schema 검증. type별:
     * <ul>
     *   <li>{@code webhook} — {@code hmacSecret}(string) 필수</li>
     * </ul>
     */
    private void validateConfig(String type, String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException("configJson is required");
        }
        Map<String, Object> parsed;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = objectMapper.readValue(configJson, Map.class);
            parsed = p;
        } catch (Exception ex) {
            throw new IllegalArgumentException("configJson is not valid JSON: " + ex.getMessage());
        }
        if ("webhook".equals(type)) {
            Object secret = parsed.get("hmacSecret");
            if (!(secret instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException(
                        "webhook config requires hmacSecret (vault credential 이름)");
            }
        }
    }

    // ====== DTOs ======

    /**
     * 등록/갱신 요청.
     *
     * @param definitionId 대상 라인 정의 ID. 필수.
     * @param triggerType  현재 {@code webhook}만 지원. 미지원 type은 400.
     * @param triggerKey   외부 식별자 — lowercase + alphanumeric + dash/underscore, 1~128자
     * @param configJson   type별 config. webhook이면 hmacSecret 필드 필수
     * @param activeFl     {@code Y}/{@code N}. 미지정 시 등록은 Y, 갱신은 기존 값 유지
     */
    public record TriggerRequest(
            @NotBlank(message = "definitionId는 필수입니다.")
            String definitionId,

            @NotBlank(message = "triggerType은 필수입니다.")
            String triggerType,

            @NotBlank(message = "triggerKey는 필수입니다.")
            @Size(min = 1, max = 128, message = "triggerKey는 1~128자.")
            @Pattern(regexp = "^[a-z0-9_-]+$",
                    message = "triggerKey는 lowercase / 숫자 / dash / underscore만 허용.")
            String triggerKey,

            @NotBlank(message = "configJson은 필수입니다.")
            String configJson,

            String activeFl
    ) {}

    /** 응답 — config는 그대로 노출 (HMAC secret 자체는 vault credential 이름 — 평문 아님). */
    public record TriggerResponse(
            String id,
            String definitionId,
            String triggerType,
            String triggerKey,
            String configJson,
            String activeFl,
            LocalDateTime regDt,
            LocalDateTime editDt
    ) {
        public static TriggerResponse from(LineTrigger t) {
            return new TriggerResponse(
                    t.id(), t.definitionId(), t.triggerType(), t.triggerKey(),
                    t.configJson(), t.activeFl(), t.regDt(), t.editDt());
        }
    }
}
