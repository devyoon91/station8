# How-To Guide

`station8` 사용자 가이드 — 액티비티 작성부터 DAG 빌드, 스케줄링, Spring Batch 통합까지.

> 환경 띄우기는 [QUICKSTART.md](QUICKSTART.md). 본 문서는 **개발자/운영자가 실제 라인을 만들고 운영하는 방법**.

---

## 1. 액티비티 작성 (Java)

라인의 단위 작업은 **`@Activity` 어노테이션이 붙은 Java 메서드**입니다.

### 1.1. 가장 간단한 액티비티

```java
package com.example.app;

import com.station8.engine.annotation.Activity;
import com.station8.engine.annotation.LineDefinition;
import org.springframework.stereotype.Component;

@LineDefinition("OrderFlow")          // 라인 이름 (선택, 클래스 단위 그룹핑)
@Component                       // Spring Bean으로 등록
public class OrderFlow {

    @Activity(value = "VALIDATE_ORDER",   // DAG 역의 activityNm으로 사용
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

부팅 시 `LineRegistry`가 `@Activity`를 스캔해 등록 → `/line/activities` 페이지에 즉시 노출됩니다. **별도 설정 없음**.

### 1.3. 입력 파라미터 규칙

지원 파라미터 타입 (선언된 순서대로 바인딩):

- `String inputJson` — 액티비티 입력 페이로드 (첫 번째 등장 시 inputData 주입). POJO 자동 역직렬화는 미구현 → 메서드 안에서 `JsonUtil.fromJson` 사용.
- `@BoundDataSource("role") JdbcTemplate` — **권장** (#113). 라인 정의의 station 바인딩에서 결정된 풀 자동 주입. DS 이름이 코드에 박히지 않아 같은 액티비티를 여러 DS에서 재사용 가능.
- `DataSourceRegistry ds` — 멀티 DS 직접 호출 (#108). 이름이 코드에 박히는 형태라 #113 도입 후엔 권장 안 함 (legacy compatibility).

지원하지 않는 타입을 선언하면 액티비티 호출 시점에 `IllegalStateException`으로 실패함.

#### Station 단위 DataSource 바인딩 (#113)

라인 정의의 각 station에 `datasourceBindings: {role: registry-name}` 매핑을 두고, 액티비티는 `@BoundDataSource("role")`로 받아씀:

```java
@Activity("MIGRATE")
public String migrate(String inputJson,
                      @BoundDataSource("source") JdbcTemplate src,
                      @BoundDataSource("target") JdbcTemplate dst) {
    List<Map<String, Object>> rows = src.queryForList("SELECT * FROM RAW_ORDER WHERE ...");
    for (Map<String, Object> r : rows) {
        dst.update("INSERT INTO MART_ORDER ...", ...);
    }
    return "ok:" + rows.size();
}
```

라인 정의 (REST API 또는 Builder UI):

```json
{
  "definitionNm": "OrderMigration",
  "nodes": [
    {"nodeId": "n-extract-load", "activityNm": "MIGRATE",
     "datasourceBindings": {"source": "ops-oracle", "target": "mart-mariadb"}}
  ]
}
```

Builder UI에서는 station 클릭 시 우측 properties 패널의 **DataSource bindings (JSON)** textarea에서 편집 가능.

> 누락된 role(예: `@BoundDataSource("source")`인데 station에 `source` 키가 없음) 또는 등록되지 않은 DS 이름이면 → `primary` fallback + WARN 로그.

#### 멀티 DataSource 사용 예 (#108)

```java
@Activity("MIGRATE")
public String migrate(String inputJson, DataSourceRegistry ds) {
    JdbcTemplate src = ds.jdbc("source-oracle");   // application.properties의 이름과 일치
    JdbcTemplate dst = ds.jdbc("target-mart");
    List<Map<String, Object>> rows = src.queryForList("SELECT * FROM RAW_ORDER WHERE ...");
    for (Map<String, Object> r : rows) {
        dst.update("INSERT INTO MART_ORDER (...) VALUES (...)", r.get("id"), ...);
    }
    return "ok:" + rows.size();
}
```

`application.properties`에 secondary DS 선언:

```properties
station8.datasources.source-oracle.url=jdbc:oracle:thin:@oracle-prod:1521:ORCL
station8.datasources.source-oracle.username=etl_reader
station8.datasources.source-oracle.password=${DB_SOURCE_ORACLE_PASSWORD}
station8.datasources.source-oracle.driver-class-name=oracle.jdbc.OracleDriver
station8.datasources.source-oracle.dialect=oracle

station8.datasources.target-mart.url=jdbc:mariadb://mart:3306/analytics
station8.datasources.target-mart.username=etl_writer
station8.datasources.target-mart.password=${DB_TARGET_MART_PASSWORD}
```

운영 시 `/admin/datasources`에서 등록 목록 / 풀 상태 확인 + Test connection ping 가능.

#### 어드민 UI에서 동적 등록 (#110)

`application.properties`에 선언하지 않고 운영 중에 즉시 추가하고 싶으면:

1. `/admin/datasources` → **+ New DataSource** 클릭
2. 이름 / JDBC URL / 사용자 / 비밀번호 / (선택) Driver / Dialect / Hikari 옵션(JSON) 입력
3. **Create** — 검증 + 풀 생성 + 즉시 활성화. health check 결과(OK / DOWN)가 상단 배너로 표시
4. 부팅 후에도 `U_LINE_DATASOURCE`에 영속화 → 다음 부팅에 자동 로드

수정/삭제도 같은 페이지에서. 수정 시 connection pool은 즉시 graceful drain 후 새 설정으로 swap (in-flight 트랜잭션 안전 종료 후 옛 풀 close).

| Source | 설명 | UI 권한 |
|--------|------|---------|
| **PRIMARY** | 엔진 상태 DB (Spring autoconfig 또는 `station8.datasources.primary`) | Test connection만 |
| **STATIC** | `application.properties`의 `station8.datasources.<name>.*` | Test connection만 |
| **DYNAMIC** | `U_LINE_DATASOURCE` 테이블 — UI에서 등록 | Test / Edit / Toggle / Delete |

> 이름 충돌 시 정적 win — UI에서 `application.properties`와 같은 이름 등록 시도 시 거부됨.

> **트랜잭션 주의**: 두 DS를 R/W하는 액티비티는 각각 별개 트랜잭션이라 부분 실패 시 데이터 불일치 가능 → 멱등 키 / upsert(MERGE) / DLQ 재처리 패턴으로 운영자가 책임 (XA/JTA 비도입). 자세한 토론은 [#111](https://github.com/devyoon91/station8/issues/111).

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

엔진은 RuntimeException 종류를 구분하지 않습니다. retryCount 횟수만큼 시도 후 `H_LINE_DLQ`로 이관됩니다 (Webhook 알림 발송).

---

## 2. DAG 정의하기

DAG = `@Activity` 메서드들을 **역/엣지 그래프**로 연결한 것.

### 2.1. 비주얼 빌더 (권장)

`/line/builder` 접속:

1. 좌측 팔레트에서 활성 카드를 캔버스로 **drag**
2. 역 클릭 → 우측 패널에서 `inputParams` JSON 편집
3. **"Connect from this node"** → 다음 역 클릭으로 엣지 연결
4. 상단 "Definition name" 입력 → **"Save"**

저장 시 검증 통과한 정의 ID 반환. 실패 시 하단에 `errorCode`(예: `WF-E305`) + 메시지 표시.

**편집 모드 (#99):** 저장된 라인을 다시 빌더에서 수정하려면 `/line/builder?id={definitionId}`로 진입 — 캔버스에 노드/엣지/inputParams/datasourceBindings/posX,posY가 모두 복원된다. 저장 시 같은 ID로 PUT(역/엣지 통째 교체, 같은 버전 유지). `definitionNm`은 read-only — 이름 변경은 새 정의 생성으로(신규 빌더 진입). `Lines` 목록 또는 정의 미리보기 페이지에서 **Edit** 버튼으로 진입 가능. 진행 중 인스턴스가 있으면 후행 단계가 새 정의 기준으로 실행되니 주의 (XA 미도입 — 운영자 책임).

### 2.2. REST API (자동화)

```bash
curl -X POST http://localhost:8080/api/line/definitions \
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

- **fan-out**: 한 역의 outgoing edges를 여러 개 → 각 후행이 동시에 PENDING
- **fan-in**: 한 역의 incoming edges를 여러 개 → 모든 선행이 COMPLETED일 때만 PENDING
- 자기 참조(`from == to`), 사이클은 검증에서 거부 (`WF-E305`/`WF-E306`)

### 2.3.1. 엣지 조건식 (#152)

엣지에 SpEL 조건식을 붙이면 활동 결과 JSON 기준으로 조건부 분기가 가능하다. 빌더에서 엣지 우클릭 → "Add/Edit condition…" 모달에 SpEL 식 입력. 조건 있는 엣지는 노란색 dashed line + 라벨로 시각화.

**표현식 형식 — SpEL** (Spring Expression Language):

| 패턴 | 예시 |
|---|---|
| 불린 비교 | `#result['success'] == true` |
| 숫자 비교 | `#result['count'] > 10` |
| 문자열 동등 | `#result['status'] == 'OK'` |
| 복합 논리 | `#result['status'] == 'OK' and #result['errors'] == 0` |
| 배열 / 컬렉션 | `#result.size() > 0` · `#result[0] == 'first'` |

활동 결과 binding:
- 결과가 JSON object → `Map`으로 파싱, `#result['key']`로 접근
- 결과가 JSON array → `List`로 파싱, `#result[0]`으로 접근
- 결과가 JSON 아닌 raw string → `#result == 'OK'` 같은 직접 비교

**시멘틱**:
- 조건 만족하는 엣지만 활성화 (분기 시 mutually exclusive 조건으로 자연스럽게 분기)
- 활성화된 엣지가 0건이면 인스턴스 `STATUS_ST = FAILED` + `OUTPUT_DATA`에 사유 기록 (`#152`)
- 조건 평가 도중 예외 (잘못된 SpEL / 타입 불일치) → 동일하게 인스턴스 FAILED + 사유 명시

**보안**: `SimpleEvaluationContext.forReadOnlyDataBinding()` 사용 — reflection / 임의 메서드 호출 차단 (`T(java.lang.Runtime).getRuntime().exec(...)` 같은 식은 평가 거부).

**저장 시 검증**: 정의 저장 시 SpEL 컴파일 시도 → 실패하면 `400 Bad Request` + `WF-E309` (`DAG_INVALID_CONDITION`).

REST 직접 호출 예:

```bash
curl -X POST http://localhost:8080/api/line/definitions \
  -H "Content-Type: application/json" \
  -d '{
    "definitionNm": "QualityGateFlow",
    "nodes": [
      {"nodeId":"n-validate","activityNm":"Validate"},
      {"nodeId":"n-process","activityNm":"Process"}
    ],
    "edges": [
      {"edgeId":"e-1","fromNodeId":"n-validate","toNodeId":"n-process",
       "conditionExpr":"#result[\"errors\"] == 0"}
    ]
  }'
```

**비범위 (별도 이슈)**: `#input` / `#runtimeParams` / 다른 활동 결과 참조 등은 추후 확장.

### 2.4. 정의 즉시 실행

```bash
curl -X POST http://localhost:8080/api/line/definitions/<DEF_ID>/run \
  -H "Content-Type: application/json" \
  -d '{"input":"{\"orderId\":\"42\"}"}'
# → {"instanceId":"<UUID>"}
```

`/line/dashboard`에서 인스턴스 진행 가시화.

### 2.5. Run options (인스턴스 단위 옵션) — #134

즉시 실행 시 본문에 `options`를 추가해 인스턴스 단위 실행 동작을 제어할 수 있다.
`options`는 모두 선택 — 미지정 시 기존 동작과 동일(후방 호환).

```bash
curl -X POST http://localhost:8080/api/line/definitions/<DEF_ID>/run \
  -H "Content-Type: application/json" \
  -d '{
    "input": "{\"orderId\":\"42\"}",
    "options": {
      "onFailure": "ABORT",
      "runtimeParams": { "region": "KR", "tier": "premium" },
      "notificationWebhookUrl": "https://hooks.example.com/instance-dlq"
    }
  }'
```

| 옵션 | 의미 | 기본값 |
|---|---|---|
| `onFailure` | 활동 retry 한도 초과 시 인스턴스 처리 정책: `CONTINUE` (기본) — 다른 활동은 계속 진행 / `ABORT` — 인스턴스 즉시 `TERMINATED` (#101 위임) / `PAUSE_ON_FAILURE` (#148) — 인스턴스를 `PAUSED`로 마킹해 운영자 개입 대기 (#139 인프라 활용 — timeline에서 Unpause + 활동 Retry 또는 Terminate 선택) | `CONTINUE` |
| `runtimeParams` | 활동에서 `LineContext.runtimeParams()`로 접근하는 string→string 맵. 정의(`U_LINE_STATION.INPUT_PARAMS`)는 건드리지 않고 이번 실행에만 영향. | `{}` |
| `notificationWebhookUrl` | DLQ 적재 + SLA 위반 시 전역 webhook 대신 이 URL로 알림 발송. | `null` (전역 사용) |
| `slaSeconds` | #138 — 인스턴스 SLA 시간 임계치 override (정의의 default보다 우선). null이면 정의의 `SLA_SECONDS` 사용. | `null` |
| `slaAction` | #138 — `ALERT_ONLY` / `AUTO_TERMINATE`. null이면 정의의 `SLA_ACTION` 사용. | `null` |

저장 위치 — `U_LINE_INSTANCE.RUN_OPTIONS` CLOB(JSON). 기본값(전부 default)인 경우 NULL로 둔다.

UI에서는 정의 미리보기 화면의 "▶ Run now" 모달 → "Advanced options" 확장 영역에서 동일한 항목을 입력할 수 있다.

활동 코드에서 runtime params 접근:

```java
@Activity(name = "ChargeOrder")
public String chargeOrder(String input, LineContext ctx) {
    String region = ctx.runtimeParams().getOrDefault("region", "default");
    // ...
}
```

`LineContext` 파라미터는 다른 지원 타입(String, `DataSourceRegistry`, `@BoundDataSource JdbcTemplate`)과 자유롭게 조합 가능.

### 2.6. SLA — 시간 임계치 + auto-kill / 알림 (#138)

라인 정의(또는 인스턴스)에 **시간 임계치**를 걸면 `SlaPoller`가 분 단위로 RUNNING 인스턴스를 검사해 위반 시 알림 + 옵션에 따라 auto-terminate.

**정의 단위 default** — 빌더의 "Line settings — SLA" 영역 또는 REST:

```bash
curl -X POST http://localhost:8080/api/line/definitions \
  -H "Content-Type: application/json" \
  -d '{
    "definitionNm": "DailyEtl",
    "slaSeconds": 3600,
    "slaAction": "AUTO_TERMINATE",
    "nodes": [...],
    "edges": [...]
  }'
```

**인스턴스 단위 override** — Run modal의 "Advanced options" 또는 RunOptions JSON:

```bash
curl -X POST http://localhost:8080/api/line/definitions/<DEF_ID>/run \
  -H "Content-Type: application/json" \
  -d '{
    "options": {
      "slaSeconds": 60,
      "slaAction": "ALERT_ONLY",
      "notificationWebhookUrl": "https://hooks.example.com/sla"
    }
  }'
```

**우선순위**: 인스턴스 RUN_OPTIONS > 정의 default > 비활성. 둘 다 없으면 SLA poller가 무시.

**액션**:
- `ALERT_ONLY` — webhook 알림만, 인스턴스 그대로 진행
- `AUTO_TERMINATE` — 알림 + 인스턴스를 `TERMINATED`로 마킹, OUTPUT_DATA에 사유 기록

**알림 페이로드** (`engine.sla.webhook-url`):

```json
{
  "type": "SLA_VIOLATION",
  "instanceId": "...",
  "workflowName": "DailyEtl",
  "startedAt": "2026-05-10T02:00:00",
  "elapsedSeconds": 3700,
  "thresholdSeconds": 3600,
  "action": "AUTO_TERMINATE"
}
```

**설정**:

```properties
# SLA webhook URL (비우면 콘솔 WARN 로그만)
engine.sla.webhook-url=https://hooks.example.com/sla

# 폴링 주기 (default 60000ms = 1분)
engine.sla.polling-interval-ms=60000
```

**주의**: SLA는 DLQ와 의미적으로 다름 — DLQ는 "활동 최종 실패", SLA는 "인스턴스 시간 초과". 다른 webhook 채널로 받기 권장.

---

## 3. Cron 스케줄링

### 3.1. 등록

`/line/schedules` → "+ New Schedule":
- Definition ID (위에서 만든 UUID)
- Cron (Spring 6필드: `초 분 시 일 월 요일`. 예: `0 */5 * * * *` = 매 5분 0초)
- Input data JSON (선택)

또는 REST:

```bash
curl -X POST http://localhost:8080/api/line/schedules \
  -H "Content-Type: application/json" \
  -d '{"definitionId":"<UUID>","cronExpr":"0 0 2 * * *","inputData":null}'
# → {"scheduleId":"<UUID>"}  (매일 02:00에 실행)
```

### 3.2. 운영

| 액션 | UI | REST |
|------|-----|------|
| 즉시 실행 (cron 무관) | "Run now" 버튼 | `POST /api/line/schedules/{id}/run-now` |
| 일시중지 | "Pause" | `PUT .../pause` |
| 재개 | "Resume" | `PUT .../resume` |
| cron 수정 | (UI 없음, REST) | `PUT .../{id}` `{"cronExpr": "..."}` |
| 삭제 | "Delete" | `DELETE .../{id}` |

### 3.3. 미스파이어 정책

폴러가 30초마다(``workflow.scheduler.interval-ms``) 만료된 cron을 가져갑니다. 폴링이 늦어 ``NEXT_RUN_DT``가 과거가 되어도 단 1회만 트리거. ``NEXT_RUN_DT``는 ``now`` 기준 ``cron.next(now)``로 갱신되어 누락 1회로 한정.

---

## 4. Spring Batch Job 통합

기존 Spring Batch `Job`을 DAG 역로 호출.

### 4.1. 준비

1. station8-app에 Spring Batch `@Bean Job` 등록 (자동 ``JobRegistry`` 등록):

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

2. DAG 역의 ``activityNm = "RUN_BATCH_JOB"``, ``inputParams`` 에 다음 JSON:

```json
{"jobName": "myExtractJob", "params": {"fileDate": "2026-05-07"}}
```

### 4.2. 동작

- 우리 엔진의 워커가 역을 PENDING으로 가져가 `SpringBatchActivityAdapter.runJob` 호출
- `JobLauncher.run(job, parameters)` 실행
- ``COMPLETED`` → ``OUTPUT_DATA``에 ``{jobName, jobExecutionId, status, exitCode}`` 기록
- ``FAILED`` → ``RuntimeException`` 변환 → 우리 엔진의 retry/DLQ 흐름

### 4.3. 멱등성

매 retry마다 ``JobParameters``에 ``__retry__`` 또는 ``__launch_ts__`` 자동 주입. 동일 파라미터 재호출 시 Spring Batch가 거부하던 문제 회피.

### 4.4. 디버깅

| 무엇을 보고 싶은가 | 어디에서 |
|------------------|--------|
| 역 단위 진행 | ``H_LINE_ACTIVITY_EXECUTION`` (우리 엔진) |
| Job/Step/청크 진행 | ``BATCH_JOB_EXECUTION`` (Spring Batch 메타) |
| 둘 연결 | ``OUTPUT_DATA``의 ``jobExecutionId`` |

---

## 5. 모니터링 / 운영

### 5.0. 로그인 / 사용자 관리 (#121)

Station8은 자체 사용자 계정 + Spring Security 기반 form login을 제공.

**부팅 시 초기 ADMIN 시드:**

세 가지 시드 동작 (멱등):

1. **명시 비밀번호** — 환경변수 또는 properties로 주입. `.env.example`을 복사해 시작:
   ```bash
   cp .env.example .env
   # .env에서 STATION8_INITIAL_ADMIN_PASSWORD 채우기
   set -a; source .env; set +a
   java -jar station8-app/build/libs/station8-app.jar
   ```
   또는 직접:
   ```bash
   STATION8_INITIAL_ADMIN_USERNAME=admin \
   STATION8_INITIAL_ADMIN_PASSWORD='Hello!1234' \
   java -jar station8-app.jar
   ```
   또는 `application.properties`:
   ```properties
   station8.security.initial-admin.username=admin
   station8.security.initial-admin.password=Hello!1234
   ```
   `docker compose`는 `docker/.env` 또는 root `.env`를 자동 로드 (`cp .env.example docker/.env`).

2. **자동 생성** (env 미설정 + DB가 빈 첫 부팅) — 정책 충족 랜덤 16자 비밀번호 생성, 콘솔에 1회 출력:
   ```
   ============================================================
     Auto-generated initial ADMIN account
       username: admin
       password: aB7kQz9xMpRtFn2!
     This password is shown ONCE. Save it now.
     After login, change it via /me/password ...
   ============================================================
   ```
   → chicken-and-egg(env 안 줘서 로그인 불가) 방지. 첫 로그인 후 `/me/password`에서 변경 권장.

3. **Skip** — 같은 username 존재 또는 (env 미설정 + 다른 사용자 이미 존재).

**비밀번호 정책:** 최소 8자 + 숫자 1+ + 특수문자 1+.

**경로 보호:**
- `/admin/**` — ADMIN 역할 필요 (DataSources / Plugins / Users 페이지)
- `/me/**` — 인증 필요 (본인 비밀번호 변경)
- `/line/**`, `/api/**` — 본 이슈 1차에선 permitAll (점진 적용은 후속)
- `/api/**`는 CSRF 면제 — 자동화/REST 클라이언트용

**사용자 관리** (`/admin/users`, ADMIN 전용):
- 신규 사용자 추가 (USER 또는 ADMIN 역할)
- 비밀번호 재설정 (ADMIN-driven)
- 활성/비활성 토글
- 소프트 삭제

**본인 비밀번호 변경**: `/me/password` — 현재 비밀번호 확인 + 새 비밀번호 정책 검증.

### 5.0.1. 라인 정의별 권한 (#140)

전역 ADMIN/USER role(#121) 위에, 라인 정의 단위로 사용자별 권한을 부여할 수 있다 — Azkaban 패턴 답습.

| 권한 | 의미 |
|---|---|
| `READ` | 정의/인스턴스 조회 (1차 비범위 — 모든 인증된 USER 통과) |
| `WRITE` | 정의 수정/삭제 (`PUT/DELETE /api/line/definitions/{id}`) |
| `EXECUTE` | 즉시 실행 + 인스턴스 제어 (run/resume/pause/unpause/terminate/activity retry) |
| `SCHEDULE` | cron 등록/수정/삭제 (`/api/line/schedules/**`) |
| `ADMIN` | 위 전체 + 권한 grant/revoke |

**권한 평가 규칙**:
1. 인증 안 된 요청 → 거부
2. 전역 `ROLE_ADMIN`(#121) → 모든 권한 자동 통과 (ACL 우회)
3. 정의에 ACL entry 0건 → **legacy/open** — 모든 인증된 USER 통과 (후방 호환)
4. ACL entry 있음 (managed) → 명시 grant만 인정, `ADMIN`은 다른 권한 자동 cascade

**자동 grant**: 정의 생성 시 생성자에게 `ADMIN` 자동 부여 → 즉시 managed 상태로 전환.

**UI**: 정의 상세 페이지(`/line/definitions/{id}`) → ADMIN 권한자에게만 "Permissions" 영역 노출. 사용자별 grant/revoke + 마지막 ADMIN 강등 방지.

**REST**:

```bash
# 권한 부여 (ADMIN 권한자만)
curl -X POST http://localhost:8080/line/definitions/<DEF_ID>/acl/grant \
  -d "username=alice" -d "permission=EXECUTE" \
  -H "X-CSRF-TOKEN: ..." -b cookie.txt

# 권한 회수
curl -X POST http://localhost:8080/line/definitions/<DEF_ID>/acl/revoke \
  -d "userId=user-uuid" -d "permission=EXECUTE" -H "X-CSRF-TOKEN: ..." -b cookie.txt
```

**비범위 (별도 follow-up 이슈로)**: READ enforcement (dashboard list 필터링) / 그룹/팀 / 감사 로그 / LDAP 연동.



### 5.1. Dashboard
- `/line/dashboard` — 인스턴스 목록 + Running/Completed/Failed 통계
- 검색 필터 (Line Name / Status / Instance ID)
- "Details" → 인스턴스별 timeline 페이지

### 5.2. Timeline
- `/line/instance/{id}` — 상단에 노선도(Line view, M2) + 하단 액티비티 시간순 스택
- 노선도: 각 역에 실행 상태 색상 (running=점멸, completed=초록 채움, failed=적색 채움, pending=무채색 외곽선)
- status dot 색상 (success/warning/danger/mute)
- 실패 액티비티는 accent-red 강조 + Error 메시지

상태별 액션 버튼:

| 인스턴스 상태 | 노출 버튼 | 동작 |
|---|---|---|
| `RUNNING` | **⏸ Pause** + **Terminate** | Pause: PAUSED로 마킹, 워커 폴링 차단. RUNNING 활동은 자연 완료. Terminate: 강제 종료 (#101) |
| `PAUSED` (#139) | **▶ Resume (unpause)** + **Terminate** | Unpause: RUNNING 복원 + COMPLETED 노드 fan-out 재평가 (Pause 동안 차단됐던 후행 promote) |
| `FAILED` | **Resume from failure** | FAILED 활동들을 PENDING으로 reset + 인스턴스 RUNNING 복원 (#101 기존 동작) |
| `COMPLETED` / `TERMINATED` | (액션 버튼 없음) | — |

활동 단위 액션:

- 활동이 `FAILED`이고 인스턴스가 `RUNNING`일 때 **↻ Retry this activity** 버튼 노출 (#139). 그 활동 1건만 PENDING으로 reset — 다른 FAILED 활동은 영향 X.
- 인스턴스가 `PAUSED`면 retry 버튼 숨김 — 먼저 Unpause 필요.

REST endpoint (자동화):

```
POST /line/instance/{id}/pause
POST /line/instance/{id}/unpause
POST /line/instance/{id}/terminate
POST /line/instance/{id}/resume                    # FAILED 인스턴스 복구
POST /line/instance/{id}/activity/{execId}/retry   # 활동 단건 retry
```

**Pause 시멘틱 주의**:
- 워커 폴링 SQL이 `EXISTS (SELECT 1 FROM U_LINE_INSTANCE WHERE STATUS_ST = 'RUNNING')`로 필터 → PAUSED 인스턴스의 PENDING 활동은 워커가 잡지 않음
- Pause 직전 RUNNING이던 활동은 워커 자연 완료 — 그 활동의 fan-out은 `DagInterpreter`가 인스턴스 RUNNING 검사로 차단
- Unpause 시 모든 COMPLETED 노드에 대해 fan-out 재평가 → Pause 동안 완료됐던 활동의 후행도 정상 promote

### 5.3. Schedules
- `/line/schedules` — 등록된 cron 일괄 관리
- Active / Paused 통계

### 5.4. DLQ
- `/line/dlq` — 최대 재시도 초과한 액티비티
- `/line/dlq/{id}` — 단건 상세 (Error / Stack trace)
- "Requeue" → 새 PENDING으로 등록 (재시도)
- "Discard" → 폐기 (DLQ_STATUS_ST = DISCARDED)

### 5.5. 활성 액티비티 카탈로그
- `/line/activities` — 등록된 모든 `@Activity` 카드
- M3 빌더의 좌측 팔레트가 같은 데이터 소비

### 5.6. Lines (노선도 미리보기)
- `/line/definitions` — 활성 라인 정의 목록
- `/line/definitions/{id}` — 저장된 라인을 서브웨이 맵 스타일 SVG로 정적 미리보기 (M1)
- `/line/instance/{id}` — 같은 렌더러가 인스턴스 진행 위치 오버레이로 동작 (M2)
- 인터랙션(M3): 역 hover → 인접 트랙·이웃 역 강조, 클릭 → 정의 페이지에서 메타데이터 패널, 인스턴스 페이지에서 해당 timeline 카드로 스크롤·일시 강조

---

## 6. 흔한 시나리오

### 6.1. 매일 02:00에 데이터 마이그레이션

1. `@Activity` 메서드 작성 (예: `MIGRATION_WRITE`)
2. `/line/builder`에서 단일 역 정의 등록
3. `/line/schedules`에서 `0 0 2 * * *` cron 등록

### 6.2. 다단계 ETL (Extract → Transform → Load)

1. 3개 액티비티(`EXTRACT_*`, `TRANSFORM_*`, `LOAD_*`)
2. 빌더에서 3개 역 + 2개 엣지 연결
3. cron 등록 또는 외부 트리거(`POST /run`)

### 6.3. 분기 (조건부 실행)

조건부 분기는 v1에서 직접 지원하지 않음 (모든 후행이 활성화). 우회:
- 2개 후행 역 모두 등록 + 각 액티비티 안에서 입력값 검사 후 no-op 처리
- 또는 단일 액티비티 안에서 조건 분기 후 결과를 다음 역 입력으로 전달

`U_LINE_TRACK.CONDITION_EXPR` 컬럼이 보존되어 있어 향후 조건 평가 도입 가능 (이슈 트래커에서 추적).

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
| 액티비티 미등록 | `WF-E307` | `/line/activities` 목록 확인. 클래스에 `@Activity` + Spring `@Component` 있는지 |
| cron 표현식 실패 | `WF-E402` | Spring 6필드(초 분 시 일 월 요일). UI 미리보기로 확인 |
| Batch Job 미등록 | `WF-E603` | `@Bean Job` 등록 + `jobName`이 빈 이름과 일치하는지 |
| DLQ가 안 채워짐 | — | retryCount만큼 시도 후 적재. backoff 누적 시간만큼 대기 (5/10/20/40/80s) |
| 로그인 안 됨 — `.env`에 비번 적었는데 거부 | — | 첫 부팅에서 자동 생성된 admin이 DB 영속됐을 가능성. 아래 "ADMIN 비밀번호 잊음/안 맞음" 참조 |

상세 코드 카탈로그: [docs/ERROR_CODES.md](ERROR_CODES.md)

### `.env` 위치 — 어디 두어야 docker compose가 읽나?

`docker-compose.yml`이 `env_file`로 두 위치 모두 optional 등록:
- root `.env` (저장소 루트)
- `docker/.env` (compose 파일 옆)

둘 중 어디 두어도 OK. 둘 다 있으면 `docker/.env`가 나중에 적용되어 동일 키는 override.

> 셸 export(`export STATION8_INITIAL_ADMIN_PASSWORD=...; docker compose up`)는 \*\*`STATION8_*` 값이 컨테이너에 안 들어감\*\* — environment에서 default 빈 값이 override함. \*\*반드시 `.env` 파일로\*\* 주입할 것.

### ADMIN 비밀번호 잊음/안 맞음 / `.env` 적었는데 매번 초기화됨

`InitialAdminSeeder`는 **같은 username이 DB에 이미 있으면 skip** (멱등 정책) — `.env`에 비밀번호 적어도 기존 admin이 있으면 적용 안 됨. 또 mariadb 데이터는 `mariadb_data` named volume에 영속 — `docker compose down -v`로 volume 삭제해야 admin 새로 시드.

해결 3가지:

1. **콘솔 로그에서 자동 생성된 비밀번호 찾기** (첫 부팅 직후, 로그 스크롤백 살아있을 때):
   ```bash
   docker compose -f docker/docker-compose.yml logs app | grep -A 6 "Auto-generated"
   ```

2. **DB 완전 초기화 + 새 비밀번호로 시드** (★ 권장):
   ```bash
   docker compose -f docker/docker-compose.yml down -v   # -v: mariadb_data volume 삭제
   # docker/.env 또는 .env에서 STATION8_INITIAL_ADMIN_PASSWORD 채우기
   docker compose -f docker/docker-compose.yml up --build -d
   ```

3. **DB 직접 admin만 삭제 후 재시작** (다른 사용자/스케줄 보존):
   ```bash
   docker exec -it swe-mariadb mariadb -uroot -prootpw -e \
     "DELETE FROM workflow.U_LINE_USER_ROLE; DELETE FROM workflow.U_LINE_USER WHERE USERNAME='admin';"
   docker compose -f docker/docker-compose.yml restart app
   ```

> 부팅 시 env에 비밀번호가 명시되어 있는데 admin이 이미 DB에 있으면 콘솔에 WARN 로그가 출력되어 운영자에게 알린다 (#128).

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
- [line-engine-spec.md](line-engine-spec.md) — 엔진 명세/아키텍처
- [DATABASE_RULE.md](DATABASE_RULE.md) — 명명 규칙
- [ERROR_CODES.md](ERROR_CODES.md) — 에러 코드 카탈로그
