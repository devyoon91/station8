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
 * #151 — Builder 키보드 단축키 + cheatsheet 모달 렌더 검증.
 *
 * <p>실제 keydown 동작은 클라이언트 사이드라 통합 테스트 범위 밖 — DOM/JS 훅이
 * 응답에 포함됐는지만 확인.</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class BuilderShortcutsRenderTest {

    @Autowired MockMvc mockMvc;

    @Test
    void builder_rendersShortcutsModalAndHandlers() throws Exception {
        mockMvc.perform(get("/line/builder"))
                .andExpect(status().isOk())
                // cheatsheet 모달 DOM
                .andExpect(content().string(containsString("id=\"shortcuts-modal\"")))
                .andExpect(content().string(containsString("id=\"shortcuts-backdrop\"")))
                .andExpect(content().string(containsString("Keyboard shortcuts")))
                // open/close API
                .andExpect(content().string(containsString("openShortcutsModal")))
                .andExpect(content().string(containsString("closeShortcutsModal")))
                // 단축키 항목 cheatsheet 표기
                .andExpect(content().string(containsString("Backspace")))
                .andExpect(content().string(containsString("Stations 탭 + 검색 input 포커스")))
                .andExpect(content().string(containsString("Connect 모드 취소")))
                // 핵심 핸들러 키워드
                .andExpect(content().string(containsString("shouldIgnoreShortcut")))
                .andExpect(content().string(containsString("isAnyModalOpen")))
                .andExpect(content().string(containsString("selectedNodeId")))
                // input/textarea 포커스 가드
                .andExpect(content().string(containsString("INPUT")))
                .andExpect(content().string(containsString("TEXTAREA")))
                // Delete / Esc / F / ? / Ctrl+S 핸들러 분기
                .andExpect(content().string(containsString("e.key === 'Delete'")))
                .andExpect(content().string(containsString("e.key === 'Backspace'")))
                .andExpect(content().string(containsString("e.key === 'Escape'")))
                .andExpect(content().string(containsString("e.key === '?'")))
                .andExpect(content().string(containsString("e.ctrlKey || e.metaKey")))
                // 토글버튼 노출
                .andExpect(content().string(containsString("Keyboard shortcuts (?)")))
                // ctx-menu Delete 항목에 단축키 hint
                .andExpect(content().string(containsString("shortcut: 'Del'")))
                .andExpect(content().string(containsString("swe-shortcut-hint")));
    }
}
