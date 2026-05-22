# Station8 Plugin Starter

Station8 플러그인을 처음 만들 때 그대로 복사해서 시작할 수 있는 최소 예제. 빌드되는 코드 + 통과하는 테스트 + 업로드 명령까지 한 사이클이 다 들어있다.

자세한 설명은 [PLUGIN_DEVELOPMENT.md](../../docs/PLUGIN_DEVELOPMENT.md).

## 안에 뭐가 들어있나

`@Activity("ECHO_UPPER")` 메서드 하나가 있는 `ExamplePlugin` 클래스와 그걸 검증하는 단위 테스트 셋. 외부 의존성은 없고 입력 문자열을 대문자로 바꿔 JSON으로 돌려준다 — 사이클 검증용 dummy다.

추가로 M16 (#247) 표현식 평가 통합 시나리오를 위한 `ExpressionTestPlugin`이 들어있다 — `scripts/scenarios/06-08`이 이 플러그인을 사용한다.

| 활동 | 용도 |
|---|---|
| `ECHO_UPPER` | 사이클 검증용 dummy (대문자 변환) |
| `ECHO_INPUT` | M16 평가 후의 INPUT_DATA를 OUTPUT_DATA로 그대로 echo |
| `REQUIRE_FIELD_ID` | 입력 JSON에 `"id"` 키가 있어야 통과 — fail-fast 검증 |
| `TRANSFORM_JSON` | 입력 JSON을 `{"echoed": <input>}` 형태로 wrap |

## 빌드

먼저 본 저장소의 SDK 모듈(`station8-engine-api`)을 로컬 Maven에 publish해둔다. 한 번만 하면 된다.

```bash
# 루트에서
./gradlew :station8-engine-api:publishToMavenLocal
```

> #283 — SDK는 호스트 엔진(`station8-engine`)에서 분리된 별도 경량 모듈. compileOnly로 잡는 contract만 들어있고 Spring/Jackson/GraalVM 같은 무거운 의존성은 포함되지 않는다 (jar 크기 ≈ 5KB).

그 뒤 starter 빌드:

```bash
cd examples/plugin-starter
gradle build
```

`build/libs/plugin-starter-0.1.0.jar`가 산출물. 테스트는 build 안에 포함된다 (`gradle test`로 따로 돌려도 됨).

SDK를 publish하기 귀찮으면 `build.gradle`의 `compileOnly` 라인을 다음처럼 바꿔도 된다:

```groovy
compileOnly files('../../station8-engine-api/build/libs/station8-engine-api-0.0.1-SNAPSHOT.jar')
```

## 호스트에 올리기

1. http://localhost:8080/admin/plugins에 ADMIN으로 로그인 → jar 업로드
2. 같은 페이지의 "↻ Reload now" 버튼
3. http://localhost:8080/line/activities에 `ECHO_UPPER` 잡혔는지 확인
4. Builder에서 노드 추가 → 입력 `hello` → Run → 결과 `{"value":"HELLO"}`

## 다음 단계

본인 플러그인으로 바꾸려면 셋을 고치면 된다.

- `settings.gradle`의 `rootProject.name`
- `build.gradle`의 `group` / `version` / `archiveBaseName`
- `src/main/java`의 패키지명과 `ExamplePlugin` 클래스/메서드 — 본인 로직으로 교체
