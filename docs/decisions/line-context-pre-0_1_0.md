# LineContext 0.1.0 직전 정비 결정

**상태**: 결정됨 (2026-05-22)
**관련 이슈**: [#321](https://github.com/devyoon91/station8/issues/321)
**선행**: [#283](https://github.com/devyoon91/station8/issues/283), [`engine-artifact-distribution.md`](engine-artifact-distribution.md)

## 배경

[`station8-engine-api`](../../station8-engine-api/) 모듈이 #283 으로 분리되며 `LineContext` 가 SDK contract 면으로 노출됐다. Maven Central에 `0.1.0` (#318)으로 박는 순간 시그니처는 forever-fixed — publish한 버전 삭제 불가. 4 항목을 0.1.0 박기 전 정비할지 결정했다.

## 결정 요약

| 항목 | 결정 | 비고 |
|---|---|---|
| 1. `setNext(String, Object)` 시그니처 | **유지** | generic / builder 패턴 다 비용 > 가치 |
| 2. `attributes()` mutability | **유지 (javadoc 명확화)** | 구현체 자유, 활동 코드는 put/remove 자제 권장 |
| 3. `Optional<Object>` 약타입 (previousOutput / loadState / nextActivityInput) | **유지** | generic-rich 시그니처는 1.0 직전 결정 |
| 4. `now()` default vs `Clock` 주입 | **유지** | 테스트는 fixture가 fixed `Instant` 주입 가능 |
| **추가**: `default boolean isRetry()` | **추가** | `attempt() > 1` 가독성 + 통일 표현 |

## 각 결정 근거

### 1. `setNext(String, Object)` — 유지

**검토한 변경안**:
- **A. Generic `<T>`** — `<T> void setNext(String, T input)` + `<T> Optional<T> nextActivityInput()`. T를 두 메서드 간에 매칭시킬 정적 보장이 없으므로 호출자가 `Optional<Object>` 만큼 약하게 캐스팅해야 함. 타입 안정성 이득 0.
- **B. Builder/Immutable** — `LineContext.next("X").withInput(map)` 같은 패턴. 활동 메서드가 새 컨텍스트를 반환해야 하므로 호스트 dispatch 로직(`JdbcTaskExecutor.scheduleNext`)이 활동 반환값을 읽는 구조로 변경 필요. 활동 시그니처(현재 `String` 반환)와 충돌.

**결정**: 유지. javadoc에 "마지막 호출이 이긴다", "thread 안전성 보장 안 함" 만 명시.

### 2. `attributes() Map<String, Object>` mutability — 유지 + 명확화

구현체가 mutable / unmodifiable 둘 다 반환할 수 있는 현재 상태를 유지하되, 활동 코드는 본 맵을 put/remove하지 않는 게 안전하다는 규약을 javadoc에 박는다.

**검토한 변경안**: 강제 `Map.copyOf` / `UnmodifiableMap` 반환. 호스트의 `DefaultLineContext` 구현 변경 필요 + 활동이 본 맵에 put하던 패턴(현재는 없는 것으로 보이지만 검증 불가)이 깨질 가능성.

**결정**: 시그니처 유지. 영속화가 필요한 상태는 [`saveState`](../../station8-engine-api/src/main/java/com/station8/engine/core/LineContext.java)로 분리하는 게 정공이라는 가이드만 추가.

### 3. `Optional<Object>` 약타입 — 유지

`previousOutput()` / `loadState()` / `nextActivityInput()` 셋 다 `Optional<Object>`. 활동 코드가 캐스팅 필요.

**검토한 변경안**: 
- generic-rich (`<T> Optional<T> loadState(Class<T> type)`) — 활동 작성자가 매번 클래스 토큰 전달, 호스트도 Jackson으로 역직렬화 책임
- 별도 typed handle (`StateHandle<T> state(Class<T>)` 같은) — API 면 확장, 직관성 ↓

**결정**: 유지. generic-rich 변경은 호스트 직렬화 구조와 깊게 얽혀있어 1.0 직전 통합 검토 — 0.1.0은 현재 형태 보존.

### 4. `now()` default vs `Clock` — 유지

`default Instant now() { return Instant.now(); }` 가 현재. 테스트의 결정성 확보는 [`InMemoryLineContext`](../../station8-engine-test/src/main/java/com/station8/engine/test/InMemoryLineContext.java) (#319)가 빌더로 fixed Instant를 주입할 수 있어 이미 해결됨.

**결정**: 유지. `Clock` 객체 노출은 API 면 확장 — 현 구조로 테스트성 충분.

## 추가된 단일 변경: `default boolean isRetry()`

```java
default boolean isRetry() { return attempt() > 1; }
```

활동 코드의 가독성과 일관성 — `if (ctx.attempt() > 1)` 같은 매직 넘버 비교 대신 의도 표현:

```java
@Activity("SEND_EMAIL")
public String send(String input, LineContext ctx) {
    if (ctx.isRetry()) {
        // idempotency 가드 — 외부 시스템에서 중복 차단
    }
}
```

semver MINOR 변경 (additive default 메서드) — 0.1.0의 첫 번째 추가 메서드 자격.

## 0.1.0 시점에 따로 결정해야 할 것 (본 RFC 범위 외)

- Gradle `version` / `group` 변경 — [#318](https://github.com/devyoon91/station8/issues/318)
- OSSRH 등록 + GPG signing — [#318](https://github.com/devyoon91/station8/issues/318)
- 첫 Central release — [#318](https://github.com/devyoon91/station8/issues/318)

## 1.0 직전 (별도 RFC)

다음 항목은 1.0 직전에 깊이 검토:

- `LineContext` generic-rich 재설계 (`previousOutput` 등)
- `setNext` immutable 패턴 (활동 시그니처 변경 포함)
- `Clock` 명시적 주입
- `Activity` 어노테이션 시그니처 (`retryCount` `long backoffSeconds` 등)
- Plugin SDK BOM 모듈

## 참고

- [`engine-artifact-distribution.md`](engine-artifact-distribution.md) — SDK 분리 + Central 배포 RFC
- [`LineContext.java`](../../station8-engine-api/src/main/java/com/station8/engine/core/LineContext.java) — 본 결정 적용된 인터페이스
- [`InMemoryLineContext.java`](../../station8-engine-test/src/main/java/com/station8/engine/test/InMemoryLineContext.java) — `isRetry()` 자동 default 통과
