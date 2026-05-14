# Station8 Plugin Starter

본 저장소의 [PLUGIN_DEVELOPMENT.md](../../docs/PLUGIN_DEVELOPMENT.md) 가이드에 따라 작성한 **컴파일 가능한 최소 스켈레톤**.

`@Activity("ECHO_UPPER")` 메서드 하나 + 단위 테스트 1건.

## 빌드

```bash
cd examples/plugin-starter
gradle build           # 또는 ../../gradlew build (루트 wrapper 재사용 가능)
```

산출물: `build/libs/plugin-starter-0.1.0.jar`

> **Note**: 본 starter는 `compileOnly`로 `com.station8:station8-engine`을 참조합니다. 루트 저장소를 먼저 `publishToMavenLocal`(또는 본 저장소의 `station8-engine` 모듈을 import)해야 컴파일이 통과합니다. 가장 간단한 방법:
>
> ```bash
> # 루트에서
> ./gradlew :station8-engine:publishToMavenLocal
> # 그 뒤 examples/plugin-starter에서 build
> ```
>
> 또는 본 디렉토리 `build.gradle`의 dependencies를 `compileOnly files('../../station8-engine/build/libs/station8-engine-0.0.1-SNAPSHOT.jar')`로 바꿔도 동작.

## 단위 테스트

```bash
gradle test
```

## 업로드 + 활성화

1. `build/libs/plugin-starter-0.1.0.jar`를 호스트로 전달
2. http://localhost:8080/admin/plugins → Upload jar
3. **↻ Reload now** 버튼 또는 앱 재시작
4. http://localhost:8080/line/activities → `ECHO_UPPER` 노출 확인
5. Builder에서 노드 추가 → 입력 `hello` → Run → 결과 `{"value":"HELLO"}`

## 다음 단계

- [PLUGIN_DEVELOPMENT.md](../../docs/PLUGIN_DEVELOPMENT.md) — 전체 스펙
- [PLUGINS.md](../../docs/PLUGINS.md) — 호스트 운영 절차
- 액티비티 추가 시 메서드만 더 작성하면 됨 (한 클래스에 여러 `@Activity` OK)
