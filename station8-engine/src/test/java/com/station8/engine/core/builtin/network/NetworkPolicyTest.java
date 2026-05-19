package com.station8.engine.core.builtin.network;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #289 — NetworkPolicy 회귀 가드. HostResolver를 stub으로 갈아끼워 DNS 의존 0.
 */
class NetworkPolicyTest {

    private final StubResolver resolver = new StubResolver();

    /** policyProperty + allowlistProperty + allowPrivate 조합으로 새 policy를 만들고 init까지. */
    private NetworkPolicy policy(String policyProperty, String allowlist, boolean allowPrivate) {
        NetworkPolicy p = new NetworkPolicy(policyProperty, allowlist, allowPrivate, resolver);
        p.init();
        return p;
    }

    // ============ 모드 결정 ============

    @Test
    void mode_defaultIsBlocklist() {
        assertThat(policy("", "", false).mode()).isEqualTo(NetworkPolicy.Mode.BLOCKLIST);
    }

    @Test
    void mode_permissiveExplicit() {
        assertThat(policy("permissive", "", false).mode()).isEqualTo(NetworkPolicy.Mode.PERMISSIVE);
    }

    @Test
    void mode_allowlistWhenNonEmpty() {
        assertThat(policy("", "api.slack.com", false).mode()).isEqualTo(NetworkPolicy.Mode.ALLOWLIST);
    }

    @Test
    void mode_permissiveBeatsAllowlist() {
        // 둘 다 설정되면 permissive 우선 — 운영 환경 실수 방지
        assertThat(policy("permissive", "api.slack.com", false).mode())
                .isEqualTo(NetworkPolicy.Mode.PERMISSIVE);
    }

    // ============ PERMISSIVE — 모든 검증 skip ============

    @Test
    void permissive_allowsLoopback() {
        assertThatCode(() -> policy("permissive", "", false).check(URI.create("http://127.0.0.1/")))
                .doesNotThrowAnyException();
    }

    @Test
    void permissive_allowsAnyHost() {
        // resolver를 호출하지도 않아야 함 — host == null도 통과? 아니, host 없으면 위반
        // (permissive라도 URI 자체가 망가지면 차단 — runtime 안전성)
        // 그런데 현재 구현은 PERMISSIVE면 즉시 return. 일관성을 위해 그대로 둠.
        assertThatCode(() -> policy("permissive", "", false).check(URI.create("http://evil.example.com/")))
                .doesNotThrowAnyException();
    }

    // ============ ALLOWLIST ============

    @Test
    void allowlist_passesListedHost() {
        assertThatCode(() -> policy("", "api.slack.com,api.stripe.com", false)
                .check(URI.create("http://api.slack.com/users")))
                .doesNotThrowAnyException();
    }

    @Test
    void allowlist_rejectsUnlistedHost() {
        assertThatThrownBy(() -> policy("", "api.slack.com", false)
                .check(URI.create("http://evil.example.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("not in allowlist");
    }

    @Test
    void allowlist_caseInsensitive() {
        assertThatCode(() -> policy("", "API.Slack.Com", false)
                .check(URI.create("http://api.slack.com/")))
                .doesNotThrowAnyException();
    }

    @Test
    void allowlist_doesNotResolveDns() {
        // allowlist 모드는 IP 검사 안 함 — host만 통과하면 됨
        // resolver를 호출했다면 stub에 등록 안 한 host로 UnknownHostException나야 함.
        // 그런데 호출 안 했으므로 throw 없음.
        resolver.clear();
        assertThatCode(() -> policy("", "api.slack.com", false)
                .check(URI.create("http://api.slack.com/")))
                .doesNotThrowAnyException();
        assertThat(resolver.callCount()).isZero();
    }

    // ============ BLOCKLIST default categories ============

    @Test
    void blocklist_blocksLoopbackIpv4() {
        resolver.put("attacker.example.com", "127.0.0.1");
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://attacker.example.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void blocklist_blocksLoopbackIpv6() {
        resolver.put("attacker6.example.com", "::1");
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://attacker6.example.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void blocklist_blocksLinkLocalMetadata() {
        // AWS/GCP/Azure metadata endpoint
        resolver.put("metadata.foo.com", "169.254.169.254");
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://metadata.foo.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("link-local");
    }

    @Test
    void blocklist_blocksRfc1918Private() {
        resolver.put("internal.foo.com", "10.0.0.5");
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://internal.foo.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("site-local");
    }

    @Test
    void blocklist_blocksMulticast() {
        resolver.put("mc.foo.com", "224.0.0.1");
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://mc.foo.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("multicast");
    }

    @Test
    void blocklist_blocksAnyLocal() {
        resolver.put("zero.foo.com", "0.0.0.0");
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://zero.foo.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("any-local");
    }

    @Test
    void blocklist_blocksBroadcast() {
        resolver.put("bc.foo.com", "255.255.255.255");
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://bc.foo.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("broadcast");
    }

    @Test
    void blocklist_blocksIpv6Ula() {
        resolver.put("ula.foo.com", "fc00::1");
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://ula.foo.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("ipv6-ula");
    }

    @Test
    void blocklist_allowsPublicIpv4() {
        resolver.put("api.example.com", "8.8.8.8");
        assertThatCode(() -> policy("", "", false)
                .check(URI.create("http://api.example.com/")))
                .doesNotThrowAnyException();
    }

    // ============ DNS rebinding — 멀티 IP ============

    @Test
    void blocklist_dnsRebinding_rejectsIfAnyResolvedIpIsInternal() {
        // 공격자가 "innocent.example.com → [8.8.8.8, 127.0.0.1]" 식으로 응답
        // 한 개라도 차단 카테고리면 위반
        resolver.put("innocent.example.com", "8.8.8.8", "127.0.0.1");
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://innocent.example.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void blocklist_unknownHost_throwsViolation() {
        // resolver에 없는 host는 UnknownHostException → 정책 위반으로 격하
        assertThatThrownBy(() -> policy("", "", false)
                .check(URI.create("http://unresolvable.example.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("cannot be resolved");
    }

    // ============ allow-private override ============

    @Test
    void allowPrivate_letsRfc1918Through_butStillBlocksLoopback() {
        resolver.put("internal.foo.com", "10.0.0.5");
        assertThatCode(() -> policy("", "", true)
                .check(URI.create("http://internal.foo.com/")))
                .doesNotThrowAnyException();

        // 같은 정책이라도 loopback은 여전히 차단 — allow-private이 loopback까지 풀지는 않음
        resolver.put("local.foo.com", "127.0.0.1");
        assertThatThrownBy(() -> policy("", "", true)
                .check(URI.create("http://local.foo.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void allowPrivate_stillBlocksMetadata() {
        resolver.put("metadata.foo.com", "169.254.169.254");
        assertThatThrownBy(() -> policy("", "", true)
                .check(URI.create("http://metadata.foo.com/")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("link-local");
    }

    // ============ URI edge cases ============

    @Test
    void check_uriWithNoHost_throwsViolation() {
        assertThatThrownBy(() -> policy("", "", false).check(URI.create("file:///etc/passwd")))
                .isInstanceOf(NetworkPolicyViolationException.class)
                .hasMessageContaining("no host");
    }

    /** 인메모리 host → InetAddress 매핑. UnknownHostException를 직접 트리거하기 위해 미등록은 throw. */
    private static final class StubResolver implements HostResolver {
        private final Map<String, List<InetAddress>> map = new HashMap<>();
        private int callCount = 0;

        void put(String host, String... ips) {
            try {
                List<InetAddress> addrs = new java.util.ArrayList<>();
                for (String ip : ips) {
                    addrs.add(InetAddress.getByName(ip));
                }
                map.put(host.toLowerCase(), addrs);
            } catch (UnknownHostException ex) {
                throw new RuntimeException(ex);
            }
        }

        void clear() {
            map.clear();
            callCount = 0;
        }

        int callCount() {
            return callCount;
        }

        @Override
        public List<InetAddress> resolve(String host) throws UnknownHostException {
            callCount++;
            List<InetAddress> result = map.get(host.toLowerCase());
            if (result == null) {
                throw new UnknownHostException(host);
            }
            return result;
        }
    }
}
