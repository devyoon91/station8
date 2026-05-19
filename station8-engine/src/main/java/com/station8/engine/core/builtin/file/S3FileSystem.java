package com.station8.engine.core.builtin.file;

import com.station8.engine.core.CredentialResolver;
import com.station8.engine.core.NoRetryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * S3 backend — `s3://` scheme. AWS SDK v2 위 얇은 wrapper.
 *
 * <p>S3-compatible(MinIO, Ceph RadosGW 등)도 같은 코드로 cover — credential schema의
 * {@code endpoint} 를 사이트 S3 endpoint로 override하면 된다.</p>
 *
 * <h3>URI 형식</h3>
 * <pre>{@code s3://bucket/key/path}</pre>
 *
 * <p>{@code bucket}은 URI host, {@code key}는 path (leading slash 제거). 인증은 활동 입력의
 * {@code credentialId}로 vault에서 조회 — URI에 access key 절대 박지 말 것.</p>
 *
 * <h3>credential type</h3>
 * <table>
 *   <tr><th>type</th><th>schema</th><th>인증</th></tr>
 *   <tr><td>{@code s3_access_key}</td>
 *       <td>{@code {"accessKeyId":"AKIA...","endpoint":"https://minio.internal:9000","region":"us-east-1","pathStyle":true}}</td>
 *       <td>credential value = SecretAccessKey. schema.accessKeyId = Access Key ID. endpoint/region/pathStyle 선택</td></tr>
 * </table>
 *
 * <p>{@code endpoint} 미지정 시 AWS S3 표준 endpoint(region 기반)로 라우팅 — AWS 직접 호출 시나리오.
 * MinIO / Ceph 같은 self-hosted는 반드시 endpoint 명시 + 보통 {@code pathStyle=true} (virtual-host
 * style 비호환).</p>
 *
 * <h3>스레드 안전성 / lifecycle</h3>
 * S3Client는 thread-safe하고 무거우므로 활동 호출마다 새로 만들지 말고 credential별로 캐싱하는 게
 * 정석이지만, 본 sub-issue 1차 구현은 호출 단위 생성(close 포함). pooling은 follow-up.
 *
 * <h3>IAM instance profile / Pod identity 자동 인증</h3>
 * 본 sub-issue 비범위 — 폐쇄망 default 가정상 explicit access key가 우선. AWS EC2 / EKS Pod Identity
 * 자동 credential은 별도 follow-up.
 */
@Component
public class S3FileSystem implements FileSystem {

    private static final Logger log = LoggerFactory.getLogger(S3FileSystem.class);

    private final CredentialResolver credentialResolver;

    public S3FileSystem(CredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    @Override
    public boolean supports(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && "s3".equalsIgnoreCase(scheme);
    }

    @Override
    public byte[] read(URI uri, String credentialId) {
        S3Target target = parseTarget(uri);
        CredentialResolver.Resolved cred = resolveCredential(credentialId);
        try (S3Client client = buildClient(cred)) {
            try {
                return client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(target.bucket())
                        .key(target.key())
                        .build()).asByteArray();
            } catch (NoSuchBucketException | NoSuchKeyException ex) {
                throw new NoRetryException("s3 object not found: " + uri + " — " + ex.getMessage(), ex);
            } catch (S3Exception ex) {
                throw classifyS3Exception(ex, "read", uri);
            } catch (SdkClientException ex) {
                // 네트워크 일시 장애 등 — retry 대상
                throw new RuntimeException("s3 client failure on read " + uri + ": " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void write(URI uri, byte[] content, String credentialId) {
        S3Target target = parseTarget(uri);
        CredentialResolver.Resolved cred = resolveCredential(credentialId);
        try (S3Client client = buildClient(cred)) {
            try {
                client.putObject(PutObjectRequest.builder()
                                .bucket(target.bucket())
                                .key(target.key())
                                .build(),
                        RequestBody.fromBytes(content == null ? new byte[0] : content));
            } catch (NoSuchBucketException ex) {
                throw new NoRetryException("s3 bucket not found: " + target.bucket(), ex);
            } catch (S3Exception ex) {
                throw classifyS3Exception(ex, "write", uri);
            } catch (SdkClientException ex) {
                throw new RuntimeException("s3 client failure on write " + uri + ": " + ex.getMessage(), ex);
            }
        }
    }

    private static S3Target parseTarget(URI uri) {
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new NoRetryException("s3 URI must have bucket (host): " + uri);
        }
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.equals("/")) {
            throw new NoRetryException("s3 URI must have key (path): " + uri);
        }
        // leading slash 제거 — S3 key는 / 로 시작하지 않음
        String key = path.startsWith("/") ? path.substring(1) : path;
        return new S3Target(uri.getHost(), key);
    }

    private CredentialResolver.Resolved resolveCredential(String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            throw new NoRetryException(
                    "s3 activity requires credentialId (vault entry of type s3_access_key)");
        }
        CredentialResolver.Resolved cred = credentialResolver.resolveByName(credentialId);
        if (cred == null) {
            throw new NoRetryException("s3 credentialId not found in vault: " + credentialId);
        }
        if (!"s3_access_key".equals(cred.type())) {
            throw new NoRetryException(
                    "s3 credential type not supported: " + cred.type()
                            + " (allowed: s3_access_key)");
        }
        return cred;
    }

    /** schema 기반 S3Client 생성. endpoint/region/pathStyle 모두 선택 (없으면 AWS default). */
    private S3Client buildClient(CredentialResolver.Resolved cred) {
        Map<String, Object> schema = cred.schema();
        Object accessKeyId = schema.get("accessKeyId");
        if (accessKeyId == null) {
            throw new NoRetryException(
                    "s3_access_key credential '" + cred.name() + "' missing schema.accessKeyId");
        }
        AwsBasicCredentials creds = AwsBasicCredentials.create(
                accessKeyId.toString(), cred.value());

        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(creds));

        Object region = schema.get("region");
        builder.region(region == null ? Region.US_EAST_1 : Region.of(region.toString()));

        Object endpoint = schema.get("endpoint");
        if (endpoint != null && !endpoint.toString().isBlank()) {
            try {
                builder.endpointOverride(URI.create(endpoint.toString()));
            } catch (IllegalArgumentException ex) {
                throw new NoRetryException(
                        "s3_access_key credential '" + cred.name() + "' schema.endpoint malformed: "
                                + endpoint, ex);
            }
        }

        Object pathStyle = schema.get("pathStyle");
        if (pathStyle instanceof Boolean b && b) {
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
        }
        return builder.build();
    }

    /** S3Exception에서 HTTP status로 retry 여부 분기. 4xx는 NoRetry, 5xx/timeout은 retry. */
    private static RuntimeException classifyS3Exception(S3Exception ex, String op, URI uri) {
        int status = ex.statusCode();
        if (status >= 400 && status < 500) {
            return new NoRetryException(
                    "s3 " + op + " HTTP " + status + " on " + uri + ": " + safeErrorMessage(ex), ex);
        }
        if (status >= 500) {
            return new RuntimeException(
                    "s3 " + op + " HTTP " + status + " on " + uri + " — retryable", ex);
        }
        // 분류 불가 — 안전하게 retry
        return new RuntimeException("s3 " + op + " failure on " + uri + ": " + ex.getMessage(), ex);
    }

    /** S3 error 메시지에 access key / 평문이 들어갈 일은 적지만 message만 노출. */
    private static String safeErrorMessage(SdkException ex) {
        return Objects.requireNonNullElse(ex.getMessage(), ex.getClass().getSimpleName());
    }

    private record S3Target(String bucket, String key) {}
}
