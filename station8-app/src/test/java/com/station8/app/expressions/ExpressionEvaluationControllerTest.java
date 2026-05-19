package com.station8.app.expressions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.station8.app.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #306 — POST /api/line/expressions/_evaluate 회귀 가드.
 *
 * <p>핵심 검증:</p>
 * <ul>
 *   <li>사용자 dummy {@code $prev/$ctx} 가 표현식에 그대로 노출</li>
 *   <li>type 보존 (number / object / array)</li>
 *   <li>평가 실패는 ok=false + error 메시지 (HTTP는 200)</li>
 *   <li>인증 필수 — 미인증은 403</li>
 *   <li>sandbox — Java reflection escape 차단</li>
 * </ul>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class ExpressionEvaluationControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private String json(Map<String, Object> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    // ============ basic evaluation ============

    @Test
    @WithMockUser
    void staticArithmetic_returnsNumber() throws Exception {
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of("expression", "{{ 1 + 2 }}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value(3))
                .andExpect(jsonPath("$.resultType").value("number"));
    }

    @Test
    @WithMockUser
    void prevJsonAccess_returnsBoundValue() throws Exception {
        Map<String, Object> prev = new HashMap<>();
        prev.put("json", Map.of("id", 42, "title", "hello"));
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "expression", "{{ $prev.json.title }}",
                                "prev", prev))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value("hello"))
                .andExpect(jsonPath("$.resultType").value("string"));
    }

    @Test
    @WithMockUser
    void ctxRunAccess_returnsNumberAttempt() throws Exception {
        Map<String, Object> ctx = Map.of(
                "input", Map.of("user", "alice"),
                "run", Map.of("id", "inst-1", "attempt", 5),
                "line", Map.of("name", "TestLine"));
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "expression", "{{ $ctx.run.attempt }}",
                                "ctx", ctx))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value(5));
    }

    @Test
    @WithMockUser
    void stringInterpolation_returnsConcatenated() throws Exception {
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "expression", "Hello {{ $ctx.input.user }}!",
                                "ctx", Map.of("input", Map.of("user", "alice"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.result").value("Hello alice!"))
                .andExpect(jsonPath("$.resultType").value("string"));
    }

    @Test
    @WithMockUser
    void objectResult_preservesShape() throws Exception {
        // 단일 표현식이 객체 반환 → resultType=object. JS에선 {a:1}이 block statement로 파싱돼
        // ({a:1}) 처럼 괄호로 감싸야 object literal로 평가됨 — 사용자도 같은 룰 따름.
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "expression", "{{ ({a: 1, b: 'two'}) }}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.resultType").value("object"))
                .andExpect(jsonPath("$.result.a").value(1))
                .andExpect(jsonPath("$.result.b").value("two"));
    }

    @Test
    @WithMockUser
    void arrayMap_returnsArray() throws Exception {
        Map<String, Object> prev = Map.of("json", Map.of("items", List.of(1, 2, 3)));
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "expression", "{{ $prev.json.items.map(x => x * 2) }}",
                                "prev", prev))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.resultType").value("array"))
                .andExpect(jsonPath("$.result[0]").value(2))
                .andExpect(jsonPath("$.result[2]").value(6));
    }

    // ============ failure paths ============

    @Test
    @WithMockUser
    void evaluationError_returnsOkFalseNotHttpError() throws Exception {
        // ReferenceError — ok=false + error message, HTTP는 200
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of("expression", "{{ unknownVar }}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsStringIgnoringCase("expr")));
    }

    @Test
    @WithMockUser
    void missingExpression_returns400() throws Exception {
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of("expression", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("required")));
    }

    @Test
    @WithMockUser
    void javaReflectionEscape_blocked() throws Exception {
        // sandbox — Java reflection 차단 (HostAccess.NONE)
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "expression", "{{ Java.type('java.lang.Runtime') }}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsStringIgnoringCase("expr")));
    }

    // ============ auth ============

    @Test
    void unauthenticated_returns403() throws Exception {
        // @WithMockUser 없음 — Spring Security가 403
        mvc.perform(post("/api/line/expressions/_evaluate")
                        .with(csrf())
                        .contentType("application/json")
                        .content(json(Map.of("expression", "{{ 1 }}"))))
                .andExpect(status().isForbidden());
    }
}
