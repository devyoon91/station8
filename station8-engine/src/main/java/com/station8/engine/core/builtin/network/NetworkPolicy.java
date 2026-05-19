package com.station8.engine.core.builtin.network;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 외부 HTTP 호출 SSRF 방어 layer.
 *
 * <p>{@code HttpRequestActivity}가 실제 호출 직전에 {@link #check(URI)}를 호출한다. 위반이면
 * {@link NetworkPolicyViolationException}을 던져 활동을 즉시 final-fail시킨다. 표현식 평가
 * 후의 final URL을 검사하므로 동적 URL 시나리오에서도 동작.</p>
 *
 * <h3>모드 — 부팅 시 결정</h3>
 * <ul>
 *   <li>{@code permissive} ({@code station8.http.policy=permissive}) — 모든 검증 off. 운영 환경 금지</li>
 *   <li>{@code allowlist} ({@code station8.http.allowlist=host1,host2,...} 비어있지 않음) —
 *       명시된 host만 통과. blocklist 무시. 폐쇄망 사이트에서 사내 API 몇 개만 노출하는 시나리오</li>
 *   <li>{@code blocklist} (default) — 아래 default blocklist 적용</li>
 * </ul>
 *
 * <h3>default blocklist (해소된 IP 기준)</h3>
 * <ul>
 *   <li>loopback ({@code 127/8}, {@code ::1})</li>
 *   <li>link-local ({@code 169.254/16}, {@code fe80::/10}) — AWS/GCP/Azure metadata 포함</li>
 *   <li>RFC1918 private ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) —
 *       {@code station8.http.allow-private=true}로 해제 가능</li>
 *   <li>multicast ({@code 224/4}, {@code ff00::/8})</li>
 *   <li>any-cast ({@code 0.0.0.0}, {@code ::})</li>
 *   <li>broadcast ({@code 255.255.255.255})</li>
 *   <li>IPv6 ULA ({@code fc00::/7})</li>
 * </ul>
 *
 * <h3>DNS rebinding 차단</h3>
 * 공개 host가 내부 IP로 resolve되는 공격을 막기 위해 {@link InetAddress#getAllByName} 결과
 * <em>전체</em>를 검사한다. 한 개라도 차단 카테고리에 걸리면 위반. 단, 검사 시점과 실제 HTTP
 * 호출 시점 사이에 DNS 응답이 바뀌는 TOCTOU(time-of-check / time-of-use) 잔여 위험은 존재 —
 * 완전 차단은 호출 시점에 IP를 pin해야 하지만 본 sub-issue 비범위. 별도 follow-up.
 *
 * <h3>호출하지 않는 곳</h3>
 * 본 정책은 활동이 명시적으로 부르는 URL만 검사. HttpClient의 자동 redirect({@code Redirect.NORMAL})
 * 는 OS 레벨 DNS 해소를 거치므로 우회 가능 — {@code HttpRequestActivity}가 redirect 정책을
 * 강화하는 follow-up이 필요할 수 있다.
 */
@Component
public class NetworkPolicy {

    private static final Logger log = LoggerFactory.getLogger(NetworkPolicy.class);

    /** 본 정책이 평가하는 모드. */
    public enum Mode { BLOCKLIST, ALLOWLIST, PERMISSIVE }

    private final String policyProperty;
    private final String allowlistProperty;
    private final boolean allowPrivate;
    private final HostResolver resolver;

    private Mode mode;
    private Set<String> allowlist;  // lowercase, exact host match

    /** 운영 코드 — JDK DNS 해소 사용. Spring이 두 생성자 중 본 것을 픽업하도록 @Autowired. */
    @Autowired
    public NetworkPolicy(@Value("${station8.http.policy:}") String policyProperty,
                         @Value("${station8.http.allowlist:}") String allowlistProperty,
                         @Value("${station8.http.allow-private:false}") boolean allowPrivate) {
        this(policyProperty, allowlistProperty, allowPrivate, HostResolver.DEFAULT);
    }

    /**
     * 테스트 / 임베디드용 — HostResolver를 직접 주입. Spring이 부르는 운영 생성자와 동일한
     * 초기화이되 DNS 의존을 stub으로 갈아끼울 수 있다.
     */
    public NetworkPolicy(String policyProperty,
                         String allowlistProperty,
                         boolean allowPrivate,
                         HostResolver resolver) {
        this.policyProperty = policyProperty == null ? "" : policyProperty.trim().toLowerCase(Locale.ROOT);
        this.allowlistProperty = allowlistProperty == null ? "" : allowlistProperty.trim();
        this.allowPrivate = allowPrivate;
        this.resolver = resolver;
    }

    /**
     * 부팅 시 mode 결정 + INFO 로그. 우선순위:
     * <ol>
     *   <li>{@code station8.http.policy=permissive} → PERMISSIVE</li>
     *   <li>{@code station8.http.allowlist} 비어있지 않음 → ALLOWLIST</li>
     *   <li>그 외 → BLOCKLIST (default)</li>
     * </ol>
     *
     * <p>Spring {@code @PostConstruct}가 자동 호출하지만 멱등하므로 테스트에서 명시 호출도 안전.</p>
     */
    @PostConstruct
    public void init() {
        Set<String> parsedAllowlist = parseAllowlist(allowlistProperty);
        if ("permissive".equals(policyProperty)) {
            this.mode = Mode.PERMISSIVE;
        } else if (!parsedAllowlist.isEmpty()) {
            this.mode = Mode.ALLOWLIST;
        } else {
            this.mode = Mode.BLOCKLIST;
        }
        this.allowlist = Collections.unmodifiableSet(parsedAllowlist);
        log.info("NetworkPolicy: mode={}, allow-private={}, allowlist={}",
                mode, allowPrivate, allowlist.isEmpty() ? "(empty)" : allowlist);
    }

    /** 현재 평가 mode. 테스트/디버그용. */
    public Mode mode() {
        return mode;
    }

    /**
     * URI 검증 — 위반 시 {@link NetworkPolicyViolationException}.
     *
     * @param uri 표현식 평가 후의 final URI (호출 직전)
     */
    public void check(URI uri) {
        if (mode == Mode.PERMISSIVE) {
            return;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new NetworkPolicyViolationException("URI has no host: " + uri);
        }
        String hostLower = host.toLowerCase(Locale.ROOT);

        if (mode == Mode.ALLOWLIST) {
            if (!allowlist.contains(hostLower)) {
                throw new NetworkPolicyViolationException(
                        "Host '" + hostLower + "' is not in allowlist");
            }
            // allowlist 통과 — IP 검사 안 함 (운영자가 명시적으로 신뢰한 host)
            return;
        }

        // BLOCKLIST: 해소된 IP 전체 검사 (DNS rebinding 방어)
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            throw new NetworkPolicyViolationException(
                    "Host '" + hostLower + "' cannot be resolved: " + ex.getMessage());
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new NetworkPolicyViolationException(
                    "Host '" + hostLower + "' resolved to no addresses");
        }
        for (InetAddress addr : addresses) {
            String reason = classifyBlocked(addr, allowPrivate);
            if (reason != null) {
                throw new NetworkPolicyViolationException(
                        "Host '" + hostLower + "' resolved to blocked address (" + reason
                                + "): " + addr.getHostAddress());
            }
        }
    }

    /** 차단 대상이면 카테고리 이름 반환, 아니면 null. */
    private static String classifyBlocked(InetAddress addr, boolean allowPrivate) {
        if (addr.isLoopbackAddress()) {
            return "loopback";
        }
        if (addr.isAnyLocalAddress()) {
            return "any-local";
        }
        if (addr.isLinkLocalAddress()) {
            return "link-local";
        }
        if (addr.isMulticastAddress()) {
            return "multicast";
        }
        if (!allowPrivate && addr.isSiteLocalAddress()) {
            return "site-local (RFC1918)";
        }
        if (addr instanceof Inet4Address) {
            byte[] b = addr.getAddress();
            if ((b[0] & 0xff) == 255 && (b[1] & 0xff) == 255
                    && (b[2] & 0xff) == 255 && (b[3] & 0xff) == 255) {
                return "broadcast";
            }
        }
        if (addr instanceof Inet6Address) {
            byte[] b = addr.getAddress();
            // ULA: fc00::/7 → 첫 byte 상위 7비트가 1111110_
            if ((b[0] & 0xfe) == 0xfc) {
                return "ipv6-ula";
            }
        }
        return null;
    }

    private static Set<String> parseAllowlist(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .forEach(out::add);
        return out;
    }
}
