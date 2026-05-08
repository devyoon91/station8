# Architecture

`simple-workflow-engine`의 모듈 구성과 런타임 흐름을 코드 기준으로 설명한다. 본 문서는 "어떻게 돌아가는가"에 답하고, "왜 그렇게 설계했는가"는 [workflow-engine-spec.md](workflow-engine-spec.md)에 둔다.

## 1. 모듈 구성

```
simple-workflow-engine/
├── engine-core/           # 엔진 코어 (어노테이션, DAG 인터프리터, 워커 폴러, DLQ)
├── service-app/           # Spring Boot 앱 (컨트롤러, Mustache 뷰, 비즈니스 액티비티)
├── e2e-tests/             # REST Assured 회귀 테스트 (compose 환경 전용)
├── scripts/scenarios/     # bash + curl 스모크 시나리오
└── docker/                # Dockerfile + docker-compose.yml
```

### engine-core

엔진의 코어 계약과 구현. Spring 의존성은 일부(`@Component`, `JdbcTemplate`)만 사용하고, 비즈니스 도메인을 모른다.

| 패키지 | 책임 |
|---|---|
| `annotation` | `@Workflow`, `@Activity` |
| `core` | `WorkflowRegistry`, `DagInterpreter`, `DagValidator`, `WorkflowWorker`, `WorkflowScheduler`, `JdbcTaskExecutor`, `DefaultWorkflowContext`, `ExponentialBackoffRetryPolicy` |
| `repository` | `JdbcActivityRepository`, `JdbcWorkflowDefinitionRepository`, `JdbcWorkflowScheduleRepository`, `JdbcDlqRepository` |
| `dialect` | `DbDialect` 추상화 (`MariaDbDialect`, `OracleDialect`) |
| `exception` | `WorkflowEngineException`, `ErrorCodes` |
| `plugin` | `PluginLoader` (URLClassLoader 기반) |
| `aspect` | `WorkflowAspect`, `ActivityAspect` (AOP 로깅) |

### service-app

엔진을 사용하는 Spring Boot 앱. 컨트롤러, Mustache 템플릿, 데모 액티비티(NOOP/MIGRATION_WRITE/RUN_BATCH_JOB), Spring Batch 어댑터.

| 패키지 | 책임 |
|---|---|
| `controller` | `LandingController`, `WorkflowMonitoringController` |
| `definition` | `WorkflowDefinitionController`, `WorkflowDefinitionService`, `BuilderController` |
| `schedule` | `ScheduleController`, `ScheduleService` |
| `catalog` | `ActivityCatalogController` |
| `migration` | `DataMigrationWorkflow`(@Workflow), `MigrationInitializer` |
| `demo` | `NoopWorkflow`(@Workflow), `DemoSeedRunner` (@Profile demo) |
| `adapter` | `SpringBatchActivityAdapter` (@Activity RUN_BATCH_JOB) |

### e2e-tests

`onlyIf { -PdockerHost or SWE_E2E_HOST }`로 게이트되는 회귀 테스트. JUnit5 + REST Assured + Awaitility + Jackson.

## 2. 핵심 데이터 모델

| 테이블 | 역할 | 라이프사이클 |
|---|---|---|
| `U_WF_DEFINITION` | DAG 메타데이터 + 버전 | 정의 등록 시 INSERT |
| `U_WF_NODE` | 노드 (activityNm, inputParams, 위치) | 정의 등록 시 INSERT |
| `U_WF_EDGE` | 노드 간 엣지 | 정의 등록 시 INSERT |
| `U_WF_INSTANCE` | 워크플로우 인스턴스 (status, input, output) | run-now 또는 스케줄 트리거 시 INSERT |
| `U_WF_SCHEDULE` | Cron 스케줄 (next_run_dt, paused_fl) | 등록 시 INSERT, 폴러 틱마다 UPDATE |
| `H_WF_ACTIVITY_EXECUTION` | 액티비티 실행 (status, retry, backoff, output) | 인스턴스 시작 시 노드별 INSERT, 워커가 lock→run→complete/fail/retry로 UPDATE |
| `H_WF_DLQ` | DLQ (NEW / REQUEUED / DISCARDED) | 최대 재시도 초과 시 INSERT, UI 액션으로 UPDATE |

상태 머신:

```
[H_WF_ACTIVITY_EXECUTION.status]

WAITING_DEPENDENCIES                  (fan-in 대기)
        │ allPredecessorsCompleted()
        ▼
PENDING ─── poll/lock ───▶ RUNNING ──┬─ success ───▶ COMPLETED
   ▲                                  │
   │ next_retry_dt + backoff          ├─ fail (재시도 가능) ─▶ PENDING
   └──────────────────────────────────┘
                                      │
                                      └─ fail (최대 재시도 초과) ─▶ FAILED + H_WF_DLQ
```

## 3. 런타임 흐름

### 3.1 부팅

```
Application.main()
  └─ SpringApplication.run()
       ├─ DataSource 자동 설정 (HikariCP)
       │
       ├─ spring.sql.init  ──▶  schema-${platform}.sql 적용
       │                         (default=h2, docker=mariadb)
       │                         ※ @Scheduled가 발화하기 전에 실행됨
       │
       ├─ DbDialect bean 결정 (Application#dbDialect)
       │     └─ engine.dialect 명시 > spring.datasource.url 자동 감지
       │
       ├─ ContextRefreshedEvent
       │     └─ WorkflowRegistry.scanActivities()
       │           └─ AopUtils.getTargetClass()로 원본만 스캔, 멱등 가드
       │
       ├─ CommandLineRunner 실행
       │     ├─ MigrationInitializer (@Order 0): 시드 데이터(SRC_DATA → PENDING)
       │     └─ DemoSeedRunner   (@Order 100, @Profile("demo")): 데모 DAG + 스케줄
       │
       └─ ApplicationReadyEvent
             ├─ PluginLoader: plugins/*.jar URLClassLoader 스캔 → registerActivity
             ├─ WorkflowWorker @Scheduled (1s 또는 5s/demo) 시작
             └─ WorkflowScheduler @Scheduled (30s 또는 5s/demo) 시작
```

핵심 변경 이유: 과거에는 `MigrationInitializer.run()`에서 schema도 적용했는데, `@Scheduled`가 그보다 먼저 발화해 `Table doesn't exist` 에러가 30초간 누적되었다. `spring.sql.init`은 DataSource bean 직후 즉시 실행되어 이 race를 제거한다.

### 3.2 DAG 정의 등록

`POST /api/workflow/definitions`

```
WorkflowDefinitionController.create(req)
  └─ WorkflowDefinitionService.createDefinition(req)
       ├─ DagValidator.validate(nodes, edges, registry.activityNames())
       │     [no-nodes / no-start / no-terminal / cycle(Kahn) / self-loop / unknown-activity / dangling-edge]
       │     └─ 위반 시 WorkflowEngineException(WF-E301~E308)
       │
       └─ JdbcWorkflowDefinitionRepository
             ├─ INSERT U_WF_DEFINITION
             ├─ INSERT U_WF_NODE   (노드 N개)
             └─ INSERT U_WF_EDGE   (엣지 M개)
```

응답: `{ "definitionId": "<UUID>" }` (201).

### 3.3 인스턴스 즉시 실행

`POST /api/workflow/definitions/{id}/run`

```
WorkflowDefinitionController.run(id, body)
  └─ WorkflowDefinitionService.runDefinition(id, inputData)
       ├─ INSERT U_WF_INSTANCE  (status=RUNNING, start_dt=now)
       │
       └─ DagInterpreter.startInstance(definitionId, instanceId, inputData)
             ├─ load nodes + edges
             ├─ findStartNodes()  ← incoming edge가 0인 노드
             └─ for each node:
                   ├─ start node:    status=PENDING                (워커가 즉시 픽업)
                   └─ non-start:     status=WAITING_DEPENDENCIES   (fan-in 대기)
                   └─ ActivityRepository.createForNode(...)
                         └─ INSERT H_WF_ACTIVITY_EXECUTION
```

### 3.4 워커 폴링과 액티비티 실행

`WorkflowWorker.@Scheduled(fixedDelayString="${workflow.polling.interval-ms}")`

```
pollActivities()
  ├─ JdbcActivityRepository.findPendingActivitiesWithLock(limit=10)
  │     [SELECT * FROM H_WF_ACTIVITY_EXECUTION
  │      WHERE status='PENDING' AND (next_retry_dt IS NULL OR next_retry_dt <= NOW())
  │      ORDER BY reg_dt ASC
  │      LIMIT 10  -- DbDialect별로 LIMIT 또는 FETCH FIRST
  │      FOR UPDATE SKIP LOCKED]
  │     └─ UPDATE status='RUNNING', start_dt=now (같은 트랜잭션)
  │
  └─ for each activity → workflowTaskExecutor.execute(processActivity)
        │
        ▼
  processActivity(activity)
    ├─ WorkflowRegistry.getActivity(name) → ActivityMetadata(bean, method, @Activity)
    ├─ resolveArguments(metadata, inputData)
    └─ method.invoke(bean, args)   ← 사용자 코드 실행
          │
          ├─ 성공:
          │    ├─ TaskExecutor.complete(ctx, result)  → status=COMPLETED, output_data
          │    └─ if (nodeId != null):
          │          DagInterpreter.onNodeCompleted(instanceId, nodeId)
          │
          └─ 실패:
               ├─ retryPolicy.isExceeded(attempt, maxRetry) ?
               │    ├─ YES: TaskExecutor.fail(ctx, cause, null)
               │    │       moveToDlq(activity, ctx, cause, maxRetry)
               │    │            └─ INSERT H_WF_DLQ + DlqNotifier.notify(...)
               │    └─ NO:  nextBackoff = retryPolicy.calculateNextBackoff(attempt, baseSec)
               │            TaskExecutor.fail(ctx, cause, nextBackoff)
               │                 └─ UPDATE status='PENDING', next_retry_dt=now+backoff, retry_cnt++
```

`SKIP LOCKED`로 다중 워커가 같은 행을 잡지 않고 자연스럽게 분배된다 — `engine-core/sql/SkipLockedConcurrencyMariaDbIT`에서 4 워커 × LIMIT 10 동시 폴링 시 30개 시드 정확히 30회 클레임됨을 검증.

### 3.5 Fan-out / Fan-in

```
DagInterpreter.onNodeCompleted(instanceId, completedNodeId)
  └─ for each outgoing edge to successor:
        if allPredecessorsCompleted(instanceId, successor):
              ActivityRepository.promoteToPending(successorId)
                  [UPDATE status='PENDING' WHERE id=? AND status='WAITING_DEPENDENCIES']
        else:
              [WAITING_DEPENDENCIES 유지]
```

`promoteToPending`은 `WHERE status='WAITING_DEPENDENCIES'` 조건부 UPDATE로 멱등성을 보장한다 — 두 선행 노드가 거의 동시에 완료되어도 후행 노드는 정확히 한 번만 PENDING으로 승격된다.

### 3.6 Cron 스케줄러

`WorkflowScheduler.@Scheduled(fixedDelayString="${workflow.scheduler.interval-ms}")`

```
pollSchedules()
  ├─ JdbcWorkflowScheduleRepository.findDueWithLock(limit=20)
  │     [SELECT * FROM U_WF_SCHEDULE
  │      WHERE next_run_dt <= NOW() AND paused_fl='N'
  │      LIMIT 20 FOR UPDATE SKIP LOCKED]
  │
  └─ for each due schedule → triggerOne(schedule)
        ├─ INSERT U_WF_INSTANCE  (input_data = schedule.inputData)
        ├─ DagInterpreter.startInstance(definitionId, instanceId, inputData)
        ├─ nextRun = WorkflowScheduler.nextFromCron(cronExpr, now)
        │     └─ 잘못된 cron → now + 1h (자동 복구)
        └─ ScheduleRepository.markRun(scheduleId, nextRun, now)
              [UPDATE next_run_dt=?, last_run_dt=?]
```

Misfire 정책은 단순하다 — `next_run_dt`가 과거인 스케줄은 트리거 시점의 `now`를 기준으로 다음 nextRun을 산출하므로, 누락된 과거 트리거를 보충하지 않는다.

### 3.7 DLQ 흐름

```
[활동 실패 → 최대 재시도 초과]
                ▼
WorkflowWorker.moveToDlq()
  ├─ INSERT H_WF_DLQ (status=NEW, error_message, stack_trace, ...)
  └─ DlqNotifier.notify(entry)   [WebhookDlqNotifier가 활성화 시 HTTP POST]

[운영자 액션]
GET  /workflow/dlq              → dlq.mustache (목록)
GET  /workflow/dlq/{id}         → dlq-detail.mustache
POST /workflow/dlq/{id}/requeue
   └─ ActivityRepository.createPending(...)   ← 새 PENDING 생성
   └─ DlqRepository.updateStatus(id, REQUEUED)
POST /workflow/dlq/{id}/discard
   └─ DlqRepository.updateStatus(id, DISCARDED)
```

### 3.8 Spring Batch 어댑터

DAG 노드의 `activityNm = "RUN_BATCH_JOB"`, `inputParams = {"jobName":"...","params":{...}}`

```
RUN_BATCH_JOB 노드 → SpringBatchActivityAdapter.runJob(inputJson)
  ├─ JobRegistry.getJob(jobName)
  ├─ buildJobParameters(input)
  │     [__retry__ 또는 __launch_ts__ 자동 주입 → JobInstance 충돌 회피]
  └─ JobLauncher.run(job, params)
        ├─ COMPLETED → return JSON {jobName, jobExecutionId, status, exitCode}
        └─ FAILED    → throw RuntimeException (워커가 재시도/DLQ 처리)
```

엔진은 `H_WF_ACTIVITY_EXECUTION`에 호출 결과를, Spring Batch는 `BATCH_JOB_EXECUTION`에 step/chunk 상세를 기록한다. `OUTPUT_DATA.jobExecutionId`로 연결.

### 3.9 PluginLoader

```
ApplicationReadyEvent
  └─ PluginLoader.loadPlugins()
        if (engine.plugins.enabled && plugins/ 존재):
          for each plugin*.jar:
            URLClassLoader(jar, parent=app-classloader)
              ├─ 부모 위임 → Spring/엔진 클래스 충돌 회피
              └─ for each class: 발견 시 WorkflowRegistry.registerActivity(...)
                  [동일 이름 충돌 시 WARN 후 스킵]
```

부모 위임 정책은 의도적이다. 자식 ClassLoader는 jar 안의 클래스만 새로 로드하고, 코어/Spring 클래스는 부모(앱)에서 가져온다 — 그렇지 않으면 플러그인이 등록한 빈을 워커가 호출할 때 `LinkageError`가 난다.

## 4. 컨트롤러와 뷰 매핑

| HTTP | 경로 | 컨트롤러 | 템플릿 | 데이터 |
|---|---|---|---|---|
| GET | `/` | `LandingController` | `landing.mustache` | — |
| GET | `/workflow/dashboard` | `WorkflowMonitoringController#dashboard` | `dashboard.mustache` | 인스턴스 목록 (`U_WF_INSTANCE`) |
| GET | `/workflow/instance/{id}` | `WorkflowMonitoringController#timeline` | `timeline.mustache` | 인스턴스 + 액티비티 이력 |
| GET | `/workflow/builder` | `BuilderController` | `builder.mustache` | (Drawflow 클라이언트 전용) |
| GET | `/workflow/schedules` | `ScheduleController#list` | `schedules.mustache` | `U_WF_SCHEDULE` |
| GET | `/workflow/dlq` | `WorkflowMonitoringController#dlqList` | `dlq.mustache` | `H_WF_DLQ` |
| GET | `/workflow/dlq/{id}` | `WorkflowMonitoringController#dlqDetail` | `dlq-detail.mustache` | DLQ 단건 |
| GET | `/workflow/activities` | `ActivityCatalogController#page` | `activities.mustache` | `WorkflowRegistry` 메모리 |

REST 엔드포인트:

| HTTP | 경로 | 응답 |
|---|---|---|
| `POST /api/workflow/definitions` | DAG 등록 | `{definitionId}` |
| `GET /api/workflow/definitions/{id}` | 단건 조회 | DAG JSON |
| `DELETE /api/workflow/definitions/{id}` | 소프트 삭제 | 204 |
| `POST /api/workflow/definitions/{id}/run` | 즉시 실행 | `{instanceId}` |
| `POST /api/workflow/schedules` | 스케줄 등록 | `{scheduleId}` |
| `GET /api/workflow/schedules` | 목록 | `[...]` |
| `POST /api/workflow/schedules/{id}/run-now` | 즉시 트리거 | `{instanceId}` |
| `GET /api/workflow/activities` | 카탈로그 | `[...]` |

## 5. 프로파일과 설정

| 프로파일 | 역할 | 활성화 |
|---|---|---|
| (default) | H2 + 짧은 폴링(1s/30s) | `./gradlew bootRun` |
| `docker` | MariaDB + 컨테이너 네트워크 | `SPRING_PROFILES_ACTIVE=docker` |
| `demo` | 부팅 직후 데모 DAG/스케줄 자동 시드 + 폴링 5s | docker compose가 `docker,demo` 동시 활성 |

핵심 프로퍼티:

| 키 | 기본 | 의미 |
|---|---|---|
| `spring.sql.init.platform` | `h2` (default) / `mariadb` (docker) | `schema-${platform}.sql` 자동 선택 |
| `engine.dialect` | (auto) | `oracle` / `mariadb` / `h2` 명시 override |
| `engine.plugins.enabled` | `false` | `plugins/*.jar` 동적 로딩 |
| `workflow.polling.interval-ms` | 1000 (demo: 5000) | 워커 폴링 간격 |
| `workflow.scheduler.interval-ms` | 30000 (demo: 5000) | 스케줄러 폴링 간격 |

## 6. 의존성 그래프 (모듈 간)

```
e2e-tests          ──▶  HTTP만 호출 (모듈 의존 없음)
                            │
                            ▼
service-app        ──▶  engine-core 의존
                            │
                            ▼
engine-core        ──▶  Spring Boot starter-jdbc / -aop, Jackson, MariaDB driver
                       (테스트만 Testcontainers, H2)
```

`engine-core`는 `service-app`을 모르고, `service-app`은 `e2e-tests`를 모른다. 단방향 의존성을 유지하여 코어를 다른 앱에 재사용할 수 있게 한다.

## 7. 관련 문서

- 명세 (Why): [workflow-engine-spec.md](workflow-engine-spec.md)
- 사용법 (How): [HOWTO.md](HOWTO.md)
- 운영 (Run): [OPERATIONS.md](OPERATIONS.md)
- DB 규칙: [DATABASE_RULE.md](DATABASE_RULE.md)
- 에러 코드: [ERROR_CODES.md](ERROR_CODES.md)
- 플러그인: [PLUGINS.md](PLUGINS.md)
- 빠른 시작: [QUICKSTART.md](QUICKSTART.md)
