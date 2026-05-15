# M16 — Expression engine 채택

**상태**: 결정됨 (2026-05-15)
**관련 이슈**: [#255](https://github.com/devyoon91/station8/issues/255), epic [#247](https://github.com/devyoon91/station8/issues/247)

## 결정

**GraalVM JavaScript** 를 M16 표현식 엔진으로 채택한다.

문법은 n8n과 동일한 `{{ $prev.json.id }}` / `{{ $ctx.input.x }}` / `{{ $credentials.token }}`.

## 페르소나 — 누가 station8 쓸 사람인가

station8는 두 종류 사용자가 한 라인을 함께 만든다:

- **노드를 만드는 사람** — 사내 시스템 연동을 `@Activity` Java 코드로 짜서 jar plugin으로 배포하는 개발자
- **노드를 엮는 사람** — DAG Builder UI에서 노드를 끌어다 input/output을 매핑하는 분석가/운영자

표현식 엔진은 후자(엮는 사람)가 쓰는 도구다. 즉 결정 1순위는 **노코드 사용자 친화성**.

레퍼런스 포지셔닝: **n8n 진영** (노코드 자동화) — BPMN 클래식 / Camunda 진영이 아닌.

근거:
- 사용자가 station8 확장 검토 시 처음부터 n8n을 비교 대상으로 잡음
- M22(item-level streaming)도 n8n 핵심 의미론을 차용
- jar plugin 모델이 n8n의 community node (npm package) 모델과 동형

## 진짜 후보 4종

| 후보 | 워크플로우 채택 사례 | 점수 |
|---|---|---|
| **GraalVM JavaScript** | n8n, Zapier (사실상 표준) | ⭐⭐⭐⭐⭐ |
| **FEEL (camunda-feel-scala)** | Camunda 8 (DMN 표준) | ⭐⭐⭐ |
| **JUEL** | Camunda 7, Activiti, Flowable | ⭐⭐ |
| **SpEL** | (Spring Integration 한정) | ⭐ |

JEXL은 첫 RFC 초안에 있었으나 워크플로우 메인스트림 사례가 거의 없어 후보에서 제외.

## 평가 기준 (우선순위 순)

1. **노코드 사용자 친화성** — 분석가가 5분 안에 쓸 수 있나
2. **n8n 마이그레이션 학습 곡선** — 사용자가 n8n에서 옮겨올 때 문법 학습 0인가
3. **Sandbox 안전성** — 사용자 표현식이 임의 Java/시스템 호출 못 하게 막을 수 있나
4. **폐쇄망 적합성** — 외부 의존 0, 라이선스 호환
5. **의존성 무게** — 본 저장소가 떠안을 jar 크기

## 후보별 평가

### GraalVM JavaScript ✅ 채택

**채택 근거**:
- 문법이 n8n과 동일 (`{{ $json.x }}`) — 사용자 학습 곡선 0
- `HostAccess.NONE` + `HostAccess.Builder` 화이트리스트로 Java 호출 면을 명시적 제어
- `Context.Builder.allowHostAccess(HostAccess.NONE)` 기본값으로 모든 Java reflection 차단
- Apache License 2.0 / UPL — 라이선스 호환
- 완전 self-contained — 외부 네트워크 호출 0, 폐쇄망 친화
- JS 표준 (ECMAScript 2022 지원) — 사용자가 `Array.prototype.map`, `JSON.parse` 등 익숙한 API 그대로

**비용**:
- jar 크기 ~50MB (graaljs + truffle + sdk) — 본 저장소가 가장 큰 의존성을 떠안음
- 첫 평가 시 JIT 워밍업 비용 (수십 ms) — DAG 노드 단위 평가라 영향 미미하지만 측정 필요
- `polyglot.js.allowHostAccess=false` 등 보안 설정 실수 시 임의 코드 실행 위험 — `ExpressionEvaluator`를 단일 진입점으로 강제하고 default 정책을 deny-all로

**Sandbox 정책 (기본)**:
```
Context.newBuilder("js")
    .allowHostAccess(HostAccess.NONE)        // Java reflection 전면 차단
    .allowIO(false)                          // 파일 시스템 접근 차단
    .allowCreateThread(false)                // 스레드 생성 차단
    .allowNativeAccess(false)                // JNI 차단
    .option("js.console", "false")           // console.log 차단
    .option("js.load", "false")              // load() 차단
    .option("engine.WarnInterpreterOnly", "false")
    .build()
```
사용자 표현식이 접근 가능한 건 LineContext가 `polyglot bindings`로 주입한 객체뿐 (`$prev`, `$ctx`, `$credentials`).

### FEEL — 거부

**채택 시 장점**:
- DMN 표준 — 비즈니스 사용자(법무·재무·HR)에게 이미 알려진 문법
- Camunda 8 진영 사용자 흡수 가능
- 안전한 제한 언어 — 임의 호출 자체가 문법적으로 불가능

**거부 사유**:
- n8n에서 옮겨오는 사용자에겐 낯섦 — `if x > 0 then "y" else "n"` 문법
- 1순위 페르소나(노코드 자동화 사용자)와 안 맞음
- `camunda-feel-scala`는 Scala 런타임을 끌고 옴 (~5MB + Scala stdlib) — GraalVM JS 대비 의존성 큰 차이 아님
- 비즈니스 친화는 강점이지만 station8의 현재 사용자층은 ops/엔지니어 중심

미래: 비즈니스 룰 노드(`feel.evaluate`) 가 별도 액티비티로 등장할 여지는 있음. 표현식 엔진과는 분리.

### JUEL — 거부

**채택 시 장점**:
- 의존성 가장 가벼움 (~100KB)
- BPMN 진영 사용자에게 익숙

**거부 사유**:
- n8n 사용자에게 `${user.name}` JSP-style 문법은 노코드 unfriendly
- 메서드 호출 시 임의 빈 메서드 호출 차단이 까다로움 (`ELContext` 직접 제어 필요)
- 워크플로우 표준이긴 하지만 BPMN 진영 외에선 거의 안 보임 — 채택 시 station8가 "Camunda 클래식 alternative"로 포지셔닝 되는 효과

### SpEL — 거부

**채택 시 장점**:
- 의존성 0 (이미 Spring 의존성에 포함)
- 본 PR 변경 가장 작음

**거부 사유**:
- 문법이 Java-like (`T(클래스).method()`) — 노코드 사용자에게 가장 unfriendly
- 워크플로우 툴 메인스트림 사례 없음 — Spring Integration 내부 사용 정도
- Sandbox 설정 까다로움 — `SimpleEvaluationContext` 강제해야 하고 실수 시 임의 Bean lookup 노출
- 첫 RFC 초안에서 "가까이 있어서" 후보였으나, 페르소나 결정 후 가장 적합도 낮음

## 폐쇄망 적합성

GraalVM JavaScript는 폐쇄망에서 동작에 문제 없음:
- 외부 네트워크 호출 0 — 평가 자체가 in-process
- 라이선스 (Apache 2.0 / UPL) — 폐쇄망 commercial use 호환
- GraalVM JDK가 아닌 **OpenJDK + graaljs jar** 조합으로 충분 — JDK 교체 불필요
- 이미지 빌드 시 한 번 jar 받아두면 이후 인터넷 0

자세한 폐쇄망 가이드는 `docs/SECRETS.md`([#112](https://github.com/devyoon91/station8/issues/112))에서 통합 다룸.

## 의존성 정책

### 추가되는 의존성
```gradle
// station8-engine/build.gradle
implementation 'org.graalvm.polyglot:polyglot:24.1.1'
implementation 'org.graalvm.polyglot:js-community:24.1.1'  // community edition (GPL+CE → UPL)
```

총 ~50MB. 현재 본 저장소 단일 jar 의존성 중 최대. 이미지 크기 영향 측정은 본 구현 PR에서 진행.

### 대안 (의존성 줄이고 싶을 때)
GraalVM JS만 별도 jar plugin 으로 분리하는 옵션도 있음:
- 코어는 `ExpressionEvaluator` 인터페이스만 + default `NoOpEvaluator`
- `station8-expression-js.jar` 가 `plugins/`에 들어오면 GraalVM JS evaluator 활성
- 폐쇄망 사이트가 표현식 안 쓰면 50MB 절약

본 결정에선 default 통합으로 가되, **이미지 크기 측정 결과가 나쁘면** 위 plugin 분리 옵션을 fallback으로 채택.

## 후속 작업

본 RFC가 머지되면:

1. M16 epic ([#247](https://github.com/devyoon91/station8/issues/247)) acceptance 갱신 — "표현식 평가기 (SpEL or Jexl)" → "GraalVM JavaScript"
2. 다음 sub-issue 생성:
   - `[FEAT] ExpressionEvaluator + GraalVM Context 설정 + sandbox 정책`
   - `[FEAT] LineContext 확장 — $prev, $ctx, $credentials polyglot binding`
   - `[FEAT] inputParams 평가 통합 (ActivityProcessor 진입점)`
   - `[DOCS] 표현식 문법 가이드 (사용자 docs)`
   - `[PERF] 이미지 크기 / 부팅 시간 / 평가 latency 측정` — 결과 나쁘면 plugin 분리 옵션 발동

## 참고 자료

- GraalVM Polyglot API: https://www.graalvm.org/latest/reference-manual/embed-languages/
- GraalVM JavaScript Sandbox: https://www.graalvm.org/latest/reference-manual/js/JavaScriptCompatibility/
- n8n expression docs: https://docs.n8n.io/code/expressions/
- Camunda FEEL: https://docs.camunda.io/docs/components/modeler/feel/what-is-feel/
