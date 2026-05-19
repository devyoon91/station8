package com.station8.engine.core.builtin.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #295 — FilePathPolicy 회귀 가드.
 *
 * <p>각 케이스에서 정책이 차단/통과를 의도대로 결정하는지 검증. tempdir 기반이라 OS 의존 0.</p>
 */
class FilePathPolicyTest {

    @TempDir
    Path tempDir;

    private Path allowedRoot;
    private Path forbiddenRoot;

    @BeforeEach
    void setUp() throws IOException {
        allowedRoot = Files.createDirectory(tempDir.resolve("allowed"));
        forbiddenRoot = Files.createDirectory(tempDir.resolve("forbidden"));
    }

    @AfterEach
    void tearDown() {
        // @TempDir이 자동 정리
    }

    @Test
    void emptyAllowedRoots_disablesLocalFs() {
        FilePathPolicy policy = FilePathPolicy.forTest("");
        assertThatThrownBy(() -> policy.check(allowedRoot.resolve("x.txt")))
                .isInstanceOf(FilePathPolicyViolationException.class)
                .hasMessageContaining("allowed-roots 미설정");
    }

    @Test
    void pathInsideAllowedRoot_passes() throws IOException {
        Files.createFile(allowedRoot.resolve("hello.txt"));
        FilePathPolicy policy = FilePathPolicy.forTest(allowedRoot.toString());
        assertThatCode(() -> policy.check(allowedRoot.resolve("hello.txt")))
                .doesNotThrowAnyException();
    }

    @Test
    void pathOutsideAllowedRoot_rejected() {
        FilePathPolicy policy = FilePathPolicy.forTest(allowedRoot.toString());
        assertThatThrownBy(() -> policy.check(forbiddenRoot.resolve("x.txt")))
                .isInstanceOf(FilePathPolicyViolationException.class)
                .hasMessageContaining("outside allowed-roots");
    }

    @Test
    void parentTraversal_caughtViaCanonicalCompare() throws IOException {
        FilePathPolicy policy = FilePathPolicy.forTest(allowedRoot.toString());
        // allowed/sub/../../forbidden/x.txt → canonicalize되어 forbidden/x.txt
        Path tricky = allowedRoot.resolve("sub").resolve("..").resolve("..")
                .resolve("forbidden").resolve("x.txt");
        assertThatThrownBy(() -> policy.check(tricky))
                .isInstanceOf(FilePathPolicyViolationException.class)
                .hasMessageContaining("outside allowed-roots");
    }

    @Test
    void writeTarget_inAllowedRoot_passesEvenIfNotYetExists() {
        FilePathPolicy policy = FilePathPolicy.forTest(allowedRoot.toString());
        Path notYet = allowedRoot.resolve("nested").resolve("deeper").resolve("output.txt");
        // 미존재 path도 ancestor canonicalize로 통과해야
        assertThatCode(() -> policy.check(notYet)).doesNotThrowAnyException();
    }

    @Test
    void writeTarget_outsideRoot_rejected_evenIfNotYetExists() {
        FilePathPolicy policy = FilePathPolicy.forTest(allowedRoot.toString());
        Path outside = forbiddenRoot.resolve("nested").resolve("new-file.txt");
        assertThatThrownBy(() -> policy.check(outside))
                .isInstanceOf(FilePathPolicyViolationException.class);
    }

    @Test
    void multipleAllowedRoots_anyMatchPasses() {
        FilePathPolicy policy = FilePathPolicy.forTest(
                allowedRoot.toString() + "," + forbiddenRoot.toString());
        assertThatCode(() -> policy.check(allowedRoot.resolve("a.txt"))).doesNotThrowAnyException();
        assertThatCode(() -> policy.check(forbiddenRoot.resolve("b.txt"))).doesNotThrowAnyException();
    }

    @Test
    void allowedRoots_reportedViaGetter() {
        FilePathPolicy policy = FilePathPolicy.forTest(allowedRoot.toString());
        assertThat(policy.allowedRoots()).hasSize(1).first().satisfies(p ->
                assertThat(p.toString()).contains("allowed"));
    }

    @Test
    void blankEntries_inCsv_areSkipped() {
        FilePathPolicy policy = FilePathPolicy.forTest(allowedRoot.toString() + ", ,,");
        assertThat(policy.allowedRoots()).hasSize(1);
    }
}
