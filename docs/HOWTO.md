# How-To Guide

`simple-workflow-engine` 사용자 가이드 — 액티비티 작성부터 DAG 빌드, 스케줄링, Spring Batch 통합까지.

> 환경 띄우기는 [QUICKSTART.md](QUICKSTART.md). 본 문서는 **개발자/운영자가 실제 워크플로우를 만들고 운영하는 방법**.

---

## 1. 액티비티 작성 (Java)

워크플로우의 단위 작업은 **`@Activity` 어노테이션이 붙은 Java 메서드**입니다.

### 1.1. 가장 간단한 액티비티

```java
package com.example.app;

import com.station8.engine.annotation.Activity;
import com.station8.engine.annotation.Workflow;
import org.springframework.stereotype.Component;

@Workflow("OrderFlow")          // 워크플로우 이름 (선택, 클래스 단위 그룹핑)
@Component                       // Spring Bean으로 등록
public class OrderFlow {

    @Activity(value = "VALIDATE_ORDER",   // DAG 노드의 activityNm으로 사용
              retryCount = 3,             // 실패 시 최대 3회 재시도
              backoffSeconds = 5)         // 첫 재시도까지 5초 (이후 5/10/20/40/80...)
    public String validate(String inputJson) {
        // 비즈니스 로직
        if (inputJson == null || inputJson.isBlank()) {
            throw new IllegalArgumentException("input required");
        }
        // 성공 시 String/Map/POJO 반환 — OUTPUT_DATA에 JSON으로 기록됨
        return "{\"status\":\"valid\"}";
    }
}
```

### 1.2. 자동 등록 + 가시화

부팅 시 `WorkflowRegistry`가 `@Activity`를 스캔해 등록 → `/workflow/activities` 페이지에 즉시 노출됩니다. **별도 설정 없음**.

### 1.3. 입력 파라미터 규칙

- 첫 파라미터는 `String inputJson`을 받는 게 권장 (JSON 문자열을 그대로 전달받아 메서드에서 직접 파싱)
- 그 외 타입은 `@Activity` 메서드 호출 시 첫 파라미터에만 inputData를 주입 (POJO 자동 역직렬화는 미구현, 필요 시 메서드 안에서 `JsonUtil.fromJson` 사용)

### 1.4. 재시도 vs 영구 실패

```java
@Activity(value = "CHARGE_PAYMENT", retryCount = 5, backoffSeconds = 10)
public String charge(String inputJson) {
    try {
        return paymentClient.charge(inputJson);
    } catch (TransientException e) {
        // RuntimeException → 엔진이 재시도 (5/10/20/40/80s 백오프)
        throw new RuntimeException("transient", e);
    } catch (FraudException e) {
        // 영구 실패 — 그래도 retryCount 회까지 시도된 후 DLQ로 이행
        throw new RuntimeException("fraud detected, will retry then DLQ", e);
    }
}
```

엔진은 RuntimeException 종류를 구분하지 않습니다. retryCount 횟수만큼 시도 후 `H_WF_DLQ`로 이관됩니다 (Webhook 알림 발송).

---

## 2. DAG 정의하기

DAG = `@Activity` 메서드들을 **노드/엣지 그래프**로 연결한 것.

### 2.1. 비주얼 빌더 (권장)

`/workflow/builder` 접속:

1. 좌측 팔레트에서 활성 카드를 캔버스로 **drag**
2. 노드 클릭 → 우측 패널에서 `inputParams` JSON 편집
3. **"Connect from this node"** → 다음 노드 클릭으로 엣지 연결
4. 상단 "Definition name" 입력 → **"Save"**

저장 시 검증 통과한 정의 ID 반환. 실패 시 하단에 `errorCode`(예: `WF-E305`) + 메시지 표시.

### 2.2. REST API (자동화)

```bash
curl -X POST http://localhost:8080/api/workflow/definitions \
  -H "Content-Type: application/json" \
  -d '{
    "definitionNm": "OrderFlow",
    "description": "주문 처리 파이프라인",
    "nodes": [
      {"nodeId":"v","nodeNm":"Validate","activityNm":"VALIDATE_ORDER","inputParams":"{}","posX":100,"posY":100},
      {"nodeId":"c","nodeNm":"Charge","activityNm":"CHARGE_PAYMENT","inputParams":"{}","posX":300,"posY":100},
      {"nodeId":"s","nodeNm":"Ship","activityNm":"SHIP_ITEM","inputParams":"{}","posX":500,"posY":100}
    ],
    "edges": [
      {"edgeId":"e1","fromNodeId":"v","toNodeId":"c"},
      {"edgeId":"e2","fromNodeId":"c","toNodeId":"s"}
    ]
  }'
# → {"definitionId":"<UUID>"}
```

### 2.3. 분기 / 병렬 / 합류

```
       ┌─ TRANSFORM_A ─┐
START ─┤                ├─ JOIN_RESULT
       └─ TRANSFORM_B ─┘
```

- **fan-out**: 한 노드의 outgoing edges를 여러 개 → 각 후행이 동시에 PENDING
- **fan-in**: 한 노드의 incoming edges를 여러 개 → 모든 선행이 COMPLETED일 때만 PENDING
- 자기 참조(`from == to`), 사이클은 검증에서 거부 (`WF-E305`/`WF-E306`)

### 2.4. 정의 즉시 실행

```bash
curl -X POST http://localhost:8080/api/workflow/definitions/<DEF_ID>/run \
  -H "Content-Type: application/json" \
  -d '{"input":"{\"orderId\":\"42\"}"}'
# → {"instanceId":"<UUID>"}
```

`/workflow/dashboard`에서 인스턴스 진행 가시화.

---

## 3. Cron 스케줄링

### 3.1. 등록

`/workflow/schedules` → "+ New Schedule":
- Definition ID (위에서 만든 UUID)
- Cron (Spring 6필드: `초 분 시 일 월 요일`. 예: `0 */5 * * * *` = 매 5분 0초)
- Input data JSON (선택)

또는 REST:

```bash
curl -X POST http://localhost:8080/api/workflow/schedules \
  -H "Content-Type: application/json" \
  -d '{"definitionId":"<UUID>","cronExpr":"0 0 2 * * *","inputData":null}'
# → {"scheduleId":"<UUID>"}  (매일 02:00에 실행)
```

### 3.2. 운영

| 액션 | UI | REST |
|------|-----|------|
| 즉시 실행 (cron 무관) | "Run now" 버튼 | `POST /api/workflow/schedules/{id}/run-now` |
| 일시중지 | "Pause" | `PUT .../pause` |
| 재개 | "Resume" | `PUT .../resume` |
| cron 수정 | (UI 없음, REST) | `PUT .../{id}` `{"cronExpr": "..."}` |
| 삭제 | "Delete" | `DELETE .../{id}` |

### 3.3. 미스파이어 정책

폴러가 30초마다(``workflow.scheduler.interval-ms``) 만료된 cron을 가져갑니다. 폴링이 늦어 ``NEXT_RUN_DT``가 과거가 되어도 단 1회만 트리거. ``NEXT_RUN_DT``는 ``now`` 기준 ``cron.next(now)``로 갱신되어 누락 1회로 한정.

---

## 4. Spring Batch Job 통합

기존 Spring Batch `Job`을 DAG 노드로 호출.

### 4.1. 준비

1. service-app에 Spring Batch `@Bean Job` 등록 (자동 ``JobRegistry`` 등록):

```java
@Configuration
public class MyBatchConfig {
    @Bean
    public Job myExtractJob(JobRepository jobRepository, Step extractStep) {
        return new JobBuilder("myExtractJob", jobRepository)
                .start(extractStep)
                .build();
    }
}
```

2. DAG 노드의 ``activityNm = "RUN_BATCH_JOB"``, ``inputParams`` 에 다음 JSON:

```json
{"jobName": "myExtractJob", "params": {"fileDate": "2026-05-07"}}
```

### 4.2. 동작

- 우리 엔진의 워커가 노드를 PENDING으로 가져가 `SpringBatchActivityAdapter.runJob` 호출
- `JobLauncher.run(job, parameters)` 실행
- ``COMPLETED`` → ``OUTPUT_DATA``에 ``{jobName, jobExecutionId, status, exitCode}`` 기록
- ``FAILED`` → ``RuntimeException`` 변환 → 우리 엔진의 retry/DLQ 흐름

### 4.3. 멱등성

매 retry마다 ``JobParameters``에 ``__retry__`` 또는 ``__launch_ts__`` 자동 주입. 동일 파라미터 재호출 시 Spring Batch가 거부하던 문제 회피.

### 4.4. 디버깅

| 무엇을 보고 싶은가 | 어디에서 |
|------------------|--------|
| 노드 단위 진행 | ``H_WF_ACTIVITY_EXECUTION`` (우리 엔진) |
| Job/Step/청크 진행 | ``BATCH_JOB_EXECUTION`` (Spring Batch 메타) |
| 둘 연결 | ``OUTPUT_DATA``의 ``jobExecutionId`` |

---

## 5. 모니터링 / 운영

### 5.1. Dashboard
- `/workflow/dashboard` — 인스턴스 목록 + Running/Completed/Failed 통계
- 검색 필터 (Workflow Name / Status / Instance ID)
- "Details" → 인스턴스별 timeline 페이지

### 5.2. Timeline
- `/workflow/instance/{id}` — 액티비티 시간순 스택
- status dot 색상 (success/warning/danger/mute)
- 실패 액티비티는 accent-red 강조 + Error 메시지

### 5.3. Schedules
- `/workflow/schedules` — 등록된 cron 일괄 관리
- Active / Paused 통계

### 5.4. DLQ
- `/workflow/dlq` — 최대 재시도 초과한 액티비티
- `/workflow/dlq/{id}` — 단건 상세 (Error / Stack trace)
- "Requeue" → 새 PENDING으로 등록 (재시도)
- "Discard" → 폐기 (DLQ_STATUS_ST = DISCARDED)

### 5.5. 활성 액티비티 카탈로그
- `/workflow/activities` — 등록된 모든 `@Activity` 카드
- M3 빌더의 좌측 팔레트가 같은 데이터 소비

---

## 6. 흔한 시나리오

### 6.1. 매일 02:00에 데이터 마이그레이션

1. `@Activity` 메서드 작성 (예: `MIGRATION_WRITE`)
2. `/workflow/builder`에서 단일 노드 정의 등록
3. `/workflow/schedules`에서 `0 0 2 * * *` cron 등록

### 6.2. 다단계 ETL (Extract → Transform → Load)

1. 3개 액티비티(`EXTRACT_*`, `TRANSFORM_*`, `LOAD_*`)
2. 빌더에서 3개 노드 + 2개 엣지 연결
3. cron 등록 또는 외부 트리거(`POST /run`)

### 6.3. 분기 (조건부 실행)

조건부 분기는 v1에서 직접 지원하지 않음 (모든 후행이 활성화). 우회:
- 2개 후행 노드 모두 등록 + 각 액티비티 안에서 입력값 검사 후 no-op 처리
- 또는 단일 액티비티 안에서 조건 분기 후 결과를 다음 노드 입력으로 전달

`U_WF_EDGE.CONDITION_EXPR` 컬럼이 보존되어 있어 향후 조건 평가 도입 가능 (이슈 트래커에서 추적).

### 6.4. 에러 알림

`engine.dlq.webhook-url` 설정 시 DLQ 적재마다 webhook 발송:

```properties
engine.dlq.webhook-url=https://hooks.slack.com/services/T00/B00/XXXX
```

---

## 7. 트러블슈팅

| 증상 | 코드 | 액션 |
|------|------|------|
| 정의 등록 실패 — 사이클 | `WF-E305` | 엣지 from/to 그래프 재검토. 자기 참조 제거 |
| 액티비티 미등록 | `WF-E307` | `/workflow/activities` 목록 확인. 클래스에 `@Activity` + Spring `@Component` 있는지 |
| cron 표현식 실패 | `WF-E402` | Spring 6필드(초 분 시 일 월 요일). UI 미리보기로 확인 |
| Batch Job 미등록 | `WF-E603` | `@Bean Job` 등록 + `jobName`이 빈 이름과 일치하는지 |
| DLQ가 안 채워짐 | — | retryCount만큼 시도 후 적재. backoff 누적 시간만큼 대기 (5/10/20/40/80s) |

상세 코드 카탈로그: [docs/ERROR_CODES.md](ERROR_CODES.md)

---

## 8. 설정 레퍼런스

```properties
# 폴러 / 스케줄러
workflow.polling.interval-ms=1000          # PENDING 액티비티 폴링 (기본 1초)
workflow.scheduler.interval-ms=30000       # cron 만료 폴링 (기본 30초)
workflow.scheduler.batch-limit=20          # 폴링 한 번에 가져올 스케줄 수

# DLQ
engine.dlq.webhook-url=                    # 비우면 알림 미발송, 콘솔 로그만

# Spring Batch
spring.batch.job.enabled=false             # 부팅 시 Job 자동 실행 차단 (필수)
spring.batch.jdbc.initialize-schema=embedded  # H2 모드에서 BATCH_* 메타 자동 생성

# 데이터소스 (MariaDB)
spring.datasource.url=jdbc:mariadb://localhost:3306/workflow
spring.datasource.username=wfuser
spring.datasource.password=wfpw
```

## 9. 관련 문서

- [QUICKSTART.md](QUICKSTART.md) — 5분 docker 가이드
- [workflow-engine-spec.md](workflow-engine-spec.md) — 엔진 명세/아키텍처
- [DATABASE_RULE.md](DATABASE_RULE.md) — 명명 규칙
- [ERROR_CODES.md](ERROR_CODES.md) — 에러 코드 카탈로그
