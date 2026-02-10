# 🤖 AGENTS.md

이 파일은 `simple-workflow-engine` 프로젝트에서 활동하는 AI 에이전트들을 위한 가이드라인 및 역할 정의를 담고 있습니다.

## 1. 에이전트 원칙 (Agent Principles)

*   **Durable Execution 우선:** 모든 설계는 장애 발생 시에도 중단 지점부터 재개 가능한 내구성 있는 실행을 최우선으로 합니다.
*   **DB 중심 설계:** 외부 메시지 큐 없이 Oracle/MariaDB의 `SKIP LOCKED` 기능을 활용한 경량 오케스트레이션을 지향합니다.
*   **일관성 유지:** 기존 코드 스타일과 명세서(`workflow-engine-spec.md`)를 엄격히 준수합니다.
*   **기록의 의무:** 모든 변경 사항은 `worklog.md`에 상세히 기록하고, 작업 진척도는 `tasks.md`의 체크박스를 업데이트하여 관리합니다. 이슈 발생 시 `issue.md`에 남깁니다.
*   **미구현 사항 표기:** 향후 작업이 필요하거나 리팩토링이 필요한 위치에는 반드시 `// TODO: 내용` 주석을 남기고, 해당 내용을 `tasks.md`의 "향후 과제" 섹션에도 반영합니다.

## 2. 작업 워크플로우 (Task Workflow)

1.  **명세 및 진척도 숙지:** 작업을 시작하기 전 `docs/workflow-engine-spec.md`, `docs/DATABASE_RULE.md` 및 `tasks.md`를 반드시 읽고 현재 상태를 파악합니다.
2.  **설계 제안 및 작업 계획:** 대규모 변경 시 인터페이스와 스키마를 먼저 제안하고 승인을 받습니다. 작업 시작 시 관련 내용을 `tasks.md`에 추가합니다.
3.  **테스트 코드 작성:** 핵심 로직에 대해서는 가능한 한 테스트 코드를 동반합니다.
4.  **문서화 및 상태 업데이트:** README.md와 관련 문서를 최신 상태로 유지하며, 작업 완료 시 `tasks.md`와 `worklog.md`를 업데이트합니다.

## 3. 기술 스택 가이드

*   **Java:** 25 버전 이상의 기능을 적극 활용하되, 가독성을 해치지 않습니다.
*   **Spring Boot:** 3.x 기반의 설정 및 관례를 따릅니다.
*   **Database:** Oracle과 MariaDB에서 공통으로 동작할 수 있는 표준 SQL 또는 추상화 레이어를 사용합니다.

## 4. 커뮤니케이션 규칙

*   모든 Task 설명 및 주석은 **한국어**를 기본으로 합니다.
*   에러 발생 시 스택 트레이스와 함께 발생 원인 및 해결 방안을 명확히 제시합니다.

---

## 5. 중앙 허브(Hub)와 Spoke 문서

이 문서는 저장소의 모든 규칙과 맥락을 연결하는 **중앙 허브(Hub)** 입니다. 작업 성격에 맞는 **세부 가이드(Spoke)** 를 선택적으로 참조하여 효율적으로 작업을 수행하십시오.

### Spoke 인덱스
- 프로젝트 개요/빠른 시작: [README.md](README.md)
- 기능 명세/아키텍처: [docs/workflow-engine-spec.md](docs/workflow-engine-spec.md)
- 데이터베이스 규칙: [docs/DATABASE_RULE.md](docs/DATABASE_RULE.md)
- 운영 가이드(배포/운영/장애): [docs/OPERATIONS.md](docs/OPERATIONS.md)
- 협업 규정(브랜치/커밋/PR): [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)
- 작업 현황/우선순위: [tasks.md](tasks.md)
- 작업 이력/결정 사항: [worklog.md](worklog.md)

### 사용 순서 가이드
1) 본 문서(AGENTS.md)에서 원칙과 워크플로우를 숙지합니다.
2) 현재 할 일/우선순위를 [tasks.md](tasks.md)에서 확인합니다.
3) 구현/수정 시 관련 Spoke를 선택해 참조합니다(예: DB 작업 → DATABASE_RULE, 엔진 변경 → workflow-engine-spec).
4) 작업 완료 후 [worklog.md](worklog.md)에 기록하고, [tasks.md](tasks.md) 체크박스를 갱신합니다.
5) 협업 절차는 [CONTRIBUTING.md](docs/CONTRIBUTING.md)를 따릅니다.

### 동기화 규칙
- 코드 내 `// TODO:` 주석을 추가했다면, 동일 항목을 [tasks.md](tasks.md)의 "향후 과제"에도 반영합니다.
- 문서 간 중복 서술은 본 문서(Hub)의 링크 체계를 우선 적용하여 관리합니다.

---

[→ Spoke 인덱스: tasks.md](tasks.md) | [→ 프로젝트 개요: README.md](README.md)
