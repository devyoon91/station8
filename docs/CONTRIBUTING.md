# CONTRIBUTING

본 문서는 ``simple-workflow-engine`` 저장소의 **브랜치 / 커밋 / PR 형식 규정**을 정의한다.
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

## 4. AGENTS.md와의 책임 분리

| 문서 | 책임 |
|---|---|
| [AGENTS.md](../AGENTS.md) | 작업 원칙(Durable/DB-중심/한국어), 작업 흐름(이슈 → 브랜치 → PR), 라벨/마일스톤 정책, 문서 인덱스 |
| **이 문서 (CONTRIBUTING.md)** | 브랜치 명명 규칙, 커밋 메시지 형식, PR 본문 형식, 템플릿 사용법 |
| [.gitmessage](../.gitmessage) | 커밋 메시지 템플릿 — ``git commit`` 시 에디터에 자동 로드 |
| [.github/pull_request_template.md](../.github/pull_request_template.md) | PR 본문 템플릿 — ``gh pr create`` 시 자동 적용 |
