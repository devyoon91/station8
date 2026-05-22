# 엔진 아티팩트 배포 채널 — SDK 모듈 분리 + Maven Central

**상태**: 결정됨 (2026-05-22)
**관련 이슈**: [#283](https://github.com/devyoon91/station8/issues/283)

## 결정

1. **`station8-engine-api` 모듈을 분리**한다 — 플러그인 SDK contract (어노테이션 + `LineContext`).
2. **Maven Central** 을 정식 릴리스의 1차 배포 채널로 한다.
3. **namespace는 `io.github.devyoon91`** — `com.station8`은 `station8.com` 도메인을 타사가 보유 중이라 클레임 불가.
4. **GitHub Packages**는 SNAPSHOT 보조 채널, **GitHub Release jar 첨부**는 폐쇄망 fallback.
5. **JitPack은 비채택** — 폐쇄망 mirror 불가.

코드 수준 결정은 이 문서를 머지하는 PR에서 동시에 적용됨 (#283 spike). 실제 OSSRH 등록과 첫 0.1.0 release는 sub-issue로 분리.

## 배경 — 왜 지금 결정해야 했나

플러그인 개발자가 엔진 API에 의존성을 걸 때 유일한 경로가 **메인 저장소 클론 → `./gradlew :station8-engine:publishToMavenLocal`** 였고, [`examples/plugin-starter/README.md`](../../examples/plugin-starter/README.md)도 그렇게 가이드 중이었다. 두 가지가 망가져 있었다:

- **`maven-publish` 플러그인이 어디에도 적용 안 되어 있음** — 가이드된 명령이 실제로 실행되지 않았다. starter `build.gradle` Line 41의 `compileOnly files('../../station8-engine/build/libs/...')` 만이 유일하게 동작하는 fallback이었다.
- **`compileOnly 'com.station8:station8-engine'`** 가 엔진 내부 30+ 클래스(`DagInterpreter`, `LineWorker`, `JdbcTaskExecutor` 등)를 외부에 노출 — 플러그인 작성자가 IDE 자동완성으로 내부 API를 잡을 수 있었고, "이건 내부였다"라고 사후 변명할 방법이 없었다.

M21까지 진행되며 SDK 면적(annotation 4종 + `LineContext`)이 잡혔다 — 정리 적기.

## 페르소나 — 누가 SDK를 쓰나

세 종류가 따로 있고, 각자 다른 contract를 본다:

| 페르소나 | 무엇을 import하나 | 안정성 약속 |
|---|---|---|
| **플러그인 개발자** (사내/외부) | `@Activity`, `@ActivityParam`, `@BoundDataSource`, `@LineDefinition`, `LineContext` | semver — 본 RFC가 다루는 면 |
| **station8-app** (모놀리식 호스트) | engine 내부 30+ 클래스 | 같은 저장소 동시 변경 — semver 약속 없음 |
| **엔진 자체 개발자** | 전부 | 약속 없음 |

본 RFC는 **첫 번째 페르소나만** 다룬다. station8-app과 engine 사이의 내부 API 안정성은 별도 후속 이슈 — 같은 모듈/저장소이므로 동시 리팩토링으로 푼다.

## SDK 분리 — `station8-engine-api`

### 들어가는 것 (현재 5 파일, 컴파일된 jar 5KB)

```
station8-engine-api/
  src/main/java/com/station8/engine/
    annotation/
      Activity.java          @Activity(value, retryCount, backoffSeconds, description, params)
      ActivityParam.java     @ActivityParam(name, kind, required, description, options, defaultValue) + Kind enum
      BoundDataSource.java   @BoundDataSource("role")
      LineDefinition.java    @LineDefinition("workflowName")
    core/
      LineContext.java       인터페이스 — 15개 메서드 (instanceId, attempt, input, setNext, saveState …)
```

### 들어가지 않는 것

- `core/` 의 나머지 (`DagInterpreter`, `LineWorker`, `JdbcTaskExecutor`, `LineRegistry`, `ExpressionEvaluator`, `PipelineGate`, `SlaPoller`, …) — host runtime
- `entity/`, `repository/`, `crypto/`, `datasource/`, `dialect/`, `exception/`, `plugin/`, `util/`, `aspect/`, `config/` — host internals
- Spring, Jackson, GraalVM, SSHD, AWS SDK 등 무거운 의존성 — api jar은 **JDK 외 0**

### 패키지명 보존

`com.station8.engine.annotation`, `com.station8.engine.core` 패키지명은 분리 후에도 동일. station8-engine은 같은 패키지에 내부 클래스(`DefaultLineContext`, `LineContextFactory` 등)를 그대로 보유 — split-package가 아닌 **shared-package across modules**. 기존 station8-app 의 ~30개 import 한 줄도 안 바꿔도 된다.

이게 의도된 design call이다 — 패키지명을 분리하려면 import 변경이 광범위해지고, `com.station8.engine.annotation.api.Activity` 같은 어색한 좌표가 생긴다.

### `station8-engine`이 트랜지티브 노출

```groovy
// station8-engine/build.gradle
apply plugin: 'java-library'
dependencies {
    api project(':station8-engine-api')   // ← api로 노출 → station8-app도 그대로 import
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    // ...
}
```

station8-app은 `implementation project(':station8-engine')` 하나만 잡고도 SDK 타입을 본다. 기존 import 그대로 컴파일 통과 확인됨 (#283 spike).

## 배포 채널 선택

| 채널 | anon read | 폐쇄망 mirror | 외부 컨트리뷰터 | SNAPSHOT |
|---|:---:|:---:|:---:|:---:|
| **Maven Central** | ✅ | ✅ (Nexus proxy 표준) | ✅ (IDE 자동 인식) | △ (별도 SNAPSHOT 저장소) |
| **GitHub Packages** | ❌ PAT 필요 | △ (토큰 운반) | △ | ✅ |
| **JitPack** | ✅ | ❌ (빌드 on-demand) | ✅ | ✅ |
| **GitHub Release jar 첨부** | ✅ | ✅ (URL만 있으면) | △ (transitive 수동) | ❌ |

### 채택: Maven Central (정식) + GitHub Packages (SNAPSHOT) + Release jar (백업)

- **Central**: `io.github.devyoon91:station8-engine-api:0.1.0` — 외부 컨트리뷰터·사내 Nexus·IDE 모두 최선
- **GitHub Packages**: main 머지 시점 자동 publish, `0.x.y-SNAPSHOT` — pre-release 검증 가능
- **GitHub Release**: tag별로 `station8-engine-api-0.1.0.jar` 첨부 — Central 장애 시 fallback, 폐쇄망 USB 운반용

### 비채택: JitPack

설정 0이라는 장점이 폐쇄망 mirror 불가 단점을 못 이긴다. 워크플로우 엔진은 사이트당 한 번 배포되고 폐쇄망 사이트가 다수일 가능성이 높다.

### 비채택: Self-hosted Nexus default

사내 표준이지만 외부 컨트리뷰터에 불친절. Central → 사내 Nexus proxy가 자연스러운 표준 패턴이라 self-hosted가 default가 될 이유 없음.

## namespace — `io.github.devyoon91`

`com.station8`이 strawman이었으나 [station8.com 도메인](https://station8.com)이 "Scheidler Web Solutions" 소유로 확인됨 (2026-05-22). Sonatype Central은 namespace 클레임 시 DNS TXT proof를 요구하므로 **클레임 불가**.

대안:
- ✅ `io.github.devyoon91` — GitHub OAuth 자동 verification, 즉시 가능, GitHub username 변경 시까지 영구
- ❌ `com.devyoon91` 같은 personal-domain — 도메인 보유 시점에 마이그레이션 가능하지만 비용 대비 이득 적음
- ❌ org 생성 후 `io.github.station8org` — GitHub org 신설은 별도 결정사항

선택지: 본 RFC는 `io.github.devyoon91`로 결정. 추후 station8.com 도메인 확보 시 `com.station8`로 마이그레이션 가능 (downstream 영향 큼 — 1.0 전에만 권장).

**Gradle `group` 변경 시점**: 본 PR이 아닌 별도 sub-issue (Central 등록과 한 묶음). 현재는 `com.station8` 그대로 — mavenLocal에서는 group이 자유라 영향 없음.

## 버전 정책

```
station8-engine-api  : semver 정식 적용 (SDK contract)
  MAJOR : breaking — 인터페이스 시그니처 변경, 어노테이션 제거
  MINOR : additive — 새 어노테이션, default 메서드 추가, ActivityParam.Kind enum 값 추가
  PATCH : javadoc / 비기능 변경

station8-engine      : host 버전 — api 버전과 독립적으로 진행
station8-app         : 제품 버전 — 둘과 무관
```

### 시작 버전: `0.1.0`

현재 모든 모듈이 `0.0.1-SNAPSHOT`. **분리 후 station8-engine-api만 0.1.0**으로 시작.

- 0.0.x = "약속 없음" → 0.1.x = "additive 정도는 보장" 신호
- 0.1.0 → 1.0 까지 사이에 `LineContext` immutable 정비, generic 도입 등 breaking 가능 — 1.0 박기 전 한 번 정리.

### 호환 매트릭스

위치: `docs/PLUGIN_DEVELOPMENT.md` 마지막 섹션 (현재 "호환성과 버전" 자리). 표 자체는 host 릴리스마다 갱신.

```markdown
| host engine | compatible plugin API |
|---|---|
| 0.1.x       | 0.1.0+                |
| 0.2.x       | 0.1.0+ (forward compat: additive only) |
| 1.0.x       | 1.0.0+                |
```

## 클래스로더 통합 함정 — semver의 한계

플러그인은 host JVM과 같은 클래스로더 패밀리에 로드되므로, **호스트가 가진 api jar이 단일 source of truth**다.

```
plugin compiled against api 0.2.0 → uses LineContext.foo()  (0.2.0에 추가된 method)
host has api 0.1.0                → no foo()
activity 호출 시점 → NoSuchMethodError / LinkageError
```

semver는 컴파일 시 안전성만 약속 — 바이너리 시점에는 다음 규칙이 실제:

- ✅ plugin이 host보다 **같거나 낮은** api 버전을 사용 (backward compat)
- ❌ plugin이 host보다 **높은** api 버전을 사용 (forward compat — additive만 안전했어야 하지만 보호 못 함)

### 후속 작업: 런타임 버전 가드 (sub-issue)

플러그인 jar의 `META-INF/MANIFEST.MF`에 `Station8-Engine-Api-Version` 헤더 박고, `PluginLoader`가 부팅 시 host api 버전과 비교 → 미스매치 시 친절한 거부 메시지 (`NoSuchMethodError` 대신).

## `station8-engine-test` 모듈 — 후속

현재 `PLUGIN_DEVELOPMENT.md` Line 200-212는 플러그인 작성자에게 `mock(LineContext.class)`를 권장. mockito는 SQL/retry 동작 검증 불가.

후속 모듈 후보:
```
station8-engine-test/   ← testImplementation
  - InMemoryLineContext (LineContext의 테스트용 구현체)
  - LineContextBuilder (fluent fixture)
  - AssertionsForActivities (활동 출력 단언 helper)
```

본 RFC 범위 외 — sub-issue로 등록.

## spike 결과 (#283 PR)

코드 분리를 먼저 해보고 RFC를 쓴 이유: "5 파일만 옮기면 됨"이 실제로 맞는지 검증. 결과:

- ✅ 5 파일 모두 JDK 의존성만 — `LineContext`의 `Object input()` / `Optional<Object>` 같은 약타입 시그니처도 JDK라 의존성 추가 없음
- ✅ api 모듈 jar = **5KB, 13 entries** (6 클래스 + 디렉토리 + MANIFEST)
- ✅ engine jar에 SDK 클래스 누출 0 (`annotation/`, `LineContext` 없음)
- ✅ station8-engine, station8-app 모두 import 변경 없이 컴파일 + 테스트 통과
- ⚠️ `java-library` 플러그인을 station8-engine에 추가 필요 (`api project(...)` 구성용) — 단일 모듈일 때는 `java`만으로 충분했음

## 단점 / 받아들이는 트레이드오프

1. **Promotion treadmill** — 새 SDK 표면 요청 시마다 "api에 넣을지" 결정. 0.1.x 단계는 보수적으로 — 외부 요청이 누적되면 그때 판단.
2. **Forward-evolution 제약** — abstract method 추가 금지, default 메서드만 — `LineContext.now()`, `nodeId()`, `runtimeParams()`가 이미 default라 패턴은 잡혀 있음.
3. **`LineContext.setNext(String, Object)` 약타입** — 0.1.0 박기 전 한 번 다듬을지 별도 검토 (mutable → builder, Object → generic). 현재는 그대로 박고 1.0 직전에 breaking 정리.
4. **테스트 fixture 부재** — `station8-engine-test` 모듈로 푼다 (후속).
5. **모듈 1개 → 2개 — 엔진 hot loop +5초** — Gradle 병렬 빌드로 상쇄, 받아들임.
6. **BOM 모듈 필요해질 가능성** — `engine-api + engine-test + ...` 버전 sync 부담이 누적되면. 1.0 즈음 별도 결정.

## 수용 기준 (#283 자체) — 결과

- [x] 옵션 중 하나로 결정 (Maven Central + io.github.devyoon91)
- [x] `station8-engine-api` 분리 — 본 PR에서 spike 완료, 후속 sub-issue 불요 (코드 머지로 끝)
- [x] 호환 매트릭스 위치/포맷 결정 (`PLUGIN_DEVELOPMENT.md` 마지막 섹션)
- [x] 폐쇄망 mirror 가이드 톤 (#112와 정렬 — sub-issue로 docs 작성)

## Sub-issue 후보 (본 RFC 머지 후 등록)

1. **[FEAT] `maven-publish` 적용 + GitHub Actions에서 main 머지 시 GitHub Packages SNAPSHOT 자동 publish**
2. **[CHORE] OSSRH 등록 + 첫 `0.1.0` Maven Central release** — Gradle group을 `io.github.devyoon91`로 변경 포함
3. **[FEAT] `station8-engine-test` 모듈** — `InMemoryLineContext` + fixture builders
4. **[FEAT] PluginLoader 런타임 버전 가드** — `MANIFEST.MF` 검사로 NoSuchMethodError 친절화
5. **[REFACTOR] `LineContext` 0.1.0 직전 1회 정비** — immutable 정책 명시, `setNext` 시그니처 검토
6. **[DOCS] 폐쇄망 mirror 가이드** — `PLUGIN_DEVELOPMENT.md`에 Nexus proxy 패턴 추가

## 비범위

- 플러그인 marketplace / registry — 더 뒤
- 플러그인 간 의존성 격리 (한 jar의 라이브러리 v2 + 다른 jar v3 동시 사용) — M-? 마일스톤
- `module-info.java` 도입 — Spring Boot가 unnamed module로 도는 게 일반적이라 보류

## 참고

- [PLUGIN_DEVELOPMENT.md](../PLUGIN_DEVELOPMENT.md) — 플러그인 작성자 가이드
- [examples/plugin-starter/](../../examples/plugin-starter/) — 최소 예제 starter
- [Sonatype Central — Register a Namespace](https://central.sonatype.org/register/namespace/) — `io.github` 자동 verification
- [GitHub Configuration - Sonatype Help](https://help.sonatype.com/en/github-configuration.html)
- #112 — 폐쇄망 secret 운반 가이드 (mirror 가이드 톤 참조)
