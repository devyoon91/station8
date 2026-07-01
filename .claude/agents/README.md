# 문서 생성 서브에이전트

Station8 코드에서 문서를 뽑아내는 Claude Code 서브에이전트 세트. 각 에이전트는 담당 소스를 읽어 `docs/generated/<섹션>/index.md` **한 파일**을 덮어쓴다. MkDocs nav가 이 경로들을 가리키므로, 재생성하면 사이트가 바로 갱신된다.

| 에이전트 | 출력 | 소스 |
|---|---|---|
| `docs-api` | `docs/generated/api/index.md` | `*Controller.java` |
| `docs-features` | `docs/generated/features/index.md` | 빌트인 액티비티, ADR, 마일스톤 |
| `docs-schema` | `docs/generated/schema/index.md` | `schema-*.sql`, `entity/*.java` |
| `docs-screens` | `docs/generated/screens/index.md` | `templates/*.mustache` + 스크린샷 manifest |
| `docs-ops` | `docs/generated/ops/index.md` | `application*.properties`, `docker/`, 운영 docs |

## 쓰는 법

세션에서 해당 에이전트를 호출한다.

```
docs-api 서브에이전트로 API 문서 생성해줘
docs-schema 로 테이블 정의서 갱신해줘
```

다섯 개를 병렬로 돌려 전체를 한 번에 갱신해도 된다 — 서로 다른 파일을 건드리므로 충돌하지 않는다.

## 재생성 시점

- 컨트롤러/엔드포인트 추가·변경 → `docs-api`
- 테이블/엔티티/마이그레이션 → `docs-schema`
- 새 기능·액티비티·ADR → `docs-features`
- 화면(mustache) 추가·변경 → 스크린샷 재캡처(`tools/screenshots`) 후 `docs-screens`
- 설정·배포·운영 절차 → `docs-ops`

## 원칙

- 출력은 섹션당 `index.md` 한 파일. nav 구조를 건드리지 않는다(페이지를 쪼개려면 `mkdocs.yml` nav도 같이 수정).
- 코드에 근거 없는 내용은 쓰지 않는다. 미확인은 미확인으로 표기.
- 문체는 글로벌 문서 작성 규칙(자연스러운 한국어, AI 어투 금지, 소스 파일 상대링크)을 따른다.
- 생성 후 반드시 `python -m mkdocs build --strict`로 링크·anchor를 검증한다.
