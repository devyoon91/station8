package com.station8.app.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #176 — PaginationModel.toggleSortHref 단위 테스트.
 *
 * <p>이전 LineMonitoringController에 private으로 묻혀 있던 빌더를 옮긴 뒤,
 * 동작이 동일한지 + 새 단위 테스트로 회귀 가드 마련.</p>
 */
class PaginationModelTest {

    @Test
    void toggleSortHref_sameColumn_togglesAscDesc() {
        // 현재 START_DT DESC인데 START_DT 클릭 → ASC
        String href = PaginationModel.toggleSortHref(
                "/line/dashboard", "START_DT", "START_DT", "DESC", Map.of());
        assertThat(href).isEqualTo("/line/dashboard?sortBy=START_DT&sortDir=ASC");

        // 현재 START_DT ASC인데 START_DT 클릭 → DESC
        String href2 = PaginationModel.toggleSortHref(
                "/line/dashboard", "START_DT", "START_DT", "ASC", Map.of());
        assertThat(href2).isEqualTo("/line/dashboard?sortBy=START_DT&sortDir=DESC");
    }

    @Test
    void toggleSortHref_differentColumn_defaultsDesc() {
        // 현재 START_DT DESC인데 END_DT 클릭 → DESC (관례)
        String href = PaginationModel.toggleSortHref(
                "/line/dashboard", "END_DT", "START_DT", "DESC", Map.of());
        assertThat(href).isEqualTo("/line/dashboard?sortBy=END_DT&sortDir=DESC");
    }

    @Test
    void toggleSortHref_noCurrentSort_defaultsDesc() {
        String href = PaginationModel.toggleSortHref(
                "/line/dashboard", "REG_DT", null, null, Map.of());
        assertThat(href).isEqualTo("/line/dashboard?sortBy=REG_DT&sortDir=DESC");
    }

    @Test
    void toggleSortHref_appendsPreserveQuery() {
        Map<String, String> preserve = new LinkedHashMap<>();
        preserve.put("workflowName", "MyFlow");
        preserve.put("statusSt", "RUNNING");
        String href = PaginationModel.toggleSortHref(
                "/line/dashboard", "START_DT", null, null, preserve);
        assertThat(href).isEqualTo(
                "/line/dashboard?sortBy=START_DT&sortDir=DESC&workflowName=MyFlow&statusSt=RUNNING");
    }

    @Test
    void toggleSortHref_skipsBlankAndNullPreserveValues() {
        Map<String, String> preserve = new LinkedHashMap<>();
        preserve.put("workflowName", "");
        preserve.put("instanceId", null);
        preserve.put("startDtFrom", "2026-05-01");
        String href = PaginationModel.toggleSortHref(
                "/line/dashboard", "START_DT", null, null, preserve);
        assertThat(href).isEqualTo("/line/dashboard?sortBy=START_DT&sortDir=DESC&startDtFrom=2026-05-01");
    }

    @Test
    void toggleSortHref_urlEncodesSpecialChars() {
        // workflowName에 space + & 등 — URL 인코딩 필요
        Map<String, String> preserve = new LinkedHashMap<>();
        preserve.put("workflowName", "My Flow & test");
        String href = PaginationModel.toggleSortHref(
                "/line/dashboard", "REG_DT", null, null, preserve);
        assertThat(href).contains("workflowName=My+Flow+%26+test");
    }

    @Test
    void toggleSortHref_nullPreserveQuery_works() {
        String href = PaginationModel.toggleSortHref(
                "/line/dlq", "FAILED_AT_DT", null, null, null);
        assertThat(href).isEqualTo("/line/dlq?sortBy=FAILED_AT_DT&sortDir=DESC");
    }
}
