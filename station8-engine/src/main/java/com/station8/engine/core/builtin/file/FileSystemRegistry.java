package com.station8.engine.core.builtin.file;

import com.station8.engine.core.NoRetryException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * URI를 보고 알맞은 {@link FileSystem} 구현체를 dispatch. Spring이 모든 FileSystem bean을
 * 자동으로 list로 주입 — local 외에 SFTP / S3가 들어와도 추가 wiring 0.
 *
 * <p>활동({@code FileReadActivity} / {@code FileWriteActivity})은 본 클래스를 거쳐 read/write.</p>
 *
 * <h3>URI 정규화</h3>
 * 사용자가 {@code /var/log/app.log} 같은 raw path를 줘도 자동으로 {@code file:///var/log/app.log}
 * 로 변환해서 LocalFileSystem로 보낸다 — scheme 없는 절대 path = local 처리하는 게 직관적.
 *
 * <p>scheme 없는 상대 path({@code subdir/file.txt})는 ambiguous라 거부 — 운영자가 어떤 디렉토리
 * 기준인지 알 수 없게 만들면 SSRF 우회 위험.</p>
 */
@Component
public class FileSystemRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileSystemRegistry.class);

    private final List<FileSystem> backends;

    public FileSystemRegistry(List<FileSystem> backends) {
        this.backends = backends;
    }

    @PostConstruct
    void init() {
        log.info("FileSystemRegistry: registered {} backend(s) — {}",
                backends.size(),
                backends.stream().map(b -> b.getClass().getSimpleName()).toList());
    }

    /**
     * URI를 처리할 backend를 찾고 read 위임. backend 없으면 {@link NoRetryException}.
     *
     * @param uriString 활동 입력 URI 또는 raw absolute path
     * @return 파일 byte 내용
     */
    public byte[] read(String uriString) {
        URI uri = normalize(uriString);
        return dispatch(uri).read(uri);
    }

    /** URI를 처리할 backend로 write 위임. */
    public void write(String uriString, byte[] content) {
        URI uri = normalize(uriString);
        dispatch(uri).write(uri, content);
    }

    private FileSystem dispatch(URI uri) {
        for (FileSystem b : backends) {
            if (b.supports(uri)) {
                return b;
            }
        }
        throw new NoRetryException(
                "no FileSystem backend supports URI scheme '" + uri.getScheme()
                        + "' (registered: "
                        + backends.stream().map(x -> x.getClass().getSimpleName()).toList() + ")");
    }

    /**
     * raw input을 URI로 정규화. 다음 규칙:
     * <ul>
     *   <li>이미 scheme 있는 URI ({@code file:///...}, {@code sftp://...}) — 그대로</li>
     *   <li>절대 path ({@code /var/...} 또는 Windows {@code C:\...}) — {@code file://}로 wrap</li>
     *   <li>상대 path ({@code subdir/file.txt}) — 거부 (ambiguous)</li>
     * </ul>
     */
    private URI normalize(String input) {
        if (input == null || input.isBlank()) {
            throw new NoRetryException("file activity uri is required");
        }
        String trimmed = input.trim();
        try {
            URI uri = new URI(trimmed);
            if (uri.getScheme() != null && !uri.getScheme().isBlank()) {
                // Windows drive letter (C:)도 scheme으로 잡힘 — 길이로 구분
                if (uri.getScheme().length() == 1
                        && Character.isLetter(uri.getScheme().charAt(0))) {
                    // C:\... 형태 → file URI로 wrap
                    return Paths.get(trimmed).toUri();
                }
                return uri;
            }
        } catch (URISyntaxException ignored) {
            // scheme 없는 raw path — 아래에서 처리
        }
        // scheme 없음 — 절대 path만 허용
        if (!Paths.get(trimmed).isAbsolute()) {
            throw new NoRetryException(
                    "file activity uri must be absolute path or scheme'd URI: '"
                            + trimmed + "' (예: /var/log/file 또는 file:///var/log/file)");
        }
        return Paths.get(trimmed).toUri();
    }
}
