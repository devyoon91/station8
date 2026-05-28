package com.station8.app.controller;

import com.station8.app.credential.CredentialController;
import com.station8.engine.crypto.CredentialCrypto;
import com.station8.engine.entity.Credential;
import com.station8.engine.repository.CredentialRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 어드민 — Credential vault 관리 UI (#358).
 *
 * <p>REST API({@link CredentialController})는 #270/#271에서 완비됐고 본 컨트롤러는 페이지 렌더링과
 * 폼 처리만 담당한다. ADMIN role만 진입 — {@link com.station8.app.security.SecurityConfig}의
 * {@code /admin/**} 권한 매처 + 본 클래스의 {@code @PreAuthorize}로 이중 가드.</p>
 *
 * <p>평문 value는 응답/화면에 절대 노출하지 않는다. 폼은 "변경할 때만 새 value 입력" 패턴.</p>
 */
@Controller
@RequestMapping("/admin/credentials")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCredentialController {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialController.class);

    /**
     * type별 schema JSON에 들어갈 키 목록. UI 폼이 각 키를 개별 input으로 받아 schemaJson을 빌드.
     * 목록에 없는 type은 schema 없이 value만 받는다 ({@code generic} 등).
     */
    private static final Map<String, List<String>> SCHEMA_KEYS_BY_TYPE = Map.of(
            "http_basic", List.of("username"),
            "api_key", List.of("header"),
            "openai_compatible", List.of("baseUrl"),
            "anthropic", List.of("baseUrl"));

    private final CredentialRepository repository;
    private final CredentialCrypto crypto;
    private final JsonUtil jsonUtil;

    public AdminCredentialController(CredentialRepository repository,
                                     CredentialCrypto crypto,
                                     JsonUtil jsonUtil) {
        this.repository = repository;
        this.crypto = crypto;
        this.jsonUtil = jsonUtil;
    }

    @GetMapping
    public String list(Model model) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Credential c : repository.findAllActive()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id());
            row.put("name", c.name());
            row.put("type", c.type());
            row.put("schemaJson", c.schemaJson());
            row.put("regDt", c.regDt());
            row.put("regId", c.regId());
            row.put("editDt", c.editDt());
            row.put("editId", c.editId());
            rows.add(row);
        }
        model.addAttribute("credentials", rows);
        model.addAttribute("totalCount", rows.size());
        model.addAttribute("navAdminCredentials", true);
        return "admin-credentials";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("id", null);
        form.put("name", "");
        form.put("type", "");
        form.put("schemaUsername", "");
        form.put("schemaHeader", "");
        form.put("schemaBaseUrl", "");
        model.addAttribute("form", form);
        model.addAttribute("isEdit", false);
        model.addAttribute("supportedTypes", CredentialController.SUPPORTED_TYPES);
        model.addAttribute("submitUrl", "/admin/credentials");
        model.addAttribute("submitLabel", "Create");
        model.addAttribute("title", "New Credential");
        model.addAttribute("navAdminCredentials", true);
        return "admin-credential-form";
    }

    @PostMapping
    public String create(@RequestParam("name") String name,
                         @RequestParam("type") String type,
                         @RequestParam("value") String value,
                         @RequestParam(value = "schemaUsername", required = false) String schemaUsername,
                         @RequestParam(value = "schemaHeader", required = false) String schemaHeader,
                         @RequestParam(value = "schemaBaseUrl", required = false) String schemaBaseUrl,
                         Authentication auth,
                         RedirectAttributes flash) {
        try {
            if (!CredentialController.SUPPORTED_TYPES.contains(type)) {
                throw new IllegalArgumentException("unsupported credential type: " + type);
            }
            if (value == null || value.isEmpty()) {
                // openai_compatible은 빈 apiKey 허용 (로컬 Ollama). 그 외 type은 필수.
                if (!"openai_compatible".equals(type)) {
                    throw new IllegalArgumentException("value is required");
                }
            }
            String schemaJson = buildSchemaJson(type, schemaUsername, schemaHeader, schemaBaseUrl);
            String id = UUID.randomUUID().toString();
            String valueEnc = (value == null || value.isEmpty())
                    ? crypto.encrypt(" ") // 빈 value는 placeholder 1자(공백)로 — credential 모듈은 non-empty 요구
                    : crypto.encrypt(value);
            Credential c = new Credential(
                    id, name, type, valueEnc, schemaJson,
                    "N", LocalDateTime.now(), auth != null ? auth.getName() : "admin",
                    null, null);
            repository.insert(c);
            log.info("Credential 등록: name={}, type={}, id={}", name, type, id);
            flash.addFlashAttribute("flashMsg", "[OK] '" + name + "' 등록 완료");
            flash.addFlashAttribute("flashOk", true);
        } catch (DuplicateKeyException ex) {
            flash.addFlashAttribute("flashMsg", "[FAIL] 이름 '" + name + "' 이미 존재 (soft delete된 항목도 unique 충돌)");
            flash.addFlashAttribute("flashOk", false);
        } catch (Exception ex) {
            log.warn("Credential 등록 실패 — name={}: {}", name, ex.getMessage());
            flash.addFlashAttribute("flashMsg", "[FAIL] 등록 실패: " + ex.getMessage());
            flash.addFlashAttribute("flashOk", false);
        }
        return "redirect:/admin/credentials";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable("id") String id, Model model, RedirectAttributes flash) {
        Credential c = repository.findById(id);
        if (c == null) {
            flash.addFlashAttribute("flashMsg", "[FAIL] credential을 찾을 수 없음: " + id);
            flash.addFlashAttribute("flashOk", false);
            return "redirect:/admin/credentials";
        }
        Map<String, Object> form = new LinkedHashMap<>();
        Map<String, Object> schema = parseSchemaSafely(c.schemaJson());
        form.put("id", c.id());
        form.put("name", c.name());
        form.put("type", c.type());
        form.put("schemaUsername", str(schema.get("username")));
        form.put("schemaHeader", str(schema.get("header")));
        form.put("schemaBaseUrl", str(schema.get("baseUrl")));
        model.addAttribute("form", form);
        model.addAttribute("isEdit", true);
        model.addAttribute("supportedTypes", CredentialController.SUPPORTED_TYPES);
        model.addAttribute("submitUrl", "/admin/credentials/" + id);
        model.addAttribute("submitLabel", "Save");
        model.addAttribute("title", "Edit Credential: " + c.name());
        model.addAttribute("navAdminCredentials", true);
        return "admin-credential-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable("id") String id,
                         @RequestParam("name") String name,
                         @RequestParam("type") String type,
                         @RequestParam(value = "value", required = false) String value,
                         @RequestParam(value = "schemaUsername", required = false) String schemaUsername,
                         @RequestParam(value = "schemaHeader", required = false) String schemaHeader,
                         @RequestParam(value = "schemaBaseUrl", required = false) String schemaBaseUrl,
                         Authentication auth,
                         RedirectAttributes flash) {
        try {
            if (!CredentialController.SUPPORTED_TYPES.contains(type)) {
                throw new IllegalArgumentException("unsupported credential type: " + type);
            }
            Credential existing = repository.findById(id);
            if (existing == null) {
                throw new IllegalStateException("credential not found: " + id);
            }
            // value 비우면 기존 ciphertext 유지, 채우면 rotate
            String valueEnc = (value == null || value.isEmpty())
                    ? existing.valueEnc()
                    : crypto.encrypt(value);
            String schemaJson = buildSchemaJson(type, schemaUsername, schemaHeader, schemaBaseUrl);
            Credential updated = new Credential(
                    id, name, type, valueEnc, schemaJson,
                    existing.delFl(),
                    existing.regDt(), existing.regId(),
                    LocalDateTime.now(), auth != null ? auth.getName() : "admin");
            repository.update(updated);
            log.info("Credential 갱신: name={}, type={}, id={}, rotated={}", name, type, id,
                    !(value == null || value.isEmpty()));
            flash.addFlashAttribute("flashMsg", "[OK] '" + name + "' 갱신 완료"
                    + (value == null || value.isEmpty() ? " (value 유지)" : " (value rotate)"));
            flash.addFlashAttribute("flashOk", true);
        } catch (DuplicateKeyException ex) {
            flash.addFlashAttribute("flashMsg", "[FAIL] 이름 '" + name + "' 충돌");
            flash.addFlashAttribute("flashOk", false);
        } catch (Exception ex) {
            log.warn("Credential 갱신 실패 — id={}: {}", id, ex.getMessage());
            flash.addFlashAttribute("flashMsg", "[FAIL] 갱신 실패: " + ex.getMessage());
            flash.addFlashAttribute("flashOk", false);
        }
        return "redirect:/admin/credentials";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") String id,
                         Authentication auth,
                         RedirectAttributes flash) {
        try {
            Credential c = repository.findById(id);
            if (c == null) {
                throw new IllegalStateException("credential not found: " + id);
            }
            repository.softDelete(id, auth != null ? auth.getName() : "admin");
            log.info("Credential 삭제: name={}, id={}", c.name(), id);
            flash.addFlashAttribute("flashMsg", "[OK] '" + c.name() + "' 삭제 (soft delete)");
            flash.addFlashAttribute("flashOk", true);
        } catch (Exception ex) {
            log.warn("Credential 삭제 실패 — id={}: {}", id, ex.getMessage());
            flash.addFlashAttribute("flashMsg", "[FAIL] 삭제 실패: " + ex.getMessage());
            flash.addFlashAttribute("flashOk", false);
        }
        return "redirect:/admin/credentials";
    }

    /** type별 schema 키만 빌드, blank는 제외. 결과 빈 객체면 null 반환 (DB에 NULL 저장). */
    private String buildSchemaJson(String type, String username, String header, String baseUrl) {
        List<String> keys = SCHEMA_KEYS_BY_TYPE.getOrDefault(type, List.of());
        Map<String, Object> schema = new LinkedHashMap<>();
        if (keys.contains("username") && username != null && !username.isBlank()) {
            schema.put("username", username.trim());
        }
        if (keys.contains("header") && header != null && !header.isBlank()) {
            schema.put("header", header.trim());
        }
        if (keys.contains("baseUrl") && baseUrl != null && !baseUrl.isBlank()) {
            schema.put("baseUrl", baseUrl.trim());
        }
        return schema.isEmpty() ? null : jsonUtil.toJson(schema);
    }

    private Map<String, Object> parseSchemaSafely(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = jsonUtil.fromJson(schemaJson, Map.class);
            return parsed != null ? parsed : Map.of();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
