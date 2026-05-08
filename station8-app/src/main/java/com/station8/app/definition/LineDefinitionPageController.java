package com.station8.app.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.engine.entity.LineDefinition;
import com.station8.engine.repository.LineDefinitionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 라인 정의 조회용 Mustache 뷰 컨트롤러.
 *
 * <ul>
 *   <li>GET /line/definitions       — 활성 정의 목록</li>
 *   <li>GET /line/definitions/{id}  — 노선도(서브웨이 맵) 미리보기 (#87 M1)</li>
 * </ul>
 *
 * 편집·실행은 {@link LineDefinitionController}(REST)에 위임한다.
 */
@Controller
public class LineDefinitionPageController {

    private final LineDefinitionRepository definitionRepository;
    private final LineDefinitionService definitionService;
    private final ObjectMapper objectMapper;

    public LineDefinitionPageController(LineDefinitionRepository definitionRepository,
                                        LineDefinitionService definitionService,
                                        ObjectMapper objectMapper) {
        this.definitionRepository = definitionRepository;
        this.definitionService = definitionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/line/definitions")
    public String list(Model model) {
        List<LineDefinition> defs = definitionRepository.findAllActiveDefinitions();
        List<Map<String, Object>> rows = defs.stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", d.id());
            m.put("definitionNm", d.definitionNm());
            m.put("description", d.description());
            m.put("versionNo", d.versionNo());
            m.put("regDt", d.regDt());
            return m;
        }).toList();
        model.addAttribute("definitions", rows);
        model.addAttribute("totalCount", rows.size());
        model.addAttribute("navLines", true);
        return "definitions";
    }

    @GetMapping("/line/definitions/{id}")
    public String preview(@PathVariable("id") String id, Model model) {
        DagDefinitionResponse def = definitionService.getDefinition(id);
        model.addAttribute("definitionId", def.definitionId());
        model.addAttribute("definitionNm", def.definitionNm());
        model.addAttribute("description", def.description());
        model.addAttribute("versionNo", def.versionNo());
        model.addAttribute("nodeCount", def.nodes().size());
        model.addAttribute("edgeCount", def.edges().size());
        model.addAttribute("graphJson", toJson(def));
        model.addAttribute("navLines", true);
        return "definition-preview";
    }

    /**
     * SVG 렌더러가 소비할 정의 JSON. Mustache view 안에 인라인 임베드되므로
     * ``<`` ``>`` ``&`` ``'``를 ``</script>`` 깨짐 없이 안전하게 인코딩.
     */
    private String toJson(DagDefinitionResponse def) {
        Map<String, Object> payload = Map.of(
                "definitionId", def.definitionId(),
                "definitionNm", def.definitionNm(),
                "nodes", def.nodes().stream().map(n -> Map.of(
                        "id", n.nodeId(),
                        "name", n.nodeNm() == null ? n.activityNm() : n.nodeNm(),
                        "activity", n.activityNm() == null ? "" : n.activityNm(),
                        "x", n.posX() == null ? 0 : n.posX(),
                        "y", n.posY() == null ? 0 : n.posY()
                )).toList(),
                "edges", def.edges().stream().map(e -> Map.of(
                        "id", e.edgeId(),
                        "from", e.fromNodeId(),
                        "to", e.toNodeId()
                )).toList()
        );
        try {
            // ``<script type="application/json">``에 인라인 임베드되므로 ``</script>`` 깨짐 방지를 위해
            // ``</``를 JSON-안전한 ``<\/``로 치환한다 (RFC 8259상 ``\/``는 허용된 escape).
            return objectMapper.writeValueAsString(payload).replace("</", "<\\/");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("graphJson 직렬화 실패", ex);
        }
    }
}
