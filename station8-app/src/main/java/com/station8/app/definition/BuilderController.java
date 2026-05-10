package com.station8.app.definition;

import com.station8.engine.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * DAG Builder UI (Drawflow 기반).
 *
 * <p>두 가지 모드 (#99):</p>
 * <ul>
 *   <li>{@code GET /line/builder} — 신규 생성 모드 (빈 캔버스)</li>
 *   <li>{@code GET /line/builder?id={definitionId}} — 편집 모드, 기존 정의를 캔버스에 프리로드</li>
 * </ul>
 *
 * <p>저장 시 신규는 {@code POST /api/line/definitions}, 편집은 {@code PUT /api/line/definitions/{id}} 호출.
 * 서비스의 {@code replaceDefinition}은 같은 ID/같은 버전 유지로 역/엣지를 통째로 교체한다 — 새 버전 분기는
 * 비범위(향후 별도 UX). 진행 중 인스턴스가 있을 수 있으니 운영자 책임으로 주의.</p>
 */
@Controller
public class BuilderController {

    private static final Logger log = LoggerFactory.getLogger(BuilderController.class);

    private final LineDefinitionService service;
    private final JsonUtil jsonUtil;

    public BuilderController(LineDefinitionService service, JsonUtil jsonUtil) {
        this.service = service;
        this.jsonUtil = jsonUtil;
    }

    @GetMapping("/line/builder")
    public String builder(@RequestParam(value = "id", required = false) String definitionId,
                          Model model) {
        model.addAttribute("navBuilder", true);

        if (definitionId == null || definitionId.isBlank()) {
            model.addAttribute("editMode", false);
            return "builder";
        }

        // 편집 모드 — 기존 정의를 JSON으로 직렬화해 페이지에 임베드
        try {
            DagDefinitionResponse existing = service.getDefinition(definitionId);
            model.addAttribute("editMode", true);
            model.addAttribute("definitionId", existing.definitionId());
            model.addAttribute("definitionNm", existing.definitionNm());
            model.addAttribute("description", existing.description() != null ? existing.description() : "");
            model.addAttribute("versionNo", existing.versionNo());
            model.addAttribute("existingDefinitionJson", jsonUtil.toJson(existing));

            // #138 — SLA 폼 미리채움
            model.addAttribute("slaSeconds", existing.slaSeconds());
            model.addAttribute("slaActionAlert", "ALERT_ONLY".equals(existing.slaAction()));
            model.addAttribute("slaActionTerminate", "AUTO_TERMINATE".equals(existing.slaAction()));
            // #141 — 동시 실행 정책 폼 미리채움
            String concurrency = existing.concurrencyPolicy() == null ? "" : existing.concurrencyPolicy();
            model.addAttribute("concurrencyConcurrent", concurrency.isEmpty() || "CONCURRENT".equals(concurrency));
            model.addAttribute("concurrencySkip", "SKIP_IF_RUNNING".equals(concurrency));
            // #142 — 태그 폼 미리채움 (쉼표 join)
            java.util.List<String> tags = existing.tags() == null ? java.util.List.of() : existing.tags();
            model.addAttribute("tagsCsv", String.join(", ", tags));
            // 셋 중 하나라도 설정됐으면 details open
            model.addAttribute("hasLineSettings", existing.slaSeconds() != null
                    || (existing.slaAction() != null && !existing.slaAction().isBlank())
                    || "SKIP_IF_RUNNING".equals(concurrency)
                    || !tags.isEmpty());
        } catch (IllegalArgumentException ex) {
            log.warn("Builder edit — definition not found: {} ({})", definitionId, ex.getMessage());
            model.addAttribute("editMode", false);
            model.addAttribute("loadError", "정의를 찾을 수 없습니다: " + definitionId);
        }
        return "builder";
    }
}
