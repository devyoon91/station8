# UI Layout & Scroll 규칙

Station8 웹 UI(`station8-app/src/main/resources/templates/*.mustache`)의 **레이아웃과 스크롤 패턴 단일 규약**.
디자인 토큰은 `static/css/design.css`에 있고, 본 문서는 그 위에서 "어떤 화면이 어떻게 스크롤하느냐"를 정의한다.

---

## 1. 두 종류 페이지

모든 페이지는 다음 둘 중 하나로 분류한다.

| 분류 | 대표 페이지 | 스크롤 모델 |
|---|---|---|
| **콘텐츠형 (Content)** | Dashboard, Lines, Definitions, DLQ, Schedules, Activities, Timeline, Admin/* | **페이지 스크롤** — 브라우저가 body를 스크롤. 콘텐츠 길이만큼 자연 확장. |
| **툴형 (Tool / Canvas)** | Builder | **viewport-locked** — 화면이 도구 그 자체. 외부 스크롤 없음. 내부 패널만 스크롤. |

신규 페이지를 만들 땐 먼저 이 둘 중 어디인지 정한 뒤 아래 패턴 중 하나를 그대로 적용한다.

---

## 2. 콘텐츠형 — 페이지 스크롤 (Default)

### 2.1 규칙

- **body / html에 height·overflow 건드리지 않는다**. 브라우저 기본값(=내용 따라 자라며 body가 스크롤) 그대로.
- 최상위 wrapper는 `.swe-container` (이미 `max-width: 1240px; margin: 0 auto`).
- 길이 제한·min-height·100vh 트릭을 **쓰지 않는다**. 콘텐츠가 길면 페이지가 길어진다.

### 2.2 표준 골격

```html
<body>
  {{> _nav }}
  <div class="swe-container" style="padding-top: var(--space-xxl); padding-bottom: var(--space-xl);">
    <h1 class="swe-display-lg">...</h1>
    <!-- 콘텐츠 카드/테이블/리스트 -->
  </div>
</body>
```

### 2.3 페이지 내부의 부분 스크롤이 필요할 때

긴 stack trace, 큰 JSON, 미리보기 영역 등 **국소적으로 갇힌 스크롤**이 필요하면 해당 박스에만:

```css
max-height: 480px;
overflow: auto;
```

(예: [dlq-detail.mustache:58](../station8-app/src/main/resources/templates/dlq-detail.mustache:58))

페이지 전체를 viewport에 가두는 것은 콘텐츠형에서 절대 하지 않는다.

---

## 3. 툴형 — viewport-locked + 패널 스크롤

### 3.1 규칙

- 도구 한 화면에 모든 작업영역(캔버스 + 좌우 패널)이 보여야 한다. 페이지 스크롤은 없는 게 기본.
- **flex 체인으로 chrome(nav + toolbar + 배너 등)을 자동 흡수**한다. 하드코딩 `calc(100vh - 56px - 120px)` 같은 것은 절대 쓰지 않는다 — chrome 크기는 가변(반응형 nav, 타이포 변경, 배너 유무)이라 곧 회귀한다.
- `body { overflow: hidden }`은 **쓰지 않는다**. 대신 `min-height: 100dvh`로 두면 정상 viewport에서 합산이 100dvh와 정확히 맞아 스크롤바가 안 뜨고, 작은 viewport에선 자연스럽게 body 스크롤이 살아난다.
- 패널마다 내부 스크롤 영역은 `flex: 1; overflow: auto`로 분리.

### 3.2 표준 골격 (Builder 기준)

```css
/* 페이지 <style> 블록 안 — 다른 페이지에 영향 X */
html { height: auto; }
body {
    min-height: 100dvh;
    display: flex;
    flex-direction: column;
}
body > .swe-container {
    flex: 1 1 auto;
    min-height: 0;            /* 핵심: flex 자식이 줄어들 수 있게 */
    display: flex;
    flex-direction: column;
}
.swe-tool-layout {           /* 캔버스 + 좌우 패널 grid */
    display: grid;
    grid-template-columns: 280px 1fr 320px;
    gap: var(--space-lg);
    flex: 1 1 auto;
    min-height: 600px;        /* 작은 viewport에서 사용성 보장 */
}
.swe-panel {                  /* 각 패널 컨테이너 */
    display: flex;
    flex-direction: column;
    overflow: hidden;
    min-height: 0;
}
.swe-panel-body {             /* 패널 내부 스크롤 영역 */
    flex: 1;
    overflow: auto;
}
```

레퍼런스 구현: [builder.mustache:10-65](../station8-app/src/main/resources/templates/builder.mustache:10)

### 3.3 왜 `100dvh`인가

- `100vh`는 모바일 브라우저에서 주소창 높이를 무시한 값을 쓴다 → 하단이 잘림.
- `100dvh` (dynamic viewport height)는 주소창 표시/숨김에 맞춰 실시간 조정.
- 지원: iOS Safari 15.4+, Chrome/Edge 108+, Firefox 101+ (2022~) — Station8 타깃 브라우저 전부 OK.

### 3.4 절대 하지 말 것

| 안티패턴 | 왜 |
|---|---|
| `body { overflow: hidden }` 단독 | chrome이 가변이라 안에서 viewport 초과하면 잘림 (#210 후속 회귀) |
| `height: calc(100vh - <고정픽셀>)` | h1 크기 변경(#207), 반응형 nav(#208), 배너 추가만 해도 깨짐 |
| `height: 100vh` (dvh 아님) | 모바일 하단 잘림 |
| flex 체인 중간에 `min-height: 0` 빠뜨림 | 자식이 부모를 밀어 올려 overflow 발생 |

---

## 4. 모달 / 오버레이

- 위치는 `position: fixed; inset: 0;` (backdrop) + `position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%)` (modal).
- `z-index` 1500(backdrop) / 1600(modal). 이 범위는 nav(100)·context menu보다 위.
- 너비는 `max-width: 90vw`로 좁은 viewport 대응.
- 모달 내부가 길면 `max-height: 90vh; overflow: auto`.

예시: [builder.mustache:185-205](../station8-app/src/main/resources/templates/builder.mustache:185) (edge-cond-modal)

---

## 5. 반응형 분기점

전역 규약 (전 페이지 공통):

| 분기점 | 영역 | 동작 |
|---|---|---|
| `(max-width: 768px)` | 전역 nav | 햄버거 메뉴 토글 (#208). swe-container padding 축소. |

툴형 (Builder) 추가 분기 (#205 PR-1):

| 분기점 | layout |
|---|---|
| `≥ 1024px` (데스크톱) | 3-column grid: `280px 1fr 320px` |
| `768~1023px` (태블릿) | 3-column grid 축소: `240px 1fr 280px` |
| `< 768px` (모바일/좁은 viewport) | flex 세로 스택. `order`로 캔버스 최상단. 캔버스 `min-height: 60vh`, 패널 `min-height: 320px; max-height: 70vh`. body 스크롤 활성 (§3.1 합산이 100dvh 초과 → 자연 스크롤). |

콘텐츠형 페이지는 768px 분기점 외에 별도 처리 거의 불필요 (grid가 자연스럽게 흐름).

> PR-2에서 모바일 fullscreen 캔버스 + 플로팅 버튼 → 패널 모달 패턴 추가 예정. PR-3은 Drawflow 터치 이벤트 보강.

---

## 6. 디버깅 체크리스트

UI에서 "아래가 짤리는" 증상이 나오면 순서대로 확인:

1. **`body { overflow: hidden }`가 잡혔는가?** → 잡혔다면 그게 root cause일 확률이 가장 높다.
2. **`height: calc(100vh - …)` 류 하드코딩이 있는가?** → 화면 chrome이 가변인데 고정값을 뺐을 가능성.
3. **flex 체인 어디선가 `min-height: 0`이 빠졌는가?** → 자식이 부모를 밀어 올림.
4. **DevTools에서 body computed height vs scrollHeight 비교** → scrollHeight가 더 크면 어딘가에서 viewport를 초과.

---

## 7. 본 문서 영향 범위

- 신규 페이지 추가/리뉴얼 시: 본 §2 또는 §3 골격 그대로 가져다 쓴다.
- 기존 페이지 회귀 수정 시: 본 §3.4 안티패턴부터 점검.
- 디자인 토큰·색상·타이포는 본 문서 밖 — `design.css` + 향후 별도 가이드.
