# HTTP 호출 정책 — SSRF 방어

`http.request` 같은 built-in 활동이 외부 URL을 부를 때, 라인 작성자가 의도적으로든 실수로든 내부 endpoint(클라우드 metadata, 사내 DB, 운영 게이트웨이 등)를 찌르지 못하게 막는 layer다. URL은 표현식으로 동적 구성되니까 코드 리뷰만으로는 안 잡힌다 — 호출 직전에 한 번 더 검증해야 한다.

이 검증을 `NetworkPolicy` 한 클래스에 모았다. 활동이 `httpClient.send` 직전에 `policy.check(uri)`를 호출, 위반이면 `NetworkPolicyViolationException`을 던져 활동이 즉시 final-fail로 간다 (재시도 안 됨).

## 세 가지 모드

부팅 시 환경변수/properties로 정한다.

| 모드 | 결정 조건 | 동작 |
|---|---|---|
| `blocklist` (default) | 아래 두 조건 모두 미적용 | default blocklist 적용. DNS 해소된 IP 전체를 검사 |
| `allowlist` | `station8.http.allowlist`가 비어있지 않음 | blocklist 무시. 명시한 host만 통과 |
| `permissive` | `station8.http.policy=permissive` | 모든 검증 off |

`permissive`와 `allowlist`가 동시에 설정되면 `permissive`가 이긴다 — 운영 환경에서 실수로 `permissive`를 두는 것을 무시하지 않기 위해서다.

부팅 로그에 현재 모드가 INFO 한 줄로 찍힌다:

```
NetworkPolicy: mode=BLOCKLIST, allow-private=false, allowlist=(empty)
```

## default blocklist — 무엇이 차단되나

`blocklist` 모드일 때 URL을 host로 풀고 DNS 해소된 IP 하나하나가 다음 카테고리에 걸리는지 본다. 한 개라도 걸리면 위반.

- **loopback** — `127.0.0.0/8`, `::1`. 같은 호스트로 도는 게 막힘
- **link-local** — `169.254.0.0/16`, `fe80::/10`. AWS/GCP/Azure metadata endpoint(`169.254.169.254`)가 여기 들어간다 — 클라우드 환경 자격증명 유출 방어의 핵심
- **RFC1918 private** — `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`. 사내망. `station8.http.allow-private=true`로 풀 수 있다 (단 loopback/link-local은 여전히 차단)
- **multicast** — `224.0.0.0/4`, `ff00::/8`
- **any-cast** — `0.0.0.0`, `::`
- **broadcast** — `255.255.255.255`
- **IPv6 ULA** — `fc00::/7` (Unique Local Address, IPv6의 RFC1918 대응)

`blocklist`는 hostname이 아니라 *해소된 IP*를 본다. 그래서 공격자가 자기 domain `attacker.example.com`을 `127.0.0.1`로 resolve되게 설정해도 막힌다 — 이게 **DNS rebinding 방어**의 1차 layer다. 단 시점은 검사하는 그 순간이고, 그 직후 OS가 다시 resolve하면서 다른 IP를 받을 가능성(TOCTOU — time-of-check vs time-of-use 격차) 은 남아있다. 완전 차단은 IP를 pin해서 HTTP 호출까지 같은 IP로 강제해야 하는데, 그건 follow-up에서 다룬다.

## allowlist — 폐쇄망 사이트 권장 패턴

`station8.http.allowlist=api.slack.com,api.stripe.com` 식으로 hosts를 csv로 명시. 이 host에만 호출 허용. blocklist는 안 본다(allowlist를 명시한 운영자가 그 host의 신뢰를 보장한다고 본다).

대소문자 무시. 정확한 host 매칭 — `api.slack.com`은 통과하지만 `evil-api.slack.com`은 차단. wildcard 미지원.

폐쇄망 사이트가 "사내 API 서너 개만 부르면 된다"는 게 흔한 패턴이라 이쪽이 더 운영하기 쉽다.

## permissive — 절대 운영에 두지 말 것

`station8.http.policy=permissive`. 모든 검증 off. 로컬 개발 / 테스트 환경에서 fixture 호출이 막히는 걸 잠깐 풀고 싶을 때만 쓴다. 운영 환경에 들어가면 SSRF 위험이 노출된다.

부팅 로그에 모드가 찍히니까 운영자가 매번 확인할 것.

## 정책 선택 의사결정

- **사이트가 외부 API 한정 + 폐쇄망** → `allowlist`. 사내 API host만 박아두면 그 외 호출은 자동 차단
- **사이트가 인터넷 광범위하게 부름 (SaaS 다수, webhook 등)** → `blocklist` default. 운영 시작 전에 metadata endpoint(`169.254.169.254`)를 부르는 라인이 없는지 한 번 점검
- **사내 PoC / 로컬 개발** → `permissive`. 배포 전에 반드시 제거
- **사내망 API 호출도 정당히 필요한 사이트** → `blocklist` + `station8.http.allow-private=true`. RFC1918만 풀리고 metadata는 여전히 차단

## 알려진 잔여 위험

- **자동 redirect 우회.** HttpClient는 default가 `Redirect.NORMAL`이라 3xx 응답을 자동 추종한다. 활동이 처음 부른 URL은 정책을 통과해도 redirect 대상은 OS DNS 해소를 그대로 거치므로 검증 layer를 우회할 수 있다 — follow-up sub-issue로 처리할 예정
- **TOCTOU.** 위에서 언급한 DNS rebinding 잔여 위험. 정책 검사 후 호출 직전에 DNS 응답이 바뀌면 다른 IP로 connection이 갈 수 있다 — IP pinning이 정착될 때까지의 한계
- **IPv6 사이트 환경.** 위 blocklist는 IPv6도 cover하지만 운영 환경마다 IPv6 enable 여부가 다르다 — 처음 활성 시 fixture host로 한 번 점검 권장

## 관련 파일

- 정책 구현: [`station8-engine/.../core/builtin/network/NetworkPolicy.java`](../station8-engine/src/main/java/com/station8/engine/core/builtin/network/NetworkPolicy.java)
- 호출 자리: [`HttpRequestActivity.request`](../station8-engine/src/main/java/com/station8/engine/core/builtin/HttpRequestActivity.java) — `httpClient.send` 직전
- 회귀 가드: [`NetworkPolicyTest`](../station8-engine/src/test/java/com/station8/engine/core/builtin/network/NetworkPolicyTest.java) — 각 차단 카테고리 / allowlist / DNS rebinding 시나리오
- 표준 참고: [OWASP SSRF cheat sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)
