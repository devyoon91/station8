# 플러그인 운영 가이드

외부 jar에 담긴 액티비티를 호스트에 올리고 활성화하고 디버깅하는 절차. 호스트를 운영하는 사람용이다.

플러그인 코드를 직접 쓰는 사람이라면 [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md)로 가는 게 맞다. 이 문서는 누군가 빌드해 보낸 jar를 받았다고 가정한다.

## 활성화

`application.properties`에 두 줄을 켜준다.

```properties
engine.plugins.enabled=true
engine.plugins.dir=plugins
```

`engine.plugins.dir`은 작업 디렉토리 기준 상대 경로 또는 절대 경로. docker compose에서는 컨테이너 안의 `/app/plugins`가 되도록 볼륨 마운트하거나, 그냥 docker 이미지에 같이 묶어 빌드해도 된다.

활성화하면 부팅 시 `plugins/`를 한 번 스캔하고, 그 뒤로는 운영자가 명시적으로 reload를 호출할 때만 다시 스캔한다. 자동 파일 watcher는 없다.

## 받은 jar를 검증할 때

업로드 전에 jar 자체가 정상인지 한 번 보고 싶다면 다섯 가지를 본다.

- `unzip -l my-plugin.jar`로 클래스 파일이 들어있는지, `com/station8/`이나 `org/springframework/` 같은 호스트 의존성이 같이 들어있지는 않은지
- `@Activity` 메서드가 있는 public 클래스가 적어도 하나 있어야 한다 (jar 안 내용으로는 직접 확인하기 어렵고, 보통 보낸 사람한테 물어보거나 일단 올려본다)
- 해당 클래스에 **public no-arg 생성자**가 있어야 한다. 없으면 등록 시 `cannot be instantiated` 로그가 뜬다
- 크기는 50MB 이하 — 어드민 업로드 한도
- 파일은 정상 jar/zip이라 `PK\x03\x04` 매직 바이트로 시작

호스트 의존성이 jar에 들어가 있으면 ClassLoader 충돌로 부팅이 깨질 수 있다. 이 경우 보낸 사람에게 `compileOnly`로 다시 빌드해달라고 요청한다 ([PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md)에 자세한 설명).

## 올리고 활성화하기

### 어드민 웹 (보통은 이 경로)

호스트 파일시스템에 직접 접근할 필요가 없다.

1. ADMIN 계정으로 로그인해서 http://localhost:8080/admin/plugins로 간다
2. "Upload jar" 폼에서 jar를 선택하고 Upload
3. 같은 페이지의 "↻ Reload now" 버튼을 누른다
4. http://localhost:8080/line/activities에서 새 액티비티 이름이 보이는지 확인

업로드 시 확장자(`.jar`) + 매직 바이트 + 50MB 한도가 자동 검증된다. 같은 이름의 파일이 이미 있으면 기존은 `.bak`로 백업되고 새 파일로 덮어쓴다.

### 호스트 파일시스템 직접

ssh나 `docker cp` 권한이 있고 어드민 웹이 부담스러우면 직접 떨궈도 된다.

```bash
scp my-plugin.jar host:/opt/station8/plugins/
# 또는
docker cp my-plugin.jar swe-app:/app/plugins/
```

그 뒤 `POST /admin/plugins/reload` 또는 앱 재시작.

## Hot reload 동작

reload 한 번 호출하면 `plugins/` 디렉토리를 다시 스캔한다. 정책은 add-only — 새로 발견된 `@Activity`만 등록하고, 이미 같은 이름이 등록되어 있으면 무시한다.

이게 의미하는 것:
- 새 액티비티 추가: 잘 동작한다
- 기존 액티비티 교체(v2 → v3): 무시된다. 같은 이름이라 conflict로 분류되고 새 버전이 등록되지 않는다. 진짜 교체가 필요하면 앱을 재시작해야 한다

reload 응답은 jar별로 네 가지로 분류된다.

- `added` — 이번에 새로 등록된 액티비티
- `conflicts` — 같은 이름이 이미 있어 skip된 액티비티
- `skippedJars` — 새 등록이 0건이었던 jar (이미 처리 끝났거나 `@Activity`가 아예 없는 jar)
- `failedJars` — 로드 실패 jar + 사유

ClassLoader는 매 reload마다 모든 jar에 새로 만들지만, 새 등록이 0건인 jar의 로더는 즉시 닫아 메모리 누수를 피한다. 새로 등록된 jar의 로더는 활동 인스턴스가 잡고 있어 JVM 종료까지 살아남는다.

동시 reload 호출은 `synchronized`로 직렬화 — 두 번째 호출은 첫 번째가 끝날 때까지 기다린다.

## 비활성화

플러그인 메커니즘 전체를 꺼두려면:

```properties
engine.plugins.enabled=false
```

특정 jar만 빼고 싶으면 `plugins/`에서 다른 폴더로 옮긴 후 재시작.

## 디버깅

### 플러그인이 인식 안 됨

- `engine.plugins.enabled=true`로 되어 있나
- `plugins/` 경로가 작업 디렉토리 기준으로 정확한가 (디폴트는 `plugins`)
- 부팅 로그에 `Plugin scan (boot) complete: added=...` 줄이 보이나
- `Failed to load plugin: ...` WARN이 떴으면 그 jar의 문제다. 메시지에 사유가 같이 찍힌다

### `cannot be instantiated`

```
WARN Plugin class com.example.X has @Activity but cannot be instantiated
     (NoSuchMethodException: ...) — skipping
```

public no-arg 생성자가 없거나, 생성자에서 예외가 던져진 것. 보낸 사람에게 확인 요청.

### `Activity name conflict`

```
WARN Activity name conflict: SEND_SLACK already registered → 플러그인 등록 스킵
```

같은 이름이 코어 또는 다른 플러그인에 이미 등록되어 있다. 해결책은 둘 중 하나 — 플러그인 jar에서 이름을 바꿔서 다시 빌드받거나, 충돌하는 쪽을 비활성화.

### `NoClassDefFoundError`

플러그인이 필요한 라이브러리가 호스트에 없을 때. 보낸 사람이 의존성을 jar에 안 묶은 것. shadow jar로 다시 빌드받거나, 해당 라이브러리를 호스트의 `station8-app/build.gradle`에 추가해 같이 패키징한다.

### 라이브러리 버전 충돌

플러그인 A는 v2, 플러그인 B는 v3 — 한쪽이 우선되고 다른 쪽은 깨진다. 본 엔진은 플러그인 간 의존성 격리를 지원하지 않는다. shadow jar로 패키지 자체를 relocate (`com.example.shaded.guava` 등)하거나, 두 플러그인이 같은 버전을 쓰도록 맞춰야 한다.

## 보안

플러그인은 호스트 JVM과 같은 권한으로 돈다. Java 17부터 `SecurityManager`가 deprecated라 코드 레벨 샌드박싱은 지원하지 않는다.

따라서 **신뢰 가능한 출처의 jar만 올린다**가 사실상 유일한 방어선이다. 외부에서 받은 jar는 SHA-256으로 한 번 검증하고, 가능하면 보낸 사람에게 빌드 환경 / 의존성 목록을 함께 받아둔다.

호스트에서 OS 레벨로 최소 권한을 강제하는 것도 한 방법이다 — docker rootless, read-only filesystem, network egress 제한 같은 것들. 본 엔진이 강제하지는 않지만, 운영 환경에서는 자율 적용을 권장한다.

## 참고

- [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md) — 플러그인 코드 작성자용
- [HOWTO.md](HOWTO.md) — 코어 액티비티 작성 일반 가이드
- [line-engine-spec.md](line-engine-spec.md) — `PluginLoader` 아키텍처
- [`examples/plugin-starter/`](../examples/plugin-starter/) — 컴파일 가능한 최소 스켈레톤
