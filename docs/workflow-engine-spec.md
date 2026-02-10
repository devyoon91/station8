# 📄 workflow-engine-spec.md

## 1. Project Overview

Temporal의 핵심 메커니즘(Durable Execution)을 벤치마킹하여, Oracle/MariaDB 환경에서 동작하는 **경량 Java 워크플로우 오케스트레이션 엔진**을 구축한다.

## 2. Technical Stack

* **Language:** Java 25+
* **Framework:** Spring Boot 3.x
* **Build Tool:** Gradle (Multi-module)
* **Database:** Oracle / MariaDB (공통 지원)
* **View Engine:** Mustache (Admin Dashboard용)

## 3. Multi-Module Architecture

프로젝트는 크게 엔진(SDK)과 서비스(Business Logic)로 분리한다.

* **`:engine-core`**:
* 워크플로우 상태 전이 및 Task 스케줄링 엔진.
* `@Workflow`, `@Activity` 커스텀 어노테이션 및 AOP 처리.
* DB 추상화 레이어 (Oracle/MariaDB 호환 SQL).


* **`:service-app`**:
* 실제 비즈니스 로직(DB to DB Migration 등) 구현.
* 엔진 SDK를 의존성으로 주입받아 워크플로우 정의.
* Mustache 기반 모니터링 UI 페이지.



## 4. Key Functional Requirements

### 4.1. Orchestration & Persistence

* 모든 Task의 실행 전/후 상태(입력값, 결과값, 스택트레이스)를 DB에 JSON 형식으로 저장한다.
* **Worker Polling:** Oracle의 `SELECT ... FOR UPDATE SKIP LOCKED`를 사용하여 분산 환경에서 작업 중복 실행을 방지한다.

### 4.2. Fault Tolerance (장애 허용)

* **Exponential Backoff:** 실패한 작업에 대해 재시도 간격을 늘려가며 자동 재시도한다.
* **State Recovery:** 서버 재시작 시 DB의 상태를 읽어 중단된 지점(Last Checkpoint)부터 재개한다.

### 4.3. Monitoring UI (Mustache)

* **Dashboard:** 전체 워크플로우 인스턴스 통계.
* **Timeline View:** 특정 인스턴스의 단계별 성공/실패 여부와 지연 시간 시각화.

## 5. Implementation Task List

1. **Database:** Oracle/MariaDB 호환 스키마 설계 및 DDL 작성.
2. **Core Engine:** Reflection/AOP를 이용한 Workflow/Activity 스캔 및 등록 로직.
3. **Scheduler:** DB 기반의 작업 큐(Polling) 및 스레드 풀 관리.
4. **UI:** Mustache를 활용한 가시성 대시보드 및 상세 이력 조회 화면.

---
[← AGENTS.md로 돌아가기](../AGENTS.md)
