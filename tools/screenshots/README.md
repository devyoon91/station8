# UI 스크린샷 캡처

화면정의서에 넣을 UI 스크린샷을 자동으로 찍는다. `puppeteer-core`가 **이미 설치된 로컬 Chrome**을 headless로 몰아서 캡처하므로, Chromium을 따로 내려받지 않는다 — 폐쇄망에서 쓰기 좋다.

## 준비

앱을 먼저 띄운다.

```bash
./gradlew :station8-app:bootRun     # http://localhost:8080
```

의존성 설치 후 환경변수 세팅.

```bash
cd tools/screenshots
npm install
cp .env.example .env      # 열어서 S8_PASS 등 채우기
```

`.env`는 git에 안 올라간다. 관리자 화면까지 찍으려면 ADMIN 계정을 넣어야 한다. 초기 ADMIN 비밀번호는 앱 최초 기동 로그에 한 번 찍히거나 `STATION8_ADMIN_PASSWORD` 환경변수로 지정한 값이다.

## 실행

```bash
npm run capture
```

`docs/assets/screenshots/`에 `<name>.<viewport>.<theme>.png`와 `manifest.json`이 생성된다. 화면정의서(`docs-screens` 서브에이전트)가 이 manifest를 읽어 화면별로 이미지를 붙인다.

## 무엇을 찍나

`screens.json`이 인벤토리다. 화면 하나 추가/수정하려면 이 파일만 고치면 된다.

| 필드 | 의미 |
|---|---|
| `name` | 파일명·manifest 키 |
| `path` | 라우트. `{id}`는 `resolve`로 런타임 치환 |
| `auth` | true면 로그인 후 접근 |
| `viewports` | `desktop`(1440×900) / `mobile`(390×844). 생략 시 desktop만 |
| `waitFor` | 캡처 전 기다릴 CSS 셀렉터 (예: 빌더 `#drawflow`) |
| `waitMs` | 추가 대기 ms |
| `resolve` | `definition` / `instance` — 목록에서 첫 id를 구해 `{id}` 치환 |

테마(light/dark)는 `prefers-color-scheme` 미디어 에뮬레이션으로 전환한다.

## 환경변수

`.env` 또는 셸 환경변수로 준다. 우선순위는 셸 > `.env`.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 대상 앱 |
| `S8_USER` / `S8_PASS` | `admin` / (없음) | 로그인 계정 |
| `CHROME_PATH` | 자동 탐색 | Chrome 실행 파일 경로 |
| `THEMES` | `light,dark` | 캡처 테마 |
| `OUT_DIR` | `../../docs/assets/screenshots` | 산출물 위치 |
| `NAV_TIMEOUT_MS` | `20000` | 페이지 이동 타임아웃 |

## Node 버전 주의

이 저장소 환경은 Node 14라 `puppeteer-core`를 14.x로 핀 고정해 뒀다. Node 18+로 올릴 수 있으면 `package.json`의 `puppeteer-core`를 최신(`^23` 등)으로 올려도 된다 — 최신 Chrome과의 궁합이 더 좋다.
