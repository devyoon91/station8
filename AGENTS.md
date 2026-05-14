# AGENTS.md

`station8` 프로젝트의 협업 가이드라인. AI 에이전트와 인간 개발자 모두를 위한 단일 진입점.

## 1. 프로젝트 노선

- Temporal 스타일 코어 + Airflow/Azkaban 스타일 운영 웹의 하이브리드.
- 액티비티(역)는 Java 코드(`@Activity`), 라인(DAG)는 데이터(웹 빌더에서 정의).
- 상세 명세는 [docs/line-engine-spec.md](docs/line-engine-spec.md) 참조.

## 2. 작업 원칙

- **Durable Execution 우선** — 장애 시 중단 지점부터 재개 가능한 내구성을 최우선으로 한다.
- **DB 중심 설계** — 외부 메시지 큐 없이 Oracle/MariaDB의 `SKIP LOCKED` 만으로 경량 오케스트레이션.
- **명세 우선** — 인터페이스/스키마 변경은 [docs/line-engine-spec.md](docs/line-engine-spec.md)와 [docs/DATABASE_RULE.md](docs/DATABASE_RULE.md) 갱신과 함께 진행한다.
- **테스트 동반** — 핵심 로직에는 단위/통합 테스트를 동반한다.
- **한국어** — 커밋 메시지, 주석, PR 본문은 기본 한국어로 작성한다.

## 3. 작업 라인 (GitHub 기반)

본 저장소는 모든 작업을 GitHub Issue로 추적한다. 별도 `tasks.md`/`worklog.md`/`issue.md`는 사용하지 않는다.

1. **이슈 확인/생성** — 작업 전 [Issues](https://github.com/devyoon91/station8/issues)에서 관련 이슈를 찾거나 만든다. 큰 단위는 `epic`, 실제 작업 단위는 `task` 라벨.
2. **마일스톤 매핑** — 이슈에 적절한 [Milestone](https://github.com/devyoon91/station8/milestones)을 설정한다.
3. **명세 숙지** — [docs/line-engine-spec.md](docs/line-engine-spec.md), [docs/DATABASE_RULE.md](docs/DATABASE_RULE.md)를 읽고 시작한다.
4. **브랜치 / 커밋 / PR 형식** — [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)의 규정을 따른다. 핵심:
   - 브랜치 이름은 `<issue#>-<짧은-영문-설명>` (이슈번호가 가장 앞에 옴).
   - 커밋 / PR은 [.gitmessage](.gitmessage)와 [.github/pull_request_template.md](.github/pull_request_template.md) 템플릿을 따른다.
   - 커밋 메시지·PR 본문에 도구 어트리뷰션(🤖 Generated with..., Co-Authored-By: Claude 등) **절대 금지**.
   - PR 본문에 `Closes #N` 필수.
5. **테스트/문서** — 변경에 영향받는 테스트와 문서(README, spec, HOWTO, ARCHITECTURE)를 함께 갱신.
6. **이슈 종료** — PR 머지 시 자동 종료. 후속 작업은 새 이슈로 분리.

## 4. TODO 주석과 Issue 동기화

- `// TODO:` 주석을 추가했다면 반드시 대응 GitHub Issue를 만들고 주석에 번호를 적는다.
  - 예: `// TODO(#42): LineWorker에 JsonUtil 주입 및 정교한 역직렬화 구현`
- 이슈 없는 떠도는 TODO는 PR 리뷰에서 거절한다.

## 5. 라벨 체계

- **종류**: `epic`, `task`, `bug`, `docs`
- **영역**: `area:station8-engine`, `area:station8-app`, `area:docs`
- **우선순위**: `priority:high`, `priority:medium`, `priority:low`

## 6. 마일스톤 / 기술 스택

마일스톤 진척과 백로그는 [GitHub Milestones](https://github.com/devyoon91/station8/milestones)가 source of truth.
기술 스택(Java 21 / Spring Boot 3.4.x / Oracle·MariaDB·H2)은 [README.md](README.md) 참조.

## 7. Spoke 인덱스 (참조 문서)

- 빠른 시작: [README.md](README.md) · [docs/QUICKSTART.md](docs/QUICKSTART.md)
- **아키텍처와 모듈 흐름**: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- 기능 명세: [docs/line-engine-spec.md](docs/line-engine-spec.md)
- 데이터베이스 규칙: [docs/DATABASE_RULE.md](docs/DATABASE_RULE.md)
- 사용자 가이드: [docs/HOWTO.md](docs/HOWTO.md)
- 플러그인 가이드 (M5): [docs/PLUGINS.md](docs/PLUGINS.md) (운영자) · [docs/PLUGIN_DEVELOPMENT.md](docs/PLUGIN_DEVELOPMENT.md) (개발자) · [examples/plugin-starter/](examples/plugin-starter/) (스타터)
- 에러 코드 카탈로그: [docs/ERROR_CODES.md](docs/ERROR_CODES.md)
- 협업 규정 (브랜치/커밋/PR): [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)
- 작업 추적: [GitHub Issues](https://github.com/devyoon91/station8/issues) · [Milestones](https://github.com/devyoon91/station8/milestones)
