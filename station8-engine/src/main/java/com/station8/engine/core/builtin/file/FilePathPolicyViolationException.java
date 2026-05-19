package com.station8.engine.core.builtin.file;

import com.station8.engine.core.NoRetryException;

/**
 * 파일 path가 {@link FilePathPolicy}에 의해 차단됐을 때 던지는 예외.
 *
 * <p>{@link NoRetryException} 상속 — 정책 위반은 재시도해도 같은 결과라 엔진의 retry 정책을
 * 무시하고 즉시 final-fail로 격하시킨다.</p>
 *
 * <p>메시지에 실제 파일 내용이나 credential을 절대 포함하지 말 것. 위반 카테고리(allow-roots
 * 미설정 / outside-roots / path-traversal 등)와 정규화된 path 정도만.</p>
 */
public class FilePathPolicyViolationException extends NoRetryException {

    public FilePathPolicyViolationException(String message) {
        super(message);
    }
}
