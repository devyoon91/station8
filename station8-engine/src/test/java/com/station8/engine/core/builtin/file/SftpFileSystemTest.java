package com.station8.engine.core.builtin.file;

import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.NoRetryException;
import com.station8.engine.util.JsonUtil;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #296 — SftpFileSystem 회귀 가드. Apache MINA SSHD 임베디드 server를 fixture로.
 *
 * <p>각 테스트는 자체 SSH server를 random port에 띄우고, 서버의 host key를 known_hosts 파일에
 * 박은 뒤, SftpFileSystem을 그 known_hosts로 설정. 외부 인프라 의존 0.</p>
 */
class SftpFileSystemTest {

    @TempDir
    Path tempDir;

    private SshServer sshd;
    private int sshPort;
    private Path sftpRoot;
    private Path hostKeyFile;
    private Path knownHostsFile;
    private StubCredentialResolver credentialResolver;
    private SftpFileSystem fs;

    private static final String TEST_USER = "tester";
    private static final String TEST_PASSWORD = "p@ssw0rd-test";

    @BeforeEach
    void setUp() throws IOException {
        sftpRoot = Files.createDirectory(tempDir.resolve("sftp-root"));
        hostKeyFile = tempDir.resolve("hostkey.ser");
        knownHostsFile = tempDir.resolve("known_hosts");

        sshd = SshServer.setUpDefaultServer();
        sshd.setPort(0);  // random
        sshd.setHost("127.0.0.1");
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyFile));
        sshd.setPasswordAuthenticator(new TestPasswordAuthenticator());
        // 모든 SFTP 세션을 sftpRoot 안에 chroot — write 시 디스크 root로 빠지지 않게.
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.start();
        sshPort = sshd.getPort();

        // 서버가 시작되면 hostkey.ser에 키가 저장됨. 그걸 known_hosts 포맷으로 변환.
        writeKnownHosts();

        credentialResolver = new StubCredentialResolver(new JsonUtil());
        fs = new SftpFileSystem(
                knownHostsFile.toString(),
                5000L, 5000L,
                credentialResolver);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (sshd != null) {
            sshd.stop(true);
        }
    }

    /** SSHD가 생성한 host key를 [host]:port ssh-rsa AAAA... 포맷으로 known_hosts에. */
    private void writeKnownHosts() throws IOException {
        SimpleGeneratorHostKeyProvider provider = (SimpleGeneratorHostKeyProvider) sshd.getKeyPairProvider();
        KeyPair pair = provider.loadKeys(null).iterator().next();
        PublicKey publicKey = pair.getPublic();
        String entry = "[127.0.0.1]:" + sshPort + " " + PublicKeyEntry.toString(publicKey) + "\n";
        Files.writeString(knownHostsFile, entry, StandardCharsets.UTF_8);
    }

    private URI sftpUri(String remotePath) {
        return URI.create("sftp://" + TEST_USER + "@127.0.0.1:" + sshPort + remotePath);
    }

    // ============ password 인증 round-trip ============

    @Test
    void password_writeRead_roundTrip() throws IOException {
        credentialResolver.put("sftp-prod",
                new CredentialResolver.Resolved(
                        "sftp-prod", "sftp_password", TEST_PASSWORD, Map.of()));

        URI uri = sftpUri("/hello.txt");
        fs.write(uri, "Hello, SFTP world!".getBytes(StandardCharsets.UTF_8), "sftp-prod");

        // 서버측 파일이 실제로 생겼는지 직접 확인
        Path written = sftpRoot.resolve("hello.txt");
        assertThat(written).exists();
        assertThat(Files.readString(written)).isEqualTo("Hello, SFTP world!");

        // 다시 read해서 같은 내용 돌아오는지
        byte[] read = fs.read(uri, "sftp-prod");
        assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("Hello, SFTP world!");
    }

    @Test
    void password_write_createsParentDirs() throws IOException {
        credentialResolver.put("sftp-prod",
                new CredentialResolver.Resolved(
                        "sftp-prod", "sftp_password", TEST_PASSWORD, Map.of()));

        URI uri = sftpUri("/nested/deeper/output.txt");
        fs.write(uri, "deep".getBytes(StandardCharsets.UTF_8), "sftp-prod");

        Path written = sftpRoot.resolve("nested/deeper/output.txt");
        assertThat(written).exists();
        assertThat(Files.readString(written)).isEqualTo("deep");
    }

    // ============ 인증 실패 ============

    @Test
    void wrongPassword_throwsNoRetry() {
        credentialResolver.put("sftp-wrong",
                new CredentialResolver.Resolved(
                        "sftp-wrong", "sftp_password", "wrong-password", Map.of()));

        URI uri = sftpUri("/file.txt");
        assertThatThrownBy(() -> fs.read(uri, "sftp-wrong"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("sftp");
    }

    @Test
    void missingCredentialId_throwsNoRetry() {
        URI uri = sftpUri("/file.txt");
        assertThatThrownBy(() -> fs.read(uri, null))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("credentialId");
        assertThatThrownBy(() -> fs.read(uri, ""))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("credentialId");
    }

    @Test
    void credentialIdNotInVault_throwsNoRetry() {
        URI uri = sftpUri("/file.txt");
        assertThatThrownBy(() -> fs.read(uri, "nonexistent"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("not found in vault");
    }

    @Test
    void wrongCredentialType_throwsNoRetry() {
        // http_bearer 같은 SFTP 외 타입을 credentialId로 주면 거부
        credentialResolver.put("not-sftp",
                new CredentialResolver.Resolved(
                        "not-sftp", "http_bearer", "xoxb-token", Map.of()));

        URI uri = sftpUri("/file.txt");
        assertThatThrownBy(() -> fs.read(uri, "not-sftp"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("credential type not supported");
    }

    // ============ known_hosts ============

    @Test
    void emptyKnownHosts_rejectsAllConnections() {
        // 빈 known_hosts 파일로 새 SftpFileSystem 생성
        SftpFileSystem fsNoHosts = new SftpFileSystem(
                "", 5000L, 5000L, credentialResolver);
        credentialResolver.put("sftp-prod",
                new CredentialResolver.Resolved(
                        "sftp-prod", "sftp_password", TEST_PASSWORD, Map.of()));

        URI uri = sftpUri("/file.txt");
        assertThatThrownBy(() -> fsNoHosts.read(uri, "sftp-prod"))
                .isInstanceOf(NoRetryException.class);
    }

    @Test
    void wrongKnownHosts_rejectsConnection() throws IOException {
        // 잘못된 fingerprint를 known_hosts에 박음 — 실제 서버 키와 불일치
        Path badKnownHosts = tempDir.resolve("bad_known_hosts");
        Files.writeString(badKnownHosts,
                "[127.0.0.1]:" + sshPort + " ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQClearlyFakeKey==\n",
                StandardCharsets.UTF_8);
        SftpFileSystem fsBadHosts = new SftpFileSystem(
                badKnownHosts.toString(), 5000L, 5000L, credentialResolver);
        credentialResolver.put("sftp-prod",
                new CredentialResolver.Resolved(
                        "sftp-prod", "sftp_password", TEST_PASSWORD, Map.of()));

        URI uri = sftpUri("/file.txt");
        assertThatThrownBy(() -> fsBadHosts.read(uri, "sftp-prod"))
                .isInstanceOf(NoRetryException.class);
    }

    // ============ URI 검증 ============

    @Test
    void missingHost_throwsNoRetry() {
        credentialResolver.put("sftp-prod",
                new CredentialResolver.Resolved(
                        "sftp-prod", "sftp_password", TEST_PASSWORD, Map.of()));
        URI uri = URI.create("sftp:///just/path");
        assertThatThrownBy(() -> fs.read(uri, "sftp-prod"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("host");
    }

    @Test
    void missingUserInUri_usesSchemaUsername() throws IOException {
        // URI에 user 없고 schema.username 있는 케이스
        Map<String, Object> schema = new HashMap<>();
        schema.put("username", TEST_USER);
        credentialResolver.put("sftp-with-user",
                new CredentialResolver.Resolved(
                        "sftp-with-user", "sftp_password", TEST_PASSWORD, schema));

        URI uri = URI.create("sftp://127.0.0.1:" + sshPort + "/schema-user.txt");
        fs.write(uri, "from-schema-user".getBytes(StandardCharsets.UTF_8), "sftp-with-user");
        assertThat(Files.readString(sftpRoot.resolve("schema-user.txt")))
                .isEqualTo("from-schema-user");
    }

    @Test
    void missingUserEverywhere_throwsNoRetry() {
        credentialResolver.put("sftp-noUser",
                new CredentialResolver.Resolved(
                        "sftp-noUser", "sftp_password", TEST_PASSWORD, Map.of()));
        URI uri = URI.create("sftp://127.0.0.1:" + sshPort + "/file.txt");
        assertThatThrownBy(() -> fs.read(uri, "sftp-noUser"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("username");
    }

    // ============ supports ============

    @Test
    void supports_sftpScheme() {
        assertThat(fs.supports(URI.create("sftp://host/path"))).isTrue();
        assertThat(fs.supports(URI.create("SFTP://host/path"))).isTrue();
        assertThat(fs.supports(URI.create("file:///path"))).isFalse();
        assertThat(fs.supports(URI.create("s3://bucket/key"))).isFalse();
    }

    // ============ fixtures ============

    /** TEST_USER + TEST_PASSWORD만 통과. */
    private static class TestPasswordAuthenticator implements PasswordAuthenticator {
        @Override
        public boolean authenticate(String username, String password, ServerSession session) {
            return TEST_USER.equals(username) && TEST_PASSWORD.equals(password);
        }
    }

    /** Mockito 없이 in-memory stub. M18 HttpRequestActivityTest와 같은 패턴. */
    private static final class StubCredentialResolver extends CredentialResolver {
        private final Map<String, Resolved> store = new HashMap<>();

        StubCredentialResolver(JsonUtil jsonUtil) {
            super(null, null, jsonUtil);
        }

        void put(String name, Resolved r) {
            store.put(name, r);
        }

        @Override
        public Resolved resolveByName(String name) {
            return store.get(name);
        }
    }
}
