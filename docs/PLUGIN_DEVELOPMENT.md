# 플러그인 개발 가이드

외부 jar에 `@Activity` 메서드를 담아 Station8 호스트에 끼워넣는 방법.

운영자 입장의 업로드/디버깅은 [PLUGINS.md](PLUGINS.md). 이쪽은 코드를 직접 쓰는 사람용이다.

## 시작하기 전에

Java 21 이상, Gradle 8.x. 호스트(엔진) 버전과 어떻게 맞춰야 하는지는 마지막 섹션에 정리.

가장 빠른 길은 [`examples/plugin-starter/`](../examples/plugin-starter/)를 그대로 복사해서 패키지명과 액티비티 이름만 바꾸는 것. 아래 설명은 그 starter가 왜 그렇게 생겼는지를 풀어 쓴 거다.

## 플러그인 하나의 골격

플러그인은 그냥 Java 클래스다. Spring 컨텍스트 안 거치고, 호스트가 `URLClassLoader`로 jar를 열어 `Class.newInstance()`로 객체를 만들고 `@Activity` 메서드를 호출한다. 그래서 두 가지가 필요하다.

1. **public no-arg 생성자** — `newInstance()`가 호출할 수 있어야 한다
2. **`@Activity`가 붙은 public 메서드 하나 이상**

```java
package com.example.station8plugin;

import com.station8.engine.annotation.Activity;

public class HelloPlugin {

    public HelloPlugin() {}

    @Activity("HELLO")
    public String hello(String input) {
        return "{\"greeting\":\"hello, " + input + "\"}";
    }
}
```

이게 전부다. 빌드해서 jar 만들고 `/admin/plugins`로 올리면 `/line/activities`에 `HELLO`가 잡힌다.

## `@Activity` 어노테이션

```java
@Activity(
    value = "MY_NAME",         // 비워두면 메서드 이름이 액티비티 이름이 됨
    retryCount = 3,            // 예외 던지면 backoff 후 다시 호출, 최대 3회
    backoffSeconds = 5,        // 첫 재시도까지 5초. 이후 5→10→20→40 식으로 지수 증가
    description = "한 줄 설명"   // Builder 팔레트 카드에 노출되는 텍스트
)
```

`retryCount=0`으로 두면 실패 즉시 FAILED로 가고 DLQ에 떨어진다. 외부 API 호출처럼 일시적 실패가 흔한 액티비티는 3~5 정도, 결정론적 처리(검증·변환 등)는 0이 자연스럽다.

## 메서드 시그니처 — 무엇을 받고 무엇을 돌려주나

호스트는 메서드의 파라미터 타입을 보고 알아서 인자를 채워준다. 네 가지를 지원하고, 그 외 타입을 선언하면 호출 단계에서 `IllegalStateException`이 뜬다 (등록 자체는 통과한다).

### `String` — 액티비티 입력

가장 흔하다. 라인을 짤 때 노드 Properties 패널에서 입력한 JSON 문자열이 그대로 전달된다. 메서드 안에서 직접 파싱해서 쓰면 된다.

```java
@Activity("VALIDATE_ORDER")
public String validate(String input) {
    // input은 예: {"orderId":"123","amount":50000}
    // Jackson이든 뭐든 자유롭게 파싱
}
```

여러 `String` 파라미터를 선언해도 입력은 **첫 번째**에만 바인딩된다. 나머지 `String`은 호출 시 에러가 난다.

### `@BoundDataSource("role") JdbcTemplate` — DB 접근

라인 정의에서 station(노드)별로 DataSource 바인딩을 매핑해두면, 해당 role이 `JdbcTemplate`으로 주입된다. 같은 액티비티를 다른 DB에 대고 재사용할 때 쓴다.

```java
@Activity("MIGRATE")
public String migrate(String input,
                      @BoundDataSource("source") JdbcTemplate src,
                      @BoundDataSource("target") JdbcTemplate dst) {
    var rows = src.queryForList("SELECT * FROM ...");
    dst.batchUpdate("INSERT INTO ... VALUES (?, ?)", ...);
    return "{\"migrated\":" + rows.size() + "}";
}
```

빌더에서 노드에 `{"source":"oracle-prod","target":"mart"}` 같은 매핑을 적어두면 그대로 들어간다. 매핑이 없거나 DataSource 이름이 등록되지 않은 경우 `primary`로 fallback되고 WARN 로그가 찍힌다.

### `LineContext` — 인스턴스 메타

진행 중인 라인 인스턴스의 정보(현재 시도 횟수, runtime params, 이전 출력 등)가 필요할 때.

```java
@Activity("DAILY_REPORT")
public String run(String input, LineContext ctx) {
    var date = ctx.runtimeParams().getOrDefault("date", LocalDate.now().toString());
    // ctx.attempt() == 2 라면 첫 시도가 실패한 재시도 호출
    // ctx.instanceId() 로 인스턴스 UUID 확인
}
```

쓸 만한 메서드 정리:

- `runtimeParams()` — Builder의 "Run now" 모달에서 입력한 옵션 맵. 같은 라인을 날짜만 바꿔 돌리는 식의 패턴에 핵심.
- `attempt()` — 1부터 시작. 재시도 횟수에 따라 동작을 바꾸고 싶을 때.
- `instanceId()`, `workflowName()`, `currentActivityName()` — 로깅/추적용.
- `setNext(name, input)` — 다음 액티비티를 코드에서 동적으로 지정. 보통은 빌더의 엣지 조건으로 처리하지만 필요할 때 사용.
- `saveState(obj)` / `loadState()` — 체크포인트. 긴 처리 중간에 진행 상태를 JSON으로 저장.

### `DataSourceRegistry`

레거시 경로. `@BoundDataSource`가 들어오기 전(#113 이전)에 직접 레지스트리에서 DS를 꺼내 쓰던 방식. 신규 코드는 `@BoundDataSource`를 쓴다.

## 반환값

뭘 돌려주든 호스트가 알아서 처리한다.

- `String`이면 그대로 `OUTPUT_DATA`에 저장된다. 가장 깔끔하다. 직접 만든 JSON 문자열을 던지면 된다.
- 객체/`Map`/POJO면 Jackson이 직렬화해서 저장한다.
- `void`나 `null`도 허용 — 출력이 비어있는 상태로 기록된다.

후속 액티비티는 빌더에서 엣지 조건식(`#result['ok'] == true` 식)으로 이 출력을 보고 분기한다.

## 예외 — 무엇이 재시도를 부르나

`@Activity` 메서드에서 던지는 모든 unchecked exception은 재시도를 유발한다. `RuntimeException` 한 번 던지면 `backoffSeconds` 만큼 기다린 후 다시 호출되고, `retryCount`만큼 반복한 뒤에도 실패하면 인스턴스가 FAILED로 들어가고 DLQ에 적재된다.

Checked exception은 Java가 시그니처에 `throws`를 강제한다. 보통은 `RuntimeException`으로 wrap해서 throw하는 게 편하다.

"재시도해도 어차피 실패할 비즈니스 오류" — 예를 들어 입력이 잘못된 경우 — 는 예외를 던지지 말고 그냥 결과 페이로드에 `{"ok":false,"reason":"..."}`처럼 적어 반환해라. 후속 노드가 엣지 조건으로 받아 분기 처리하면 된다. 별도의 "영구 실패" 마커는 아직 없다.

## 빌드

핵심은 `compileOnly`로 코어를 참조하는 것. 호스트가 런타임에 코어를 이미 갖고 있으므로 jar에 같이 묶으면 ClassLoader 충돌이 난다.

```groovy
plugins { id 'java' }

group = 'com.example'
version = '0.1.0'

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
    mavenLocal()       // 본 저장소를 publishToMavenLocal 해두고 쓸 때
    mavenCentral()
}

dependencies {
    compileOnly 'com.station8:station8-engine:0.0.1-SNAPSHOT'

    // 본인 비즈니스 의존성만 포함
    // implementation 'org.apache.httpcomponents.client5:httpclient5:5.4'

    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
    testImplementation 'com.station8:station8-engine:0.0.1-SNAPSHOT'
}

tasks.jar {
    archiveBaseName = 'my-plugin'
}

test { useJUnitPlatform() }
```

엔진을 어디서 가져오느냐가 살짝 까다롭다. 현재 본 저장소는 정식 Maven 좌표를 publish하지 않으므로, 먼저 루트에서 `./gradlew :station8-engine:publishToMavenLocal`을 한 번 돌려두면 위 설정이 그대로 동작한다. 일회성이라면 그냥 jar 파일을 직접 참조해도 된다 — `compileOnly files('../station8-engine/build/libs/station8-engine-0.0.1-SNAPSHOT.jar')`.

### 의존성을 어디까지 jar에 포함할지

작은 라이브러리는 `implementation`으로 평범하게 묶으면 된다. 호스트가 부모 ClassLoader라서 충돌이 거의 없다.

문제가 생기는 건 호스트도 가진 라이브러리의 다른 버전을 쓰려고 할 때다. Jackson, Guava, SLF4J 같은 것들이 대표적이다. 이때는 `shadow` 플러그인으로 패키지 자체를 relocate해버린다.

```groovy
plugins {
    id 'java'
    id 'com.gradleup.shadow' version '8.3.5'
}
tasks.shadowJar {
    archiveClassifier = ''
    relocate 'com.google.common', 'com.example.shaded.guava'
}
```

평소엔 신경 쓸 필요 없고, `NoClassDefFoundError`가 뜨거나 동작이 이상하면 그때 의심하면 된다.

## 테스트

플러그인이 평범한 Java 클래스니까 그냥 `new`해서 호출하면 된다. Spring 띄울 필요 없다.

```java
class HelloPluginTest {

    @Test
    void hello_includesInput() {
        String out = new HelloPlugin().hello("world");
        assertEquals("{\"greeting\":\"hello, world\"}", out);
    }
}
```

`LineContext`를 받는 메서드는 Mockito로 한 줄짜리 mock을 만들면 된다.

```java
@Test
void run_usesDateFromRuntimeParams() {
    LineContext ctx = mock(LineContext.class);
    when(ctx.runtimeParams()).thenReturn(Map.of("date", "2026-05-01"));

    String out = new ReportPlugin().run("{}", ctx);

    assertTrue(out.contains("\"date\":\"2026-05-01\""));
}
```

`@BoundDataSource JdbcTemplate`을 받는 메서드는 in-memory H2를 띄워 진짜 DB로 테스트하는 게 가장 확실하다. mock 대체는 SQL 검증을 못 하니까.

## 업로드 전 체크

jar를 호스트에 올리기 전에 다섯 가지만 확인한다.

```bash
./gradlew test                                        # 1. 테스트 통과
jar tf build/libs/my-plugin-0.1.0.jar | head          # 2. .class 파일이 보이는지
unzip -l build/libs/my-plugin-0.1.0.jar | grep station8   # 3. com/station8/ 안 들어갔는지
unzip -l build/libs/my-plugin-0.1.0.jar | grep springframework   # 4. spring도 안 들어갔는지
ls -lh build/libs/my-plugin-0.1.0.jar                 # 5. 50MB 이하인지
```

3·4번은 비어 있어야 한다 — 거기 뭐가 잡히면 `compileOnly` 대신 `implementation`을 쓴 것. 잡힌 채로 올리면 호스트의 같은 클래스와 충돌해서 부팅이 깨질 수 있다.

## 호스트에 올리기

가장 편한 건 어드민 웹이다. http://localhost:8080/admin/plugins에 ADMIN으로 로그인한 후 jar 파일을 올리고 같은 페이지의 "↻ Reload now" 버튼을 누른다. 앱 재시작 없이 등록된다.

올라간 뒤에는 http://localhost:8080/line/activities에서 액티비티 이름이 보여야 한다. Builder의 좌측 팔레트에도 카드로 나타난다.

로그에서 확인할 메시지:

```
Registered plugin activity: HELLO -> HelloPlugin.hello
Plugin reload jar my-plugin-0.1.0.jar: added=1, conflicts=0
```

`added=0, conflicts=1`이라면 같은 이름의 액티비티가 이미 등록되어 있어서 추가가 무시된 것. 플러그인 jar에서 `@Activity` 이름을 바꾸거나, 기존 등록을 비활성화한 후 재시도한다.

`Failed to load plugin: ...`은 jar가 깨졌거나, 코어/Spring 클래스를 포함했거나, no-arg 생성자가 없을 때 뜬다.

## 호환성과 버전

지금 본 저장소는 v0.0.1이라 안정 commitment 없이 가고 있다. `@Activity`의 시그니처나 `LineContext` 인터페이스가 마이너 릴리스에서 바뀔 수 있고, 그 경우 release note에 적힌다. 1.0이 나오면 그때 SemVer 정식 적용 예정.

지금 시점에서는 엔진 0.0.x ↔ 플러그인 0.0.x 매트릭스만 신경 쓰면 된다. 엔진을 올릴 때 본인 플러그인도 같이 다시 빌드한다고 생각하는 게 가장 안전하다.

## 보안

플러그인은 호스트 JVM과 같은 권한으로 돈다. Java 17부터 `SecurityManager`가 deprecated라서 코드 레벨 샌드박싱은 사실상 불가능하고, 본 엔진도 시도하지 않는다.

운영자 입장에서는 "신뢰 가능한 소스의 jar만 올린다"가 유일한 방어선이다. 그래서 플러그인 코드를 쓰는 입장에서도 호스트를 잠재적으로 죽일 수 있는 짓은 피해야 한다 — 무한 루프, 거대한 파일을 메모리에 통째로 올리기, OS 명령 직접 호출 등. 비밀번호나 API 키는 jar에 hardcode하지 말고 환경변수나 외부 시크릿 매니저(#112)를 거쳐 받는다.

## 아직 안 되는 것들

- 라이프사이클 hook (`@PostConstruct` 같은 초기화 콜백)
- 플러그인 간 의존성 격리 (한 jar의 라이브러리 v2 + 다른 jar v3 동시 사용)
- v2 → v3 hot replace — 현재는 add-only라 새 jar의 이름이 같으면 무시된다. 교체하려면 일단 호스트에서 이름 충돌을 풀어야 함
- 원격 jar URL 자동 fetch / 마켓플레이스
- 자동 정적 분석 / 보안 스캔

## 참고

- [PLUGINS.md](PLUGINS.md) — 호스트 운영자 시점
- [HOWTO.md](HOWTO.md) — 코어 액티비티 작성 일반 가이드 (플러그인 외)
- [line-engine-spec.md](line-engine-spec.md) — 엔진 전체 아키텍처
- [`examples/plugin-starter/`](../examples/plugin-starter/) — 컴파일·테스트·업로드 사이클이 통째로 들어있는 최소 예제

코드에서 자세히 보고 싶으면:
- [`Activity.java`](../station8-engine/src/main/java/com/station8/engine/annotation/Activity.java)
- [`BoundDataSource.java`](../station8-engine/src/main/java/com/station8/engine/annotation/BoundDataSource.java)
- [`ActivityArgumentResolver.java`](../station8-engine/src/main/java/com/station8/engine/core/ActivityArgumentResolver.java) — 파라미터를 어떻게 채우는지 한 파일에 다 있다
- [`LineContext.java`](../station8-engine/src/main/java/com/station8/engine/core/LineContext.java)
- [`PluginLoader.java`](../station8-engine/src/main/java/com/station8/engine/plugin/PluginLoader.java) — jar 스캔과 등록 흐름
