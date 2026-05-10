package com.station8.engine.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #137 — {@link InstanceQueryFilter} 정규화 / 화이트리스트 검증.
 */
class InstanceQueryFilterTest {

    @Test
    void defaults_areRegDtDesc() {
        InstanceQueryFilter f = InstanceQueryFilter.empty();
        assertThat(f.sortBy()).isEqualTo("REG_DT");
        assertThat(f.sortDir()).isEqualTo("DESC");
    }

    @Test
    void sortBy_isUppercased() {
        InstanceQueryFilter f = new InstanceQueryFilter(null, null, null, null, null, "start_dt", "asc");
        assertThat(f.sortBy()).isEqualTo("START_DT");
        assertThat(f.sortDir()).isEqualTo("ASC");
    }

    @Test
    void sortBy_unknown_fallsBackToDefault() {
        // SQL 인젝션 방지 — 화이트리스트 외 값은 무시
        InstanceQueryFilter f = new InstanceQueryFilter(null, null, null, null, null, "; DROP TABLE", "wat");
        assertThat(f.sortBy()).isEqualTo("REG_DT");
        assertThat(f.sortDir()).isEqualTo("DESC");
    }

    @Test
    void ofLegacy_singleStatus_wrapsInList() {
        InstanceQueryFilter f = InstanceQueryFilter.ofLegacy("foo", "RUNNING", "abc");
        assertThat(f.workflowName()).isEqualTo("foo");
        assertThat(f.statusList()).containsExactly("RUNNING");
        assertThat(f.instanceId()).isEqualTo("abc");
        assertThat(f.startDtFrom()).isNull();
        assertThat(f.startDtTo()).isNull();
    }

    @Test
    void ofLegacy_blankStatus_isNull() {
        InstanceQueryFilter f = InstanceQueryFilter.ofLegacy(null, "  ", null);
        assertThat(f.statusList()).isNull();
    }
}
