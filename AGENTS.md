# 🤖 AGENTS.md

이 파일은 `simple-workflow-engine` 프로젝트에서 활동하는 AI 에이전트 및 개발자를 위한 협업 가이드라인입니다.

## 1. 프로젝트 노선 (Direction)

* **Temporal 스타일 코어** + **Airflow/Azkaban 스타일 운영 웹**의 하이브리드.
* 액티비티(노드)는 Java 코드(`@Activity`), 워크플로우(DAG)는 데이터(웹 빌더에서 정의).
* 상세는 [docs/workflow-engine-spec.md](docs/workflow-engine-spec.md) 참조.

## 2. 작업 원칙

* **Durable Execution 우선:** 모든 설계는 장애 발생 시에도 중단 지점부터 재개 가능한 내구성을 최우선으로 한다.
* **DB 중심 설계:** 외부 메시지 큐 없이 Oracle/MariaDB의 `SKIP LOCKED`를 활용한 경량 오케스트레이션.
* **명세 우선:** 인터페이스/스키마 변경은 [docs/workflow-engine-spec.md](docs/workflow-engine-spec.md)와 [docs/DATABASE_RULE.md](docs/DATABASE_RULE.md) 갱신과 함께 진행한다.
* **테스트 동반:** 핵심 로직에는 가능한 한 단위/통합 테스트를 동반한다.
* **한국어:** 커밋 메시지, 주석, PR 본문은 기본적으로 한국어로 작성한다.

## 3. 작업 워크플로우 (GitHub 기반)

본 저장소는 **모든 작업을 GitHub Issue로 추적**합니다. 별도 `tasks.md`/`worklog.md`/`issue.md`는 사용하지 않습니다.

1. **이슈 확인/생성** — 작업 시작 전 [Issues](https://github.com/devyoon91/simple-workflow-engine/issues) 탭에서 관련 이슈를 찾거나 새로 만든다. 큰 단위는 `epic` 라벨로 묶고, 실제 작업 단위는 `task` 라벨을 붙인다.
2. **마일스톤 매핑** — 이슈에 적절한 [Milestone](https://github.com/devyoon91/simple-workflow-engine/milestones)(M1~M5)을 설정한다.
3. **명세/규칙 숙지** — [docs/workflow-engine-spec.md](docs/workflow-engine-spec.md), [docs/DATABASE_RULE.md](docs/DATABASE_RULE.md)를 반드시 읽고 작업한다.
4. **브랜치/PR** — `claude/<short-name>` 또는 `feature/#<issue>-<short-name>` 컨벤션. PR 본문에 `Closes #N`을 명시한다.
5. **테스트/문서** — 변경에 영향받는 테스트와 문서(README, spec, HOWTO)를 함께 갱신한다.
6. **이슈 종료** — PR 머지 시 자동 종료. 머지 후 추가로 발견된 후속 작업은 새 이슈로 분리한다.

## 4. TODO 주석 ↔ Issue 동기화

* 코드에 `// TODO:` 주석을 추가했다면, **반드시 대응되는 GitHub Issue를 만들고 주석에 이슈 번호를 적는다**.
  * 예: `// TODO(#42): WorkflowWorker에 JsonUtil 주입 및 정교한 역직렬화 구현`
* 이슈 없는 떠도는 TODO는 PR 리뷰에서 거절한다.

## 5. 라벨 체계

* **종류**: `epic`, `task`, `bug`, `docs`
* **영역**: `area:engine-core`, `area:service-app`, `area:docs`
* **우선순위**: `priority:high`, `priority:medium`, `priority:low`

## 6. 마일스톤

* `M1 — DAG 정의 모델`: 스키마 + 인터프리터(분기/병렬) + `WAITING_DEPENDENCIES` 상태
* `M2 — Cron 스케줄러`: `U_WF_SCHEDULE` + 트리거 폴러 + UI
* `M3 — 그래프 빌더 UI`: 노드 팔레트 + 캔버스 + DAG 검증
* `M4 — 액티비티 카탈로그`: `WorkflowRegistry` 메타데이터 노출
* `M5 (선택) — 외부 jar 폴더 스캔`: `plugins/*.jar` 동적 로딩

## 7. 기술 스택 가이드

* **Java 25+** 기능을 적극 활용하되 가독성을 해치지 않는다.
* **Spring Boot 3.x** 관례를 따른다.
* **Database**: Oracle/MariaDB에서 공통 동작하는 표준 SQL 또는 `DbDialect` 추상화 레이어를 사용한다.

## 8. Spoke 인덱스 (참조 문서)

* 빠른 시작: [README.md](README.md) · [docs/QUICKSTART.md](docs/QUICKSTART.md)
* 기능 명세/아키텍처: [docs/workflow-engine-spec.md](docs/workflow-engine-spec.md)
* 데이터베이스 규칙: [docs/DATABASE_RULE.md](docs/DATABASE_RULE.md)
* 사용자 가이드: [docs/HOWTO.md](docs/HOWTO.md)
* 운영 가이드: [docs/OPERATIONS.md](docs/OPERATIONS.md)
* **에러 코드 카탈로그: [docs/ERROR_CODES.md](docs/ERROR_CODES.md)**
* 협업 규정(브랜치/커밋/PR): [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)
* 작업 추적: [GitHub Issues](https://github.com/devyoon91/simple-workflow-engine/issues) · [Milestones](https://github.com/devyoon91/simple-workflow-engine/milestones) · [Projects](https://github.com/devyoon91/simple-workflow-engine/projects)
