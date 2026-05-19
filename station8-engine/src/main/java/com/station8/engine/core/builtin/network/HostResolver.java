package com.station8.engine.core.builtin.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * DNS 해소를 단일 추상 함수로 노출 — {@link NetworkPolicy}가 테스트에서 fake로 갈아끼울 수 있게.
 *
 * <p>운영 코드는 {@link InetAddress#getAllByName}을 그대로 호출. 테스트는 host → IP 매핑을
 * 인메모리 stub으로 대체해서 DNS rebinding 시나리오(공개 host가 내부 IP로 resolve)나 멀티 IP
 * 응답을 재현한다.</p>
 *
 * <p>NetworkPolicy 단독으로 InetAddress.getAllByName을 호출하면 모킹이 어렵고
 * (PowerMock 도입 부담), 실제 DNS lookup이 빌드 환경마다 달라 테스트가 flaky해진다.</p>
 */
public interface HostResolver {

    /**
     * host를 해소해서 모든 IP를 반환한다. 멀티 IP(round-robin DNS)인 경우 전부 포함되며,
     * 호출자({@link NetworkPolicy})는 그 모두에 대해 차단 검사를 수행한다.
     *
     * @param host hostname 또는 IP literal
     * @return 1개 이상의 InetAddress
     * @throws UnknownHostException 해소 실패
     */
    List<InetAddress> resolve(String host) throws UnknownHostException;

    /** JDK 표준 InetAddress.getAllByName을 위임 — 운영 코드 default. */
    HostResolver DEFAULT = host -> Arrays.asList(InetAddress.getAllByName(host));
}
