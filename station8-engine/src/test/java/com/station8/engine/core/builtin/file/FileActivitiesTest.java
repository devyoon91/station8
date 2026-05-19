package com.station8.engine.core.builtin.file;

import com.station8.engine.core.NoRetryException;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #295 — file.read / file.write 활동 회귀 가드.
 *
 * <p>실제 tempdir 파일에 round-trip으로 검증. FileSystemRegistry는 Spring 없이 list 직접 구성.</p>
 */
class FileActivitiesTest {

    @TempDir
    Path tempDir;

    private JsonUtil jsonUtil;
    private FileReadActivity readActivity;
    private FileWriteActivity writeActivity;

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        FilePathPolicy policy = FilePathPolicy.forTest(tempDir.toString());
        LocalFileSystem local = new LocalFileSystem(policy);
        FileSystemRegistry registry = new FileSystemRegistry(List.of(local));
        readActivity = new FileReadActivity(jsonUtil, registry);
        writeActivity = new FileWriteActivity(jsonUtil, registry);
    }

    // ============ text format ============

    @Test
    void writeRead_textRoundTrip() {
        Path target = tempDir.resolve("hello.txt");
        writeActivity.write(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "text",
                "content", "Hello, world!")));

        String result = readActivity.read(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "text")));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(result, Map.class);
        assertThat(parsed).containsEntry("format", "text");
        assertThat(parsed).containsEntry("content", "Hello, world!");
        assertThat(parsed.get("sizeBytes")).isEqualTo("Hello, world!".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void writeRead_textWithExplicitEncoding() {
        Path target = tempDir.resolve("euc-kr.txt");
        String korean = "한글 텍스트";
        writeActivity.write(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "text",
                "encoding", "EUC-KR",
                "content", korean)));

        String result = readActivity.read(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "text",
                "encoding", "EUC-KR")));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(result, Map.class);
        assertThat(parsed).containsEntry("content", korean);
    }

    // ============ json format ============

    @Test
    void writeRead_jsonRoundTrip() {
        Path target = tempDir.resolve("data.json");
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "alice");
        payload.put("age", 30);
        payload.put("tags", List.of("admin", "ops"));

        writeActivity.write(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "json",
                "content", payload)));

        String result = readActivity.read(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "json")));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(result, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) parsed.get("content");
        assertThat(content).containsEntry("name", "alice").containsEntry("age", 30);
        assertThat(content.get("tags")).isEqualTo(List.of("admin", "ops"));
    }

    @Test
    void read_jsonFormat_butContentIsNotJson_throwsNoRetry() throws IOException {
        Path target = tempDir.resolve("not-json.txt");
        Files.writeString(target, "this is plain text, not JSON");
        assertThatThrownBy(() -> readActivity.read(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "json"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("not valid JSON");
    }

    // ============ binary format ============

    @Test
    void writeRead_binaryRoundTrip() {
        Path target = tempDir.resolve("blob.bin");
        byte[] raw = {0x00, 0x01, 0x02, 0x03, (byte) 0xff, (byte) 0xfe};
        String base64 = Base64.getEncoder().encodeToString(raw);

        writeActivity.write(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "binary",
                "content", base64)));

        String result = readActivity.read(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "binary")));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = jsonUtil.fromJson(result, Map.class);
        assertThat(parsed).containsEntry("content", base64);
        assertThat(parsed.get("sizeBytes")).isEqualTo(raw.length);  // base64 길이가 아닌 원본
    }

    @Test
    void write_binaryFormat_butContentIsNotBase64_throwsNoRetry() {
        Path target = tempDir.resolve("bad.bin");
        assertThatThrownBy(() -> writeActivity.write(jsonUtil.toJson(Map.of(
                "uri", target.toUri().toString(),
                "format", "binary",
                "content", "not base64 string!!"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("Base64");
    }

    // ============ path policy 통합 ============

    @Test
    void read_outsideAllowedRoot_throwsNoRetry() throws IOException {
        Path outside = Files.createTempFile("outside-", ".txt");
        outside.toFile().deleteOnExit();
        Files.writeString(outside, "should be blocked");
        assertThatThrownBy(() -> readActivity.read(jsonUtil.toJson(Map.of(
                "uri", outside.toUri().toString(),
                "format", "text"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("outside allowed-roots");
    }

    // ============ 입력 검증 ============

    @Test
    void read_missingUri_throwsNoRetry() {
        assertThatThrownBy(() -> readActivity.read(jsonUtil.toJson(Map.of("format", "text"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("uri is required");
    }

    @Test
    void read_unsupportedFormat_throwsNoRetry() {
        assertThatThrownBy(() -> readActivity.read(jsonUtil.toJson(Map.of(
                "uri", tempDir.resolve("x.txt").toUri().toString(),
                "format", "xml"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void read_unknownScheme_throwsNoRetry() {
        assertThatThrownBy(() -> readActivity.read(jsonUtil.toJson(Map.of(
                "uri", "sftp://host/path",
                "format", "text"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("no FileSystem backend");
    }

    @Test
    void read_relativePathWithoutScheme_throwsNoRetry() {
        assertThatThrownBy(() -> readActivity.read(jsonUtil.toJson(Map.of(
                "uri", "subdir/file.txt",
                "format", "text"))))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void write_textFormat_butContentIsNotString_throwsNoRetry() {
        Path target = tempDir.resolve("oops.txt");
        Map<String, Object> input = new HashMap<>();
        input.put("uri", target.toUri().toString());
        input.put("format", "text");
        input.put("content", Map.of("not", "a string"));
        assertThatThrownBy(() -> writeActivity.write(jsonUtil.toJson(input)))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("requires content as String");
    }

    @Test
    void write_nullContent_writesEmptyFile() throws IOException {
        Path target = tempDir.resolve("empty.txt");
        Map<String, Object> input = new HashMap<>();
        input.put("uri", target.toUri().toString());
        input.put("format", "text");
        input.put("content", null);
        writeActivity.write(jsonUtil.toJson(input));
        assertThat(Files.readAllBytes(target)).isEmpty();
    }
}
