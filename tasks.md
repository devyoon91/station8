# ✅ Tasks.md

이 파일은 `simple-workflow-engine` 프로젝트의 구현 상태와 향후 계획을 추적합니다.
- 상위 허브 문서: [AGENTS.md](AGENTS.md) — 본 문서의 변경/체크박스 갱신은 반드시 허브의 규칙(동기화 규칙)을 따르세요.

## 🏁 완료된 작업 (Completed Tasks)

### 1. 프로젝트 기초 설정
- [x] Gradle 멀티 모듈 프로젝트 구조 설계 (`:engine-core`, `:service-app`)
- [x] Gradle Wrapper 9.1.0 구성 및 Linux/Windows 실행 스크립트 확보
- [x] JDK 25 업그레이드 및 환경 설정
- [x] 프로젝트 핵심 명세서 (`workflow-engine-spec.md`) 작성
- [x] AI 에이전트 협업 가이드라인 (`AGENTS.md`) 및 DB 규칙 (`DATABASE_RULE.md`) 정의

### 2. 데이터베이스 레이어
- [x] Oracle/MariaDB 호환 핵심 테이블 설계 (`U_WF_INSTANCE`, `H_WF_ACTIVITY_EXECUTION`)
- [x] DB 벤더별 특화 스키마 분리 (`schema-oracle.sql`, `schema-mariadb.sql`)
- [x] Java Record 기반 엔티티(Entity) 모델 구현
- [x] `JdbcActivityRepository` 구현 (SKIP LOCKED 폴링 쿼리 포함)
- [x] 멀티 DB 호환을 위한 `DbDialect` 전략 패턴 도입

### 3. 코어 엔진 핵심
- [x] 커스텀 어노테이션 정의 (`@Workflow`, `@Activity`)
- [x] 핵심 인터페이스 설계 (`WorkflowContext`, `TaskExecutor`, `WorkflowExecutor`)
- [x] `WorkflowRegistry`를 통한 어노테이션 기반 빈/메서드 자동 스캐닝 및 등록
- [x] Spring AOP를 이용한 워크플로우 실행 로깅 및 상태 관리 (`WorkflowAspect`)
- [x] `WorkflowWorker`를 이용한 DB 폴링 및 스레드 풀 기반 비동기 작업 실행

### 4. 기능 보완 및 가시성
- [x] Jackson 기반 JSON 직렬화/역직렬화 유틸리티 (`JsonUtil`)
- [x] `DefaultWorkflowContext` 및 `JdbcTaskExecutor` 구현 (상태 저장 및 오케스트레이션)
- [x] Mustache 기반 모니터링 대시보드 및 타임라인 UI 구현

### 5. 실무 예제 (service-app)
- [x] H2 기반 단일 DataSource로 SRC_DATA → DEST_DATA 마이그레이션 샘플 구성
- [x] `@Workflow`/`@Activity(MIGRATION_WRITE)` 구현 및 레지스트리 자동 스캔 연동
- [x] 앱 기동 시 초기 데이터 적재 및 PENDING 작업 시드(CommandLineRunner)
- [x] 실패 강제(`Second Data`)로 백오프 재시도 동작 검증(엔진의 `fail()` 로직 활용)

---

## 🚀 향후 과제 (Our Homework)

### 1. 엔진 기능 고도화
- [x] **지수 백오프(Exponential Backoff) 수식 실구현**: 현재 TODO로 남아있는 재시도 간격 계산 로직 완성
- [x] **워크플로우 재개(Resume) 로직**: 중단된 지점부터 정확히 다시 시작하는 `WorkflowExecutor.resumeWorkflow()` 실구현
- [x] **ActivityInvoker 정교화**: 리플렉션 호출 시 파라미터 타입 매칭 및 JSON 역직렬화 로직 보완
- [x] **최종 실패 처리 (DLQ)**: 최대 재시도 횟수 초과 시 `FAILED_FINAL` 상태 전환 및 `H_WF_DLQ` 테이블 적재
    - [x] H_WF_DLQ 스키마 설계 및 DDL 작성 (Oracle/MariaDB/H2)
    - [x] 엔진 코어 최종 실패 처리 로직 구현 (FAILED_FINAL + DLQ 적재)
    - [x] 웹훅 알림 기능 구현 (WebhookDlqNotifier SPI)

### 2. 안정성 및 테스트
- [ ] **핵심 로직 단위 테스트**: `WorkflowWorker`, `TaskExecutor` 등에 대한 JUnit 테스트 코드 작성
- [ ] **통합 테스트**: Testcontainers 등을 활용한 Oracle/MariaDB 실제 연동 테스트
- [ ] **Backpressure 정교화**: 스레드 풀 큐 가득 참 현상에 대한 모니터링 및 설정 최적화

### 3. 모니터링 UI 강화
- [ ] **실시간 상태 업데이트**: WebSocket 또는 폴링을 통한 대시보드 실시간 갱신
- [x] **작업 수동 조작**: 대시보드에서 실패한 작업을 강제로 재시도하거나 중단하는 기능 추가
- [ ] **검색 및 필터링**: 인스턴스 ID, 워크플로우 명, 기간별 검색 기능 보완

### 4. 문서화 및 표준화
- [ ] **사용자 가이드 작성**: 개발자가 엔진을 사용하여 워크플로우를 정의하는 방법(How-to) 문서화
- [ ] **에러 코드 표준화**: 엔진 내부 예외 및 에러 메시지 체계 정리

