package com.station8.app.definition;

import com.station8.app.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #136 — Builder 우클릭 컨텍스트 메뉴 렌더링 검증.
 *
 * <p>Mustache가 새 마크업을 깨지 않고 렌더하고 핵심 JS 훅이 응답에 포함됐는지 확인.
 * 실제 우클릭 동작/키보드 nav는 클라이언트 사이드라 통합 테스트 범위 밖.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class ContextMenuRenderTest {

    @Autowired MockMvc mockMvc;

    @Test
    void builder_rendersContextMenuDom() throws Exception {
        // mustache 응답: DOM + 외부 ctx-menu.js 참조
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                // 떠다니는 메뉴 DOM + ARIA
                .andExpect(content().string(containsString("id=\"ctx-menu\"")))
                .andExpect(content().string(containsString("class=\"swe-ctx-menu\"")))
                .andExpect(content().string(containsString("role=\"menu\"")))
                // #181 PR-4 — JS 로직은 외부 모듈
                .andExpect(content().string(containsString("/js/builder/ctx-menu.js")))
                .andExpect(content().string(containsString("/js/builder/edge-condition-modal.js")));
    }

    /**
     * #181 PR-4 — ctx-menu.js: show/close + 노드 액션 4종 + 키보드 nav 검증.
     */
    @Test
    void ctxMenuJs_includesShowCloseAndNodeActions() throws Exception {
        mockMvc.perform(get("/js/builder/ctx-menu.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("showCtxMenu(")))
                .andExpect(content().string(containsString("closeCtxMenu()")))
                // 노드 액션 4개 (D1=b)
                .andExpect(content().string(containsString("Edit params + bindings")))
                .andExpect(content().string(containsString("Connect from this node")))
                .andExpect(content().string(containsString("Disconnect all edges")))
                .andExpect(content().string(containsString("Delete node")))
                // 키보드 nav (D4=a)
                .andExpect(content().string(containsString("ArrowDown")))
                .andExpect(content().string(containsString("ArrowUp")))
                .andExpect(content().string(containsString("Escape")));
    }

    /**
     * #181 PR-4 — edge-condition-modal.js: 엣지 액션 + parseConnectionFromElement.
     */
    @Test
    void edgeConditionModalJs_includesEdgeActions() throws Exception {
        mockMvc.perform(get("/js/builder/edge-condition-modal.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("disconnectEdge")))
                .andExpect(content().string(containsString("disconnectAllEdges")))
                .andExpect(content().string(containsString("parseConnectionFromElement")));
    }
}
