package com.station8.app.controller;

import com.station8.engine.datasource.DataSourceInfo;
import com.station8.engine.datasource.DataSourceRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 어드민 — 등록된 DataSource 진단/모니터링 페이지.
 *
 * <p>D8 결정에 따라 1차는 진단(목록 / 풀 통계 / 테스트 ping)만 제공.
 * 추가/수정/삭제 폼은 [#110](https://github.com/devyoon91/station8/issues/110) 후속에서.</p>
 */
@Controller
@RequestMapping("/admin/datasources")
public class AdminDataSourceController {

    private final DataSourceRegistry registry;

    public AdminDataSourceController(DataSourceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public String list(Model model) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DataSourceInfo info : registry.snapshot()) {
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
            rows.add(row);
        }
        model.addAttribute("datasources", rows);
        model.addAttribute("totalCount", rows.size());
        long healthyCount = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("healthy"))).count();
        model.addAttribute("healthyCount", healthyCount);
        model.addAttribute("unhealthyCount", rows.size() - healthyCount);

        // 네비 active flag
        model.addAttribute("navAdminDataSources", true);

        return "admin-datasources";
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
}
