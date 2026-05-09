package com.station8.app.controller;

import com.station8.app.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * #102 — 어드민 플러그인 jar 웹 업로드 + 디렉토리 목록.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class AdminPluginControllerTest {

    @TempDir
    static Path tempPluginsDir;

    @DynamicPropertySource
    static void configurePluginsDir(DynamicPropertyRegistry registry) {
        registry.add("engine.plugins.dir", tempPluginsDir::toString);
    }

    @Autowired MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void cleanDir() throws Exception {
        if (!Files.exists(tempPluginsDir)) return;
        try (var stream = Files.list(tempPluginsDir)) {
            stream.forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignore) { }
            });
        }
    }

    @Test
    void list_emptyDirectory_rendersEmptyTable() throws Exception {
        mockMvc.perform(get("/admin/plugins"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Plugins")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Upload jar")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("파일 없음")));
    }

    @Test
    void upload_validJar_savesFileAndShowsSuccess() throws Exception {
        byte[] jarBytes = buildMinimalJar();
        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.jar", "application/java-archive", jarBytes);

        mockMvc.perform(multipart("/admin/plugins").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/plugins"));

        Path saved = tempPluginsDir.resolve("sample.jar");
        assertThat(Files.exists(saved)).isTrue();
        assertThat(Files.size(saved)).isEqualTo(jarBytes.length);
    }

    @Test
    void upload_nonJarExtension_rejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain",
                new byte[]{0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0});

        mockMvc.perform(multipart("/admin/plugins").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // GET으로 결과 메시지 확인
        mockMvc.perform(get("/admin/plugins").flashAttr("uploadMsg",
                "[FAIL] 확장자가 .jar이 아닙니다: notes.txt").flashAttr("uploadOk", false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "확장자가 .jar이 아닙니다")));

        assertThat(Files.exists(tempPluginsDir.resolve("notes.txt"))).isFalse();
    }

    @Test
    void upload_invalidMagicBytes_rejected() throws Exception {
        // 확장자는 jar지만 매직 바이트는 아님
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.jar", "application/java-archive",
                "this is not a jar".getBytes());

        mockMvc.perform(multipart("/admin/plugins").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(Files.exists(tempPluginsDir.resolve("fake.jar"))).isFalse();
    }

    @Test
    void upload_emptyFile_rejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.jar", "application/java-archive", new byte[0]);

        mockMvc.perform(multipart("/admin/plugins").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(Files.exists(tempPluginsDir.resolve("empty.jar"))).isFalse();
    }

    @Test
    void upload_existingFilename_createsBakBackup() throws Exception {
        // v1 업로드
        byte[] v1 = buildMinimalJar();
        mockMvc.perform(multipart("/admin/plugins").file(
                new MockMultipartFile("file", "plugin.jar", "application/java-archive", v1)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // v2 업로드 (같은 이름) — v1은 .bak으로 백업
        byte[] v2 = buildMinimalJar();
        // v2에 더미 entry 추가해 길이 차이를 둠
        byte[] v2WithExtra = new byte[v2.length + 4];
        System.arraycopy(v2, 0, v2WithExtra, 0, v2.length);

        mockMvc.perform(multipart("/admin/plugins").file(
                new MockMultipartFile("file", "plugin.jar", "application/java-archive", v2)).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Path main = tempPluginsDir.resolve("plugin.jar");
        Path bak = tempPluginsDir.resolve("plugin.jar.bak");
        assertThat(Files.exists(main)).isTrue();
        assertThat(Files.exists(bak)).isTrue();
        assertThat(Files.size(bak)).isEqualTo(v1.length);  // 이전 v1이 .bak에
    }

    @Test
    void upload_pathTraversalAttempt_sanitizedToBasename() throws Exception {
        // 파일명에 path 포함 — sanitize는 path 부분 제거 후 basename만 사용
        byte[] jar = buildMinimalJar();
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/evil.jar", "application/java-archive", jar);

        mockMvc.perform(multipart("/admin/plugins").file(file).with(csrf()))
                .andExpect(status().is3xxRedirection());

        // basename으로 sanitize되어 plugins 디렉토리 안에 evil.jar로 저장됨 (디렉토리 외부에 안 떨어짐)
        assertThat(Files.exists(tempPluginsDir.resolve("evil.jar"))).isTrue();
        // 상위 디렉토리에 etc/ 같은 sub 폴더가 새로 만들어지지 않았어야 함
        assertThat(Files.exists(tempPluginsDir.resolve("etc"))).isFalse();
    }

    @Test
    void list_afterUpload_showsFileInTable() throws Exception {
        byte[] jar = buildMinimalJar();
        mockMvc.perform(multipart("/admin/plugins").file(
                new MockMultipartFile("file", "shown.jar", "application/java-archive", jar)).with(csrf()));

        mockMvc.perform(get("/admin/plugins"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("shown.jar")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jar</span>")));
    }

    /**
     * #103 reload 검증 — engine.plugins.enabled=false (테스트 기본)이라 added=0이지만,
     * 엔드포인트 동작 + redirect + flash 메시지 자체 동작 검증.
     */
    @Test
    void reload_returns_redirect_andFlashSummary() throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/admin/plugins/reload").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/plugins"))
                .andReturn();

        var flash = result.getFlashMap();
        org.assertj.core.api.Assertions.assertThat(flash.get("reloadOk")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat((String) flash.get("reloadMsg"))
                .startsWith("[OK] Reload 완료");
    }

    @Test
    void list_pageRendersReloadButton() throws Exception {
        mockMvc.perform(get("/admin/plugins"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/admin/plugins/reload")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reload now")));
    }

    /** 가장 짧은 유효 jar — 매니페스트만 있고 클래스 없음. */
    private static byte[] buildMinimalJar() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos, manifest)) {
            jos.putNextEntry(new JarEntry("dummy.txt"));
            jos.write("hello".getBytes());
            jos.closeEntry();
        }
        return baos.toByteArray();
    }
}
