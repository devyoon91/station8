package com.station8.app.controller;

import com.station8.engine.exception.LineEngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #174 — 전역 REST 예외 매핑. 모든 {@code @RestController} / {@code @ResponseBody} 응답에서
 * 던진 예외를 표준 {@link ErrorResponse} 포맷으로 변환.
 *
 * <p>이전 패턴 (controller-level {@code @ExceptionHandler})은 두 군데 중복 +
 * 다른 컨트롤러는 default Spring 응답이라 일관성 없었음 — 본 클래스로 통합.</p>
 *
 * <h3>매핑 규칙</h3>
 * <ul>
 *   <li>{@link LineEngineException} → 400 + {@code errorCode} 보존</li>
 *   <li>{@link IllegalArgumentException} → 400</li>
 *   <li>{@link AccessDeniedException} → 403 (Spring Security가 직접 401/403을 던지므로 정상 매핑)</li>
 *   <li>{@link MethodArgumentNotValidException} → 400 + 필드별 에러 details
 *       ({@code @Valid} 도입 #175 후 활성)</li>
 * </ul>
 *
 * <p>Mustache view 컨트롤러는 자체 redirect/flash 패턴을 사용하므로 본 advice 영향 없음 —
 * {@code @ResponseBody}/{@code @RestController}만 매칭.</p>
 */
@RestControllerAdvice
public class GlobalRestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalRestExceptionHandler.class);

    @ExceptionHandler(LineEngineException.class)
    public ResponseEntity<ErrorResponse> handleEngine(LineEngineException ex) {
        log.warn("LineEngineException — code={}, message={}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        log.debug("IllegalArgumentException — {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(AccessDeniedException ex) {
        log.info("AccessDenied — {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("ACCESS_DENIED", "권한이 없습니다."));
    }

    /**
     * Bean Validation (#175) 활성화 시 사용. 필드별 에러를 {@code details}에 묶어 단일 응답으로.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            // 같은 필드 여러 메시지면 첫 메시지만 — Bean Validation 충돌 방지
            fieldErrors.putIfAbsent(fe.getField(),
                    fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage());
        }
        log.debug("MethodArgumentNotValid — fields={}", fieldErrors.keySet());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("VALIDATION_FAILED",
                        "요청 검증 실패: " + fieldErrors.size() + "개 필드", fieldErrors));
    }
}
