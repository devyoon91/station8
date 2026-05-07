package com.bangrang.workflow.app.definition;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * DAG Builder UI (Drawflow 기반).
 * GET /workflow/builder — 좌측 액티비티 팔레트 + 중앙 캔버스 + 우측 속성 패널
 *
 * 사용 흐름:
 *  1. 좌측 ``Activity Catalog`` 항목을 캔버스로 drag → drop
 *  2. 노드 클릭 후 "Connect from this node" → 다음 노드 클릭으로 엣지 연결
 *  3. 우측 패널에서 ``inputParams`` JSON 편집 → "Update params"
 *  4. 상단 "Save" → POST /api/workflow/definitions (검증 통과 시 definitionId 반환)
 */
@Controller
public class BuilderController {

    @GetMapping("/workflow/builder")
    public String builder(Model model) {
        model.addAttribute("navBuilder", true);
        return "builder";
    }
}
