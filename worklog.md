# 📓 Worklog

이 파일은 프로젝트 진행 과정에서의 작업 내용을 상세히 기록합니다.

## [2026-02-10] 초기 설계 및 환경 설정

### 작업 내용
*   **프로젝트 명세서 작성:** `docs/workflow-engine-spec.md`를 통해 프로젝트 목표 및 아키텍처 정의.
*   **데이터베이스 스키마 설계:** `engine-core/src/main/resources/sql/schema.sql` 작성.
    *   `WF_INSTANCE`: 워크플로우 인스턴스 관리.
    *   `WF_ACTIVITY_EXECUTION`: 개별 액티비티 실행 이력 및 상태 관리.
*   **핵심 인터페이스 및 어노테이션 정의:**
    *   `@Workflow`, `@Activity`: 워크플로우 및 액티비티 식별용.
    *   `WorkflowExecutor`: 워크플로우 실행 및 재개 인터페이스.
    *   `ActivityRepository`: DB 기반 작업 큐 폴링 인터페이스.
*   **문서 업데이트:**
    *   `README.md`: 프로젝트 개요 및 구조 반영.
    *   `AGENTS.md`: AI 에이전트 협업 규칙 정의.

### 결정 사항
*   **Durable Execution:** Temporal 방식을 따라 모든 상태를 DB에 저장하여 내구성을 확보하기로 함.
*   **Polling 방식:** 별도의 메시지 큐 없이 RDBMS의 `SKIP LOCKED` 기능을 활용하여 아키텍처 단순화.

### 다음 단계
*   `engine-core` 모듈의 Reflection 기반 어노테이션 스캐닝 구현.
*   Spring AOP를 이용한 Activity 실행 프록시 구현.


## [2026-02-10] DB 규칙 반영 (DATABASE_RULE 준수)

### 작업 내용
*   `docs/DATABASE_RULE.md`의 명명/공통 컬럼/제약조건 규칙을 `schema.sql`에 반영.
    - 테이블 접두사: `WF_INSTANCE` → `U_WF_INSTANCE`(마스터), `WF_ACTIVITY_EXECUTION` → `H_WF_ACTIVITY_EXECUTION`(이력).
    - 컬럼 접미사: `STATUS`→`STATUS_ST`, `RETRY_COUNT`→`RETRY_CNT`, `START_TIME/END_TIME`→`START_DT/END_DT`, `NEXT_RETRY_TIME`→`NEXT_RETRY_DT`.
    - 공통 컬럼 추가: `USE_FL`, `VIEW_FL`, `DEL_FL`, `REG_DT`, `REG_ID`, `EDIT_DT`, `EDIT_ID`.
    - 제약조건 명명: `U_WF_INSTANCE_PK`, `H_WF_ACTIVITY_EXECUTION_PK`, `H_WF_ACTIVITY_EXECUTION_FK01`.
    - 인덱스 명명: `H_WF_ACTIVITY_EXECUTION_IDX01` (폴링용 `STATUS_ST`, `NEXT_RETRY_DT`).
    - 타입 정규화: 호환성 및 규칙 일관성을 위해 날짜 타입을 `DATE`로 통일.
*   README 업데이트: 생성 대상 테이블명을 규칙 적용 명칭으로 변경.

### 결정 사항
*   엔진 전반의 스키마는 내부 DB 규칙을 1급 시민으로 채택한다.
*   이후 추가 테이블도 U_/H_ 접두 및 공통 컬럼/제약 명명 규칙을 필수 적용한다.

### TODO
*   리포지토리/DAO 작성 시 신규 컬럼명 반영 확인.
*   워커 폴링 SQL 샘플(Oracle/MariaDB) 추가.


## [2026-02-10] 엔티티 설계 (Java Record 활용)

### 작업 내용
*   `U_WF_INSTANCE` 테이블 대응 `WorkflowInstance` 레코드 작성.
*   `H_WF_ACTIVITY_EXECUTION` 테이블 대응 `ActivityExecution` 레코드 작성.
*   LOB 컬럼(JSON 페이로드) 처리를 위해 `String` 타입 매핑 (Oracle CLOB, MariaDB LONGTEXT 공통 대응).
*   `docs/DATABASE_RULE.md`에 따른 공통 컬럼(use_fl, reg_dt 등) 필드 포함.

### 결정 사항
*   데이터 불변성 보장 및 간결한 코드를 위해 Java 17의 `record`를 기본 엔티티 모델로 채택.
*   날짜 타입은 `java.time.LocalDateTime`을 사용하여 RDBMS 날짜 타입과 매핑.

### 다음 단계
*   `ActivityRepository` 실구현체(MyBatis 또는 JdbcTemplate) 및 `SKIP LOCKED` 쿼리 작성.

## [2026-02-10] 코어 엔진 인터페이스 설계 (@Workflow/@Activity, Context/Executor)

### 작업 내용
*   어노테이션 정의:
    - `@Workflow`: 워크플로우 클래스 식별용(선택적 이름 지정).
    - `@Activity`: 액티비티 메서드 식별 및 재시도 정책(`retryCount`, `backoffSeconds`).
*   인터페이스 정의(특정 DB 기술 비종속):
    - `WorkflowContext`: 인스턴스/액티비티 실행 맥락 표준화. 입력, 이전 출력, 시도 횟수, 임시 속성, 다음 단계 힌트, 체크포인트 저장/로드 API 포함.
    - `TaskExecutor`: 현재 액티비티 실행, 다음 액티비티 스케줄, 완료/실패 처리, 체크포인트 저장 API 제공.

### 결정 사항
*   모든 계약은 순수 인터페이스로 정의하여 JPA/MyBatis 등 구현체 선택의 자유를 확보.
*   시간 의존성은 `Instant` 기반 `now()` 디폴트 메서드로 추상화하여 테스트 용이성 확보.

### 다음 단계
*   Reflection/AOP 기반으로 `@Workflow`/`@Activity` 스캐닝 및 바인딩 구현.
*   `TaskExecutor`의 구현체 초안 작성(JdbcTemplate 기반) 및 폴링 루프 연계.


## [2026-02-10] 스케줄링 및 작업 실행 로직 구현

### 작업 내용
*   **`JdbcActivityRepository` 구현:**
    *   Oracle/MariaDB의 `FOR UPDATE SKIP LOCKED`를 지원하는 폴링 쿼리 작성.
    *   PENDING 작업을 조회하는 즉시 RUNNING 상태로 업데이트하여 중복 실행 방지.
*   **`WorkflowWorker` 클래스 작성:**
    *   Spring `@Scheduled`를 활용한 주기적 DB 폴링 기능.
    *   `ThreadPoolTaskExecutor`를 이용한 비동기 작업 실행(Worker Polling) 구현.
*   **`WorkflowEngineConfig` 설정:**
    *   `@EnableScheduling` 활성화.
    *   워크플로우 전용 스레드 풀(`wf-worker-`) 구성 및 Backpressure 정책(`CallerRunsPolicy`) 설정.

### 결정 사항
*   **DB 벤더 호환성:** `SKIP LOCKED` 문법은 Oracle과 MariaDB(10.6+)에서 공통으로 지원되므로 표준 쿼리로 채택.
*   **비동기 처리:** 메인 폴링 루프의 지연을 방지하기 위해 작업 실행은 별도 스레드 풀에 위임.

### 다음 단계
*   `WorkflowContext`의 실제 구현체(`DefaultWorkflowContext`) 및 팩토리 작성.
*   `TaskExecutor`의 상세 구현을 통해 실제 비즈니스 로직(Activity) 호출 연동.


## [2026-02-10] 공통 기능 보완 (JSON, Multi-DB, AOP)

### 작업 내용
*   **JSON 직렬화 지원:**
    *   Jackson 기반 `JsonUtil` 구현.
    *   Java Time Module 등록을 통해 `LocalDateTime` 등 날짜 타입 직렬화 호환성 확보.
*   **멀티 DB 호환성(Dialect) 강화:**
    *   `DbDialect` 인터페이스 및 `OracleDialect`, `MariaDbDialect` 구현.
    *   `JdbcActivityRepository`에서 Dialect를 주입받아 페이징(`LIMIT`/`FETCH FIRST`) 및 시간 함수 처리 로직 고도화.
*   **AOP 기반 워크플로우 로깅:**
    *   `WorkflowAspect` 구현.
    *   `@Workflow` 어노테이션이 선언된 빈의 메서드 호출 전/후를 가로채어 `U_WF_INSTANCE` 테이블에 실행 이력(시작, 성공, 실패 상태 및 입출력 JSON) 자동 기록.

### 결정 사항
*   **DB 추상화:** 쿼리 내 직접적인 벤더 종속성을 제거하고 Dialect 전략 패턴을 사용하여 확장성 확보.
*   **자동화:** 개발자가 명시적으로 로그 코드를 작성하지 않아도 어노테이션만으로 영속성 로그가 남도록 AOP 적용.

### 다음 단계
*   `DefaultWorkflowContext` 구현 및 Activity 실행 결과의 JSON 역직렬화 연동.


## [2026-02-10] 스키마 검증 및 보완

### 작업 내용
*   **`U_WF_INSTANCE` 테이블 보완:**
    *   `WorkflowContext`의 스냅샷 저장 기능 지원을 위해 `STATE_DATA` (CLOB) 컬럼 추가.
*   **성능 최적화 (인덱스 추가):**
    *   `H_WF_ACTIVITY_EXECUTION` 테이블의 외래 키 컬럼인 `INSTANCE_ID`에 인덱스(`H_WF_ACTIVITY_EXECUTION_IDX02`) 추가.
*   **엔티티 및 AOP 연동 수정:**
    *   `WorkflowInstance` 레코드에 `stateData` 필드 추가.
    *   `WorkflowAspect`에서 인스턴스 생성 시 기본값(`USE_FL`, `VIEW_FL`, `DEL_FL`) 명시적 삽입 로직 보완.

### 결정 사항
*   **Durable State:** 인스턴스의 중간 상태를 저장할 수 있는 공간(`STATE_DATA`)을 확보함으로써 더욱 강력한 Durable Execution 지원이 가능해짐.
*   **DB 규칙 준수:** `DATABASE_RULE.md`의 인덱스 명명 규칙을 철저히 준수하여 일관성 유지.

## [2026-02-10] DB별 스키마 분리 (Oracle/MariaDB)

### 작업 내용
*   **스키마 파일 분리:**
    - 기존 `schema.sql`을 `schema-oracle.sql`로 변경 및 Oracle 특화 문법(VARCHAR2, CLOB, DATE) 명시.
    - MariaDB 전용 `schema-mariadb.sql` 신규 작성 (VARCHAR, LONGTEXT, DATETIME, InnoDB 설정).
*   **문서 업데이트:**
    - `README.md`에 DB 벤더별 스키마 생성 안내 수정.

### 결정 사항
*   DB 벤더별 미세한 타입 차이(CLOB vs LONGTEXT 등)를 확실히 관리하기 위해 통합 파일 대신 분리된 스키마 파일을 제공하기로 함.

### 다음 단계
*   `WorkflowContext` 및 `TaskExecutor` 실제 구현체 작성.

## [2026-02-10] Context/Executor 실구현 추가

### 작업 내용
*   `DefaultWorkflowContext` 구현
    - 입력, 이전 출력, 속성 맵, 다음 액티비티 힌트(`setNext`) 관리
    - `saveState`/`loadState`를 통해 스냅샷을 JSON 문자열로 보존 (`JsonUtil` 연동)
*   `TaskExecutor` JDBC 구현체 `JdbcTaskExecutor` 추가
    - `complete()`: 결과를 JSON 직렬화 후 `H_WF_ACTIVITY_EXECUTION`에 `COMPLETED`로 업데이트, next 힌트 존재 시 PENDING 생성
    - `fail()`: 에러 메시지/스택트레이스를 저장하고 `FAILED` 업데이트, 선택적 backoff 시각 설정(재시도 생성은 상위 정책으로 위임)
    - `scheduleNext()`: 다음 액티비티를 `PENDING` 상태로 INSERT
*   리포지토리 확장
    - `ActivityRepository#createPending(...)` 메서드 추가 및 `JdbcActivityRepository`에 INSERT 로직 구현(UUID PK, 공통 컬럼 기본값 적용)

### 결정 사항
*   `executionId`는 현재 컨텍스트의 `attributes`에 키(`executionId`)로 주입하여 `TaskExecutor`에서 참조한다. (Worker가 책임)
*   재시도 정책(최대 횟수/지수 백오프 해석)은 상위 레이어(Worker/Invoker)에서 수행하고, `TaskExecutor`는 결과 반영과 다음 단계 생성에 집중한다.

### 빌드/검증
*   `./gradlew build` 성공(컴파일 정상).

## [2026-02-10] Reflection 기반 엔진 자동화 (2단계) 완성

### 작업 내용
*   **`WorkflowRegistry` 구현:**
    - 애플리케이션 시작 시 `@Workflow` 및 `@Activity` 어노테이션이 붙은 빈(Bean)과 메서드를 스캔하여 맵(Map) 형태로 등록하는 레지스트리 로직 구현.
    - `ApplicationListener<ContextRefreshedEvent>`를 활용하여 컨텍스트 로딩 완료 후 자동 스캔.
*   **`ActivityAspect` 추가:**
    - `@Activity` 어노테이션이 붙은 메서드 호출 시 실행 이력을 로그로 남기는 AOP 프록시 기초 구현.
*   **`WorkflowWorker` 고도화:**
    - `WorkflowRegistry`와 연동하여 실제 등록된 빈의 메서드를 리플렉션으로 호출하도록 로직 보완.
    - 액티비티 실행 성공 시 `TaskExecutor.complete()`를 호출하여 다음 단계 스케줄링 연동.
    - 액티비티 실패 시 `@Activity` 설정을 읽어 `TaskExecutor.fail()`을 통해 재시도 예약 연동.
*   **`JdbcTaskExecutor` 보완:**
    - `fail()` 메서드에서 실제 시도 횟수(`context.attempt()`)를 반영하도록 수정.
    - 재시도 예약 시 새로운 `PENDING` 레코드를 생성하는 로직 활성화.

### 결정 사항
*   **자동화:** 개발자는 로직 메서드에 어노테이션만 붙이면 엔진이 자동으로 인식하여 DB 폴링 및 비동기 실행을 처리하도록 함.
*   **내구성:** 리플렉션 호출 시 발생하는 예외를 가로채어 DB에 실패 상태와 재시도 시각을 정확히 기록함으로써 Durable Execution 보장.

### 빌드/검증
*   `./gradlew build` 성공(컴파일 정상).

### 작업 내용
*   **코드 내 TODO 주석 추가:**
    - `WorkflowWorker`: ContextFactory 연동, Reflection 기반 Invoker 구현, 재시도 정책 적용 지점에 주석 추가.
    - `WorkflowAspect`: 중단된 워크플로우 재개(resume) 대응 및 상세 에러 로깅 보완 지점에 주석 추가.
    - `JdbcTaskExecutor`: 시도 횟수 업데이트 및 정책 기반 최종 실패 처리 지점에 주석 추가.
*   **가이드라인 업데이트:**
    - `AGENTS.md`에 미구현 사항에 대해 `// TODO:` 주석을 의무적으로 남기도록 원칙 추가.

### 결정 사항
- 현재 골격이 잡힌 코드에서 실제 구현이 누락된 복잡한 로직(Reflection 호출, 지수 백오프 계산 등)은 `// TODO:`로 명시하여 향후 단계에서 집중적으로 다루기로 함.
- 에이전트 협업 시 미구현 지점을 명확히 공유하기 위해 주석 규칙을 가이드라인에 명문화함.

## [2026-02-11] 기본 JDK 버전 업그레이드 (Java 25)

### 작업 내용
*   프로젝트 기본 JDK 버전을 Java 17에서 **Java 25**로 업그레이드.
*   `build.gradle`의 `sourceCompatibility`를 '25'로 수정.
*   `README.md`, `docs/workflow-engine-spec.md`, `AGENTS.md`의 기술 스택 정보를 Java 25로 업데이트.

### 결정 사항
*   최신 Java 기능을 활용할 수 있도록 프로젝트 기준 버전을 Java 25로 상향 조정.

## [2026-02-11] Gradle Wrapper 업그레이드 (v9.1.0)

### 작업 내용
*   Gradle Wrapper 버전을 **9.1.0**으로 업그레이드.
*   `gradle/wrapper/gradle-wrapper.properties`의 `distributionUrl`을 `gradle-9.1.0-bin.zip`으로 수정.
*   `gradle-wrapper.jar` 바이너리 파일을 직접 다운로드하여 형상관리에 포함.
*   `.\gradlew.bat --version`을 통해 9.1.0 적용 확인.

### 결정 사항
*   Java 25 환경을 완벽히 지원하기 위해 Gradle 최신 안정 버전인 9.1.0을 사용하기로 함.
*   환경 독립적인 빌드를 위해 `gradle-wrapper.jar`를 명시적으로 프로젝트에 포함시킴.

## [2026-02-10] Mustache 기반 모니터링 UI 개발 (3단계)

### 작업 내용
*   **Repository 확장:**
    - `ActivityRepository`에 인스턴스 목록 조회(`findAllInstances`), 상세 조회(`findInstanceById`), 활동 이력 조회(`findActivitiesByInstanceId`) 메서드 추가.
    - `JdbcActivityRepository`에 위 메서드들의 실구현 및 `WorkflowInstanceRowMapper` 추가.
*   **모니터링 컨트롤러 구현:**
    - `WorkflowMonitoringController` 작성: 대시보드 및 타임라인 뷰 데이터 바인딩.
    - 인스턴스 상태별(RUNNING, COMPLETED, FAILED) 통계 로직 포함.
*   **Mustache 템플릿 작성:**
    - `dashboard.mustache`: Bootstrap 5 기반의 전체 인스턴스 현황판 및 목록.
    - `timeline.mustache`: 특정 인스턴스의 단계별 실행 이력을 시각화한 타임라인 뷰.
*   **프로젝트 구조 조정:**
    - 명세에 따라 `:service-app` 모듈 디렉토리를 생성하고 UI 관련 소스 배치.

### 결정 사항
*   **가시성 확보:** 텍스트 로그 중심의 엔진 상태를 UI로 시각화하여 운영 및 디버깅 편의성 증대.
*   **Bootstrap 활용:** 별도의 디자인 작업 없이 표준 CSS 프레임워크를 활용하여 깔끔하고 반응형인 관리 화면 구축.

### 다음 단계
*   워크플로우 재개(Resume) 및 수동 중단(Terminate) 기능 UI 연동.
*   실제 비즈니스 시나리오(예: DB to DB 마이그레이션) 테스트 케이스 작성 및 UI 확인.

## [2026-02-11] Gradle 멀티 모듈 및 Wrapper 구성

### 작업 내용
*   **멀티 모듈 구조 확립**:
    - 루트 디렉토리에 `settings.gradle` (include `:engine-core`, `:service-app`) 생성.
    - 루트 `build.gradle`에 공통 설정(Java 17, Spring Boot dependency management) 정의.
    - `engine-core/build.gradle`: 핵심 라이브러리(JDBC, AOP, Jackson) 의존성 설정.
    - `service-app/build.gradle`: `engine-core` 프로젝트 의존성 및 Web/Mustache 설정.
*   **Gradle Wrapper 구성**:
    - `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.5) 작성.
    - `gradlew.bat` 윈도우용 실행 스크립트 작성.

### 결정 사항
*   **빌드 자동화**: Gradle Wrapper를 프로젝트에 포함시켜 환경에 구애받지 않는 빌드 재생산성을 확보함.
*   **모듈 분리**: 엔진 SDK(`engine-core`)와 실행 애플리케이션(`service-app`)의 책임을 명확히 분리하여 재사용성 증대.

### TODO
*   `gradle-wrapper.jar` 바이너리 파일 확보 및 형상관리 포함 필요.
*   리눅스 환경에서의 `gradlew` 실행 권한(`chmod +x gradlew`) 부여 필요.
*   `./gradlew build` 검증.

## [2026-02-11] 리눅스용 Gradle Wrapper (gradlew) 추가

### 작업 내용
*   리눅스 및 macOS 등 POSIX 환경에서 사용할 수 있는 `gradlew` 쉘 스크립트 작성.
*   표준 Gradle 래퍼 템플릿을 기반으로 하여 `gradle/wrapper/gradle-wrapper.jar`를 참조하도록 구성.

### 결정 사항
*   현재 환경에 `gradle`이 설치되어 있지 않아 자동 생성이 불가능하므로, 표준 스크립트 내용을 바탕으로 수동 생성함.
*   윈도우 환경에서는 `gradlew.bat`를, 리눅스 환경에서는 `gradlew`를 사용하여 일관된 빌드 경험을 제공함.

## [2026-02-11] 프로젝트 전체 문서 체계화 (tasks.md 도입)

### 작업 내용
*   **`tasks.md` 신규 생성**:
    - 현재까지 완료된 작업(기초 설정, DB 레이어, 코어 엔진, 모니터링 UI)을 체크박스 형식으로 정리.
    - 향후 구현 및 개선이 필요한 '우리의 숙제(Our Homework)' 목록을 범주별(엔진 고도화, 안정성, UI, 문서화)로 정의.
*   **`AGENTS.md` 가이드라인 강화**:
    - 에이전트 원칙에 `tasks.md`를 통한 진척도 관리 의무 명시.
    - 미구현 사항 발생 시 `// TODO:` 주석과 `tasks.md`의 "향후 과제"를 동시 업데이트하도록 규칙 정의.
*   **문서 간 역할 재정의**:
    - `tasks.md`: 현재 구현 상태 및 계획(진척도 중심).
    - `worklog.md`: 과거 작업의 상세 이력 및 결정 사항(로그 중심).
    - `README.md`: 프로젝트 개요 및 빠른 시작 가이드(사용자 중심).

### 결정 사항
*   **가시성 향상**: 단순 로그 형태의 `worklog.md`만으로는 전체 프로젝트의 완성도를 파악하기 어려워, 명시적인 태스크 관리 파일인 `tasks.md`를 도입함.
*   **에이전트 협업 표준화**: 모든 에이전트가 동일한 `tasks.md`를 보고 작업 우선순위를 결정하도록 함.

### 다음 단계
*   `tasks.md`의 "향후 과제" 중 최우선 순위인 **지수 백오프 실구현** 및 **워크플로우 재개 로직** 착수.


## [2026-02-11] 문서 허브(Hub)-Spoke 체계 연결 보완

### 작업 내용
*   `AGENTS.md`에 중앙 허브(Hub) 선언 추가 및 Spoke 인덱스/사용 순서/동기화 규칙 명문화.
*   `README.md`의 Documentation 섹션에서 AGENTS.md를 시작점으로 안내하도록 문구 보강.
*   `docs/workflow-engine-spec.md` 말미에 허브 복귀 링크 추가.
*   `docs/OPERATIONS.md` 중복 내용을 정리하고 허브 복귀 링크를 유지.
*   `tasks.md` 상단에 허브 안내 문구 추가, 하단 중복 섹션 제거.

### 결정 사항
*   문서 간 중복 서술은 허브(Hub) 문서의 링크 체계로 관리하며, 각 Spoke는 말미에 허브 복귀 링크를 유지한다.
*   `// TODO:` 주석과 `tasks.md`의 "향후 과제"는 반드시 동기화한다.

### TODO
*   CONTRIBUTING/DATABASE_RULE의 기존 허브 복귀 링크 유지 모니터링.
*   신규 Spoke 문서가 생길 경우 AGENTS.md 인덱스에 즉시 반영.

## [2026-02-11] 엔진 기능 고도화 (지수 백오프, 재개 로직)

### 작업 내용
*   **지수 백오프(Exponential Backoff) 구현**:
    - `ExponentialBackoffRetryPolicy` 클래스를 추가하여 시도 횟수에 따른 지연 시간 계산 로직(base * 2^(n-1)) 반영.
    - `WorkflowWorker`에서 `@Activity` 설정을 읽어 최대 재시도 횟수 체크 및 지수 백오프 기반 재시도 예약 연동.
*   **워크플로우 재개(Resume) 로직 구현**:
    - `JdbcWorkflowExecutor`를 통해 `resumeWorkflow()` 실구현.
    - `ActivityRepository`에 `resetToPending()` 메서드를 추가하여 실패한 활동을 수동으로 재시작할 수 있도록 함.
*   **UI 연동**:
    - `WorkflowMonitoringController`에 재개(`resume`) API 추가.
    - `timeline.mustache`에 실패한 인스턴스에 대한 'Resume' 버튼 추가.
*   **리플렉션 보완**:
    - `WorkflowWorker`에서 메서드 파라미터 타입을 체크하여 인자 바인딩 로직 기초 강화.

### 결정 사항
*   최대 재시도 횟수 초과 시 더 이상 자동 재시도를 생성하지 않고 `FAILED` 상태로 고정하여 수동 재개(Resume)를 유도함.
*   지수 백오프의 최대 지연 시간은 시스템 안정성을 위해 1시간으로 제한함.

## [2026-02-11] 브랜드 반영 (패키지명 변경)

### 작업 내용
*   전체 프로젝트 패키지명을 `com.example.*`에서 `com.bangrang.*`으로 변경.
*   Java 소스 파일의 패키지 선언 및 import 문 일괄 업데이트.
*   디렉토리 구조를 신규 패키지명에 맞춰 재배치 (`com/example` -> `com/bangrang`).
*   `build.gradle` 및 주요 문서(`README.md`, `AGENTS.md` 등) 내 패키지 참조 업데이트.

### 결정 사항
*   사용자 브랜드(`bangrang`)를 프로젝트 전반에 적용하여 소속감 및 아이덴티티 강화.


## [2026-02-11] 인코딩(UTF-8) 주석 깨짐 복구

### 작업 내용
* `H_WF_DLQ` 테이블 DDL 추가 (Oracle/MariaDB)
  - 공통 컬럼(DATABASE_RULE) 준수: USE_FL/VIEW_FL/DEL_FL/REG_DT/REG_ID/EDIT_DT/EDIT_ID
  - 제약조건: `H_WF_DLQ_PK`, `H_WF_DLQ_FK01(U_WF_INSTANCE)`, `H_WF_DLQ_FK02(H_WF_ACTIVITY_EXECUTION)`
  - 인덱스: `H_WF_DLQ_IDX01(DLQ_STATUS_ST, REG_DT)`, `H_WF_DLQ_IDX02(INSTANCE_ID)`
* DLQ 상태 모델 정의(초안): `DLQ_STATUS_ST` = NEW, REQUEUED, DISCARDED
* 최종 실패 시나리오 설계 반영: `FAILED_FINAL`로 고정 후 DLQ 적재(워크플로우명/액티비티명 포함)

### 다음 단계(M1 계속)
* FAILED_FINAL 상태 전이 명세 및 엔진 설정(`engine.dlq.enabled`, `engine.dlq.notifier=webhook`) 초안 문서화
* H2용 테스트 스키마 검토(선택)


### 작업 내용
* **DLQ 엔티티/리포지토리 구현:**
  - `DlqEntry` Java Record 엔티티 생성 (H_WF_DLQ 테이블 대응)
  - `DlqRepository` 인터페이스 정의 (insert, findAll, findById, updateStatus)
  - `JdbcDlqRepository` JDBC 구현체 작성 (@Repository, RowMapper 포함)
* **DLQ 알림 SPI 구현:**
  - `DlqNotifier` 인터페이스 정의 (SPI)
  - `WebhookDlqNotifier` 기본 구현체 작성 (java.net.http.HttpClient 기반 POST 발송, URL 미설정 시 콘솔 로그 대체)
* **WorkflowWorker DLQ 연동:**
  - 최대 재시도 초과 시 `moveToDlq()` 메서드로 DLQ 적재 + 웹훅 알림 발송
  - DlqRepository, DlqNotifier, JsonUtil 의존성 주입 추가
  - DefaultWorkflowContext 생성자 7개 파라미터 정합성 수정
* **WorkflowEngineConfig 업데이트:**
  - `DlqNotifier` 빈 등록 (`engine.dlq.webhook-url` 프로퍼티 연동)
* **H2 테스트 스키마 추가:**
  - `schema-h2.sql` 신규 작성 (U_WF_INSTANCE, H_WF_ACTIVITY_EXECUTION, H_WF_DLQ)
* **빌드 환경 정비:**
  - Spring Boot 3.2.2 → 3.4.2, dependency-management 1.1.4 → 1.1.7 업그레이드 (Gradle 9.1.0 호환)
  - Gradle 9에서 `sourceCompatibility` 직접 설정 불가 → `java {}` 블록으로 변경
  - 전체 Java 파일 UTF-8 BOM 일괄 제거
  - engine-core `bootJar` 제거 (spring-boot 플러그인 미적용 라이브러리 모듈)
  - service-app에 `spring-boot-starter-jdbc` 의존성 추가

### 빌드/검증
* `gradlew compileJava` BUILD SUCCESSFUL

### 다음 단계
* 테스트 코드 강화: 핵심 로직 단위 테스트 및 H2/Testcontainers 통합 테스트

### 원인/대응
* 원인: Windows PowerShell 환경에서 텍스트 파이프라인과 `Set-Content -Encoding UTF8` 재저장 과정이 원문 멀티바이트 한글을 손상.
* 대응: 손상된 주석 블록을 정상 한글로 교체하고, 파일 저장을 UTF-8(BOM 없음)로 유지.

### 재발 방지
* 에디터/IDE 저장 인코딩을 UTF-8(무 BOM) 고정.
* PowerShell 대량 치환 시 `Set-Content` 대신 `.NET` API(`[System.IO.File]::WriteAllText(utf8 no BOM)`) 사용 권고.
* Gradle 컴파일 옵션(UTF-8) 유지: 루트 `build.gradle` `JavaCompile.options.encoding = 'UTF-8'` 확인.

