# Plugin Development Guide

외부 jar로 제공되는 액티비티(`@Activity`)를 작성·검증·배포하는 **플러그인 개발자**용 가이드 (#105).

> 운영자(ops) 입장의 업로드/활성화/디버깅 절차는 [PLUGINS.md](PLUGINS.md). 본 문서는 **플러그인 코드 작성자** 시점.

## 1. 전체 흐름

```
┌────────────┐   ┌─────────────┐   ┌──────────┐   ┌──────────────┐
│ 1. 스펙 숙지 │ → │ 2. 코드 작성  │ → │ 3. jar    │ → │ 4. 자가 검증  │
└────────────┘   └─────────────┘   │  빌드     │   └──────┬───────┘
                                   └──────────┘          │
                                                          ▼
                                                  ┌──────────────┐
                                                  │ 5. 호스트 업로드 │
                                                  │   + 활성화 확인 │
                                                  └──────────────┘
```

전 사이클을 따라가는 최소 예제는 [`examples/plugin-starter/`](../examples/plugin-starter/) 참조.

## 2. 사전 요구사항

| 도구 | 버전 |
|---|---|
| **Java** | 21+ |
| **Gradle** | 8.x+ (wrapper 권장) |
| **호스트(엔진)** | 본 저장소 main 기준 — 호환성은 §9 참조 |

## 3. `@Activity` 계약 (descriptive)

본 절은 **현재 코드 동작을 그대로 기술**합니다 — 향후 안정 계약(SemVer)으로 격상될 수 있으나, v0.x 동안은 release note에서 변경 사항 안내.

### 3.1 어노테이션

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Activity {
    String value() default "";       // 액티비티 이름 (빈 값이면 메서드명 사용)
    int retryCount() default 3;      // 실패 시 최대 재시도 횟수
    long backoffSeconds() default 5; // 첫 재시도까지 대기 (이후 5/10/20/40/80... 지수)
    String description() default ""; // Builder 팔레트/카탈로그 UI 노출용 한 줄 설명 (#192)
}
```

### 3.2 클래스 요구

- **public no-arg 생성자** 필수 — `PluginLoader`가 `Class.getDeclaredConstructor().newInstance()`로 인스턴스화.
- 클래스명은 **고유 패키지** 사용 권장 (`com.example.station8plugin.*` 등) — 코어/타 플러그인과 충돌 회피.
- Spring `@Component` 어노테이션은 **불필요** — 플러그인은 Spring 컨텍스트 밖에서 인스턴스화됨.

### 3.3 메서드 파라미터 — 허용 타입

[`ActivityArgumentResolver`](../station8-engine/src/main/java/com/station8/engine/core/ActivityArgumentResolver.java) 가 다음 4종을 지원:

| 타입 | 의미 | 비고 |
|---|---|---|
| `String` | 액티비티 입력 페이로드 (raw JSON 또는 텍스트) | **첫 번째 등장한** `String` 파라미터에만 바인딩. 메서드 내부에서 직접 파싱. |
| `@BoundDataSource("role") JdbcTemplate` | DataSource 바인딩 — station의 `datasourceBindings` 맵에서 `role` 키로 매핑된 DS의 `JdbcTemplate` | role 누락 시 `primary` fallback + WARN 로그 |
| `LineContext` | 인스턴스 메타 (id/name/attempt/runtimeParams 등) + state 저장/조회 | §4 참조 |
| `DataSourceRegistry` | 멀티 DS 직접 액세스 (deprecated) | 신규 코드는 `@BoundDataSource` 권장 |

지원하지 않는 타입이 선언되면 **호출 단계에서** `IllegalStateException`이 발생 (등록은 통과). 다음 메서드는 등록되지만 실행 시 실패:

```java
@Activity("BAD")
public String bad(Map<String, Object> input) {  // ❌ Map은 미지원
    return null;
}
```

#### 파라미터 패턴 — 자주 쓰는 조합

```java
// (a) 가장 흔한 — 입력만 받음
@Activity("VALIDATE_ORDER")
public String validate(String input) { ... }

// (b) DataSource 바인딩 1개 + 입력
@Activity("WRITE_AUDIT")
public String write(String input, @BoundDataSource("target") JdbcTemplate jdbc) { ... }

// (c) DataSource 2개 (src → dst 마이그레이션 패턴)
@Activity("MIGRATE")
public String migrate(String input,
                      @BoundDataSource("source") JdbcTemplate src,
                      @BoundDataSource("target") JdbcTemplate dst) { ... }

// (d) LineContext (runtime params / instance meta 활용)
@Activity("REPORT")
public String report(String input, LineContext ctx) {
    String date = ctx.runtimeParams().getOrDefault("date", "today");
    int attempt = ctx.attempt();
    ...
}

// (e) 입력이 필요 없을 때
@Activity("NOOP")
public String noop() {
    return "{\"ok\":true}";
}
```

### 3.4 반환 타입

- **`String`** — 그대로 `OUTPUT_DATA`에 JSON으로 저장 (가장 흔한 패턴; 직접 `\"{\\\"ok\\\":true}\"` 형태 반환)
- **`Object` / POJO / `Map`** — Jackson으로 자동 직렬화 후 저장
- **`void`** — 반환값 없음 (저장은 `null`)
- **`null`** 반환 — 허용; OUTPUT_DATA는 비어있음

### 3.5 예외 의미

> 본 절은 v0.x 동안 **descriptive** — 향후 prescriptive로 격상될 수 있음.

| Throw | 동작 |
|---|---|
| `RuntimeException` (모든 unchecked) | 재시도 큐로 → backoff 후 재호출 (최대 `retryCount`) |
| `Error` (`OutOfMemoryError` 등) | 재시도되지만 JVM이 위험 상태 — 가급적 캐치하지 말 것 |
| Checked exception | Java가 메서드 시그니처에 `throws` 강제 — 가능하면 `RuntimeException`으로 wrap 후 throw |
| **정상 반환 (예외 없음)** | COMPLETED — 더 이상 재시도 안 함 |

**영구 실패 표현** — 현재 별도 마커 없음. 재시도가 의미 없는 비즈니스 실패(예: 잘못된 입력)는:
1. 메서드 내부에서 입력 검증 후 정상 반환 (`{"ok":false,"reason":"..."}` 같은 페이로드)
2. 후속 액티비티가 이 결과를 보고 분기 처리 (Builder의 **edge 조건**)

`retryCount` 모두 소진 시 → `FAILED` + DLQ로 이동.

## 4. `LineContext` API

`@Activity` 메서드에 `LineContext` 파라미터를 선언하면 인스턴스 메타에 접근 가능.

```java
public interface LineContext {
    String instanceId();          // 라인 인스턴스 UUID
    String workflowName();         // 라인 이름 (@LineDefinition value)
    String currentActivityName();  // 현재 액티비티 이름
    int attempt();                 // 시도 횟수 (최초 = 1, 재시도 = 2, 3, ...)
    Object input();                // 입력 객체 (Jackson 역직렬화된 POJO)
    Optional<Object> previousOutput();        // 이전 액티비티 출력
    Map<String, Object> attributes();         // 컨텍스트 부가 속성 (직렬화 보존)
    Map<String, String> runtimeParams();      // 즉시 실행 모달에서 입력한 옵션 맵 (#134)
    Instant now();                            // 현재 시간 (테스트 시 Clock 대체 가능)
    void setNext(String activityName, Object input); // 다음 액티비티 동적 지정
    void saveState(Object snapshot);          // 체크포인트 저장 (Object → JSON)
    Optional<Object> loadState();             // 체크포인트 조회
}
```

### 사용 예 — runtime params로 날짜 override

```java
@Activity("DAILY_REPORT")
public String run(String input, LineContext ctx) {
    LocalDate date = LocalDate.parse(
            ctx.runtimeParams().getOrDefault("date", LocalDate.now().toString()));
    // ... date 기준 처리
    return "{\"date\":\"" + date + "\",\"rows\":42}";
}
```

운영자가 Builder의 **Run now** 모달에서 `{"date":"2026-05-01"}`을 입력하면 그 값을 받음. 미입력 시 오늘 날짜.

## 5. 빌드

### 5.1 Gradle (권장)

```groovy
// build.gradle
plugins {
    id 'java'
}

group = 'com.example'
version = '0.1.0'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // Station8 엔진은 본 저장소를 직접 빌드해서 jar를 publishToMavenLocal 하거나
    // CI artifact를 fetch. 자세한 절차는 §9 호환성 매트릭스 참조.
}

dependencies {
    // 코어는 compileOnly — 런타임은 호스트가 제공 (jar에 포함하지 말 것)
    compileOnly 'com.station8:station8-engine:0.0.1-SNAPSHOT'

    // 본인 비즈니스 의존성만 포함 (예: HTTP 클라이언트)
    // implementation 'org.apache.httpcomponents.client5:httpclient5:5.4'

    // 테스트
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
    testImplementation 'com.station8:station8-engine:0.0.1-SNAPSHOT'
}

tasks.jar {
    archiveBaseName = 'my-plugin'
}

test {
    useJUnitPlatform()
}
```

### 5.2 출력

`./gradlew build` → `build/libs/my-plugin-0.1.0.jar`

### 5.3 의존성 정책

- **코어/Spring 의존성은 절대 포함 금지** — 호스트가 제공 (`compileOnly` 사용)
- **본인 비즈니스 의존성**만 jar에 포함:
  - 작은 라이브러리: `implementation`으로 일반 jar 빌드 (호스트가 부모 ClassLoader로 부담)
  - **충돌 가능성이 큰 라이브러리** (예: Guava, Jackson 변형판): `shadow` plugin으로 패키지 relocation 권장 (e.g. `com.example.shaded.guava`)

```groovy
// shadow 사용 시 (충돌 회피)
plugins {
    id 'java'
    id 'com.gradleup.shadow' version '8.3.5'
}
tasks.shadowJar {
    archiveClassifier = ''  // shadow가 -all 접미사 추가하므로 비움
    relocate 'com.google.common', 'com.example.shaded.guava'
}
```

## 6. 단위 테스트

플러그인은 평범한 Java 클래스 — `new ExamplePlugin().method(...)` 직접 호출로 테스트 가능. Spring 컨텍스트 불필요.

```java
class ExamplePluginTest {

    @Test
    void echo_returnsInputUppercase() {
        ExamplePlugin plugin = new ExamplePlugin();
        String result = plugin.echo("hello");
        assertThat(result).contains("\"value\":\"HELLO\"");
    }

    @Test
    void echo_emptyInput_returnsEmptyValue() {
        ExamplePlugin plugin = new ExamplePlugin();
        assertThat(plugin.echo("")).contains("\"value\":\"\"");
    }
}
```

### `LineContext`가 필요한 액티비티 — 간단한 mock

```java
class ReportPluginTest {

    @Test
    void run_usesRuntimeParamsDate() {
        LineContext ctx = mock(LineContext.class);
        when(ctx.runtimeParams()).thenReturn(Map.of("date", "2026-05-01"));
        when(ctx.attempt()).thenReturn(1);

        String out = new ReportPlugin().run("{}", ctx);

        assertThat(out).contains("\"date\":\"2026-05-01\"");
    }
}
```

(Mockito 사용 시 `testImplementation 'org.mockito:mockito-core:5.x'` 추가)

## 7. 자가 검증 (업로드 전 체크리스트)

업로드 전에 다음을 확인:

- [ ] `./gradlew test` 통과
- [ ] jar 안에 `@Activity` 메서드가 있는 public 클래스 존재 — 확인 명령:
  ```bash
  unzip -p build/libs/my-plugin-0.1.0.jar META-INF/MANIFEST.MF
  jar tf build/libs/my-plugin-0.1.0.jar | grep '\.class$' | head
  ```
- [ ] 클래스에 **public no-arg 생성자** 존재 (`javap -p` 확인)
- [ ] **코어/Spring 의존성 미포함** — `unzip -l` 결과에 `org/springframework/`, `com/station8/` 클래스 없어야 함
- [ ] jar 크기 < **50MB** (`AdminPluginController` 업로드 한도)
- [ ] 매직 바이트 `PK\x03\x04`로 시작 (정상 zip/jar는 자동 OK)

## 8. 업로드 + 활성화

### 옵션 A — 어드민 웹 (권장, #102)

1. http://localhost:8080/admin/plugins → **Upload jar** 폼 → 파일 선택 → Upload
2. 검증 통과 시 `engine.plugins.dir`에 저장. 같은 이름이 있으면 기존은 `.bak`로 백업
3. **Hot reload (#103)**: 같은 페이지의 **↻ Reload now** 버튼 → 앱 재시작 없이 등록 완료
4. http://localhost:8080/line/activities 에서 등록 확인 + Builder의 Activities 팔레트에 노출

### 옵션 B — 호스트 파일시스템 직접

1. `scp my-plugin-0.1.0.jar host:/opt/station8/plugins/` (또는 `docker cp`)
2. `POST /admin/plugins/reload` (또는 앱 재시작)
3. `/line/activities`에서 확인

### 부팅/리로드 로그 확인

```
Registered plugin activity: ECHO -> ExamplePlugin.echo
Plugin reload jar my-plugin-0.1.0.jar: added=1, conflicts=0
```

오류 패턴:
- `Plugin class ... has @Activity but cannot be instantiated` → public no-arg 생성자 누락
- `Activity name conflict: ECHO already registered` → 같은 이름 충돌 (§8.4 참조)
- `Failed to load plugin: ...` → 의존 누락/jar 손상

### 충돌 시 (같은 `@Activity` 이름이 이미 있음)

- **Add only 정책** (#103) — 새 등록 skip + WARN 로그
- 해결: 플러그인 jar에서 이름을 변경하거나, 충돌하는 쪽을 비활성화

## 9. 호환성 (best-effort)

| 엔진 버전 | 플러그인 호환 | 비고 |
|---|---|---|
| 0.0.x | 0.0.x | `@Activity` 시그니처 변경은 release note 명시 |
| 1.x (예정) | 별도 매트릭스 | 1.0 출시 시 SemVer 정식 commit 예정 |

v0.x 동안 breaking change 정책:
- `@Activity` 파라미터 타입 추가는 **호환** (기존 플러그인 영향 없음)
- 파라미터 타입 제거/이름 변경은 **non-backward** — release note에 안내
- `LineContext` 메서드 추가는 **호환** (기본 메서드 사용), 제거는 **non-backward**

## 10. 보안 (호스트 운영자가 인지해야 할 점 — 개발자에게도 영향)

- 플러그인은 **호스트 JVM과 같은 권한**으로 실행됨
- `SecurityManager` 기반 샌드박싱은 Java 17+에서 deprecated → 본 엔진은 미지원
- 따라서 **신뢰 가능한 출처의 jar만** 호스트에 업로드되는 것을 전제
- 플러그인 개발자 책임:
  - 외부 입력(`String input`)을 신뢰하지 말고 검증
  - 파일/네트워크/DB 접근을 최소 권한으로 제한 (호스트가 OS 레벨로 강제하기 어려우므로 자율 준수)
  - 비밀번호/API 키는 환경변수 또는 외부 시크릿 매니저(#112) 사용 — jar 안에 절대 hardcode 금지

## 11. 비범위 (현재 미지원)

- 플러그인 라이프사이클 hook (`@PostConstruct` 같은 초기화 콜백)
- 플러그인 간 의존성 격리 (한 jar의 라이브러리 v2 + 다른 jar v3 동시 사용)
- 자동 정적 분석 / 보안 스캔
- 원격 jar URL 자동 fetch / 마켓플레이스
- 플러그인 v2 → v3 hot replace (현재는 add-only)

## 12. 참고

- [PLUGINS.md](PLUGINS.md) — 운영자 시점의 업로드/디버깅
- [HOWTO.md](HOWTO.md) — `@Activity` 일반 가이드 (코어 액티비티 포함)
- [line-engine-spec.md](line-engine-spec.md) — 엔진 아키텍처
- [`examples/plugin-starter/`](../examples/plugin-starter/) — 컴파일 가능한 최소 스켈레톤
- 핵심 코드:
  - [`station8-engine/.../annotation/Activity.java`](../station8-engine/src/main/java/com/station8/engine/annotation/Activity.java)
  - [`station8-engine/.../annotation/BoundDataSource.java`](../station8-engine/src/main/java/com/station8/engine/annotation/BoundDataSource.java)
  - [`station8-engine/.../core/ActivityArgumentResolver.java`](../station8-engine/src/main/java/com/station8/engine/core/ActivityArgumentResolver.java)
  - [`station8-engine/.../core/LineContext.java`](../station8-engine/src/main/java/com/station8/engine/core/LineContext.java)
  - [`station8-engine/.../plugin/PluginLoader.java`](../station8-engine/src/main/java/com/station8/engine/plugin/PluginLoader.java)
