package com.station8.engine.core.builtin.file;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local 파일시스템 backend — `file://` scheme. JDK {@link Files} 위에 얇은 wrapper.
 *
 * <p>read/write 직전에 {@link FilePathPolicy#check}로 경로 검증. 위반이면 즉시 final-fail
 * ({@link FilePathPolicyViolationException}는 NoRetryException 상속).</p>
 *
 * <p>URI parsing:</p>
 * <ul>
 *   <li>{@code file:///var/log/app.log} — 표준</li>
 *   <li>scheme 없는 path도 처리하기 위해 호출자({@link FileSystemRegistry})가 정규화. 본 클래스는
 *       {@code file} scheme만 처리</li>
 * </ul>
 *
 * <p>write 시 부모 디렉토리가 없으면 만든다 (allowed-roots 안이라는 전제 하에). 같은 path가
 * 있으면 덮어쓴다 — atomic write는 별도 sub-issue로.</p>
 */
@Component
public class LocalFileSystem implements FileSystem {

    private final FilePathPolicy pathPolicy;

    public LocalFileSystem(FilePathPolicy pathPolicy) {
        this.pathPolicy = pathPolicy;
    }

    @Override
    public boolean supports(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && "file".equalsIgnoreCase(scheme);
    }

    @Override
    public byte[] read(URI uri, String credentialId) {
        // local backend는 credentialId 무시 — OS 파일 권한이 인증의 전부.
        Path path = toPath(uri);
        pathPolicy.check(path);
        try {
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            // 일시적 I/O 실패는 일반 RuntimeException → 엔진이 retry
            throw new RuntimeException("file.read I/O failure: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void write(URI uri, byte[] content, String credentialId) {
        // local backend는 credentialId 무시.
        Path path = toPath(uri);
        pathPolicy.check(path);
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(path, content == null ? new byte[0] : content);
        } catch (IOException ex) {
            throw new RuntimeException("file.write I/O failure: " + ex.getMessage(), ex);
        }
    }

    /** {@code file:///abs/path} → {@link Path}. URI가 정상이면 {@code Paths.get(URI)}가 그대로. */
    private static Path toPath(URI uri) {
        try {
            return Paths.get(uri);
        } catch (Exception ex) {
            throw new FilePathPolicyViolationException(
                    "file URI 변환 실패 (" + ex.getClass().getSimpleName() + "): " + uri);
        }
    }
}
