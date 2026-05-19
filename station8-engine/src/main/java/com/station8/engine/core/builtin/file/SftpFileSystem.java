package com.station8.engine.core.builtin.file;

import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.NoRetryException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceLoader;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;

/**
 * SFTP backend — `sftp://` scheme. Apache MINA SSHD 2.x 위 얇은 wrapper.
 *
 * <h3>URI 형식</h3>
 * <pre>{@code sftp://user@host:port/abs/path/to/file}</pre>
 *
 * <p>{@code user}는 URI에서 (또는 credential schema에서 override 가능), 인증 자격증명은
 * 활동 입력의 {@code credentialId}로 vault에서 조회. URI에 평문 password 절대 박지 말 것 —
 * URI는 라인 정의 / 로그 / inputData 등 여러 경로에 나타날 수 있다.</p>
 *
 * <h3>credential type</h3>
 * <table>
 *   <tr><th>type</th><th>schema</th><th>인증</th></tr>
 *   <tr><td>{@code sftp_password}</td><td>{@code {}} (optional {@code username} override)</td>
 *       <td>URI user (또는 schema.username) + credential value = password</td></tr>
 *   <tr><td>{@code sftp_key}</td><td>{@code {"privateKey":"-----BEGIN..."}}</td>
 *       <td>URI user (또는 schema.username) + schema.privateKey = OpenSSH/PEM 형식 키.
 *           credential value는 passphrase (선택, 빈 값이면 키 자체가 unencrypted)</td></tr>
 * </table>
 *
 * <h3>known_hosts</h3>
 * {@code station8.file.sftp.known-hosts} property로 OpenSSH 포맷 known_hosts 파일 path 명시.
 * 빈 값이면 모든 SFTP 연결을 거부 (TOFU 명시 거부 — 첫 연결도 검증된 키가 있어야 통과).
 * Unknown 서버 fingerprint는 차단 + 로그에 fingerprint를 남겨 운영자가 known_hosts에 추가할
 * 단서 제공.
 *
 * <h3>연결 lifecycle</h3>
 * 활동 호출 단위로 connect + disconnect. 풀링은 별도 sub-issue. 작은 파일에는 충분하지만
 * 잦은 호출에서는 connection 부담이 있을 수 있음 — 운영 보고 후 평가.
 */
@Component
public class SftpFileSystem implements FileSystem {

    private static final Logger log = LoggerFactory.getLogger(SftpFileSystem.class);

    /** OpenSSH known_hosts 파일 path. 빈 값이면 fail-closed. */
    private final String knownHostsPath;
    private final Duration connectTimeout;
    private final Duration authTimeout;

    private final CredentialResolver credentialResolver;

    public SftpFileSystem(
            @Value("${station8.file.sftp.known-hosts:}") String knownHostsPath,
            @Value("${station8.file.sftp.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${station8.file.sftp.auth-timeout-ms:10000}") long authTimeoutMs,
            CredentialResolver credentialResolver) {
        this.knownHostsPath = knownHostsPath == null ? "" : knownHostsPath.trim();
        this.connectTimeout = Duration.ofMillis(connectTimeoutMs);
        this.authTimeout = Duration.ofMillis(authTimeoutMs);
        this.credentialResolver = credentialResolver;
    }

    @Override
    public boolean supports(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && "sftp".equalsIgnoreCase(scheme);
    }

    @Override
    public byte[] read(URI uri, String credentialId) {
        return withSftpClient(uri, credentialId, sftp -> {
            String remotePath = uri.getPath();
            try (InputStream in = sftp.read(remotePath)) {
                return in.readAllBytes();
            }
        });
    }

    @Override
    public void write(URI uri, byte[] content, String credentialId) {
        withSftpClient(uri, credentialId, sftp -> {
            String remotePath = uri.getPath();
            // 부모 디렉토리 자동 생성 — 호출자가 인스턴스 ID 같은 동적 path를 줘도 동작.
            ensureParentDirs(sftp, remotePath);
            try (OutputStream out = sftp.write(remotePath)) {
                out.write(content == null ? new byte[0] : content);
            }
            return null;
        });
    }

    /**
     * SftpClient 라이프사이클을 책임지고 콜백 실행. connect → auth → callback → 정리 (try-with-resources).
     */
    private <T> T withSftpClient(URI uri, String credentialId, SftpOperation<T> op) {
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new NoRetryException("sftp URI must have host: " + uri);
        }
        if (uri.getPath() == null || uri.getPath().isBlank()) {
            throw new NoRetryException("sftp URI must have path: " + uri);
        }
        if (credentialId == null || credentialId.isBlank()) {
            throw new NoRetryException(
                    "sftp activity requires credentialId (vault entry of type sftp_password or sftp_key)");
        }
        CredentialResolver.Resolved cred = credentialResolver.resolveByName(credentialId);
        if (cred == null) {
            throw new NoRetryException("sftp credentialId not found in vault: " + credentialId);
        }

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 22;
        String user = resolveUsername(uri, cred);

        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(buildKeyVerifier());
        client.start();
        try {
            try (ClientSession session = client.connect(user, host, port)
                    .verify(connectTimeout).getSession()) {
                applyCredential(session, cred);
                session.auth().verify(authTimeout);
                try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                    return op.execute(sftp);
                }
            }
        } catch (NoRetryException ex) {
            throw ex;
        } catch (IOException ex) {
            // 메시지로 분류 — server key / 인증 / 권한 실패는 재시도 무의미.
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (msg.contains("server key did not validate")
                    || msg.contains("key not accepted")
                    || msg.contains("authentication")
                    || msg.contains("permission denied")
                    || msg.contains("no such file")) {
                throw new NoRetryException(
                        "sftp non-retryable failure (host=" + host + "): " + ex.getMessage(), ex);
            }
            // 그 외 IOException (네트워크 일시 장애, 연결 끊김 등)은 재시도 대상.
            throw new RuntimeException(
                    "sftp I/O failure (host=" + host + ", path=" + uri.getPath() + "): "
                            + ex.getMessage(), ex);
        } catch (Exception ex) {
            // RuntimeException 류 (인증 실패 wrap 등) — 재시도 무의미로 격하.
            throw new NoRetryException(
                    "sftp protocol failure (host=" + host + "): " + ex.getMessage(), ex);
        } finally {
            client.stop();
        }
    }

    /** URI user > schema.username override 우선순위. 둘 다 없으면 거부. */
    private static String resolveUsername(URI uri, CredentialResolver.Resolved cred) {
        String fromUri = uri.getUserInfo();
        if (fromUri != null && !fromUri.isBlank()) {
            // userinfo에 password 부분이 있을 수도 있는데 (`user:pass`) — `user`만
            int colon = fromUri.indexOf(':');
            return colon < 0 ? fromUri : fromUri.substring(0, colon);
        }
        Object fromSchema = cred.schema().get("username");
        if (fromSchema != null) {
            return fromSchema.toString();
        }
        throw new NoRetryException(
                "sftp URI에 user가 없고 credential schema에도 username 없음: " + uri);
    }

    /** type별로 password / key 인증 적용. */
    private void applyCredential(ClientSession session, CredentialResolver.Resolved cred) {
        switch (cred.type()) {
            case "sftp_password" -> session.addPasswordIdentity(cred.value());
            case "sftp_key" -> {
                Object pk = cred.schema().get("privateKey");
                if (pk == null) {
                    throw new NoRetryException(
                            "sftp_key credential '" + cred.name() + "' missing schema.privateKey");
                }
                String passphrase = cred.value();
                try {
                    KeyPairResourceLoader loader = SecurityUtils.getKeyPairResourceParser();
                    // FilePasswordProvider — passphrase 매번 그대로 반환 (재시도 의미 없음).
                    FilePasswordProvider pwd = (sess, resource, retry) -> passphrase;
                    NamedResource resource = NamedResource.ofName("vault:" + cred.name());
                    InputStream in = new ByteArrayInputStream(pk.toString().getBytes());
                    Collection<KeyPair> pairs = loader.loadKeyPairs(session, resource, pwd, in);
                    if (pairs == null || pairs.isEmpty()) {
                        throw new NoRetryException(
                                "sftp_key credential '" + cred.name() + "' privateKey parse failed");
                    }
                    pairs.forEach(session::addPublicKeyIdentity);
                } catch (NoRetryException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new NoRetryException(
                            "sftp_key credential '" + cred.name() + "' key load failed: "
                                    + ex.getMessage(), ex);
                }
            }
            default -> throw new NoRetryException(
                    "sftp credential type not supported: " + cred.type()
                            + " (allowed: sftp_password, sftp_key)");
        }
    }

    /**
     * known_hosts 파일이 있으면 그걸로 검증 + 불일치는 reject. 미설정이면 모든 연결 차단
     * (fail-closed — TOFU 우회 방지).
     */
    private ServerKeyVerifier buildKeyVerifier() {
        if (knownHostsPath.isBlank()) {
            log.warn("station8.file.sftp.known-hosts 미설정 — 모든 SFTP 연결이 거부됨. "
                    + "운영자가 known_hosts 파일을 명시해야 함.");
            return RejectAllServerKeyVerifier.INSTANCE;
        }
        Path file = Paths.get(knownHostsPath);
        if (!Files.exists(file)) {
            log.warn("station8.file.sftp.known-hosts={} 가 존재하지 않음 — 모든 SFTP 연결 거부.",
                    knownHostsPath);
            return RejectAllServerKeyVerifier.INSTANCE;
        }
        // unknown host는 reject — TOFU 명시 거부.
        return new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, file);
    }

    /**
     * remote path의 부모 디렉토리들을 SFTP mkdir로 보장. 이미 존재하면 silent skip.
     */
    private static void ensureParentDirs(SftpClient sftp, String remotePath) throws IOException {
        int lastSlash = remotePath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }
        String parent = remotePath.substring(0, lastSlash);
        // 점진적으로 만들면서 EEXIST는 무시
        StringBuilder cur = new StringBuilder();
        for (String segment : parent.split("/")) {
            if (segment.isEmpty()) continue;
            cur.append('/').append(segment);
            try {
                sftp.mkdir(cur.toString());
            } catch (IOException ex) {
                // 이미 존재면 무시. 다른 에러는 throw.
                String msg = ex.getMessage();
                if (msg == null || !msg.toLowerCase().contains("exist")) {
                    // 권한 부족 / 더 깊은 문제 — 위로 전파하지 않고 write 시점에 실패하게 둠 (mkdir이
                    // 동작 안 해도 path가 이미 OK일 수 있음)
                    log.debug("sftp mkdir failed at {}: {} (계속 진행)", cur, msg);
                }
            }
        }
    }

    @FunctionalInterface
    private interface SftpOperation<T> {
        T execute(SftpClient sftp) throws IOException;
    }

    /** 디버그용 — 현재 설정된 known_hosts path. */
    public String knownHostsPath() {
        return knownHostsPath;
    }
}
