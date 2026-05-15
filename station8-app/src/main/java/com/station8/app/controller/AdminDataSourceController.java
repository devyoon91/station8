package com.station8.app.controller;

import com.station8.engine.datasource.DataSourceInfo;
import com.station8.engine.datasource.DataSourceRegistry;
import com.station8.engine.entity.DataSourceDefinition;
import com.station8.engine.repository.DataSourceDefinitionRepository;
import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 어드민 — DataSource 진단 + 동적 등록/수정/삭제 (#108 + #110).
 *
 * <p>등록 출처별 권한:</p>
 * <ul>
 *   <li>PRIMARY — Test connection만</li>
 *   <li>STATIC (application.properties) — Test connection만 (UI 수정 불가)</li>
 *   <li>DYNAMIC (U_LINE_DATASOURCE) — Test / Edit / Toggle / Delete</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin/datasources")
public class AdminDataSourceController {

    private static final Logger log = LoggerFactory.getLogger(AdminDataSourceController.class);

    private final DataSourceRegistry registry;
    private final DataSourceDefinitionRepository repository;
    private final JsonUtil jsonUtil;

    public AdminDataSourceController(DataSourceRegistry registry,
                                     DataSourceDefinitionRepository repository,
                                     JsonUtil jsonUtil) {
        this.registry = registry;
        this.repository = repository;
        this.jsonUtil = jsonUtil;
    }

    @GetMapping
    public String list(Model model) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DataSourceInfo info : registry.snapshot()) {
            DataSourceRegistry.Source source = registry.sourceOf(info.name());
            DataSourceDefinition def = source == DataSourceRegistry.Source.DYNAMIC
                    ? repository.findByName(info.name()) : null;
            Map<String, Object> row = new HashMap<>();
            row.put("name", info.name());
            row.put("url", info.url());
            row.put("username", info.username());
            row.put("dialect", info.dialect());
            row.put("healthy", info.healthy());
            row.put("activeConn", info.activeConn());
            row.put("idleConn", info.idleConn());
            row.put("totalConn", info.totalConn());
            row.put("errorMsg", info.errorMsg());
            row.put("hasPoolStats", info.activeConn() >= 0);
            row.put("source", source.name());
            row.put("isPrimary", source == DataSourceRegistry.Source.PRIMARY);
            row.put("isStatic", source == DataSourceRegistry.Source.STATIC);
            row.put("isDynamic", source == DataSourceRegistry.Source.DYNAMIC);
            row.put("enabled", def == null || "Y".equals(def.enabledFl()));
            rows.add(row);
        }
        // DEL_FL='N' AND ENABLED_FL='N' 항목도 표시 (registry에 없지만 운영자가 다시 켤 수 있어야 함)
        for (DataSourceDefinition def : repository.findAll()) {
            if ("Y".equals(def.enabledFl())) continue; // 위 루프에서 처리됨
            // disabled 항목은 registry에 없음
            Map<String, Object> row = new HashMap<>();
            row.put("name", def.name());
            row.put("url", def.jdbcUrl());
            row.put("username", def.username());
            row.put("dialect", def.dialect() != null ? def.dialect() : "(auto)");
            row.put("healthy", false);
            row.put("activeConn", -1);
            row.put("idleConn", -1);
            row.put("totalConn", -1);
            row.put("errorMsg", "Disabled");
            row.put("hasPoolStats", false);
            row.put("source", "DYNAMIC");
            row.put("isPrimary", false);
            row.put("isStatic", false);
            row.put("isDynamic", true);
            row.put("enabled", false);
            rows.add(row);
        }
        model.addAttribute("datasources", rows);
        model.addAttribute("totalCount", rows.size());
        long healthyCount = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("healthy"))).count();
        model.addAttribute("healthyCount", healthyCount);
        model.addAttribute("unhealthyCount", rows.size() - healthyCount);
        model.addAttribute("navAdminDataSources", true);
        return "admin-datasources";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "");
        form.put("jdbcUrl", "");
        form.put("username", "");
        form.put("password", "");
        form.put("driverClass", "");
        form.put("dialect", "");
        form.put("hikariOptionsJson", "");
        form.put("enabled", true);
        model.addAttribute("form", form);
        model.addAttribute("isEdit", false);
        model.addAttribute("submitUrl", "/admin/datasources");
        model.addAttribute("submitLabel", "Create");
        model.addAttribute("title", "New DataSource");
        model.addAttribute("navAdminDataSources", true);
        return "admin-datasource-form";
    }

    @PostMapping
    public String create(@RequestParam("name") String name,
                         @RequestParam("jdbcUrl") String jdbcUrl,
                         @RequestParam(value = "username", required = false) String username,
                         @RequestParam(value = "password", required = false) String password,
                         @RequestParam(value = "driverClass", required = false) String driverClass,
                         @RequestParam(value = "dialect", required = false) String dialect,
                         @RequestParam(value = "hikariOptionsJson", required = false) String hikariOptionsJson,
                         @RequestParam(value = "enabled", required = false) String enabledRaw,
                         RedirectAttributes flash) {
        boolean enabled = enabledRaw != null;
        try {
            // 정적/PRIMARY 충돌 체크
            DataSourceRegistry.Source existing = registry.sourceOf(name);
            if (existing != DataSourceRegistry.Source.NONE) {
                throw new IllegalStateException("이름 '" + name + "'은 이미 " + existing + "으로 등록되어 있습니다");
            }
            if (repository.findByName(name) != null) {
                throw new IllegalStateException("이름 '" + name + "'은 이미 DB에 존재합니다 (Soft delete 후에도 unique 제약)");
            }
            Map<String, String> hikari = parseHikari(hikariOptionsJson);

            DataSourceDefinition def = new DataSourceDefinition(
                    UUID.randomUUID().toString(),
                    name, jdbcUrl, nullIfBlank(username), nullIfBlank(password),
                    nullIfBlank(driverClass), nullIfBlank(dialect),
                    hikariOptionsJson != null && !hikariOptionsJson.isBlank() ? hikariOptionsJson.trim() : null,
                    enabled ? "Y" : "N", "N",
                    LocalDateTime.now(), "admin", null, null);
            repository.insert(def);
            log.info("DataSource definition saved: {} (id={})", name, def.id());

            // enabled면 즉시 registry에 등록 (D5 — 폼 등록 허용 + 헬스 다운 표시 + Test connection 기능)
            if (enabled) {
                DataSourceRegistry.DynamicSpec spec = new DataSourceRegistry.DynamicSpec(
                        name, jdbcUrl, nullIfBlank(username), nullIfBlank(password),
                        nullIfBlank(driverClass), nullIfBlank(dialect), hikari);
                DataSourceRegistry.TestResult test = registry.register(spec);
                if (test.success()) {
                    flash.addFlashAttribute("testMsg",
                            "[OK] '" + name + "' 등록 완료 — health check OK (" + test.latencyMs() + " ms)");
                    flash.addFlashAttribute("testOk", true);
                } else {
                    flash.addFlashAttribute("testMsg",
                            "[WARN] '" + name + "' 등록 완료 (DB 저장 + 풀 빌드) — 단, health check 실패: " + test.errorMsg());
                    flash.addFlashAttribute("testOk", false);
                }
            } else {
                flash.addFlashAttribute("testMsg", "[INFO] '" + name + "' 비활성화 상태로 등록 (풀 미생성)");
                flash.addFlashAttribute("testOk", true);
            }
        } catch (Exception ex) {
            log.warn("DataSource create failed for '{}': {}", name, ex.getMessage());
            flash.addFlashAttribute("testMsg", "[FAIL] 등록 실패: " + ex.getMessage());
            flash.addFlashAttribute("testOk", false);
        }
        return "redirect:/admin/datasources";
    }

    @GetMapping("/{name}/edit")
    public String editForm(@PathVariable("name") String name, Model model, RedirectAttributes flash) {
        DataSourceDefinition def = repository.findByName(name);
        if (def == null) {
            flash.addFlashAttribute("testMsg", "[FAIL] DYNAMIC 등록 정보를 찾을 수 없음: " + name);
            flash.addFlashAttribute("testOk", false);
            return "redirect:/admin/datasources";
        }
        Map<String, Object> form = new HashMap<>();
        form.put("name", def.name());
        form.put("jdbcUrl", def.jdbcUrl());
        form.put("username", def.username() == null ? "" : def.username());
        form.put("password", ""); // 빈 칸 = 변경 안 함
        form.put("driverClass", def.driverClass() == null ? "" : def.driverClass());
        form.put("dialect", def.dialect() == null ? "" : def.dialect());
        form.put("hikariOptionsJson", def.hikariOptions() == null ? "" : def.hikariOptions());
        form.put("enabled", "Y".equals(def.enabledFl()));
        model.addAttribute("form", form);
        model.addAttribute("isEdit", true);
        model.addAttribute("submitUrl", "/admin/datasources/" + def.name());
        model.addAttribute("submitLabel", "Save");
        model.addAttribute("title", "Edit DataSource: " + def.name());
        model.addAttribute("navAdminDataSources", true);
        return "admin-datasource-form";
    }

    @PostMapping("/{name}")
    public String update(@PathVariable("name") String name,
                         @RequestParam("jdbcUrl") String jdbcUrl,
                         @RequestParam(value = "username", required = false) String username,
                         @RequestParam(value = "password", required = false) String password,
                         @RequestParam(value = "driverClass", required = false) String driverClass,
                         @RequestParam(value = "dialect", required = false) String dialect,
                         @RequestParam(value = "hikariOptionsJson", required = false) String hikariOptionsJson,
                         @RequestParam(value = "enabled", required = false) String enabledRaw,
                         RedirectAttributes flash) {
        boolean enabled = enabledRaw != null;
        try {
            DataSourceDefinition existing = repository.findByName(name);
            if (existing == null) {
                throw new IllegalStateException("DYNAMIC 등록 정보 없음: " + name);
            }
            String storedPwd = (password == null || password.isEmpty()) ? existing.password() : password;
            DataSourceDefinition updated = new DataSourceDefinition(
                    existing.id(), existing.name(), jdbcUrl,
                    nullIfBlank(username), password,  // password는 update 메서드에서 keepPasswordIfBlank 처리
                    nullIfBlank(driverClass), nullIfBlank(dialect),
                    hikariOptionsJson != null && !hikariOptionsJson.isBlank() ? hikariOptionsJson.trim() : null,
                    enabled ? "Y" : "N",
                    existing.delFl(),
                    existing.regDt(), existing.regId(),
                    LocalDateTime.now(), "admin");
            repository.update(updated, /*keepPasswordIfBlank*/ true);

            // 풀 swap (enabled→enabled), register (disabled→enabled), unregister (enabled→disabled)
            DataSourceRegistry.Source source = registry.sourceOf(name);
            Map<String, String> hikari = parseHikari(hikariOptionsJson);
            DataSourceRegistry.DynamicSpec spec = new DataSourceRegistry.DynamicSpec(
                    name, jdbcUrl, nullIfBlank(username), storedPwd,
                    nullIfBlank(driverClass), nullIfBlank(dialect), hikari);

            if (enabled) {
                DataSourceRegistry.TestResult test;
                if (source == DataSourceRegistry.Source.DYNAMIC) {
                    test = registry.swap(spec);
                } else {
                    test = registry.register(spec);
                }
                if (test.success()) {
                    flash.addFlashAttribute("testMsg",
                            "[OK] '" + name + "' 갱신 완료 — health check OK (" + test.latencyMs() + " ms)");
                    flash.addFlashAttribute("testOk", true);
                } else {
                    flash.addFlashAttribute("testMsg",
                            "[WARN] '" + name + "' 갱신 완료 — health check 실패: " + test.errorMsg());
                    flash.addFlashAttribute("testOk", false);
                }
            } else {
                if (source == DataSourceRegistry.Source.DYNAMIC) {
                    registry.unregister(name);
                }
                flash.addFlashAttribute("testMsg", "[INFO] '" + name + "' 비활성화 (풀 제거)");
                flash.addFlashAttribute("testOk", true);
            }
        } catch (Exception ex) {
            log.warn("DataSource update failed for '{}': {}", name, ex.getMessage());
            flash.addFlashAttribute("testMsg", "[FAIL] 갱신 실패: " + ex.getMessage());
            flash.addFlashAttribute("testOk", false);
        }
        return "redirect:/admin/datasources";
    }

    @PostMapping("/{name}/test")
    public String testConnection(@PathVariable("name") String name, RedirectAttributes flash) {
        DataSourceRegistry.TestResult r = registry.testConnection(name);
        if (r.success()) {
            flash.addFlashAttribute("testMsg",
                    "[OK] " + name + " — SELECT 1 succeeded in " + r.latencyMs() + " ms");
            flash.addFlashAttribute("testOk", true);
        } else {
            flash.addFlashAttribute("testMsg",
                    "[FAIL] " + name + " — " + r.errorMsg() + " (" + r.latencyMs() + " ms)");
            flash.addFlashAttribute("testOk", false);
        }
        return "redirect:/admin/datasources";
    }

    @PostMapping("/{name}/delete")
    public String delete(@PathVariable("name") String name, RedirectAttributes flash) {
        try {
            DataSourceDefinition def = repository.findByName(name);
            if (def == null) {
                throw new IllegalStateException("DYNAMIC 등록 정보 없음: " + name);
            }
            if (registry.sourceOf(name) == DataSourceRegistry.Source.DYNAMIC) {
                registry.unregister(name);
            }
            repository.softDelete(def.id());
            flash.addFlashAttribute("testMsg", "[OK] '" + name + "' 삭제 완료 (soft delete + 풀 close)");
            flash.addFlashAttribute("testOk", true);
        } catch (Exception ex) {
            log.warn("DataSource delete failed for '{}': {}", name, ex.getMessage());
            flash.addFlashAttribute("testMsg", "[FAIL] 삭제 실패: " + ex.getMessage());
            flash.addFlashAttribute("testOk", false);
        }
        return "redirect:/admin/datasources";
    }

    @PostMapping("/{name}/toggle-enabled")
    public String toggleEnabled(@PathVariable("name") String name, RedirectAttributes flash) {
        try {
            DataSourceDefinition def = repository.findByName(name);
            if (def == null) {
                throw new IllegalStateException("DYNAMIC 등록 정보 없음: " + name);
            }
            boolean newEnabled = !"Y".equals(def.enabledFl());
            repository.setEnabled(def.id(), newEnabled);

            DataSourceRegistry.Source source = registry.sourceOf(name);
            if (newEnabled && source == DataSourceRegistry.Source.NONE) {
                Map<String, String> hikari = jsonUtil.fromJsonToStringMap(def.hikariOptions());
                DataSourceRegistry.DynamicSpec spec = new DataSourceRegistry.DynamicSpec(
                        def.name(), def.jdbcUrl(), def.username(), def.password(),
                        def.driverClass(), def.dialect(), hikari);
                DataSourceRegistry.TestResult result = registry.register(spec);
                flash.addFlashAttribute("testMsg",
                        result.success()
                                ? "[OK] '" + name + "' 활성화 + 풀 등록 (" + result.latencyMs() + " ms)"
                                : "[WARN] '" + name + "' 활성화 — health check 실패: " + result.errorMsg());
                flash.addFlashAttribute("testOk", result.success());
            } else if (!newEnabled && source == DataSourceRegistry.Source.DYNAMIC) {
                registry.unregister(name);
                flash.addFlashAttribute("testMsg", "[OK] '" + name + "' 비활성화 + 풀 close");
                flash.addFlashAttribute("testOk", true);
            } else {
                flash.addFlashAttribute("testMsg", "[OK] '" + name + "' enabled flag → " + (newEnabled ? "Y" : "N"));
                flash.addFlashAttribute("testOk", true);
            }
        } catch (Exception ex) {
            log.warn("DataSource toggle failed for '{}': {}", name, ex.getMessage());
            flash.addFlashAttribute("testMsg", "[FAIL] 토글 실패: " + ex.getMessage());
            flash.addFlashAttribute("testOk", false);
        }
        return "redirect:/admin/datasources";
    }

    // ---- helpers ----

    private Map<String, String> parseHikari(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        return jsonUtil.fromJsonToStringMap(json.trim());
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
