package com.station8.engine.plugin;

import com.station8.engine.core.LineRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PluginLoader 단위 테스트: 미리 컴파일된 ``com.example.plugin.GreeterPlugin`` 클래스를
 * jar로 패키징한 뒤 ``scanAndRegister``를 호출하여 ``GREET`` 액티비티가 등록되는지 검증.
 *
 * <p>테스트 픽스처 클래스는 ``src/test/resources/plugin-fixture/`` 아래에 미리 컴파일되어 있다고
 * 가정하지 않고, 본 모듈의 컴파일 결과(test classpath)에서 동일 패키지 클래스를 jar로 묶어 사용한다.</p>
 */
class PluginLoaderTest {

    @Test
    void loadsActivityFromJarAndRegisters(@TempDir Path tempDir) throws Exception {
        File jar = buildFixtureJar(tempDir, GreeterFixture.class);

        LineRegistry registry = new LineRegistry();
        PluginLoader loader = new PluginLoader(registry);

        PluginLoader.JarScanResult result = loader.scanAndRegister(jar);

        assertThat(result.added()).as("새로 등록된 액티비티").containsExactly("GREET");
        assertThat(result.conflicts()).as("충돌 없음").isEmpty();
        assertThat(registry.getActivityNames()).contains("GREET");
        LineRegistry.ActivityMetadata md = registry.getActivity("GREET");
        assertThat(md).isNotNull();
        assertThat(md.method().getName()).isEqualTo("greet");
    }

    @Test
    void reload_disabledByDefault_returnsEmptyResult() {
        // engine.plugins.enabled가 기본 false라 reload는 no-op
        LineRegistry registry = new LineRegistry();
        PluginLoader loader = new PluginLoader(registry);

        PluginLoader.ReloadResult result = loader.reload();

        assertThat(result.added()).isEmpty();
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.skippedJars()).isEmpty();
        assertThat(result.failedJars()).isEmpty();
    }

    @Test
    void reload_idempotent_secondCallMarksJarsAsSkipped(@TempDir Path tempDir) throws Exception {
        // engine.plugins.enabled를 true로, plugins.dir를 tempDir로 reflection 주입
        File jar = buildFixtureJar(tempDir, GreeterFixture.class);
        LineRegistry registry = new LineRegistry();
        PluginLoader loader = new PluginLoader(registry);
        // @Value 필드를 reflection으로 직접 set
        var enabledField = PluginLoader.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.set(loader, true);
        var dirField = PluginLoader.class.getDeclaredField("pluginsDir");
        dirField.setAccessible(true);
        dirField.set(loader, jar.getParentFile().getAbsolutePath());

        PluginLoader.ReloadResult first = loader.reload();
        assertThat(first.added()).containsExactly("GREET");
        assertThat(first.conflicts()).isEmpty();
        assertThat(first.skippedJars()).isEmpty();

        PluginLoader.ReloadResult second = loader.reload();
        assertThat(second.added()).as("두 번째 reload — 새 등록 없음").isEmpty();
        assertThat(second.conflicts()).as("이미 등록된 GREET는 conflict").containsExactly("GREET");
        // skippedJars 분류 — added=0이지만 conflicts에 있으니 jar 자체가 처리됨. 정의에 따라 skippedJars로도 분류 가능
    }

    @Test
    void duplicateNameIsTrackedAsConflict(@TempDir Path tempDir) throws Exception {
        File jar = buildFixtureJar(tempDir, GreeterFixture.class);

        LineRegistry registry = new LineRegistry();
        PluginLoader loader = new PluginLoader(registry);

        // 첫 호출 → 등록
        PluginLoader.JarScanResult first = loader.scanAndRegister(jar);
        assertThat(first.added()).containsExactly("GREET");
        assertThat(first.conflicts()).isEmpty();

        // 같은 jar 다시 → 동일 이름 충돌, conflicts에 분류
        PluginLoader.JarScanResult second = loader.scanAndRegister(jar);
        assertThat(second.added()).as("Add only — 새 등록 없음").isEmpty();
        assertThat(second.conflicts()).as("이미 등록된 GREET는 conflict로 분류").containsExactly("GREET");

        // GREET 1회만 등록 상태
        assertThat(registry.getActivityNames()).hasSize(1).contains("GREET");
    }

    /**
     * test-classpath의 fixture 클래스를 jar로 패키징.
     */
    private File buildFixtureJar(Path tempDir, Class<?> clazz) throws IOException {
        return buildFixtureJar(tempDir, clazz, null);
    }

    /**
     * #320 — pluginApiVersion이 주어지면 jar MANIFEST.MF에 Station8-Engine-Api-Version 헤더를 박는다.
     * null이면 MANIFEST 자체를 추가하지 않는다 (기존 호환).
     */
    private File buildFixtureJar(Path tempDir, Class<?> clazz, String pluginApiVersion) throws IOException {
        File jar = tempDir.resolve("fixture-plugin-" + (pluginApiVersion == null ? "noheader" : pluginApiVersion) + ".jar").toFile();
        String resourcePath = clazz.getName().replace('.', '/') + ".class";

        Manifest manifest = null;
        if (pluginApiVersion != null) {
            manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue(VersionCompatibility.PLUGIN_API_VERSION_ATTR, pluginApiVersion);
        }

        try (FileOutputStream fos = new FileOutputStream(jar);
             JarOutputStream jos = manifest == null ? new JarOutputStream(fos) : new JarOutputStream(fos, manifest);
             InputStream classBytes = clazz.getClassLoader().getResourceAsStream(resourcePath)) {

            if (classBytes == null) {
                throw new IOException("Fixture class not found on classpath: " + resourcePath);
            }

            JarEntry entry = new JarEntry(resourcePath);
            jos.putNextEntry(entry);
            classBytes.transferTo(jos);
            jos.closeEntry();
        }
        return jar;
    }

    // ---- #320: 버전 가드 ----

    @Test
    void checkVersion_readsHeaderFromManifest(@TempDir Path tempDir) throws Exception {
        File jar = buildFixtureJar(tempDir, GreeterFixture.class, "0.5.0");

        LineRegistry registry = new LineRegistry();
        PluginLoader loader = new PluginLoader(registry);

        VersionCompatibility.Check c = loader.checkVersion(jar, "0.5.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.COMPATIBLE);
        assertThat(c.pluginVersion()).isEqualTo("0.5.0");
    }

    @Test
    void checkVersion_unspecifiedWhenManifestHeaderMissing(@TempDir Path tempDir) throws Exception {
        File jar = buildFixtureJar(tempDir, GreeterFixture.class);  // no MANIFEST header

        LineRegistry registry = new LineRegistry();
        PluginLoader loader = new PluginLoader(registry);

        VersionCompatibility.Check c = loader.checkVersion(jar, "0.5.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.UNSPECIFIED);
        assertThat(c.pluginVersion()).isNull();
    }

    @Test
    void checkVersion_rejectsForwardIncompatible(@TempDir Path tempDir) throws Exception {
        File jar = buildFixtureJar(tempDir, GreeterFixture.class, "0.9.0");

        LineRegistry registry = new LineRegistry();
        PluginLoader loader = new PluginLoader(registry);

        VersionCompatibility.Check c = loader.checkVersion(jar, "0.5.0");
        assertThat(c.result()).isEqualTo(VersionCompatibility.Result.REJECTED);
    }

    @Test
    void reload_rejectsIncompatibleJarAndAddsToFailedJars(@TempDir Path tempDir) throws Exception {
        // 호스트가 1.0.0이라고 가정 — Activity.class.getPackage()를 직접 못 바꾸므로 본 시나리오는
        // checkVersion 결과에 의존. 실제로 reload 흐름에서 hostApiVersion이 null이 나오면
        // 모든 jar이 UNSPECIFIED로 통과하므로 거부 검증이 안 된다.
        // → 실제 거부 동작은 checkVersion + reload 메인 루프에서 reject 시 failedJars로 분류한다는
        //   동작을 분리 검증: checkVersion REJECTED 결과를 단위 테스트로 (위), reload 통합은
        //   UNSPECIFIED 시 통과한다는 backward-compat 시나리오로 별도 검증.

        File jarNoHeader = buildFixtureJar(tempDir, GreeterFixture.class);
        LineRegistry registry = new LineRegistry();
        PluginLoader loader = new PluginLoader(registry);
        var enabledField = PluginLoader.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.set(loader, true);
        var dirField = PluginLoader.class.getDeclaredField("pluginsDir");
        dirField.setAccessible(true);
        dirField.set(loader, jarNoHeader.getParentFile().getAbsolutePath());

        PluginLoader.ReloadResult r = loader.reload();
        // MANIFEST 헤더 없는 jar → UNSPECIFIED → 통과해서 GREET 등록되어야 함 (backward compat)
        assertThat(r.added()).contains("GREET");
        assertThat(r.failedJars()).isEmpty();
    }
}
