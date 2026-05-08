# Error Codes

워크플로우 엔진의 에러 코드 카탈로그 + 사용자 액션 매핑.

코드 체계: `WF-E<영역><번호>`

| 영역 | 범위 | 의미 |
|------|------|------|
| 1xx | WF-E101~199 | JSON / 직렬화 |
| 2xx | WF-E201~299 | Registry / Context / Activity 실행 컨텍스트 |
| 3xx | WF-E301~399 | DAG 정의 / 검증 |
| 4xx | WF-E401~499 | Schedule / Cron |
| 5xx | WF-E501~599 | Definition 라이프사이클 |
| 6xx | WF-E601~699 | Adapter (Spring Batch 등) |
| 9xx | WF-E901~999 | 시스템 / 알림 / 미분류 |

---

## 1xx — JSON / 직렬화

| 코드 | 의미 | 사용자 액션 |
|------|------|-----------|
| **WF-E101** `JSON_SERIALIZATION_ERROR` | 객체 → JSON 직렬화 실패 | 입력 객체에 직렬화 불가능한 필드(예: 순환 참조)가 있는지 점검 |
| **WF-E102** `JSON_DESERIALIZATION_ERROR` | JSON → 객체 역직렬화 실패 | INPUT_DATA의 JSON 형식 점검. 잘못된 escape는 `JdbcTaskExecutor.fail`의 이중 직렬화 결함(#49)에서 자주 발생했으나 fix됨 |

## 2xx — Registry / Context / Activity 실행

| 코드 | 의미 | 사용자 액션 |
|------|------|-----------|
| **WF-E201** `WORKFLOW_NOT_FOUND` | 등록되지 않은 workflowName | `@Workflow` 어노테이션 + Spring 컴포넌트 스캔 확인 |
| **WF-E202** `ACTIVITY_NOT_FOUND` | 등록되지 않은 activityName | `/api/workflow/activities`에서 등록된 액티비티 목록 확인. `@Activity` 어노테이션 누락 가능성 |
| **WF-E203** `INVALID_ARGUMENT` | 메서드 호출 시 파라미터 변환 실패 | INPUT_DATA가 메서드 파라미터 타입과 일치하는지 점검 |
| **WF-E204** `CONTEXT_ATTRIBUTE_MISSING` | WorkflowContext에 필수 attribute 누락 | 워커가 PENDING 처리 시 executionId 주입을 누락한 코드 결함. 운영자 액션 없음, 이슈 보고 |
| **WF-E205** `ACTIVITY_INVOCATION_FAILED` | Reflection 호출에서 예외 | 액티비티 메서드 내부에서 raise된 예외가 cause로 포함됨. 메서드 비즈니스 로직 점검 |
| **WF-E206** `INSTANCE_NOT_FOUND` | 워크플로우 인스턴스 ID 미존재 | URL의 instance ID 오타 확인 |

## 3xx — DAG 정의 / 검증

| 코드 | 의미 | 사용자 액션 |
|------|------|-----------|
| **WF-E301** `DAG_INVALID` | 1건 이상 위반의 컨테이너 | message에 누적된 코드들을 보고 각 코드별 액션 |
| **WF-E302** `DAG_NO_NODES` | 노드 0개 | 정의에 최소 1개 노드 추가 |
| **WF-E303** `DAG_NO_START_NODE` | incoming edge 0개 노드 부재 | 첫 노드는 다른 노드의 후행이 아니어야 함. 사이클일 가능성 |
| **WF-E304** `DAG_NO_TERMINAL_NODE` | outgoing edge 0개 노드 부재 | 끝 노드는 다른 노드의 선행이 아니어야 함. 사이클일 가능성 |
| **WF-E305** `DAG_CYCLE_DETECTED` | 사이클 존재 | 메시지의 노드 ID 리스트에서 순환 경로 제거 |
| **WF-E306** `DAG_SELF_LOOP` | 자기-참조 엣지 | from == to 인 엣지 제거 |
| **WF-E307** `DAG_UNKNOWN_ACTIVITY` | 미등록 액티비티 참조 | `/workflow/activities`에서 등록 목록 확인 후 노드의 activityNm 정정 |
| **WF-E308** `DAG_DANGLING_EDGE` | 엣지가 정의 외부 노드를 참조 | 엣지의 from/to 노드 ID가 동일 정의 안의 노드인지 확인 |

## 4xx — Schedule / Cron

| 코드 | 의미 | 사용자 액션 |
|------|------|-----------|
| **WF-E401** `SCHEDULE_NOT_FOUND` | 스케줄 ID 미존재 | URL/요청의 ID 오타. 또는 이미 삭제됨 |
| **WF-E402** `CRON_INVALID` | Spring CronExpression 파싱 실패 | 6필드 (sec min hour day mon dow) 형식 확인. `0 */5 * * * *` 같은 패턴 |
| **WF-E403** `SCHEDULE_TRIGGER_FAILED` | 폴러가 인스턴스 시작 중 실패 | 로그에서 cause 확인. nextRun이 1분 뒤로 자동 미뤄지므로 자동 복구 |

## 5xx — Definition 라이프사이클

| 코드 | 의미 | 사용자 액션 |
|------|------|-----------|
| **WF-E501** `DEFINITION_NOT_FOUND` | 정의 ID 미존재 | URL/요청의 ID 오타. 또는 소프트 삭제됨 |
| **WF-E502** `DEFINITION_NM_REQUIRED` | definitionNm 누락 | 등록 요청에 정의 이름 필수 |
| **WF-E503** `DEFINITION_NODES_REQUIRED` | nodes 빈 배열 | 최소 1개 노드 등록 |

## 6xx — Adapter (Spring Batch)

| 코드 | 의미 | 사용자 액션 |
|------|------|-----------|
| **WF-E601** `BATCH_INPUT_BLANK` | RUN_BATCH_JOB 입력 빈 값 | DAG 노드의 inputParams에 `{"jobName": "...", "params": {...}}` 입력 |
| **WF-E602** `BATCH_JOB_NAME_MISSING` | inputParams에 jobName 키 누락 | inputParams JSON에 jobName 추가 |
| **WF-E603** `BATCH_JOB_NOT_REGISTERED` | JobRegistry에 없는 jobName | Spring Batch `@Bean Job`이 등록되었는지 확인. ApplicationContext 부팅 로그 점검 |
| **WF-E604** `BATCH_JOB_LAUNCH_FAILED` | JobLauncher.run 자체 실패 | params 형식, JobInstance 충돌 여부 점검. cause 로그 |
| **WF-E605** `BATCH_JOB_FAILED` | Job이 BatchStatus.FAILED로 종료 | OUTPUT_DATA의 jobExecutionId로 BATCH_JOB_EXECUTION 테이블 조회 |

## 9xx — 시스템 / 알림 / 미분류

| 코드 | 의미 | 사용자 액션 |
|------|------|-----------|
| **WF-E901** `DLQ_NOTIFICATION_FAILED` | DLQ 적재 후 webhook 알림 발송 실패 | webhook URL / 네트워크 / 응답 코드 점검. DLQ 적재 자체는 성공 |
| **WF-E999** `UNEXPECTED_ERROR` | 분류 미정 예외 | 로그에서 stack trace 확인. 반복되면 이슈 보고 |

---

## 외부 API 응답 형식

`WorkflowEngineException` → `400 Bad Request` (또는 적절한 status):

```json
{
  "errorCode": "WF-E305",
  "message": "DAG 검증 실패 (1건): WF-E305: 사이클에 포함된 노드(잔여 indegree>0): [c-a, c-b]"
}
```

`IllegalArgumentException` → `400 Bad Request`:

```json
{
  "message": "유효하지 않은 cron 표현식: not a cron"
}
```

## 관련 문서

- 엔진 명세: [docs/workflow-engine-spec.md](workflow-engine-spec.md)
- 운영 가이드: [docs/OPERATIONS.md](OPERATIONS.md)
- 빠른 시작: [docs/QUICKSTART.md](QUICKSTART.md)
