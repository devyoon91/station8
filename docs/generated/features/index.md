# 기능정의서

Station8이 무엇을 하는 제품인지 사용자·기획자 관점에서 정리한다. "이 버튼을 누르면 무슨 일이 일어나는가", "어떤 제약이 있는가"를 기능 단위로 적었다. 설계를 왜 그렇게 했는지는 [의사결정 문서(ADR)](../../decisions/m16-expression-engine.md)에, 화면별 조작은 화면정의서에, 스키마는 테이블 정의서에 각각 있다.

Station8은 n8n 계열의 노코드 자동화 툴에 가깝다. 역(활동)은 개발자가 `@Activity` Java 코드로 만들고, 라인(DAG)은 운영자가 웹 빌더에서 역을 끌어다 엮는다. 두 종류 사용자가 한 라인을 같이 만드는 구도가 제품 전반에 깔려 있다. 실행 상태·이력·DLQ는 전부 RDBMS에 영속화돼서, 워커가 죽었다 살아나도 이어서 돈다. 폐쇄망/on-prem이 기본 운영 시나리오라, 외부 인터넷 없이도 대부분의 기능이 동작하도록 설계돼 있다.

## 라인 정의와 실행

라인은 코드가 아니라 데이터다. 노드(역)와 엣지(연결)로 이뤄진 DAG를 JSON으로 저장하고, 웹 빌더에서 편집한다. 개발자가 새 라인 파일을 배포하는 게 아니라, 운영자가 `POST /api/line/definitions`로 만들고 `PUT /api/line/definitions/{id}`로 위상 전체를 교체한다. 정의는 `GET /api/line/definitions/{id}/export`로 JSON 파일로 뽑고 `POST /api/line/definitions/import`로 다시 넣을 수 있어서, 환경 간 이관이나 버전 백업에 쓴다. 삭제는 물리 삭제가 아니라 soft-delete(`delFl='Y'`)라 이력이 남는다.

실행은 `POST /api/line/definitions/{id}/run`으로 즉시 시작한다. 이때 입력 데이터와 실행 옵션(실패 시 정책, 런타임 파라미터, 완료 알림 webhook)을 같이 넘길 수 있다. 실행이 시작되면 인스턴스가 하나 생기고, `GET /api/line/instances/{id}/state`로 인스턴스·활동·노드 상태 스냅샷을 폴링해 진행을 본다. 잘못 돌기 시작한 인스턴스는 `POST /api/line/instances/{id}/terminate`로 강제 종료한다.

내부적으로 라인은 durable execution 모델로 돈다. `H_LINE_ACTIVITY_EXECUTION`의 한 행이 노드 1회 실행이고, 워커 풀이 `FOR UPDATE SKIP LOCKED`로 PENDING 행을 집어가 처리한다. DAG 시작 시 노드마다 행 하나를 만들어 두고, 선행 노드가 끝나면 다음 노드를 PENDING으로 승격한다. 조건부 분기는 이 승격 판정에 얹힌다 — 엣지 조건이 표현식으로 평가돼서, 조건이 참인 하류 노드만 진행한다.

### fan-out / fan-in (M22)

노드 출력이 배열이면 다음 노드가 각 원소마다 한 번씩 실행된다. n8n의 핵심 의미론이고 M22가 채운 자리다. HTTP로 레코드 배열을 받거나, CSV 행을 읽거나, LLM에 N건을 돌릴 때 노드 안에서 직접 루프를 돌지 않아도 된다.

핵심은 이걸 새 실행 모델로 만들지 않았다는 점이다. 노드가 배열 `[a, b, c]`를 내면 다음 노드의 실행 행을 원소당 하나씩(`ITEM_INDEX` 0, 1, 2) 만든다. 그러면 세 가지가 공짜로 떨어진다.

- **병렬성** — item 행 3개가 전부 PENDING이면 워커 풀이 동시에 집어간다. 새 동시성 메커니즘이 필요 없다.
- **부분 재시도** — item 1번만 실패하면 그 행만 PENDING으로 남아 재시도되고, 성공한 0·2번은 그대로 완료 상태.
- **DLQ 연동** — item 단위로 최대 재시도를 넘기면 그 item만 DLQ에 적재된다.

fan-out으로 갈라진 하류에서는 `$item`(현재 원소), `$items`(전체 배열), `$itemIndex`(인덱스)를 표현식으로 쓴다. 실행 모드는 병렬이 기본이고, 하류 API나 DataSource를 한 번에 하나씩 때려야 하는 경우(rate limit, 순서 의존)를 위해 노드별 sequential 토글을 둔다. sequential은 `ITEM_INDEX` 순서로 한 번에 하나만 PENDING이 되도록 게이트를 건다.

갈라진 레인을 다시 합칠 때는 collector(aggregate) 노드를 쓴다. collector는 선행 item 출력 K개를 `$items` 배열로 모아 1회만 실행한다. 즉 fan-out은 한 번 열리면 collector를 만날 때까지 하류로 전파된다. 빌더 UI에서 fan-out 노드와 collector 마커를 시각적으로 구분해 보여준다.

제약이 몇 가지 있다. 단일 객체 출력은 length-1 배열의 특수 케이스로 흡수되므로 기존 라인은 그대로 돈다(`ITEM_INDEX DEFAULT 0`). 하지만 중첩 fan-out(item 노드가 또 배열을 내는 경우)의 정확한 의미론은 아직 확정되지 않았고, 1단계 fan-out + 명시적 collector만 먼저 지원한다. collector가 K개 중 일부가 DLQ로 빠졌을 때 부분 배열로 진행할지 막을지도 미해결이다. K가 수만 건인 대량 fan-out은 행 폭증 우려가 있어 상한 정책과 함께 후속으로 검토 중이다. 자세한 결정 배경은 [M22 item streaming ADR](../../decisions/m22-item-streaming.md).

## 스케줄링

정의를 반복 실행하려면 cron 스케줄을 건다. `POST /api/line/schedules`로 cron 식을 등록하고, `PUT /api/line/schedules/{id}`로 식을 바꾼다. 등록 전에 `GET /api/line/schedules/preview-cron`으로 식이 유효한지, 다음 몇 번이 언제 매치되는지 미리 확인할 수 있다.

일시 정지와 재개가 분리돼 있다. `PUT /api/line/schedules/{id}/pause`를 하면 스케줄이 남아 있되 새 실행을 만들지 않고, `PUT .../resume`으로 다시 켠다. 정의를 지우지 않고 잠깐 멈추고 싶을 때 쓴다. cron을 기다리지 않고 지금 한 번 돌리고 싶으면 `POST /api/line/schedules/{id}/run-now`로 즉시 트리거한다. 스케줄도 soft-delete라 이력이 남는다.

## 트리거

라인을 시작시키는 경로는 세 가지다 — 수동 실행(위 `.../run`), cron 스케줄, 그리고 외부 시스템이 부르는 webhook.

webhook 트리거는 관리자가 `POST /api/line/triggers`로 만든다(ADMIN 권한 필요). 만들 때 configJson에 `hmacSecret`을 넣는데, 여기엔 평문 secret이 아니라 vault credential 이름(type `webhook_hmac`)만 박힌다. 외부 시스템은 `POST /api/triggers/webhook/{key}`로 호출한다. 이 endpoint 자체는 로그인을 요구하지 않는다(permitAll) — HMAC이 인증 역할을 대신한다.

들어온 요청은 순서대로 검증을 거친다. key로 트리거를 찾아 활성 상태·webhook 타입인지 보고, 허용 method를 확인하고, rate limit(트리거별 `maxPerMinute`/`burstSize`, 초과 시 429)을 건다. 그다음이 서명과 리플레이 방어다.

- **HMAC 서명** — `X-Signature` 헤더에 HMAC-SHA256(payload, secret)의 hex 값을 담아 보낸다. 서버는 vault에서 secret을 꺼내 같은 값을 계산하고 constant-time 비교로 맞춰본다(timing 공격 방어).
- **리플레이 방어** (기본 활성) — `X-Timestamp`(epoch millis) 헤더가 필수다. 현재 시각 ± `station8.webhook.replay-window-seconds` 안이어야 하고, HMAC 대상 payload가 `timestamp + "\n" + body`로 바뀐다. 같은 `(key, timestamp, signature)` 튜플은 window 안에서 한 번만 통과한다. 서명이 맞은 다음에 dedup을 기록해서, 외부가 보낸 임의 서명으로 캐시를 오염시키지 못하게 막는다.

검증을 통과하면 body가 라인 입력이 되어 인스턴스가 시작된다. 응답으로 `instanceId`, `started`, `definitionId`가 돌아온다. onFailure/중복 실행 정책 때문에 시작이 막히면 `started: false`와 함께 사유가 온다.

리플레이 방어를 끄면(legacy) payload가 body만으로 계산되고 timestamp 검증이 사라진다. 하지만 그러면 재전송 공격에 노출되므로 기본값을 켜둔 채 쓰는 게 맞다. 트리거 관리 API의 목록/조회는 로그인 사용자면 되지만 생성/수정/삭제는 ADMIN만 가능하다.

## 재시도와 DLQ

활동이 실패하면 엔진이 exponential backoff로 재시도한다. 활동마다 `@Activity(retryCount=..., backoffSeconds=...)`로 재시도 횟수와 간격을 정한다 — 예를 들어 `http.request`는 3회/2초, `llm.chat`은 2회/5초다. 다만 재시도가 무의미한 실패는 즉시 최종 실패로 격하한다. 입력 형식 오류, HTTP 4xx, 정책 위반(SSRF/path traversal), LLM context length 초과 같은 것들이 `NoRetryException`으로 분류돼 backoff 없이 바로 실패한다. 재시도해봐야 같은 결과라서다.

최대 재시도를 넘긴 활동은 DLQ(dead letter queue)로 간다. `GET /line/dlq`에서 workflow 이름·활동 이름·에러 메시지·상태로 필터해 목록을 보고, `GET /line/dlq/{id}`로 에러 메시지·스택트레이스·재시도 횟수를 확인한다. 처리는 두 갈래다.

- **재처리** — `POST /line/dlq/{id}/requeue`로 같은 인스턴스에 새 PENDING 활동을 만들어 다시 태운다. 외부 시스템이 잠깐 죽었다가 살아난 경우처럼, 조건이 바뀐 뒤 다시 돌리고 싶을 때.
- **폐기** — `POST /line/dlq/{id}/discard`로 DISCARDED 처리해 포기한다.

DLQ까지 안 가고 실행 중인 인스턴스의 특정 활동만 다시 돌리고 싶으면 `POST /line/instance/{id}/activity/{execId}/retry`로 그 활동만 PENDING으로 되돌린다.

활동이 DLQ에 적재되는 순간 webhook으로 알림을 쏘는 기능도 있다(M20 `WebhookDlqNotifier`). DLQ 항목 JSON을 설정된 URL로 POST해서, 운영자가 대시보드를 안 보고 있어도 실패를 알아채게 한다.

## 빌트인 액티비티

개발자가 직접 만든 활동 외에, 엔진에 기본 내장된 활동이 넷 있다. 노드 엮는 사람이 form에 파라미터만 채우면 바로 쓴다. 모두 `@Activity` 메타로 정의돼 있어 활동 카탈로그(`GET /api/line/activities`)에 노출되고, 입력값에는 표현식을 쓸 수 있다.

### http.request — 외부 API 호출

절대 URL로 HTTP 요청을 보낸다. method는 GET/POST/PUT/DELETE/PATCH만 허용하고 그 외는 즉시 실패한다. body는 POST/PUT/PATCH에서만 쓰며, object/list를 넣으면 자동으로 JSON 직렬화하고 `Content-Type: application/json`을 붙인다. `credentialId`를 지정하면 vault credential 타입에 따라 Authorization 등을 자동 주입한다(아래 표). 사용자가 headers에 같은 키를 명시하면 그 값이 이긴다.

응답은 상태 코드로 갈린다. 2xx/3xx는 성공, 4xx는 재시도 무의미로 즉시 실패, 5xx와 네트워크 오류/타임아웃은 재시도 대상이다. 호출 직전 [SSRF 방어 정책](../../HTTP_POLICY.md)이 최종 URL을 검사해서, 클라우드 metadata나 사내 내부망을 찌르는 요청을 막는다.

| 파라미터 | 종류 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `method` | select | 예 | GET | GET/POST/PUT/DELETE/PATCH |
| `url` | string | 예 | — | 절대 URL(http/https). 표현식 가능 |
| `headers` | object | | `{}` | 추가 헤더. credential 자동 헤더보다 우선 |
| `body` | object | | | POST/PUT/PATCH 본문. string이면 그대로, object면 JSON |
| `timeoutMs` | number | | 30000 | ms 단위. 최대 300000(5분) |
| `credentialId` | credential | | | vault 이름. 타입별 Authorization 등 자동 주입 |

credential 타입별 자동 헤더 주입:

| type | 주입되는 헤더 |
|---|---|
| `http_bearer` | `Authorization: Bearer <value>` |
| `http_basic` | `Authorization: Basic base64(username:value)` |
| `api_key` | schema의 `header` 키에 value (예: `X-API-Key: <value>`) |
| `generic` | 자동 주입 없음 — headers에 직접 표현식으로 |

재시도 정책은 3회/2초.

### file.read / file.write — 파일 입출력

URI scheme으로 backend를 고른다 — `file://`(로컬), `sftp://`, `s3://`. 셋 다 구현돼 있고, SFTP/S3는 credential로 인증한다(S3-compatible인 MinIO·Ceph도 같은 코드로 커버). local backend는 인증이 없는 대신 [path traversal 방어 정책](../../FILE_POLICY.md)이 걸린다. `station8.file.local.allowed-roots`에 명시한 디렉토리 안에서만 읽고 쓸 수 있고, 이 값이 비어 있으면 local 파일 활동 자체가 fail-closed로 전부 막힌다.

format이 read/write 양쪽에서 데이터 형태를 정한다 — `text`(charset decode/encode), `json`(Jackson parse/serialize), `binary`(Base64). read는 `{uri, format, sizeBytes, content}`를 돌려주고, write는 `{uri, sizeBytes}`를 돌려준다.

file.read 파라미터:

| 파라미터 | 종류 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `uri` | string | 예 | — | file/sftp/s3 URI 또는 절대 path. 표현식 가능 |
| `format` | select | | text | text/json/binary. binary는 Base64로 |
| `encoding` | string | | UTF-8 | text/json 모드 charset |
| `credentialId` | credential | | | SFTP/S3 인증용. local은 무시 |

file.write는 위에 더해 `content`(object)를 받는다 — format에 맞춰 text=String, json=Object, binary=Base64 String. 둘 다 재시도 3회/2초.

### llm.chat — LLM 호출

LLM에 프롬프트를 던져 응답을 받는다. `credentialId`가 가리키는 credential의 타입이 provider를 결정한다 — `openai_compatible`은 OpenAI/Ollama/vLLM/LocalAI/Azure를 한 경로로 덮고, `anthropic`은 별도 adapter로 붙는다. 폐쇄망에서는 credential의 baseUrl만 사내 Ollama/vLLM endpoint로 바꾸면 외부 호출 0으로 같은 코드가 돈다.

메시지는 두 방식이다. `prompt`(+ 선택적 `systemPrompt`)로 단일 user 메시지를 주거나, `messages`에 `[{role, content}]` 목록을 명시한다. `tools`를 넘기면 모델이 도구 호출을 요청할 수 있고, 그 요청은 응답의 `toolCalls`로 돌아온다(실제 실행은 안 함 — 실행까지 반복하려면 아래 llm.agent).

응답은 non-streaming으로 한 번에 받는다. 노드 경계에선 어차피 완성본을 넘겨야 해서 streaming의 이점이 사라진다는 판단이다. 호출마다 토큰·비용이 `H_LINE_LLM_USAGE`에 기록돼서, 어느 라인·프롬프트가 비용을 얼마나 쓰는지 집계할 수 있다. 단가를 모르는 사내 모델은 토큰만 기록하고 비용은 null로 둔다. 429/5xx/네트워크는 재시도, context length 초과·인증 오류는 즉시 실패다. usage 기록이 실패해도 활동은 성공 처리한다 — LLM 호출은 이미 과금됐으니 재시도로 이중 과금하지 않으려는 것.

| 파라미터 | 종류 | 필수 | 설명 |
|---|---|---|---|
| `credentialId` | credential | 예 | provider 접속 credential. 타입이 provider 결정(openai_compatible/anthropic) |
| `model` | string | 예 | 모델 식별자(예: gpt-4o, llama3.1) |
| `prompt` | string | | 단일 user 메시지(messages 안 줄 때) |
| `systemPrompt` | string | | system 메시지(prompt 모드) |
| `messages` | object | | `[{role, content}]` 명시 목록 |
| `temperature` | number | | 샘플링 온도. 비우면 provider 기본값 |
| `maxTokens` | number | | 응답 최대 토큰 |
| `tools` | object | | 도구 정의 목록. 모델이 호출 요청 시 toolCalls로 반환 |

재시도 정책은 2회/5초.

### llm.agent — agentic loop

"LLM 호출 → 도구 호출 요청 → 실행 → 결과 되먹임 → 다시 호출 → ... → 모델이 멈출 때까지" 반복하는 agent 루프다. n8n AI Agent 노드에 해당한다. llm.chat이 도구 호출 요청을 돌려주기만 하는 반면, 여기서는 그 도구를 실제로 실행하고 결과를 대화에 다시 넣어 모델을 재호출한다.

도구는 노드 config의 `tools`가 노출 목록이자 실행 allowlist를 겸한다. 모델이 목록 밖 도구를 부르면 거부 메시지를 결과로 되먹인다. 도구 실행이 실패하면 에러 텍스트를 되먹여서 모델이 재시도·우회·포기를 판단하게 하고, 루프는 죽지 않고 계속된다. 도구 이름은 등록된 활동으로 매핑된다.

안전장치로 `maxIterations`가 있다(기본 10, 상한 50으로 클램프). 무한 루프와 비용 폭주를 막는다. 각 iteration마다 usage를 기록해서 누적 비용을 볼 수 있다. 결과에는 최종 응답, 실제 반복 횟수, 종료 사유(`stop`은 모델이 도구 없이 끝냄, `max_iterations`는 상한 도달), 전체 토큰 합, 도구 호출 추적이 담긴다.

| 파라미터 | 종류 | 필수 | 설명 |
|---|---|---|---|
| `credentialId` | credential | 예 | provider 접속 credential(type openai_compatible) |
| `model` | string | 예 | 모델 식별자 |
| `prompt` | string | 예 | 최초 user 메시지 |
| `systemPrompt` | string | | system 메시지 |
| `tools` | object | | 노출+실행 허용 도구. name은 등록된 활동 |
| `maxIterations` | number | | 최대 반복. 기본 10, 상한 50 |
| `temperature` | number | | 샘플링 온도 |
| `maxTokens` | number | | iteration당 응답 최대 토큰 |

재시도 정책은 1회/5초. LLM provider 추상의 설계 배경은 [M23 LLM provider ADR](../../decisions/m23-llm-provider-abstraction.md).

## 크리덴셜 볼트

라인 활동이 외부 시스템에 접근할 때 쓰는 secret을 보관하는 작은 vault다. 평문을 DB에 그대로 두지 않고, 마스터 키 하나로 AES-GCM 256비트(변조 감지가 결합된 표준 대칭 암호)로 암호화해 `U_LINE_CREDENTIAL.VALUE_ENC`에 저장한다. 저장 형식은 `Base64(IV(12B) || ciphertext || authTag(16B))`이고, 암호화마다 IV를 새로 뽑는다. 마스터 키는 앱 안에 없고 환경변수 `STATION8_CREDENTIAL_KEY`(Base64 32바이트)로 외부 주입한다 — 키 관리가 곧 vault 관리다.

관리자가 `POST /api/line/credentials`로 등록하면 값이 암호화돼 저장된다. 목록·조회 API(`GET /api/line/credentials`)는 메타데이터만 돌려주고 복호화된 값은 절대 응답에 싣지 않는다. 값 갱신(`PUT`)은 새 값을 주면 rotation하고, 비우면 기존 암호문을 유지한다. 등록·수정·삭제는 ADMIN만, 목록·조회는 로그인 사용자면 된다.

credential은 용도별 타입으로 나뉜다. 활동이 form에서 타입에 맞는 credential을 골라 붙이고, 활동 코드는 타입을 보고 어떻게 쓸지 정한다.

- HTTP 인증 — `http_bearer`, `http_basic`, `api_key`, `generic`
- 파일 backend — `sftp_password`, `sftp_key`, `s3_access_key`
- webhook 트리거 — `webhook_hmac`
- LLM provider — `openai_compatible`, `anthropic`

활동 실행 중 표현식으로 값을 끌어올 때는 `{{ $credentials.<name>.value }}`로 접근한다. 이 시점에 한 번 복호화된다. DataSource 비밀번호는 별도 layer라 vault와 다른 영역이다 — 부팅 시 DB pool을 만들기 위한 secret과, 라인이 실행 도중 쓰는 secret은 라이프사이클이 다르다. 자세한 운영은 [Credential Vault 가이드](../../SECRETS.md).

## 데이터소스와 표현식

Station8은 기본 DB 외에 여러 DataSource를 등록해 쓸 수 있다. `/admin/datasources`에서 PRIMARY/STATIC/DYNAMIC 데이터소스를 관리한다. DYNAMIC 데이터소스는 앱을 재시작하지 않고 만들고(`POST /admin/datasources`) 풀을 교체할 수 있고, `.../test`로 연결(SELECT 1)과 지연을 확인한다. 활성/비활성 토글로 풀 생성 자체를 켜고 끌 수 있으며, 비활성 데이터소스는 풀을 만들지 않는다. 목록 화면에 풀 통계(active/idle/total 커넥션)가 같이 뜬다.

노드 입력값에는 `{{ ... }}` 안에 JavaScript 표현식을 써서 직전 노드 출력·라인 입력·런타임 값을 끌어온다. 문법은 n8n과 동일하다 — `{{ $prev.json.id }}`, `{{ $ctx.input.x }}`, `{{ $credentials.token.value }}`. n8n에서 옮겨오는 사용자의 학습 곡선이 거의 없도록 GraalVM JavaScript를 엔진으로 골랐다.

표현식은 sandbox 안에서 평가된다. Java reflection·파일 IO·스레드 생성·JNI·`console.log`·`load()`가 전부 차단돼 있고, 사용자 표현식이 만질 수 있는 건 LineContext가 주입한 바인딩(`$prev`, `$ctx`, `$credentials`, 그리고 fan-out 레인의 `$item`/`$items`/`$itemIndex`)뿐이다. 정적 입력(`{{` 없는 값)은 평가를 건너뛰어 사실상 무료고, 표현식 1건은 수백 µs 수준이라 활동 단위 비용(DB 쿼리 ms, HTTP 호출 100ms)에 묻힌다. 빌더에서 `POST /api/line/expressions/_evaluate`로 표현식을 실제 값에 대해 미리 돌려볼 수 있다. 문법과 예제는 [표현식 가이드](../../EXPRESSIONS.md), 엔진 선택 배경은 [M16 표현식 엔진 ADR](../../decisions/m16-expression-engine.md).

## 보안과 ACL

접근 제어는 역할과 per-line 권한 두 축이다.

역할 쪽은 ADMIN이 관리자 기능을 쥔다. `/admin/**` 경로(사용자·크리덴셜·데이터소스·플러그인 관리)는 ADMIN만 들어간다. 사용자 관리(`/admin/users`)에서 계정 생성·비밀번호 리셋·활성화 토글·삭제를 하는데, 비밀번호는 정책 검증을 거치고 자기 자신은 비활성화하거나 삭제할 수 없다.

per-line 권한은 grant 기반이다. `LineAclService`가 라인마다 READ/WRITE/EXECUTE/SCHEDULE 권한을 따진다. 라인을 만든 사람은 그 라인의 ADMIN grant를 자동으로 받고, 관리자가 다른 사용자에게 개별 권한을 부여한다. non-admin 사용자는 READ 권한이 있는 정의만 목록에서 본다. 컨트롤러 메서드가 `canWrite(id)`, `canExecute(id)`, `canSchedule(id)` 같은 ACL 식으로 보호돼서, 권한 없는 정의는 수정·실행·스케줄이 막힌다.

## 플러그인

빌트인 활동과 개발자가 앱에 직접 넣은 활동 외에, 외부 jar에 담긴 `@Activity`를 호스트에 얹을 수 있다. 플러그인은 그냥 Java 클래스다 — Spring 컨텍스트를 안 거치고, 호스트가 `URLClassLoader`로 jar를 열어 no-arg 생성자로 객체를 만들고 `@Activity` 메서드를 부른다. 그래서 플러그인 클래스에는 public no-arg 생성자와 `@Activity`가 붙은 public 메서드가 최소 하나 있어야 한다.

관리자가 `/admin/plugins`에서 jar를 업로드한다. 업로드는 검증을 거친다 — 확장자가 `.jar`, zip 매직 바이트(`50 4B 03 04`)로 시작, 50MB 이하, 파일명 sanitize. 같은 이름 jar가 이미 있으면 `.bak`으로 백업하고 덮어쓴다. 업로드한다고 바로 활성화되진 않고, `POST /admin/plugins/reload`로 핫로드하거나 앱을 재시작해야 `@Activity`가 등록된다. reload는 `plugins/` 디렉토리를 다시 스캔해서 새 활동을 붙이고 중복을 걸러내며, 결과로 added/conflicts/skipped/failed를 돌려준다. 자동 파일 watcher는 없다 — 명시적 reload만.

한 가지 흔한 함정은 호스트 의존성을 jar에 같이 넣는 것이다. `com.station8.*`이나 Spring 클래스가 jar 안에 들어가면 ClassLoader 충돌로 깨질 수 있어서, 플러그인은 그 의존성을 `compileOnly`로 빌드해야 한다. 운영 절차는 [플러그인 운영 가이드](../../PLUGINS.md), 코드 작성은 [플러그인 개발 가이드](../../PLUGIN_DEVELOPMENT.md).
