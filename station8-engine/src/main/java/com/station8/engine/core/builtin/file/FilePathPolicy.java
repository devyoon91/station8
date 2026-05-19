package com.station8.engine.core.builtin.file;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Local 파일 활동의 SSRF 대응 — path traversal / allowlist 검증.
 *
 * <p>{@link LocalFileSystem}이 read/write 직전에 {@link #check(Path)}를 호출한다. 위반 시
 * {@link FilePathPolicyViolationException} (NoRetryException 상속) → 활동 즉시 final-fail.</p>
 *
 * <h3>방어 모델</h3>
 * 운영자가 {@code station8.file.local.allowed-roots} 에 명시한 디렉토리 안에서만 read/write 허용.
 * 비어있으면 local FS 자체가 비활성 — 어떤 file:// URI도 차단된다 (의도된 fail-closed default).
 *
 * <p>실제 검증은 canonical path 비교로:</p>
 * <ol>
 *   <li>요청 path를 {@code toRealPath()}로 해소 — symlink 따라가서 실제 위치 확정</li>
 *   <li>각 allowed-root도 같은 방식으로 해소</li>
 *   <li>요청 path가 어느 root의 자식인지 prefix 매칭</li>
 * </ol>
 *
 * <p>이렇게 하면 {@code ../} escape와 symlink로 root 밖 가리키는 우회를 모두 막을 수 있다.</p>
 *
 * <h3>존재하지 않는 path</h3>
 * write 시 대상 파일은 아직 없을 수 있다. 그래서 부모 디렉토리까지 거슬러 올라가면서 가장
 * 가까운 존재하는 ancestor를 해소한 뒤, 그 결과가 root 안인지로 판단.
 */
@Component
public class FilePathPolicy {

    private static final Logger log = LoggerFactory.getLogger(FilePathPolicy.class);

    private final String rootsProperty;
    private List<Path> allowedRoots;

    public FilePathPolicy(@Value("${station8.file.local.allowed-roots:}") String rootsProperty) {
        this.rootsProperty = rootsProperty == null ? "" : rootsProperty.trim();
    }

    /** 부팅 시 csv 파싱 + 각 root를 절대경로로 해소. */
    @PostConstruct
    public void init() {
        List<Path> parsed = new ArrayList<>();
        if (!rootsProperty.isBlank()) {
            for (String entry : rootsProperty.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                try {
                    Path absolute = Paths.get(trimmed).toAbsolutePath();
                    parsed.add(absolute);
                } catch (Exception ex) {
                    log.warn("FilePathPolicy: invalid allowed-root '{}' — skipping ({}: {})",
                            trimmed, ex.getClass().getSimpleName(), ex.getMessage());
                }
            }
        }
        this.allowedRoots = Collections.unmodifiableList(parsed);
        if (allowedRoots.isEmpty()) {
            log.info("FilePathPolicy: allowed-roots=(empty) — local file 활동 모두 차단됨. "
                    + "station8.file.local.allowed-roots 로 디렉토리 지정 필요.");
        } else {
            log.info("FilePathPolicy: allowed-roots={}", allowedRoots);
        }
    }

    /** 디버그/테스트용 — 현재 적용 중인 root 목록. */
    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /**
     * 검증 진입점. {@link LocalFileSystem}이 read/write 직전에 호출.
     *
     * @param target 활동이 접근하려는 path (절대/상대 모두 OK — 본 메서드가 정규화)
     * @throws FilePathPolicyViolationException 위반 시
     */
    public void check(Path target) {
        if (allowedRoots.isEmpty()) {
            throw new FilePathPolicyViolationException(
                    "local file 활동은 station8.file.local.allowed-roots 미설정으로 비활성. "
                            + "운영자가 허용 디렉토리를 명시해야 함.");
        }
        Path canonical = canonicalize(target);
        for (Path root : allowedRoots) {
            Path canonicalRoot = canonicalize(root);
            if (canonical.startsWith(canonicalRoot)) {
                return;  // 통과
            }
        }
        throw new FilePathPolicyViolationException(
                "path '" + canonical + "' is outside allowed-roots " + allowedRoots);
    }

    /**
     * canonical path 해소. 존재하는 path는 {@code toRealPath()}로 symlink까지 따라가고, 미존재이면
     * 가장 가까운 존재하는 ancestor를 해소한 뒤 남은 segment를 붙여 절대 경로를 만든다.
     *
     * <p>이게 중요한 이유: write target은 아직 파일이 없을 수 있는데, 그 path의 상위 디렉토리가
     * symlink로 root 밖을 가리키면 우회 가능. ancestor canonical 후 비교가 그걸 막는다.</p>
     */
    private Path canonicalize(Path raw) {
        Path absolute = raw.toAbsolutePath();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            try {
                return absolute.toRealPath();
            } catch (IOException ex) {
                // realPath 실패 — 정규화만 (normalize는 ../ 제거)
                return absolute.normalize();
            }
        }
        // 미존재 — 존재하는 ancestor를 찾아 거기까지 realPath, 나머지는 append
        Path existing = absolute;
        List<Path> tail = new ArrayList<>();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name != null) {
                tail.add(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            // root까지 미존재 — 정규화만
            return absolute.normalize();
        }
        try {
            Path realExisting = existing.toRealPath();
            Path result = realExisting;
            Collections.reverse(tail);
            for (Path segment : tail) {
                result = result.resolve(segment);
            }
            return result.normalize();
        } catch (IOException ex) {
            return absolute.normalize();
        }
    }

    /** 테스트 헬퍼 — 명시적으로 init() 재호출. csv 파싱 후 사용. */
    public static FilePathPolicy forTest(String rootsCsv) {
        FilePathPolicy p = new FilePathPolicy(rootsCsv);
        p.init();
        return p;
    }
}
