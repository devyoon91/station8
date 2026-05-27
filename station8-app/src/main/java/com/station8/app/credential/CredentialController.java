package com.station8.app.credential;

import com.station8.engine.crypto.CredentialCrypto;
import com.station8.engine.entity.Credential;
import com.station8.engine.repository.CredentialRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * M17 (#271) — credential vault REST CRUD.
 *
 * <ul>
 *   <li>{@code POST   /api/line/credentials}           — 생성 (ADMIN) — value 즉시 암호화 후 저장</li>
 *   <li>{@code GET    /api/line/credentials}           — 목록 (인증 USER) — value 절대 없음</li>
 *   <li>{@code GET    /api/line/credentials/{id}}      — 단건 (인증 USER) — value 없음</li>
 *   <li>{@code PUT    /api/line/credentials/{id}}      — 갱신 (ADMIN) — value optional (있으면 rotate)</li>
 *   <li>{@code DELETE /api/line/credentials/{id}}      — soft delete (ADMIN)</li>
 * </ul>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>응답 직렬화에 {@code valueEnc}/{@code value} 절대 없음 — {@link CredentialResponse}는
 *       메타만 노출하는 record</li>
 *   <li>type은 화이트리스트 검증 — 모르는 type은 400</li>
 *   <li>ADMIN role만 쓰기 (POST/PUT/DELETE) — 일반 USER는 read-only</li>
 * </ul>
 *
 * <p>예외는 {@code GlobalRestExceptionHandler}가 표준 {@code ErrorResponse}로 변환.</p>
 */
@RestController
@RequestMapping("/api/line/credentials")
public class CredentialController {

    /**
     * 초기 type 화이트리스트. 미지원 type은 400.
     *
     * <ul>
     *   <li>{@code http_basic} / {@code http_bearer} / {@code api_key} / {@code generic} — M17 (#270)</li>
     *   <li>{@code sftp_password} / {@code sftp_key} — M19 SFTP backend (#296)</li>
     *   <li>{@code s3_access_key} — M19 S3 backend (#297)</li>
     *   <li>{@code webhook_hmac} — M20 webhook trigger (#310). value: HMAC secret</li>
     *   <li>{@code openai_compatible} — M23 LLM (#339). value: apiKey, schema.baseUrl: endpoint</li>
     * </ul>
     */
    static final Set<String> SUPPORTED_TYPES = Set.of(
            "http_basic", "http_bearer", "api_key", "generic",
            "sftp_password", "sftp_key",
            "s3_access_key",
            "webhook_hmac",
            "openai_compatible");

    private final CredentialRepository repository;
    private final CredentialCrypto crypto;

    public CredentialController(CredentialRepository repository, CredentialCrypto crypto) {
        this.repository = repository;
        this.crypto = crypto;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CredentialResponse> create(@Valid @RequestBody CredentialRequest req,
                                                     Authentication auth) {
        validateType(req.type());
        if (req.value() == null || req.value().isEmpty()) {
            throw new IllegalArgumentException("value is required on create");
        }

        String id = UUID.randomUUID().toString();
        String valueEnc = crypto.encrypt(req.value());
        Credential c = new Credential(
                id, req.name(), req.type(), valueEnc, req.schemaJson(),
                "N",
                LocalDateTime.now(), auth != null ? auth.getName() : "system",
                null, null);
        try {
            repository.insert(c);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException(
                    "credential name already exists: " + req.name());
        }
        Credential saved = repository.findById(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(CredentialResponse.from(saved));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<CredentialResponse> list() {
        return repository.findAllActive().stream().map(CredentialResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CredentialResponse> get(@PathVariable("id") String id) {
        Credential c = repository.findById(id);
        if (c == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(CredentialResponse.from(c));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CredentialResponse> update(@PathVariable("id") String id,
                                                     @Valid @RequestBody CredentialRequest req,
                                                     Authentication auth) {
        validateType(req.type());
        Credential existing = repository.findById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        // value 있으면 rotate, 없으면 기존 ciphertext 유지
        String valueEnc = (req.value() == null || req.value().isEmpty())
                ? existing.valueEnc()
                : crypto.encrypt(req.value());

        Credential updated = new Credential(
                id, req.name(), req.type(), valueEnc, req.schemaJson(),
                existing.delFl(),
                existing.regDt(), existing.regId(),
                LocalDateTime.now(), auth != null ? auth.getName() : "system");
        try {
            repository.update(updated);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException(
                    "credential name conflict: " + req.name());
        }
        return ResponseEntity.ok(CredentialResponse.from(repository.findById(id)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable("id") String id,
                                                      Authentication auth) {
        if (repository.findById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        repository.softDelete(id, auth != null ? auth.getName() : "system");
        return ResponseEntity.ok(Map.of("id", id, "status", "deleted"));
    }

    private static void validateType(String type) {
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "unsupported credential type: " + type +
                    " (supported: " + SUPPORTED_TYPES + ")");
        }
    }
}
