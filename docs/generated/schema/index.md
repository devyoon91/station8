# 테이블 정의서

Station8 엔진의 RDBMS 스키마를 테이블 단위로 정리한다. 기준 스키마는
[`schema-mariadb.sql`](../../../station8-engine/src/main/resources/sql/schema-mariadb.sql)이고,
[`schema-h2.sql`](../../../station8-engine/src/main/resources/sql/schema-h2.sql)·[`schema-oracle.sql`](../../../station8-engine/src/main/resources/sql/schema-oracle.sql)의
dialect 차이는 각 테이블 하단 노트에 적었다. 컬럼 타입은 MariaDB 기준으로 표기하고, H2/Oracle이 다르면 노트에서 대응 타입을 밝힌다.

엔티티 매핑은 [`entity/`](../../../station8-engine/src/main/java/com/station8/engine/entity/) 하위 Java `record`를 가리킨다. `U_LINE_USER` 계열 보안 테이블은 전용 엔티티 record가 없고 Spring Security 레이어에서 직접 다룬다.

## 명명 규칙 요약

전체 규칙은 [DB 명명 규칙](../../DATABASE_RULE.md) 참조. 이 문서를 읽는 데 필요한 최소한만 옮긴다.

- 접두어 **`U_`** = 마스터/구성 테이블, **`H_`** = 이력/실행 테이블. `H_LINE_*`는 `REG_DT`를 폴링 키로도 쓴다 (`ORDER BY REG_DT ASC`).
- 형식은 `<접두어>_<도메인>_<엔티티>`, 단수형, 식별자는 전부 대문자·따옴표 없음 (Oracle/MariaDB/H2 공통 호환).
- 컬럼 접미어: `_ID`(PK/FK), `_NM`(name), `_NO`(number), `_DT`(date/timestamp), `_FL`(플래그 `'Y'/'N'`), `_CNT`(count), `_ORD`(order), `_CD`(enum code), `_ST`(status/state).
- 공통 컬럼: `DEL_FL`(soft-delete, default `'N'`), `REG_DT`/`REG_ID`(등록), `EDIT_DT`/`EDIT_ID`(수정). 아래 표에서는 지면을 아끼려 이 5개 공통 컬럼을 생략하고, 테이블별로 특이사항이 있을 때만 언급한다.
- 인덱스는 `<TABLE>_IDX<NN>`, PK는 `<TABLE>_PK`, FK는 `<TABLE>_FK<NN>`, UNIQUE는 `<TABLE>_U<NN>`.

전 dialect 공통 타입 대응:

| MariaDB | H2 | Oracle |
|---|---|---|
| `VARCHAR(n)` | `VARCHAR(n)` | `VARCHAR2(n)` |
| `INT` / `BIGINT` | `INT` | `NUMBER` |
| `LONGTEXT` | `CLOB` | `CLOB` |
| `DATETIME` | `TIMESTAMP` | `DATE` |
| `DECIMAL(p,s)` | `DECIMAL(p,s)` | `NUMBER(p,s)` |

---

## 라인 정의계 (U_LINE_*)

사용자가 데이터로 정의하는 DAG의 구성 요소다. 프로젝트가 정의를 담고, 정의가 역(STATION)과 궤도(TRACK)로 그래프를 이룬다. #364 이후 STATION/TRACK의 PK는 `(DEFINITION_ID, ID)` composite다 — nodeId/edgeId가 정의 내부 식별자라 정의 간 재사용되기 때문.

### U_LINE_PROJECT

라인 정의의 1차 컨테이너. 모든 정의는 정확히 1개 프로젝트에 소속되고, 시드된 `default` 프로젝트가 미지정 정의의 fallback이다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK (UUID) |
| PROJECT_NM | VARCHAR(100) | N | | 프로젝트명 (UNIQUE) |
| DESCRIPTION | VARCHAR(500) | Y | | 설명 |

- **PK** `U_LINE_PROJECT_PK` (ID)
- **UNIQUE** `U_LINE_PROJECT_U01` (PROJECT_NM)
- **INDEX** IDX01 (DEL_FL)
- 시드 상수: `DEFAULT_PROJECT_ID = 00000000-0000-0000-0000-000000000001`, `DEFAULT_PROJECT_NM = default`.
- 매핑: [`LineProject`](../../../station8-engine/src/main/java/com/station8/engine/entity/LineProject.java)

### U_LINE_DEFINITION

DAG의 본체. 이름+버전으로 유일하며 `ACTIVE_FL`로 활성 버전을 가른다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| DEFINITION_NM | VARCHAR(100) | N | | 정의명 |
| DESCRIPTION | VARCHAR(500) | Y | | 설명 |
| VERSION_NO | INT | N | 1 | 버전 번호 |
| ACTIVE_FL | VARCHAR(1) | N | 'Y' | 활성 버전 여부 |
| SLA_SECONDS | BIGINT | Y | | SLA 시간 임계치(초). NULL=비활성 (#138) |
| SLA_ACTION | VARCHAR(20) | Y | | SLA 위반 시 동작: `ALERT_ONLY` / `AUTO_TERMINATE` (#138) |
| CONCURRENCY_POLICY | VARCHAR(20) | Y | | `CONCURRENT`(기본) / `SKIP_IF_RUNNING` (#141) |
| PROJECT_ID | VARCHAR(50) | Y | | 소속 프로젝트. Seeder가 backfill (#168) |

- **PK** `U_LINE_DEFINITION_PK` (ID)
- **UNIQUE** `U_LINE_DEFINITION_U01` (DEFINITION_NM, VERSION_NO)
- **INDEX** IDX01 (ACTIVE_FL, DEL_FL), IDX02 (PROJECT_ID)
- Dialect 차이: MariaDB/H2 스키마는 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS PROJECT_ID`로 기존 prod DB 호환 처리(#168). Oracle은 `IF NOT EXISTS` 미지원이라 스키마 주석에 수동 `ALTER TABLE U_LINE_DEFINITION ADD PROJECT_ID VARCHAR2(50)`을 안내한다.
- 매핑: [`LineDefinition`](../../../station8-engine/src/main/java/com/station8/engine/entity/LineDefinition.java)

### U_LINE_STATION

정의 내 역(役) = 액티비티 호출 단위. DAG 노드에 해당한다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | 노드 ID. 정의 내부에서만 unique (composite PK 일부) |
| DEFINITION_ID | VARCHAR(50) | N | | 소속 정의 (composite PK 일부) |
| NODE_NM | VARCHAR(100) | Y | | 노드 표시명 |
| ACTIVITY_NM | VARCHAR(100) | N | | 호출할 액티비티명 |
| INPUT_PARAMS | LONGTEXT | Y | | 입력 파라미터 JSON |
| DATASOURCE_BINDINGS | LONGTEXT | Y | | `map<role,name>` JSON. `@BoundDataSource("role")`가 참조. NULL이면 `primary` fallback (#113) |
| STREAM_MODE | VARCHAR(20) | N | 'NONE' | fan-out 모드: `NONE` / `FAN_OUT` / `COLLECT` (M22 #371) |
| POS_X_NO | INT | Y | | 캔버스 X 좌표 |
| POS_Y_NO | INT | Y | | 캔버스 Y 좌표 |

- **PK** `U_LINE_STATION_PK` (DEFINITION_ID, ID) — #364 composite
- **FK** `U_LINE_STATION_FK01` (DEFINITION_ID) → U_LINE_DEFINITION(ID)
- **INDEX** IDX01 (DEFINITION_ID), IDX02 (ACTIVITY_NM)
- `STREAM_MODE` 값: `NONE`(기본, 선행 출력 통째로) / `FAN_OUT`(배열 원소마다 1회, item-scoped) / `COLLECT`(fan-out 레인 출력을 배열로 모아 1회). opt-in — 명시하지 않으면 기존 동작 그대로. 마이그레이션: [`add-stream-mode`](../../../assets/sql/migrations/2026-06-09-add-stream-mode.md).
- #364 composite PK 배경: [`composite-station-pk`](../../../assets/sql/migrations/2026-06-19-composite-station-pk.md).
- 매핑: [`LineStation`](../../../station8-engine/src/main/java/com/station8/engine/entity/LineStation.java)

### U_LINE_TRACK

정의 내 궤도(엣지) = DAG 의존성. `FROM_NODE` 완료 후 `TO_NODE` 활성화.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | 엣지 ID. 정의 내부에서만 unique (composite PK 일부) |
| DEFINITION_ID | VARCHAR(50) | N | | 소속 정의 (composite PK 일부) |
| FROM_NODE_ID | VARCHAR(50) | N | | 시작 노드 |
| TO_NODE_ID | VARCHAR(50) | N | | 도착 노드 |
| CONDITION_EXPR | VARCHAR(500) | Y | | 엣지 조건식 (표현식) |

- **PK** `U_LINE_TRACK_PK` (DEFINITION_ID, ID) — #364 composite
- **FK** FK01 (DEFINITION_ID) → U_LINE_DEFINITION(ID); FK02 (DEFINITION_ID, FROM_NODE_ID) → U_LINE_STATION(DEFINITION_ID, ID); FK03 (DEFINITION_ID, TO_NODE_ID) → U_LINE_STATION(DEFINITION_ID, ID)
- **UNIQUE** `U_LINE_TRACK_U01` (DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID)
- **INDEX** IDX01 (DEFINITION_ID), IDX02 (FROM_NODE_ID), IDX03 (TO_NODE_ID)
- FK02/FK03이 composite로 바뀌면서 같은 정의 내 역만 매칭한다 (#364).
- 매핑: [`LineTrack`](../../../station8-engine/src/main/java/com/station8/engine/entity/LineTrack.java)

---

## 실행계 (U_LINE_INSTANCE, H_LINE_ACTIVITY_EXECUTION)

정의를 실제로 돌린 실행 상태. 인스턴스가 1회 실행을 나타내고, 액티비티 실행 이력이 노드별(그리고 fan-out 레인별) 실행 행을 담는다.

### U_LINE_INSTANCE

라인 정의의 1회 실행. `STATE_DATA`에 LineContext 상태를 JSON으로 보존한다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| WORKFLOW_NAME | VARCHAR(100) | N | | 실행 대상 워크플로명 |
| DEFINITION_ID | VARCHAR(50) | Y | | 소속 라인 정의. 런타임이 이 값으로 정의를 특정. 레거시/선형(`@Activity`)은 NULL (#364) |
| STATUS_ST | VARCHAR(20) | N | | `RUNNING` / `COMPLETED` / `FAILED` / `TERMINATED` |
| INPUT_DATA | LONGTEXT | Y | | 입력 JSON |
| OUTPUT_DATA | LONGTEXT | Y | | 출력 JSON |
| STATE_DATA | LONGTEXT | Y | | LineContext 상태 JSON |
| RUN_OPTIONS | LONGTEXT | Y | | 인스턴스 단위 실행 옵션 JSON (#134) |
| START_DT | DATETIME | Y | | 시작 시각 |
| END_DT | DATETIME | Y | | 종료 시각 |

- **PK** `U_LINE_INSTANCE_PK` (ID)
- `RUN_OPTIONS` 형태: `{"onFailure":"continue|abort", "runtimeParams":{...}, "notificationWebhookUrl":"..."}` (#134).
- `DEFINITION_ID`는 #364에서 추가. 마이그레이션 시점 RUNNING이던 DAG 인스턴스는 NULL이라 fan-out 활성화가 멈출 수 있어, drain 후 적용 또는 best-effort backfill 권장 ([composite-station-pk](../../../assets/sql/migrations/2026-06-19-composite-station-pk.md)).
- 매핑: [`LineInstance`](../../../station8-engine/src/main/java/com/station8/engine/entity/LineInstance.java)

### H_LINE_ACTIVITY_EXECUTION

노드별 액티비티 실행 이력. 워커 폴링의 핫 테이블이다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| INSTANCE_ID | VARCHAR(50) | N | | 소속 인스턴스 (FK) |
| NODE_ID | VARCHAR(50) | Y | | DAG 모드에서 U_LINE_STATION 노드. 레거시(선형)는 NULL |
| ITEM_INDEX | INT | N | 0 | fan-out 레인 인덱스. 같은 (INSTANCE_ID, NODE_ID) 내 원소 구분. 비-fan-out/레거시=0 (M22 #368) |
| ACTIVITY_NAME | VARCHAR(100) | N | | 액티비티명 |
| STATUS_ST | VARCHAR(30) | N | | `WAITING_DEPENDENCIES` / `PENDING` / `RUNNING` / `COMPLETED` / `FAILED` |
| INPUT_DATA | LONGTEXT | Y | | 입력 JSON |
| OUTPUT_DATA | LONGTEXT | Y | | 출력 JSON |
| ERROR_MESSAGE | LONGTEXT | Y | | 에러 메시지 |
| STACK_TRACE | LONGTEXT | Y | | 스택트레이스 |
| RETRY_CNT | INT | Y | 0 | 재시도 횟수 |
| NEXT_RETRY_DT | DATETIME | Y | | 다음 재시도 예정 시각 |
| START_DT | DATETIME | Y | | 시작 시각 |
| END_DT | DATETIME | Y | | 종료 시각 |

- **PK** `H_LINE_ACTIVITY_EXECUTION_PK` (ID)
- **FK** `H_LINE_ACTIVITY_EXECUTION_FK01` (INSTANCE_ID) → U_LINE_INSTANCE(ID)
- **INDEX** IDX01 (STATUS_ST, NEXT_RETRY_DT) — 폴링 핫 패스; IDX02 (INSTANCE_ID); IDX03 (INSTANCE_ID, NODE_ID)
- #364: `NODE_ID → U_LINE_STATION` FK **없음**. STATION PK가 `(DEFINITION_ID, ID)` composite로 바뀌어 단일 NODE_ID로 참조 불가. 실행 행은 `INSTANCE_ID → U_LINE_INSTANCE.DEFINITION_ID`로 정의가 특정되어 정합성은 인스턴스 스코프로 보장.
- H2 노트: 워커 폴링은 `SKIP LOCKED`로 행을 집는데, H2는 이를 완전히 지원하지 않아 테스트/임베디드 위주로만 쓴다. 운영은 MariaDB/Oracle 기준.
- 마이그레이션: [`add-item-index`](../../../assets/sql/migrations/2026-06-09-add-item-index.md). Oracle은 `ADD COLUMN IF NOT EXISTS`가 없어 재실행 전 컬럼 존재 확인 필요.
- 매핑: [`ActivityExecution`](../../../station8-engine/src/main/java/com/station8/engine/entity/ActivityExecution.java)

---

## 실패·재시도 (H_LINE_DLQ)

최대 재시도를 넘긴 실행을 격리하는 Dead Letter Queue.

### H_LINE_DLQ

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| INSTANCE_ID | VARCHAR(50) | N | | 소속 인스턴스 (FK) |
| EXECUTION_ID | VARCHAR(50) | Y | | 원 실행 행 (FK) |
| WORKFLOW_NAME | VARCHAR(100) | N | | 워크플로명 |
| ACTIVITY_NAME | VARCHAR(100) | N | | 실패 액티비티명 |
| DLQ_STATUS_ST | VARCHAR(20) | N | | `NEW` / `REQUEUED` / `DISCARDED` |
| ERROR_MESSAGE | LONGTEXT | Y | | 에러 메시지 |
| STACK_TRACE | LONGTEXT | Y | | 스택트레이스 |
| RETRY_CNT | INT | Y | 0 | 누적 재시도 횟수 |
| MAX_RETRY_CNT | INT | Y | | 최대 재시도 한도 |
| FAILED_AT_DT | DATETIME | Y | | 최종 실패 시각 |

- **PK** `H_LINE_DLQ_PK` (ID)
- **FK** FK01 (INSTANCE_ID) → U_LINE_INSTANCE(ID); FK02 (EXECUTION_ID) → H_LINE_ACTIVITY_EXECUTION(ID)
- **INDEX** IDX01 (DLQ_STATUS_ST, REG_DT) — 상태별 시간순 폴링; IDX02 (INSTANCE_ID)
- 매핑: [`DlqEntry`](../../../station8-engine/src/main/java/com/station8/engine/entity/DlqEntry.java)

---

## 스케줄·트리거 (U_LINE_SCHEDULE, U_LINE_TRIGGER)

정의를 자동 기동하는 두 경로. 정기 cron과 외부 이벤트(webhook 등).

### U_LINE_SCHEDULE

cron 표현식을 정의에 매핑해 정기적으로 인스턴스를 시작한다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| DEFINITION_ID | VARCHAR(50) | N | | 대상 정의 (FK) |
| CRON_EXPR | VARCHAR(100) | N | | Spring CronExpression (5/6 필드) |
| NEXT_RUN_DT | DATETIME | Y | | 다음 실행 예정. 폴러가 이 값의 만료를 검사 |
| LAST_RUN_DT | DATETIME | Y | | 마지막 실행 시각 |
| PAUSED_FL | VARCHAR(1) | N | 'N' | `'Y'`면 폴러가 무시 |
| INPUT_DATA | LONGTEXT | Y | | 정기 실행 시 주입할 입력 JSON |

- **PK** `U_LINE_SCHEDULE_PK` (ID)
- **FK** `U_LINE_SCHEDULE_FK01` (DEFINITION_ID) → U_LINE_DEFINITION(ID)
- **INDEX** IDX01 (PAUSED_FL, NEXT_RUN_DT) — 만료 cron 폴링; IDX02 (DEFINITION_ID)
- 매핑: [`LineSchedule`](../../../station8-engine/src/main/java/com/station8/engine/entity/LineSchedule.java)

### U_LINE_TRIGGER

cron 외 트리거. 현재 `webhook`이 유일한 타입이고, 향후 kafka/mq가 같은 행 형태를 재사용한다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK (UUID) |
| DEFINITION_ID | VARCHAR(50) | N | | 대상 정의 (FK) |
| TRIGGER_TYPE | VARCHAR(32) | N | | `webhook` (현재). 향후 kafka 등 |
| TRIGGER_KEY | VARCHAR(128) | N | | type별 lookup 키. webhook은 URL path (UNIQUE) |
| CONFIG_JSON | TEXT | Y | | type별 config. webhook은 `{hmacSecret, allowedMethods}` |
| ACTIVE_FL | VARCHAR(1) | N | 'Y' | 활성 여부 |

- **PK** `U_LINE_TRIGGER_PK` (ID)
- **FK** `U_LINE_TRIGGER_FK01` (DEFINITION_ID) → U_LINE_DEFINITION(ID)
- **UNIQUE** `U_LINE_TRIGGER_U01` (TRIGGER_KEY)
- **INDEX** IDX01 (TRIGGER_KEY, ACTIVE_FL) — webhook lookup; IDX02 (DEFINITION_ID)
- Dialect 차이: `REG_DT`/`EDIT_DT`가 다른 테이블과 달리 세 dialect 모두 `TIMESTAMP` 타입이다 (MariaDB에서 `EDIT_DT TIMESTAMP NULL`). `CONFIG_JSON`은 MariaDB `TEXT` / H2·Oracle `CLOB`.
- 매핑: [`LineTrigger`](../../../station8-engine/src/main/java/com/station8/engine/entity/LineTrigger.java)

---

## 보안 (사용자·역할·ACL·크리덴셜·DataSource)

Spring Security 사용자/역할, 정의별 ACL, 암호화 크리덴셜, 동적 DataSource 레지스트리. 사용자·역할·ACL·태그 테이블은 전용 엔티티 record 없이 보안 레이어에서 직접 매핑한다.

### U_LINE_USER

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| USERNAME | VARCHAR(64) | N | | 로그인 ID (UNIQUE) |
| PASSWORD_HASH | VARCHAR(80) | N | | BCrypt 해시(60) + 여유 |
| DISPLAY_NM | VARCHAR(100) | Y | | 표시명 |
| ENABLED_FL | VARCHAR(1) | N | 'Y' | 비활성화 시 로그인 차단 |

- **PK** `U_LINE_USER_PK` (ID) · **UNIQUE** `U_LINE_USER_U01` (USERNAME) · **INDEX** IDX01 (DEL_FL, ENABLED_FL)

### U_LINE_USER_ROLE

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| USER_ID | VARCHAR(50) | N | | 사용자 (FK) |
| ROLE | VARCHAR(50) | N | | `ADMIN` / `USER` |

- **PK** `U_LINE_USER_ROLE_PK` (ID) · **FK** FK01 (USER_ID) → U_LINE_USER(ID) · **UNIQUE** U01 (USER_ID, ROLE) · **INDEX** IDX01 (USER_ID)

### U_LINE_DEFINITION_ACL

정의별 권한. entry 0개면 legacy/open(모든 USER 통과), 1개라도 있으면 managed → 명시적 grant 필요. 정의 생성 시 생성자에게 ADMIN 자동 부여 (#140).

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| DEFINITION_ID | VARCHAR(50) | N | | 대상 정의 (FK, ON DELETE CASCADE) |
| USER_ID | VARCHAR(50) | N | | 대상 사용자 (FK, ON DELETE CASCADE) |
| PERMISSION | VARCHAR(20) | N | | `READ` / `WRITE` / `EXECUTE` / `SCHEDULE` / `ADMIN` |

- **PK** `U_LINE_DEFINITION_ACL_PK` (ID) · **FK** FK01 (DEFINITION_ID) → U_LINE_DEFINITION(ID) CASCADE; FK02 (USER_ID) → U_LINE_USER(ID) CASCADE · **UNIQUE** U01 (DEFINITION_ID, USER_ID, PERMISSION) · **INDEX** IDX01 (DEFINITION_ID), IDX02 (USER_ID)

### U_LINE_DEFINITION_TAG

정의 태그 (free-form, many-to-many). 공통 컬럼 중 `REG_DT`/`REG_ID`만 두고 나머지는 없다 (#142).

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| DEFINITION_ID | VARCHAR(50) | N | | 대상 정의 (composite PK 일부, FK CASCADE) |
| TAG | VARCHAR(50) | N | | 태그 문자열 (composite PK 일부) |

- **PK** `U_LINE_DEFINITION_TAG_PK` (DEFINITION_ID, TAG) · **FK** FK01 (DEFINITION_ID) → U_LINE_DEFINITION(ID) CASCADE · **INDEX** IDX01 (TAG)

### U_LINE_DATASOURCE

application.properties 정적 선언과 별개로 운영자가 어드민 UI에서 동적으로 관리하는 DataSource. 이름이 정적 선언과 충돌하면 정적이 win, DB 행은 비활성화되어 무시된다. 비밀번호는 plain text 저장 (#110, 시크릿 통합은 후속).

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| NAME | VARCHAR(100) | N | | DataSource 이름 (UNIQUE) |
| JDBC_URL | VARCHAR(1000) | N | | JDBC URL |
| USERNAME | VARCHAR(255) | Y | | 접속 계정 |
| PASSWORD | VARCHAR(2000) | Y | | 접속 비밀번호 (plain text) |
| DRIVER_CLASS | VARCHAR(255) | Y | | 드라이버 클래스 |
| DIALECT | VARCHAR(50) | Y | | `mariadb` / `oracle` (URL 추론 fallback) |
| HIKARI_OPTIONS | LONGTEXT | Y | | Hikari 옵션 JSON map. null/blank=기본값 |
| ENABLED_FL | VARCHAR(1) | N | 'Y' | `'N'`이면 풀 build 안 함 |

- **PK** `U_LINE_DATASOURCE_PK` (ID) · **UNIQUE** `U_LINE_DATASOURCE_U01` (NAME) · **INDEX** IDX01 (DEL_FL, ENABLED_FL)
- 매핑: [`DataSourceDefinition`](../../../station8-engine/src/main/java/com/station8/engine/entity/DataSourceDefinition.java)

### U_LINE_CREDENTIAL

AES-GCM 암호화 secret 저장소 (M17 #270). 평문은 응답·로그에 노출되지 않고, 표현식 평가 시에만 복호화한다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| NAME | VARCHAR(100) | N | | `{{ $credentials.<NAME> }}` 참조 키 (UNIQUE) |
| TYPE | VARCHAR(50) | N | | `http_basic` / `http_bearer` / `api_key` / `generic` |
| VALUE_ENC | LONGTEXT | N | | Base64( IV(12B) ‖ ciphertext ‖ authTag(16B) ) |
| SCHEMA_JSON | LONGTEXT | Y | | 타입별 메타 (예: http_basic의 username field) |

- **PK** `U_LINE_CREDENTIAL_PK` (ID) · **UNIQUE** `U_LINE_CREDENTIAL_U01` (NAME) · **INDEX** IDX01 (DEL_FL)
- 매핑: [`Credential`](../../../station8-engine/src/main/java/com/station8/engine/entity/Credential.java)

---

## 관측 (H_LINE_LLM_USAGE)

LLM 호출의 토큰·비용을 남기는 append-only 이력 (M23 #339).

### H_LINE_LLM_USAGE

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|---|---|---|---|---|
| ID | VARCHAR(50) | N | | PK |
| INSTANCE_ID | VARCHAR(50) | Y | | 소속 인스턴스 |
| NODE_ID | VARCHAR(50) | Y | | 노드. 레거시/선형은 NULL |
| ACTIVITY_NAME | VARCHAR(100) | Y | | 액티비티명 |
| PROVIDER | VARCHAR(50) | N | | LLM 제공자 |
| MODEL | VARCHAR(100) | N | | 모델명 |
| INPUT_TOKENS | INT | Y | 0 | 입력 토큰 수 |
| OUTPUT_TOKENS | INT | Y | 0 | 출력 토큰 수 |
| ESTIMATED_COST_USD | DECIMAL(12,6) | Y | | 추정 비용(USD). 가격 미상 모델은 NULL |
| PROMPT_HASH | VARCHAR(64) | Y | | 프롬프트 해시 |

- **PK** `H_LINE_LLM_USAGE_PK` (ID)
- **INDEX** IDX01 (INSTANCE_ID), IDX02 (MODEL)
- FK 없음 (append-only). 활동 연결은 INSTANCE_ID + NODE_ID + ACTIVITY_NAME 조합 (0.1.0 LineContext가 execution id를 미노출).
- Dialect 차이: 비용 컬럼이 MariaDB/H2 `DECIMAL(12,6)` / Oracle `NUMBER(12,6)`, 토큰 컬럼이 Oracle에서 `NUMBER(10)`.
- 매핑: [`LlmUsageEntry`](../../../station8-engine/src/main/java/com/station8/engine/entity/LlmUsageEntry.java)
