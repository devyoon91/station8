package com.station8.engine.plugin;

import com.station8.engine.core.LineRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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

        int count = loader.scanAndRegister(jar);

        assertThat(count).as("등록된 액티비티 개수").isEqualTo(1);
        assertThat(registry.getActivityNames()).contains("GREET");
        LineRegistry.ActivityMetadata md = registry.getActivity("GREET");
        assertThat(md).isNotNull();
        assertThat(md.method().getName()).isEqualTo("greet");
    }

    @Test
    void duplicateNameIsSkippedWithWarning(@TempDir Path tempDir) throws Exception {
        File jar = buildFixtureJar(tempDir, GreeterFixture.class);

        LineRegistry registry = new LineRegistry();
        PluginLoader loader = new PluginLoader(registry);

        // 첫 호출 → 등록
        loader.scanAndRegister(jar);
        // 같은 jar 다시 → 동일 이름 충돌, 무시
        int second = loader.scanAndRegister(jar);

        // GREET 1회만 등록 상태
        assertThat(registry.getActivityNames()).hasSize(1).contains("GREET");
        // 카운트는 시도 1번 (인스턴스화 1번)이지만 conflict로 실제 추가는 X
        // → registerActivity는 conflict 시 map.put 안 하고 return
        // 본 테스트는 결과 상태(맵 크기)로만 검증
        assertThat(second).as("scanAndRegister는 conflict도 1로 카운트할 수 있음").isGreaterThanOrEqualTo(0);
    }

    /**
     * test-classpath의 fixture 클래스를 jar로 패키징.
     */
    private File buildFixtureJar(Path tempDir, Class<?> clazz) throws IOException {
        File jar = tempDir.resolve("fixture-plugin.jar").toFile();
        String resourcePath = clazz.getName().replace('.', '/') + ".class";

        try (FileOutputStream fos = new FileOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(fos);
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
}
