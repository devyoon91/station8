# Line Engine Specification

## 1. Project Overview

Temporal의 **Durable Execution** 메커니즘을 코어로 하고, Airflow/Azkaban 스타일의 **웹 기반 DAG 빌더 + Cron 스케줄러 + 운영 모니터링**을 그 위에 올리는 **하이브리드 라인 엔진**.

### 1.1. 노선 (Direction)

- **액티비티(역)** 는 Java 코드로 작성한다 (`@Activity` 메서드). 새 액티비티가 필요하면 개발자가 jar를 추가/배포한다.
- **라인(DAG)** 는 코드가 아니라 데이터로 정의한다. 운영자가 웹 UI에서 역을 끌어다 연결하고, cron을 설정하고, 실행/모니터링한다.
- **상태(인스턴스/실행이력/DLQ)** 는 모두 RDBMS에 영속화된다 (Oracle/MariaDB).

### 1.2. 무엇이 아닌가

- "웹에서 Java 코드를 짜는" 시스템이 아니다 (보안·격리 비용 과다).
- Airflow의 Python DAG처럼 코드 파일로 라인을 정의하지 않는다 (운영자 친화 우선).
- Spring Batch를 대체하지 않는다. 필요 시 Spring Batch Job을 어댑터 역로 감싸서 호출한다.

## 2. Technical Stack

* **Language:** Java 21 (LTS)
* **Framework:** Spring Boot 3.4.x
* **Build Tool:** Gradle (Multi-module)
* **Database:** Oracle / MariaDB (공통 지원)
* **View Engine:** Mustache + Drawflow (DAG 빌더 클라이언트)

## 3. Multi-Module Architecture

* **`:station8-engine`** — 엔진 SDK
  * `@Line`, `@Activity` 어노테이션 + AOP/Registry
  * DAG 정의/인터프리터 (분기/병렬 fan-out/fan-in)
  * Worker 폴링(SKIP LOCKED), 재시도(Exponential Backoff), DLQ
  * Cron 스케줄러 (DAG 정의 단위 정기 실행)
  * DB 추상화 레이어 (Oracle/MariaDB 호환)
* **`:station8-app`** — 운영 웹
  * 그래프 빌더 UI (DAG 작성/편집)
  * Cron 스케줄 관리 UI
  * 운영 대시보드 / 타임라인 / DLQ 관리
  * 액티비티 카탈로그 (등록된 `@Activity` 목록 노출)

## 4. Key Functional Requirements

### 4.1. DAG 정의 모델

* `U_WF_DEFINITION` (라인 정의: 이름, 설명, cron, 활성여부, 버전)
* `U_WF_NODE` (역: 정의ID, activityName, 입력 파라미터, 캔버스 좌표 x/y)
* `U_WF_EDGE` (의존성: fromNode, toNode, 조건)
* DAG 검증: 사이클 금지, 시작 역 1개 이상, 미등록 액티비티 참조 금지

### 4.2. 인터프리터 (분기/병렬)

* 역은 **모든 선행 역이 COMPLETED일 때** 실행된다.
* 새로운 액티비티 상태: `WAITING_DEPENDENCIES` (선행 미완료) → `PENDING` (실행 가능) → `RUNNING` → `COMPLETED`/`FAILED`.
* fan-out: 한 역의 후행 N개를 동시 PENDING으로 전이.
* fan-in: 후행 역은 모든 선행이 COMPLETED여야 PENDING.
* 역 간 데이터 전달은 최소화한다 — 각 역이 DB를 직접 R/W 한다는 전제. 실행 컨텍스트(라인 인스턴스ID, 처리 날짜 등)만 입력으로 전달.

### 4.3. Cron 스케줄러

* `U_WF_SCHEDULE` (정의ID, cron 표현식, 다음 실행 시각, 일시중지 여부)
* `@Scheduled` 트리거 폴러: 만료된 cron을 찾아 `startLine(definitionId)` 호출.
* 수동 실행도 동일 경로 (운영자가 UI에서 "지금 실행" 버튼).

### 4.4. Orchestration & Persistence (기존 유지)

* 모든 액티비티의 입력값/결과값/스택트레이스를 DB에 JSON으로 저장.
* Worker Polling: `FOR UPDATE SKIP LOCKED` 분산 처리.

### 4.5. Fault Tolerance (기존 유지 + 확장)

* Exponential Backoff 자동 재시도.
* 최대 재시도 초과 → `H_WF_DLQ` 적재 + Webhook 알림.
* DLQ Requeue/Discard.

### 4.6. Monitoring UI (확장)

* **Dashboard:** 인스턴스 목록/통계/검색·필터.
* **DAG Builder:** 역 팔레트 + 캔버스 (드래그·드롭, 저장/로드).
* **Schedule Manager:** cron 등록/일시중지/즉시 실행.
* **Activity Catalog:** 등록된 `@Activity` 메타데이터 노출.
* **Timeline + Graph View:** 인스턴스 실행을 DAG 그래프로 시각화 (성공/실패/지연 색상).
* **DLQ Console:** 실패한 액티비티 Requeue/Discard.

## 5. 비범위 (Out of Scope)

* 웹에서 Java 코드 작성/컴파일.
* Airflow의 XCom 같은 무거운 역 간 데이터 전달 (각 역은 DB 직접 R/W).
* RBAC/멀티테넌시 (필요 시 후속 마일스톤).
