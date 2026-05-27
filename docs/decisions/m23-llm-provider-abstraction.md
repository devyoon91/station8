# M23 — LLM provider 추상

**상태**: 결정됨 (2026-05-27)
**관련 이슈**: [#335](https://github.com/devyoon91/station8/issues/335), epic [#254](https://github.com/devyoon91/station8/issues/254)

## 결정

M23 AI Agent 통합의 토대가 되는 LLM 호출 추상을 다음과 같이 정한다.

- **wire 포맷은 OpenAI Chat Completions를 1차 기준**으로 삼는다. OpenAI 자체뿐 아니라 Ollama·vLLM·LocalAI·Azure OpenAI가 모두 이 포맷을 따르므로, 한 코드 경로로 클라우드와 폐쇄망 로컬 모델을 같이 덮는다. Anthropic은 wire 포맷이 달라 별도 adapter로 붙인다.
- 토큰/비용은 **별도 `U_LLM_USAGE` 테이블**에 기록한다.
- 응답은 **non-streaming**만 지원한다. streaming의 fan-out 활용은 M22(item-level streaming) 도입 후 재검토.
- **tool calling은 본 RFC에서 추상만 확정**(통합 스키마 + provider별 normalize)하고, 실제 구현은 후속 sub-issue로 분리한다.
- MCP/ACP client는 본 RFC 범위 밖. single-shot LLM 호출 추상에 집중하고 별도 sub-issue로 다룬다.

## 페르소나 — M16 결정의 연장

M16에서 정한 두 사용자 구도가 그대로 이어진다. `@Activity` Java 코드를 짜는 **노드 만드는 사람**과, DAG Builder에서 노드를 엮는 **노드 엮는 사람**. LLM 노드도 노드 엮는 사람이 form에서 model·prompt·temperature를 채워 쓰는 게 1순위 시나리오다.

포지셔닝도 동일하게 n8n 진영이다. n8n의 LangChain 노드군(LLM 호출 + tool + agent 루프)이 M23이 차지하려는 자리다. 다만 n8n이 LangChain에 의존하는 것과 달리, station8은 무거운 agent 프레임워크를 끌고 오지 않고 HTTP + JSON 수준의 얇은 추상으로 간다. 이유는 폐쇄망 적합성과 의존성 무게 통제다.

## Provider 추상

### OpenAI 포맷을 기준으로 잡는 이유

"OpenAI 우선"은 OpenAI라는 회사만 고른다는 뜻이 아니다. OpenAI의 Chat Completions API(`POST /v1/chat/completions`, `messages` 배열, `usage` 토큰 리포트)가 사실상 업계 표준 wire 포맷이 됐다. 로컬 추론 엔진들이 전부 이 포맷의 호환 endpoint를 노출한다.

- **Ollama** — `http://localhost:11434/v1/chat/completions` (OpenAI 호환 모드)
- **vLLM** — OpenAI 호환 서버 모드 기본 제공
- **LocalAI** — 드롭인 OpenAI 대체
- **Azure OpenAI** — 거의 동일 (deployment 이름 차이 정도)

즉 OpenAI 포맷을 base로 잡으면, 사내 GPU에 Ollama 띄운 폐쇄망 환경도 **baseUrl과 apiKey만 바꿔** 같은 코드로 호출된다. 클라우드 OpenAI와 사내 로컬 모델 사이에 코드 분기가 없다. 이게 폐쇄망 default 운영 시나리오와 OpenAI 우선 선택이 충돌하지 않고 오히려 맞물리는 지점이다.

Anthropic Messages API는 wire 포맷이 다르다(`system`이 top-level, `content` 블록 구조, tool 결과 표현 방식 차이). 그래서 OpenAI 호환군과 한 구현으로 묶지 못하고 별도 adapter가 필요하다.

### SPI 구조

```
LlmProvider (SPI)
 ├─ OpenAiCompatibleProvider   // OpenAI / Ollama / vLLM / LocalAI / Azure — (baseUrl, apiKey, model) config로 구분
 └─ AnthropicProvider          // Anthropic Messages API — 별도 wire 포맷
```

공통 contract:

```
LlmRequest  = (model, messages[], systemPrompt?, temperature?, maxTokens?, tools?)
LlmResponse = (content, toolCalls[], usage{inputTokens, outputTokens}, finishReason)
```

`@Activity("llm.chat")`는 이 SPI를 호출할 뿐 provider별 분기를 모른다. provider 선택은 노드에 바인딩된 credential 종류로 결정된다(아래 인증 모델).

1차 구현 범위는 `OpenAiCompatibleProvider` 하나다. 이것만으로 OpenAI + 로컬 모델군이 다 커버되므로 가장 넓은 사용처를 가장 적은 코드로 연다. `AnthropicProvider`는 그다음 sub-issue.

## 인증 모델

M17 credential vault와 엮는다. LLM provider 접속 정보(apiKey, baseUrl)는 코드나 라인 정의에 평문으로 박지 않고 vault credential로 관리한다. M21에서 들어온 credential picker(vault dropdown, [#308](https://github.com/devyoon91/station8/issues/308))를 그대로 재사용해서, 노드 엮는 사람이 form에서 credential을 골라 붙인다.

credential 종류는 provider 계열별로 나눈다.

- **OpenAI-compatible** — `(baseUrl, apiKey)`. baseUrl이 `api.openai.com`이면 클라우드, 사내 Ollama면 `http://ollama.internal:11434/v1`. apiKey가 빈 값이어도 되는 로컬 모델 케이스 허용.
- **Anthropic** — `(apiKey)`. baseUrl 고정.

표현식 엔진(M16)의 `$credentials` 바인딩과도 일관된다. 구체적인 credential type 스키마는 `[FEAT] llm.chat` 구현 sub-issue에서 확정한다.

## 토큰/비용 추적

별도 `U_LLM_USAGE` 테이블에 호출 단위로 기록한다.

```sql
U_LLM_USAGE (
  id                    PK,
  activity_execution_id FK → U_ACTIVITY_EXECUTION,
  provider              VARCHAR,   -- 'openai' / 'anthropic' / 'ollama' ...
  model                 VARCHAR,
  input_tokens          INT,
  output_tokens         INT,
  estimated_cost_usd    DECIMAL(10,6),  -- 단가 미상 모델이면 NULL (토큰은 그대로 기록)
  prompt_hash           VARCHAR,   -- 동일 프롬프트 반복 탐지용
  created_at            TIMESTAMP
)
```

ActivityExecution에 컬럼을 더하는 방식(sparse 컬럼) 대신 별도 테이블을 고른 이유는 LLM 호출이 provider/model/prompt 차원으로 집계 가치가 크기 때문이다. "이번 달 gpt-4o가 claude보다 얼마 더 썼나", "어느 라인의 어떤 프롬프트가 비용 폭주 원인인가" 같은 질문에 join 한 번으로 답할 수 있다. 매 호출 row insert 비용은 LLM 호출 자체(수백 ms~수 초)에 비하면 noise다.

비용 계산은 모델별 단가 테이블로 한다. 단가는 자주 바뀌므로 상수로 박지 않고 설정(application properties 또는 단가 테이블)으로 둔다. 단가를 모르는 모델(사내 파인튜닝 등)은 `estimated_cost_usd`를 NULL로 두되 토큰 수는 기록한다.

## 에러 처리

LLM API는 실패 양상이 일반 HTTP와 다르므로 액티비티 레벨에서 명시적으로 다룬다.

- **Rate limit (429)** — `Retry-After` 헤더를 존중해 bounded retry. 한도 초과 시 액티비티 실패로 격하 → 기존 DLQ 흐름.
- **Context length 초과** — 입력이 모델 한도를 넘으면 액티비티 실패로 격하한다. 자동 truncate는 하지 않는다. 잘린 입력으로 그럴듯하지만 틀린 답이 나오는 게 명시적 실패보다 위험하다.
- **Network / timeout** — M18 `HttpRequestActivity`의 retry 정책을 재사용한다. LLM 호출도 결국 HTTP라 인프라가 겹친다.
- **Provider fallback** (A 실패 시 B로 자동 전환) — 본 RFC 범위 밖. 멀티 provider 라우팅은 복잡도가 커서 후속으로 미룬다.

## Streaming

non-streaming만 지원한다. API 호출 시 streaming을 끄고 완성된 응답을 한 번에 받는다.

station8 노드는 `입력 → 처리 → 출력` 경계가 명확하고, 다음 노드는 이전 노드 출력이 완성된 뒤 실행된다. streaming은 "출력이 시간에 따라 흘러나온다"는 의미인데, 노드 경계에선 어차피 완성본을 넘겨야 하므로 본질적 이득이 사라진다. 내부적으로 streaming 호출 후 collect하는 방식도 코드 복잡도만 늘 뿐 사용자 가치가 없다.

재검토 트리거: M22(item-level streaming, [#253](https://github.com/devyoon91/station8/issues/253))가 들어와 "노드 출력 array → 다음 노드 N번 fan-out" 의미론이 생기면, streaming 응답을 chunk별 fan-out으로 매핑하는 게 의미를 갖는다. 그때 다시 본다.

## Tool calling

기본 LLM 호출은 텍스트 in → 텍스트 out이라 모델이 자기 학습 밖 정보(실시간 조회, 계산, 사내 시스템)를 다루지 못한다. tool calling은 모델이 "이 도구를 이 인자로 호출해달라"고 멈추면 호출자가 실제 실행 후 결과를 모델에 되먹이는 패턴이다. 이게 agent 루프의 핵심 메커니즘이다.

본 RFC는 **추상만 확정**한다.

- **통합 tool 정의** — JSON Schema 기반. OpenAI와 Anthropic 모두 tool 인자를 JSON Schema로 받으므로 공통 형태가 자연스럽다.
- **provider별 normalize** — 응답에서 tool 호출 표현이 다르다. OpenAI는 `tool_calls` 필드, Anthropic은 `content[].type == "tool_use"`. 이를 공통 `LlmResponse.toolCalls: List<ToolCall>`로 정규화한다. 이 로직은 `llm.chat`과 AgenticLoop이 공유하는 자산이라 추상 결정을 RFC에 둔다.

실제 구현(tool 정의 form, 실행 연결, station8 액티비티 자체를 tool로 노출할지)은 무겁고 AgenticLoop과 묶이므로 별도 sub-issue로 뺀다.

## MCP / ACP와의 관계

본 RFC는 stateless single-shot LLM 호출 추상에 한정한다.

- **MCP** (Model Context Protocol — 모델이 외부 도구/데이터 서버에 표준 방식으로 연결하는 프로토콜) client는 별도 sub-issue `[FEAT] mcp.call`.
- **ACP** (Agent Communication Protocol — 에이전트 간 통신 표준) client는 스펙 안정 시점을 봐서 별도 sub-issue.

둘 다 LLM provider 추상 위에 얹히는 상위 레이어라, 토대(본 RFC)를 먼저 깔고 따로 다룬다.

## 폐쇄망 적합성

OpenAI 포맷을 기준으로 잡은 덕에 폐쇄망 default 시나리오가 1급으로 지원된다. 사내 GPU에 Ollama나 vLLM을 띄우고 OpenAI-compatible credential의 baseUrl만 사내 endpoint로 가리키면, 외부 인터넷 호출 0으로 M23 기능 전부(llm.chat, tool calling, AgenticLoop)가 동작한다. 클라우드 OpenAI/Anthropic은 외부 호출이라 폐쇄망에선 자연히 비활성된다.

즉 "폐쇄망에서 LLM 못 쓴다"가 아니라 "폐쇄망에선 로컬 모델로 같은 코드 경로를 쓴다"가 된다.

## 의존성 정책

LLM provider 호출은 결국 HTTP + JSON이다. 공식 SDK(`openai-java`, `anthropic-java`)를 쓰면 편하지만 의존성 무게와 폐쇄망 운반 부담이 늘고, SDK가 특정 wire 버전에 묶인다.

방향: **M18 `HttpRequestActivity` 인프라를 재사용해 HTTP 직접 호출**한다. 추가 의존성을 최소화하고 wire 포맷 변화에 직접 대응한다. JSON 매핑은 이미 있는 Jackson으로 충분하다. 공식 SDK 채택 여부의 최종 판단은 `[FEAT] llm.chat` 구현 sub-issue에서, 실제 코드량을 보고 정한다.

## 후속 작업 (sub-issue)

본 RFC 머지 후 epic [#254](https://github.com/devyoon91/station8/issues/254) 아래로 분리한다.

1. `[FEAT] llm.chat 액티비티 + U_LLM_USAGE 토큰/비용 로깅` — `OpenAiCompatibleProvider` 1종, credential type 확정, non-streaming, 비용 단가 설정. 1차 구현의 핵심.
2. `[FEAT] tool calling 추상 — 통합 JSON Schema + provider normalize` — `LlmResponse.toolCalls` 정규화, `llm.chat`에 tool 지원 추가. AgenticLoop의 전제.
3. `[FEAT] AgenticLoop 컨트롤 노드` — tool 호출 ↔ 결과 되먹임 반복, stop condition, max iterations 안전장치.
4. `[FEAT] AnthropicProvider` — Anthropic Messages API adapter.
5. `[FEAT] mcp.call 액티비티 + tool discovery` — MCP server 연결.
6. `[FEAT] acp.call 액티비티` — ACP 스펙 확정 후.
7. `[DEMO] agent ↔ workflow 양방향 데모` — M20 webhook trigger와 결합해 "agent가 workflow 호출 / workflow가 agent 호출" 양방향.
8. `[DOCS] LLM 노드 사용자 가이드` — 노드 엮는 사람용.

## 참고 자료

- OpenAI Chat Completions API: https://platform.openai.com/docs/api-reference/chat
- Anthropic Messages API: https://docs.anthropic.com/en/api/messages
- Ollama OpenAI 호환 endpoint: https://github.com/ollama/ollama/blob/main/docs/openai.md
- Model Context Protocol (MCP): https://modelcontextprotocol.io/
- n8n AI / LangChain 노드: https://docs.n8n.io/integrations/builtin/cluster-nodes/
