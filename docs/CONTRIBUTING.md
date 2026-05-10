# CONTRIBUTING

본 문서는 ``station8`` 저장소의 **브랜치 / 커밋 / PR 형식 규정**을 정의한다.
작업 흐름(이슈 → 브랜치 → 변경 → PR → 리뷰)과 협업 원칙은 [AGENTS.md](../AGENTS.md)를 참조한다.

> **AI 에이전트 주의**: 본 저장소는 ``.gitmessage`` 커밋 템플릿과 ``.github/pull_request_template.md`` PR 템플릿을 제공한다.
> 매번 같은 규격으로 PR/커밋을 만들기 위해 두 템플릿을 그대로 따른다.

---

## 1. 브랜치 전략

- **``main``** — 단일 trunk. 모든 변경은 PR을 통해서만 머지된다.
- **토픽 브랜치** — 모든 작업은 ``main``에서 분기한 토픽 브랜치에서 진행한다.
  - 형식: ``<issue#>-<짧은-영문-설명>``
  - 예: ``76-contributing-rewrite``, ``42-skip-locked-poller``, ``18-dlq-discard-button``
  - 이슈번호가 **반드시 brand 가장 앞**에 와야 한다 (브랜치 목록만 봐도 어느 이슈인지 식별).
  - 짧은 설명은 영문 kebab-case, 5단어 이내.
- ``develop``/``release/*``/``hotfix/*`` 등 멀티 trunk 전략은 사용하지 않는다.

## 2. 커밋 컨벤션

### 2.1 형식

```
<TYPE>: <한국어 제목 (50자 내외)>

<한국어 본문 — "무엇을 / 왜" 변경했는지 (한 줄당 72자 이내로 줄바꿈)>

Closes #<이슈번호>
```

### 2.2 Type 키워드

| Type | 용도 |
|---|---|
| ``FEATURE`` | 새 기능 추가 |
| ``FIX`` | 버그 수정 |
| ``DOCS`` | 문서 추가/수정/삭제 |
| ``REFACTOR`` | 동작 변경 없는 코드 구조 개선 |
| ``TEST`` | 테스트 코드 추가/수정 |
| ``CHORE`` | 빌드/설정/의존성/CI 등 잡무 |

복합 변경은 ``DOCS+CHORE`` 같은 결합도 허용한다.

### 2.3 작성 규칙

- **제목**: ``TYPE:`` 뒤 한 칸 띄고 한국어 제목. 마침표(``.``) 금지. 명령조보다 변경의 요지를 압축.
- **본문**: 한국어. 무엇을 바꾸었는가 + 왜 바꾸었는가. 한 줄당 72자 내 직접 줄바꿈.
- **마지막 줄**: ``Closes #<번호>`` 로 이슈 자동 닫힘 링크.
- **금지**: ``🤖 Generated with ...`` 푸터, ``Co-Authored-By: Claude/Bot/...`` 라인 등 **도구 어트리뷰션을 절대 포함하지 않는다.**

### 2.4 예시

```
FIX: 웹 화면 한글 깨짐 — 응답 UTF-8 강제 설정

Spring Boot 3.x는 server.servlet.encoding.force 기본값이 false여서 Tomcat이
플랫폼 기본 charset(Windows 한글 환경의 MS949 또는 HTTP 1.1 기본 ISO-8859-1)으로
응답을 직렬화 → 한글이 ``?``로 변환되어 화면에 표시된다.

application.properties에 force=true 등 인코딩 설정을 추가하여 OrderedCharacterEncodingFilter가
플랫폼 무관하게 응답 charset을 UTF-8로 강제 설정하도록 한다.

Closes #75
```

### 2.5 커밋 템플릿 적용 (1회)

저장소 로컬에 한해 커밋 템플릿을 자동 사용하도록 설정한다.

```bash
git config commit.template .gitmessage
```

이후 ``git commit`` 실행 시 [.gitmessage](../.gitmessage)가 에디터에 자동 로드된다.

---

## 3. Pull Request 절차

1. ``main``에서 ``<issue#>-...`` 형식 토픽 브랜치를 만든다.
2. 변경을 커밋하고 origin으로 푸시한다.
3. ``gh pr create`` 또는 GitHub UI로 PR을 만든다 — [.github/pull_request_template.md](../.github/pull_request_template.md)가 자동 적용된다.
4. PR 본문의 체크리스트를 모두 채운다 (``Closes #N``, 변경 요약, Test plan, 영향 범위).
5. CI(빌드/테스트) 통과를 확인한다.
6. 리뷰어 1인 이상의 승인 후 머지(Squash merge 권장).
7. 머지 시 토픽 브랜치는 자동/수동으로 삭제한다.

### 3.1 PR 제목 형식

커밋 제목과 동일한 ``<TYPE>: <한국어 제목> (#<이슈번호>)`` 형식.
예: ``FIX: 웹 화면 한글 깨짐 — 응답 UTF-8 강제 설정 (#75)``

### 3.2 Squash merge 시 커밋 메시지

GitHub Squash 화면에서 PR 본문이 squash 커밋의 본문이 되므로, **PR 본문도 위 커밋 본문 규칙(한국어, 도구 어트리뷰션 금지)을 따른다.**

---

## 4. 코딩 스타일

### 4.1 로깅

- **로그 메시지 본문에 이슈 번호를 노출하지 않는다.**
  - 운영 환경 콘솔 로그는 사용자/운영자가 보는 출력이므로, 내부 트래킹용 식별자(`[#141]`, `[#164]` 등)가 노이즈가 된다.
  - 이슈 추적은 코드 주석 / Javadoc / 커밋 메시지로 충분하다.

```java
// ✗ 금지 — 콘솔에 [#141] 노출
log.warn("[#141] 동시 실행 SKIP — definitionId={}", definitionId);

// ✓ 권장 — 메시지만
log.warn("동시 실행 SKIP — definitionId={}", definitionId);
// 필요하면 Javadoc/주석에 이슈 표기
```

- 로그 레벨 가이드:
  - `DEBUG` — 진입/평가 결과 (운영 시 끔)
  - `INFO` — 의도적 상태 변화 ("정의 생성: id=...")
  - `WARN` — 부분 실패 / fallback 사용
  - `ERROR` — 운영 이슈 / 데이터 손실 가능성

### 4.2 제어문 — 중괄호 필수

**단일 if/else/for/while 문도 반드시 중괄호 사용. 한 줄 if-return 금지.**

```java
// ✗ 금지
if (workflowName == null) return true;
if (def == null) return;
if (cond) doSomething();

// ✓ 권장
if (workflowName == null) {
    return true;
}
if (def == null) {
    return;
}
if (cond) {
    doSomething();
}
```

이유:
- 단일 라인 추가 시 누락 방지 (Apple goto fail 류 사고 방지)
- diff에서 영향 범위 명확
- IDE 자동 정렬에 일관

### 4.3 Javadoc 준수

**모든 public 타입(class/interface/enum/record)은 클래스 Javadoc을 반드시 작성하고, 모든 public 메서드/생성자/필드도 Javadoc을 작성한다.** package-private이라도 비자명한 동작이면 작성 권장.

#### 4.3.1 필수 항목

| 대상 | 필수 |
|---|---|
| `public class` / `interface` / `enum` / `record` | **클래스 Javadoc 필수** — 책임, 핵심 협력 객체, 디자인 패턴 |
| `public` 메서드 / 생성자 | 동작 설명 + `@param` (모든 인자) + `@return` (void 아닌 경우) + `@throws` (선언/주요 unchecked 예외) |
| `public` 상수 / 필드 | 의미 + 단위 / 허용 범위 |
| record component | record 본문 Javadoc 안에서 `@param name 설명` 형식으로 — record는 component별 Javadoc을 component 위에 따로 못 붙임 |
| enum 상수 | 각 상수 위에 한 줄 Javadoc — 의미 + 사용 시점 |

#### 4.3.2 클래스 Javadoc 위치 — 어노테이션 위

**클래스/타입 Javadoc은 반드시 클래스 어노테이션 위에 위치한다.** 어노테이션과 타입 선언 사이에 끼우면 Javadoc 도구가 인식 못 하거나 IDE 인덱싱이 깨진다.

```java
// ✗ 금지 — Javadoc이 어노테이션 아래에 위치
@Service
@Transactional
/**
 * 라인 정의 등록/수정/삭제 서비스.
 */
public class LineDefinitionService { ... }

// ✓ 권장 — Javadoc → 어노테이션 → 타입 선언
/**
 * 라인 정의 등록/수정/삭제 서비스.
 */
@Service
@Transactional
public class LineDefinitionService { ... }
```

같은 규칙이 메서드/필드의 Javadoc에도 적용 — Javadoc은 항상 어노테이션 위.

#### 4.3.3 작성 규칙

- **언어**: 한국어 — 단, 식별자(`@param x`, 클래스명)는 그대로
- **첫 문장 = 한 줄 요약** — Javadoc 인덱스에 그대로 노출되므로 마침표로 종결되는 한 문장
- **이슈 번호 포함 OK** — 로그와 달리 Javadoc은 추적 정보가 권장됨. 예: `<p>#164 — Pipeline 모드 게이트.</p>`
- **디자인 패턴 명시** — Strategy/Repository/Facade 등 적용 패턴이 있으면 첫 단락에서 언급
- **상호 참조** — 협력 객체는 `{@link OtherClass}` / `{@link #method}` / `{@code 표현식}` 사용
- **TODO/FIXME 금지** — Javadoc/주석에 TODO 쓰지 말고 GitHub 이슈로 분리 (§4.5와 일관)

#### 4.3.4 예시

```java
/**
 * #177 — Concurrency 정책의 Strategy 추상화.
 *
 * <p>{@link ConcurrencyPolicy} enum이 DB/직렬화 키 역할이라면, 본 sealed interface는
 * 정책별 실제 동작을 다형성으로 분리한다 — `if (policy == X)` 분기를 제거 (OCP).</p>
 *
 * <h3>두 게이트 시점</h3>
 * <ul>
 *   <li>{@link #evaluateOnStart} — 인스턴스 시작 시점.</li>
 *   <li>{@link #evaluateOnDispatch} — 활동 dispatch 시점.</li>
 * </ul>
 */
public sealed interface ConcurrencyStrategy { ... }

/**
 * 활동 dispatch 가능 여부를 판단.
 *
 * @param instanceId   현재 인스턴스 ID
 * @param nodeId       dispatch 대상 노드 ID
 * @param workflowName 정의 이름 (활성 정의 lookup용)
 * @return true = dispatch 진행, false = 게이트로 차단 (호출자가 PENDING 복구)
 */
public boolean canDispatch(String instanceId, String nodeId, String workflowName) { ... }
```

record component 예시:

```java
/**
 * Dispatch 시점 context.
 *
 * @param instanceId               본인 인스턴스
 * @param nodeId                   dispatch 대상 노드
 * @param workflowName             워크플로 이름
 * @param myStep                   본 노드의 위상 단계
 * @param nodesAtStep              step → 그 단계 노드 ID 집합 lookup
 * @param priorInstanceIds         선행 RUNNING 인스턴스 ID 목록 (자기 제외)
 * @param isNodeCompletedInPrior   (priorId, nodeId) → 해당 노드 COMPLETED?
 * @param isAnyNodeStartedInPrior  (priorId, targets) → 노드 집합 중 하나라도 STARTED?
 */
record DispatchContext(...) {}
```

#### 4.3.5 미준수 처리

- 신규 코드: PR 리뷰에서 차단 사유 (CI 자동 검사는 별도 follow-up)
- 기존 코드: 만지는 메서드/클래스에 누락이 있으면 함께 추가. 일괄 정리는 별도 REFACTOR PR

### 4.4 임포트 정리

**안 쓰는 import 문은 반드시 제거한다.** PR 머지 전 IDE 자동 정리(IntelliJ: `Ctrl+Alt+O` / Eclipse: `Ctrl+Shift+O`) 또는 수동으로 제거.

- 미사용 import는 컴파일 시 deprecation 경고 또는 일부 IDE 경고로 표시되며, 클래스 의존을 흐리게 만들어 리팩터 시 잘못된 영향 분석을 유발한다.
- `import static`도 동일하게 미사용은 제거.
- 와일드카드 import(`import x.*`) 지양 — 명시적 단일 import 유지 (단, 같은 패키지에서 5개 이상 사용 시 IDE 자동 변환 허용).

### 4.5 인라인 주석

#### 4.5.1 중요 로직 주석 — 필수

**비자명한 동작 / 부수효과 / 트레이드오프가 있는 구간에는 인라인 주석을 작성한다.** 코드만 봐서는 "왜 이렇게 했는지" 알 수 없는 결정을 명문화.

작성 대상:
- **검증 / 락 / 트랜잭션 경계** — `// FOR UPDATE 락 보유 — 트랜잭션 끝까지 race 방지`
- **데드락/edge case 회피** — `// 단계 S+gap에 노드 없으면 파이프라인 끝 — 데드락 회피로 통과`
- **순서가 중요한 분기** — `// 1) ACL 검사가 SQL 보다 먼저 — 미인증 사용자가 DB 부담 주지 않도록`
- **외부 시스템 가정** — `// Drawflow 0.0.59는 connection 클래스명에 input/output 인덱스 포함`
- **임시/방어 코드** — `// 다른 활동이 이미 트리거 → idempotent 처리`

스타일:
- 한국어 본문 (식별자는 그대로)
- "무엇을" 보다 "왜"에 초점 (코드는 무엇은 보여줌)
- 한 줄로 충분하면 `//`, 여러 줄이면 `/* ... */`
- 주석은 항상 해당 코드 **바로 위** — 같은 줄 trailing 주석은 짧은 단위 변환에만 (`// seconds`)

#### 4.5.2 TODO / FIXME 등 마커 주석 — 금지

**TODO / FIXME / XXX / HACK 마커는 코드에 남기지 않는다.** 모두 GitHub 이슈로 분리해 추적.

이유:
- 코드 검색으로만 추적되어 실수로 머지된 후 잊힘
- 우선순위 / 담당자 / 의존성 관리가 git blame에 묻혀 가시성 ↓
- 이슈로 빼면 라벨 / 마일스톤 / 코멘트 / 닫힘 검증이 자연스러움

```java
// ✗ 금지
// TODO: 캐싱 추가 필요
// FIXME: 동시성 race 가능
// HACK: 임시로 sleep 추가

// ✓ 권장
// (이슈로 추적 — 해당 부분 코드는 정상 동작하면 그대로 두고, 개선은 이슈 PR에서)
// 별도 GitHub 이슈로 분리 — 본 코드는 현재 정확하게 동작
```

PR 리뷰 시 마커가 발견되면 머지 차단 + 이슈 분리 요청.

### 4.6 적용 대상

신규 코드 + 수정하는 라인은 본 §4 규칙(로깅 / 중괄호 / Javadoc / 임포트 / 인라인 주석)을 모두 따른다. 기존 코드의 일괄 정리는 별도 REFACTOR PR로 분리.

---

## 5. AGENTS.md와의 책임 분리

| 문서 | 책임 |
|---|---|
| [AGENTS.md](../AGENTS.md) | 작업 원칙(Durable/DB-중심/한국어), 작업 흐름(이슈 → 브랜치 → PR), 라벨/마일스톤 정책, 문서 인덱스 |
| **이 문서 (CONTRIBUTING.md)** | 브랜치 명명 규칙, 커밋 메시지 형식, PR 본문 형식, 코딩 스타일, 템플릿 사용법 |
| [.gitmessage](../.gitmessage) | 커밋 메시지 템플릿 — ``git commit`` 시 에디터에 자동 로드 |
| [.github/pull_request_template.md](../.github/pull_request_template.md) | PR 본문 템플릿 — ``gh pr create`` 시 자동 적용 |
