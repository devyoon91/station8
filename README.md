# 🚀 Simple Workflow Engine

Oracle 및 MariaDB 환경에서 동작하는 **경량 Java 워크플로우 오케스트레이션 엔진**입니다. Temporal의 Durable Execution 개념을 벤치마킹하여, 별도의 메시지 큐 없이 데이터베이스의 `SKIP LOCKED` 기능을 활용해 안정적인 작업 실행을 보장합니다.

## 🌟 Key Features

*   **Durable Execution:** 모든 Task의 실행 상태를 DB에 영속화하여 장애 발생 시 중단된 지점부터 재개 가능합니다.
*   **Worker Polling:** DB 레벨의 잠금(`FOR UPDATE SKIP LOCKED`)을 활용한 분산 작업 처리.
*   **Retry Policy:** 실패한 작업에 대한 Exponential Backoff 기반의 자동 재시도.
*   **Simple SDK:** `@Workflow`, `@Activity` 어노테이션을 이용한 쉬운 워크플로우 정의.
*   **Monitoring Dashboard:** Mustache 기반의 실시간 워크플로우 상태 모니터링 (예정).

## 🛠 Tech Stack

*   **Language:** Java 25+
*   **Framework:** Spring Boot 3.x
*   **Database:** Oracle / MariaDB
*   **Build Tool:** Gradle

## 🏗 Project Structure

*   `engine-core`: 워크플로우 엔진 핵심 로직, 어노테이션, DB 추상화 레이어.
*   `service-app`: 비즈니스 로직 구현 및 모니터링 대시보드.
*   `e2e-tests`: REST Assured 기반 정식 회귀 테스트 (compose 띄운 상태에서만 활성).

## 🚦 Quick Start (Docker)

```bash
git clone git@github.com:devyoon91/simple-workflow-engine.git
cd simple-workflow-engine
docker compose -f docker/docker-compose.yml up --build -d
# → http://localhost:8080/workflow/dashboard
```

상세 가이드: **[docs/QUICKSTART.md](docs/QUICKSTART.md)** (5분 설명)

### Prerequisites

*   Docker Desktop 24+ (필수) · Java 21+ (선택, 직접 빌드 시) · bash + curl + jq (선택, 시나리오 자동화)
*   Oracle 또는 MariaDB 데이터베이스 (운영 시)

### Database Setup (수동)

- **Oracle:** `engine-core/src/main/resources/sql/schema-oracle.sql` 실행
- **MariaDB:** `engine-core/src/main/resources/sql/schema-mariadb.sql` 실행
- (Docker compose 사용 시 자동 적용 — `MigrationInitializer`)

## 📝 Documentation

먼저 중앙 허브 문서인 [AGENTS.md](AGENTS.md)를 읽고, 작업 성격에 맞는 Spoke 문서를 선택하여 진행하세요.

*   [Agent Collaboration Guidelines (Hub)](AGENTS.md)
*   [Quick Start](docs/QUICKSTART.md) — 5분 설명 (docker 한 줄)
*   [Workflow Engine Specification](docs/workflow-engine-spec.md)
*   [Database Rules](docs/DATABASE_RULE.md)
*   [Operations Guide](docs/OPERATIONS.md)
*   [Contributing Guide](docs/CONTRIBUTING.md)
*   [GitHub Issues](https://github.com/devyoon91/simple-workflow-engine/issues) · [Milestones](https://github.com/devyoon91/simple-workflow-engine/milestones) — 작업 추적/우선순위/이력

## 🔧 Core Engine Interfaces

다음의 추상 인터페이스/어노테이션을 통해 특정 DB 기술에 종속되지 않는 코어 엔진 계약을 제공합니다.

- `@Workflow` (annotation): 워크플로우 클래스를 식별하기 위한 어노테이션. 선택적 이름 지정 가능.
- `@Activity` (annotation): 워크플로우 내 액티비티 메서드 식별 및 재시도 정책(`retryCount`, `backoffSeconds`) 지정.
- `WorkflowExecutor` (interface): 워크플로우 인스턴스 시작(`startWorkflow`), 재개(`resumeWorkflow`).
- `WorkflowContext` (interface): 인스턴스/액티비티 실행 맥락. 입력/출력, 시도 횟수, 임시 속성, 다음 태스크 힌트, 체크포인트 저장/로드.
- `TaskExecutor` (interface): 현재 액티비티 실행(`executeCurrent`), 다음 액티비티 스케줄(`scheduleNext`), 완료/실패 처리, 체크포인트 저장.
- `WorkflowWorker` (class): DB 폴링(`SKIP LOCKED`) 및 스레드 풀 기반 비동기 작업 실행기.