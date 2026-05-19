package com.station8.engine.core;

/**
 * 활동이 던지면 엔진이 재시도하지 않고 즉시 FAILED로 격하시키는 marker 예외.
 *
 * <p>일반 {@link RuntimeException}은 {@code @Activity(retryCount=N)} 만큼 재시도 후 DLQ로
 * 이동한다. 그런데 본질적으로 재시도해도 결과가 같은 실패가 있다 — HTTP 4xx, 입력 검증 오류,
 * 잘못된 자격증명 등. 이런 경우엔 재시도 비용만 늘 뿐 의미가 없어서 즉시 final-fail 시킨다.</p>
 *
 * <p>{@link ActivityProcessor#handleFailure}가 cause 타입을 검사해 분기한다. 본 예외를 던지면
 * {@code retryPolicy.isExceeded} 결과와 무관하게 final-fail 경로(DLQ + onFailure 정책)로 진입한다.</p>
 *
 * <h3>사용 예</h3>
 * <pre>{@code
 *   if (response.statusCode() >= 400 && response.statusCode() < 500) {
 *       throw new NoRetryException("HTTP 4xx — client error, will not retry", null);
 *   }
 * }</pre>
 */
public class NoRetryException extends RuntimeException {

    public NoRetryException(String message) {
        super(message);
    }

    public NoRetryException(String message, Throwable cause) {
        super(message, cause);
    }
}
