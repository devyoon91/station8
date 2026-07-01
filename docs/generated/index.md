# 생성 문서

이 섹션은 코드에서 뽑아낸 문서다. 손으로 관리하는 `docs/`의 가이드와 달리, 컨트롤러·엔티티·스키마·뷰가 바뀌면 다시 생성해 갱신한다.

| 문서 | 소스 | 생성 서브에이전트 |
|---|---|---|
| [API 문서](api/index.md) | `*Controller.java` 엔드포인트 | `docs-api` |
| [기능정의서](features/index.md) | 액티비티 · 마일스톤 · 이슈 | `docs-features` |
| [테이블 정의서](schema/index.md) | `schema-*.sql` · `entity/*.java` | `docs-schema` |
| [화면정의서](screens/index.md) | `templates/*.mustache` + 스크린샷 | `docs-screens` |
| [운영 메뉴얼](ops/index.md) | 설정 · 배포 · 장애 대응 | `docs-ops` |

## 생성 방법

문서 생성기는 `.claude/agents/`에 서브에이전트로 들어 있다. Claude Code 세션에서 해당 에이전트를 호출하면, 코드를 읽어 이 섹션의 markdown을 채운다.

```
# 예: API 문서 갱신
docs-api 서브에이전트에게 "API 문서 생성해줘" 라고 요청

# 전체 갱신
docs-api / docs-features / docs-schema / docs-screens / docs-ops 를 순서대로 호출
```

각 에이전트의 상세 스펙과 출력 규칙은 [.claude/agents/README.md](https://github.com/devyoon91/station8/blob/main/.claude/agents/README.md) 참고.

## 화면정의서 스크린샷

화면정의서는 실제 UI 스크린샷을 함께 싣는다. 스크린샷은 `tools/screenshots/`의 puppeteer-core 하네스가 로컬 Chrome headless로 캡처한다. 앱을 먼저 띄운 뒤:

```bash
cd tools/screenshots
npm install
npm run capture       # → docs/assets/screenshots/*.png
```

자세한 건 [tools/screenshots/README.md](https://github.com/devyoon91/station8/blob/main/tools/screenshots/README.md).
