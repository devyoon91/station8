package com.station8.engine.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionCompatibilityTest {

    @Test
    void unspecified_whenPluginHeaderMissing() {
        VersionCompatibility.Check c = VersionCompatibility.check("0.1.0", null);
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.UNSPECIFIED);
        assertThat(c.message()).contains("미선언");
    }

    @Test
    void unspecified_whenHostIs00x_bestEffortPeriod() {
        VersionCompatibility.Check c = VersionCompatibility.check("0.0.1-SNAPSHOT", "0.1.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.UNSPECIFIED);
        assertThat(c.message()).contains("0.0.x");
    }

    @Test
    void unspecified_whenParseFails() {
        VersionCompatibility.Check c = VersionCompatibility.check("not-a-version", "0.1.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.UNSPECIFIED);
        assertThat(c.message()).contains("파싱 실패");
    }

    @Test
    void compatible_whenPluginEqualsHost() {
        VersionCompatibility.Check c = VersionCompatibility.check("0.1.0", "0.1.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.COMPATIBLE);
    }

    @Test
    void compatible_whenPluginLowerMinor_sameMajor() {
        VersionCompatibility.Check c = VersionCompatibility.check("0.3.0", "0.1.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.COMPATIBLE);
    }

    @Test
    void rejected_whenPluginHigherMinor_sameMajor() {
        VersionCompatibility.Check c = VersionCompatibility.check("0.1.0", "0.2.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.REJECTED);
        assertThat(c.message()).contains("호스트 업그레이드");
    }

    @Test
    void rejected_whenMajorDiffers() {
        VersionCompatibility.Check c = VersionCompatibility.check("1.0.0", "2.0.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.REJECTED);
        assertThat(c.message()).contains("major 버전 불일치");
    }

    @Test
    void rejected_pluginMajorLower() {
        // 1.x host, 0.x plugin → still rejected (major mismatch)
        VersionCompatibility.Check c = VersionCompatibility.check("1.5.0", "0.9.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.REJECTED);
    }

    @Test
    void compatible_strips_snapshot_suffix() {
        VersionCompatibility.Check c = VersionCompatibility.check("0.2.0", "0.1.0-SNAPSHOT");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.COMPATIBLE);
    }

    @Test
    void compatible_strips_build_metadata() {
        VersionCompatibility.Check c = VersionCompatibility.check("0.2.0+abc123", "0.2.0+def456");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.COMPATIBLE);
    }

    @Test
    void parse_handles_two_part_version_as_patch_zero() {
        VersionCompatibility.Sem s = VersionCompatibility.Sem.parse("0.1");
        assertThat(s).isNotNull();
        assertThat(s.major()).isEqualTo(0);
        assertThat(s.minor()).isEqualTo(1);
        assertThat(s.patch()).isEqualTo(0);
    }

    @Test
    void parse_returnsNullForGarbage() {
        assertThat(VersionCompatibility.Sem.parse(null)).isNull();
        assertThat(VersionCompatibility.Sem.parse("")).isNull();
        assertThat(VersionCompatibility.Sem.parse("1")).isNull();
        assertThat(VersionCompatibility.Sem.parse("v1.0.0")).isNull();
    }
}
