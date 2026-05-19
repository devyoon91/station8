package com.station8.engine.core.builtin.network;

import com.station8.engine.core.NoRetryException;

/**
 * URL이 네트워크 정책({@link NetworkPolicy})에 의해 차단됐을 때 던지는 예외.
 *
 * <p>{@link NoRetryException}을 상속한다 — 정책 위반은 재시도해도 결과가 같으므로
 * 엔진의 retry 정책을 무시하고 즉시 final-fail로 격하시켜야 한다.</p>
 *
 * <p>메시지에 평문/credential 등 민감 정보를 절대 포함하지 말 것 — 정책 위반이라는
 * 사실 + 어떤 카테고리(loopback / link-local / allowlist mismatch 등) 정도만 노출.</p>
 */
public class NetworkPolicyViolationException extends NoRetryException {

    public NetworkPolicyViolationException(String message) {
        super(message);
    }
}
