# 표현식 가이드

노드 입력에 `{{ ... }}` 안의 JavaScript 표현식을 써서 직전 노드 출력 / 라인 입력 / 런타임 값을 끌어올 수 있다. n8n과 동일한 문법이라 옮겨오는 사용자는 학습 곡선이 거의 없다.

> M16 (#247) RFC 결정에 따라 GraalVM JavaScript 채택. 폐쇄망 / sandbox 친화적. 자세한 결정 근거는 [docs/decisions/m16-expression-engine.md](decisions/m16-expression-engine.md).

---

## 1. 5분 안에 시작

가장 간단한 예시 — 직전 노드 출력의 `id` 필드를 끌어오기:

```json
{
  "orderId": "{{ $prev.json.id }}"
}
```

이 입력이 활동에 도달하면 `{{ $prev.json.id }}`는 평가되어 실제 값으로 치환된다. 직전 노드가 `{"id": 42, "status": "OK"}`를 출력했다면 활동은 `{"orderId": 42}`를 받는다 (number 보존).

---

## 2. 표현식 vs 정적 값

| 입력 | 동작 |
|---|---|
| `"plain text"` | 그대로 전달 (평가 안 함) |
| `"{{ 1 + 1 }}"` | 평가 → `2` (number) |
| `"Hello {{ name }}!"` | 평가 + 합성 → `"Hello world!"` (string) |
| `{ "x": "{{ $prev.json.x }}" }` | JSON 모드 — 모든 leaf string 재귀 평가 |

**규칙**:
- 표현식이 단독 (양옆 텍스트 0)이면 → 평가 결과 raw value (Number / Boolean / Object / Array / String 보존)
- 정적 텍스트와 혼재면 → 합성 후 String
- 입력에 `{{`가 전혀 없으면 평가/JSON 파싱 자체를 안 함 (정적 노드 입력은 오버헤드 0)

---

## 3. 사용 가능한 변수

### `$prev` — 직전 노드 출력

n8n 호환 shape:

```js
$prev = {
  json: <직전 활동의 출력>,    // POJO / Map / List / 원시값
  binary: {}                   // M22 item-streaming/binary 영역 (placeholder)
}
```

예시:

```js
{{ $prev.json.id }}              // 직전 출력의 id 필드
{{ $prev.json.items.length }}    // 배열 길이
{{ $prev.json.items[0].name }}   // 배열 인덱스 + 필드
```

직전 출력이 JSON 문자열이면 자동 파싱된다. JSON이 아니면 raw string 그대로 노출.

**적용 범위** (#267): 단일 predecessor (선형 체인) 노드에서만 활성. 다음 경우는 `$prev`가 `null`로 박힌다 — `$prev.json.x` 접근 시 TypeError → 활동 FAILED:
- **start 노드** — 들어오는 엣지 0건 (라인 시작점)
- **fan-in 노드** — 들어오는 엣지 2건 이상 (어떤 predecessor를 골라야 할지 모호)
- **legacy/linear 모드 활동** — `nodeId` 없는 비-DAG 활동

fan-in 노드에선 `$ctx.input`을 사용하거나 `$prev.json?.x ?? defaultVal`로 안전하게 폴백.

### `$ctx` — 현재 라인 컨텍스트

```js
$ctx = {
  input: <현재 활동에 들어온 입력>,   // POJO / Map / List / String
  run: {
    id: "<인스턴스 ID>",
    attempt: <현재 시도 횟수>          // 1부터
  },
  line: {
    name: "<라인 정의 이름>",
    activity: "<현재 활동 이름>"
  },
  runtime: { /* RUN_OPTIONS의 named params, #134 */ }
}
```

예시:

```js
{{ $ctx.input.user }}            // 라인 입력의 user 필드
{{ $ctx.run.id }}                // 인스턴스 ID (로깅 / DLQ 추적용)
{{ $ctx.run.attempt }}           // 재시도 중인지 판단
{{ $ctx.line.name }}             // 라인 정의 이름
{{ $ctx.runtime.date }}          // 즉시 실행 시 모달에서 입력한 named param
```

### `$credentials` — 시크릿 (vault lazy 해소)

`/admin/credentials`에 등록한 항목을 이름으로 끌어온다. lookup은 표현식이 실제 멤버를 액세스하는 순간에만 발생 — 표현식이 `$credentials`를 참조 안 하면 DB 쿼리 0, decrypt 0.

```js
{{ $credentials.slack.value }}        // 복호화된 평문 (bearer token, api key 등)
{{ $credentials.slack.type }}         // "http_bearer" / "http_basic" / "api_key" / "generic"
{{ $credentials.slack.name }}         // 등록한 이름 그대로
{{ $credentials.basic.username }}     // http_basic의 username (schema 필드)
{{ $credentials.basic.value }}        // http_basic의 password (복호화)
```

규칙:
- `.value` 액세스 시점에만 decrypt — schema 필드(`username` 등)만 쓰면 decrypt 비용 0
- 등록 안 된 이름 → `null` (`$credentials.foo` 자체가 null이라 `.value` 접근 시 TypeError → 활동 FAILED)
- 읽기 전용 — `$credentials.x.value = 'y'`는 예외
- 평문은 응답/로그에 안 나옴 — `ExpressionEvaluator`의 `HostAccess.NONE` 정책으로 reflection escape 차단

타입별 schema 필드는 `/admin/credentials` 등록 폼 참조. `http_basic`은 `username` + `value(=password)`, `http_bearer`는 `value(=token)`만, `generic`은 `value` + 사용자 정의 메타.

---

## 4. JavaScript 표준 함수 — 사용 가능한 것 / 차단된 것

표현식은 GraalVM JavaScript (ECMAScript 2022)로 평가된다. 사용 가능 / 차단의 대원칙:

- **사용 가능**: 순수 JavaScript 표준 라이브러리 (Math, JSON, String, Array, Date, Object, Number, ...)
- **차단**: 외부 세계와 닿는 모든 것 (Java reflection / 파일 / 네트워크 / 스레드 / native code / `console` / `load`)

차단 항목은 사용 시 평가 실패 → 활동 FAILED로 격하된다 (워커는 살아있음). Sandbox 정책 결정 근거는 [m16-expression-engine.md §"GraalVM JavaScript ✅ 채택"](decisions/m16-expression-engine.md).

### 사용 가능

```js
{{ Math.max(1, 2, 3) }}
{{ JSON.stringify({a: 1}) }}
{{ "hello".toUpperCase() }}
{{ [1, 2, 3].map(x => x * 2) }}
{{ new Date().getTime() }}
{{ Object.keys({a: 1, b: 2}) }}
```

### 차단 (활동 FAILED)

```js
{{ Java.type('java.lang.Runtime') }}    // Java reflection
{{ Polyglot.import('foo') }}            // Polyglot escape
{{ load('http://...') }}                // 외부 로드
{{ console.log('x') }}                  // console (디버그 노이즈 차단)
{{ $prev.json.getClass() }}             // 노출 객체의 Java 메서드
```

---

## 5. 자주 쓰는 패턴

### 직전 출력 필드 꺼내기

```json
{ "userId": "{{ $prev.json.user.id }}" }
```

### 문자열 합성

```json
{ "subject": "Hello {{ $ctx.input.name }}! Order {{ $prev.json.orderId }} ready." }
```

### 조건부 값

```json
{ "channel": "{{ $prev.json.priority === 'high' ? 'pager' : 'slack' }}" }
```

### 배열 변환

```json
{ "ids": "{{ $prev.json.items.map(x => x.id) }}" }
```

결과: `{ "ids": [1, 2, 3] }` (배열 보존)

### 직전 출력 객체 그대로 echo

```json
{ "passthrough": "{{ $prev.json }}" }
```

`$prev.json`이 `{x: 1, y: 2}`면 결과는 `{ "passthrough": {"x":1,"y":2} }` (객체 보존).

### default 값

```json
{ "limit": "{{ $ctx.runtime.limit ?? 100 }}" }
```

### 타임스탬프 / 날짜

```json
{ "executedAt": "{{ new Date().toISOString() }}" }
```

---

## 6. 실패 처리

표현식 평가 실패 시:

1. 해당 활동이 **FAILED**로 마킹된다 (워커 / 라인 인스턴스는 영향 없음)
2. 재시도 정책이 적용된다 — `@Activity(retryCount = N)` / `RUN_OPTIONS`
3. 최종 실패 시 DLQ 적재 + onFailure 정책 (`CONTINUE` / `ABORT` / `PAUSE_ON_FAILURE`)
4. 실패 사유는 활동 row의 `errorMessage` + `stackTrace` 컬럼, 운영 UI의 timeline 패널에서 확인 가능

전형적인 실패 사유:

| 메시지 | 원인 | 해결 |
|---|---|---|
| `unknownVar is not defined` | 변수 이름 오타 (`$pre.json` 대신 `$prev.json`) | 변수 명세 (§3) 확인 |
| `Cannot read properties of null (reading 'x')` | `$prev.json` 자체가 null인데 필드 접근 | `$prev.json?.x ?? defaultVal` |
| `SyntaxError: Unexpected token` | `{{ ... }}` 안 JS 문법 오류 | 표현식만 떼서 브라우저 콘솔에서 검증 |
| `Java.type is not defined` | Sandbox에서 차단된 호출 (§4) | 다른 방식으로 우회 |

---

## 7. n8n에서 옮겨오는 사람을 위한 차이점

| 항목 | n8n | station8 |
|---|---|---|
| 문법 | `{{ $json.x }}` | `{{ $prev.json.x }}` (변수 이름 다름) |
| 직전 출력 | `$json` (직전 노드 출력 단축) | `$prev.json` (n8n의 `$node["prev"].json` 형태) |
| 라인 입력 | `$input.first().json` | `$ctx.input` |
| 노드 메타 | `$node["NodeName"]` | `$ctx.line` (현재 노드만) |
| 환경/시크릿 | `$env`, `$secrets` | `$credentials.<name>.value` |
| Sandbox | 제한 적음 | strict — Java 메서드 호출 일체 차단 |
| Item streaming | 1순위 의미론 | M22 별도 마일스톤 — 현재는 평가 단위 = 활동 단위 1회 |

문법은 거의 같고 변수 이름만 다름. `$prev.json` ↔ `$json`, `$ctx.input` ↔ `$input`만 외우면 80%는 그대로 옮겨진다.

---

## 관련 문서

- [docs/HOWTO.md](HOWTO.md) — 액티비티 / DAG / 운영 전반
- [docs/decisions/m16-expression-engine.md](decisions/m16-expression-engine.md) — 엔진 선택 RFC
- [#247](https://github.com/devyoon91/station8/issues/247) — M16 epic
