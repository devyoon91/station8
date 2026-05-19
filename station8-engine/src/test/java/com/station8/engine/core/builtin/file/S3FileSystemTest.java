package com.station8.engine.core.builtin.file;

import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.NoRetryException;
import com.station8.engine.util.JsonUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #297 — S3FileSystem 회귀 가드. testcontainers MinIOContainer로 진짜 S3-호환 endpoint를
 * 띄워서 round-trip 검증. 외부 AWS 의존 0.
 *
 * <p>MinIOContainer는 처음 한 번만 띄우고 ({@code @BeforeAll}) 모든 테스트가 공유 — 테스트 시간
 * 절약.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3FileSystemTest {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String BUCKET = "station8-test";

    private MinIOContainer minio;
    private String endpoint;

    private JsonUtil jsonUtil;
    private StubCredentialResolver credentialResolver;
    private S3FileSystem fs;

    @BeforeAll
    void startMinio() {
        // Docker 없는 환경(local dev, CI without Docker)에서는 전체 테스트 graceful skip.
        // e2e-tests 모듈의 -PdockerHost 게이트와 같은 패턴.
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker 환경 미감지 — S3 통합 테스트 skip (testcontainers MinIO 필요)");

        minio = new MinIOContainer("minio/minio:RELEASE.2024-10-13T13-34-11Z")
                .withUserName(ACCESS_KEY)
                .withPassword(SECRET_KEY);
        minio.start();
        endpoint = minio.getS3URL();

        // 테스트용 bucket 미리 생성
        try (S3Client admin = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true).build())
                .build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @AfterAll
    void stopMinio() {
        if (minio != null) {
            minio.stop();
        }
    }

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        credentialResolver = new StubCredentialResolver(jsonUtil);
        fs = new S3FileSystem(credentialResolver);
    }

    /** schema에 endpoint/region/pathStyle/accessKeyId 다 박힌 standard credential. */
    private CredentialResolver.Resolved validCred(String name) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("accessKeyId", ACCESS_KEY);
        schema.put("endpoint", endpoint);
        schema.put("region", "us-east-1");
        schema.put("pathStyle", true);
        return new CredentialResolver.Resolved(name, "s3_access_key", SECRET_KEY, schema);
    }

    // ============ round-trip ============

    @Test
    void writeRead_roundTrip() {
        credentialResolver.put("minio-test", validCred("minio-test"));

        URI uri = URI.create("s3://" + BUCKET + "/hello.txt");
        fs.write(uri, "Hello, S3 world!".getBytes(StandardCharsets.UTF_8), "minio-test");

        byte[] read = fs.read(uri, "minio-test");
        assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("Hello, S3 world!");
    }

    @Test
    void write_overwritesExistingObject() {
        credentialResolver.put("minio-test", validCred("minio-test"));

        URI uri = URI.create("s3://" + BUCKET + "/overwrite.txt");
        fs.write(uri, "first".getBytes(StandardCharsets.UTF_8), "minio-test");
        fs.write(uri, "second".getBytes(StandardCharsets.UTF_8), "minio-test");

        byte[] read = fs.read(uri, "minio-test");
        assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("second");
    }

    @Test
    void write_emptyContent_createsZeroByteObject() {
        credentialResolver.put("minio-test", validCred("minio-test"));

        URI uri = URI.create("s3://" + BUCKET + "/empty.txt");
        fs.write(uri, new byte[0], "minio-test");

        byte[] read = fs.read(uri, "minio-test");
        assertThat(read).isEmpty();
    }

    @Test
    void write_nullContent_treatedAsEmpty() {
        credentialResolver.put("minio-test", validCred("minio-test"));

        URI uri = URI.create("s3://" + BUCKET + "/null.txt");
        fs.write(uri, null, "minio-test");

        byte[] read = fs.read(uri, "minio-test");
        assertThat(read).isEmpty();
    }

    @Test
    void write_keyWithDeepPath() {
        credentialResolver.put("minio-test", validCred("minio-test"));

        URI uri = URI.create("s3://" + BUCKET + "/nested/deeper/object.txt");
        fs.write(uri, "deep".getBytes(StandardCharsets.UTF_8), "minio-test");

        byte[] read = fs.read(uri, "minio-test");
        assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("deep");
    }

    // ============ 객체 / bucket 부재 ============

    @Test
    void read_nonexistentKey_throwsNoRetry() {
        credentialResolver.put("minio-test", validCred("minio-test"));

        URI uri = URI.create("s3://" + BUCKET + "/does-not-exist.txt");
        assertThatThrownBy(() -> fs.read(uri, "minio-test"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void read_nonexistentBucket_throwsNoRetry() {
        credentialResolver.put("minio-test", validCred("minio-test"));

        URI uri = URI.create("s3://nonexistent-bucket-xyz/file.txt");
        assertThatThrownBy(() -> fs.read(uri, "minio-test"))
                .isInstanceOf(NoRetryException.class);
    }

    // ============ credential 검증 ============

    @Test
    void wrongSecretKey_throwsNoRetry() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("accessKeyId", ACCESS_KEY);
        schema.put("endpoint", endpoint);
        schema.put("region", "us-east-1");
        schema.put("pathStyle", true);
        credentialResolver.put("bad-creds",
                new CredentialResolver.Resolved(
                        "bad-creds", "s3_access_key", "wrong-secret", schema));

        URI uri = URI.create("s3://" + BUCKET + "/x.txt");
        assertThatThrownBy(() -> fs.read(uri, "bad-creds"))
                .isInstanceOf(NoRetryException.class);
    }

    @Test
    void missingCredentialId_throwsNoRetry() {
        URI uri = URI.create("s3://" + BUCKET + "/x.txt");
        assertThatThrownBy(() -> fs.read(uri, null))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("credentialId");
    }

    @Test
    void credentialIdNotInVault_throwsNoRetry() {
        URI uri = URI.create("s3://" + BUCKET + "/x.txt");
        assertThatThrownBy(() -> fs.read(uri, "nonexistent"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("not found in vault");
    }

    @Test
    void wrongCredentialType_throwsNoRetry() {
        credentialResolver.put("not-s3",
                new CredentialResolver.Resolved(
                        "not-s3", "http_bearer", "token", Map.of()));

        URI uri = URI.create("s3://" + BUCKET + "/x.txt");
        assertThatThrownBy(() -> fs.read(uri, "not-s3"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("credential type not supported");
    }

    @Test
    void credentialMissingAccessKeyId_throwsNoRetry() {
        // schema에 accessKeyId 안 박힌 케이스
        Map<String, Object> schema = new HashMap<>();
        schema.put("endpoint", endpoint);
        schema.put("region", "us-east-1");
        credentialResolver.put("no-akid",
                new CredentialResolver.Resolved(
                        "no-akid", "s3_access_key", SECRET_KEY, schema));

        URI uri = URI.create("s3://" + BUCKET + "/x.txt");
        assertThatThrownBy(() -> fs.read(uri, "no-akid"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("accessKeyId");
    }

    // ============ URI 검증 ============

    @Test
    void uriMissingBucket_throwsNoRetry() {
        credentialResolver.put("minio-test", validCred("minio-test"));
        URI uri = URI.create("s3:///just-key");
        assertThatThrownBy(() -> fs.read(uri, "minio-test"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void uriMissingKey_throwsNoRetry() {
        credentialResolver.put("minio-test", validCred("minio-test"));
        URI uri = URI.create("s3://" + BUCKET);
        assertThatThrownBy(() -> fs.read(uri, "minio-test"))
                .isInstanceOf(NoRetryException.class)
                .hasMessageContaining("key");
    }

    // ============ supports ============

    @Test
    void supports_s3Scheme() {
        assertThat(fs.supports(URI.create("s3://bucket/key"))).isTrue();
        assertThat(fs.supports(URI.create("S3://bucket/key"))).isTrue();
        assertThat(fs.supports(URI.create("file:///path"))).isFalse();
        assertThat(fs.supports(URI.create("sftp://host/path"))).isFalse();
    }

    /** Mockito 없이 in-memory stub. */
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
