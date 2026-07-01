# 운영 메뉴얼

Station8을 배포·설정·튜닝하고 장애에 대응하는 운영자용 문서. 처음 띄우는 절차는 [Quickstart](../../QUICKSTART.md)에 있고, 여기서는 운영 관점(프로파일 선택, 튜닝, 시크릿, 백업, 런북)만 다룬다.

확인된 키·경로는 코드/프로퍼티에서 검증했다. 아직 코드에 없는 기능(무중단 키 로테이션, Actuator 관측성 등)은 "미구현"이라고 솔직히 적었다.

## 배포

### 프로파일

Spring 프로파일 3종이 있다. `SPRING_PROFILES_ACTIVE` 환경변수로 선택한다.

| 프로파일 | 프로퍼티 파일 | DataSource | 용도 |
|---|---|---|---|
| default (미지정) | `application.properties` | H2 임베디드 (MariaDB 모드, in-memory) | 로컬 개발·테스트. 재기동 시 데이터 소멸 |
| `docker` | `application-docker.properties` | MariaDB (compose 네트워크의 `mariadb:3306`) | 컨테이너 운영 |
| `demo` | `application-demo.properties` | 위에 override | 부팅 직후 샘플 DAG·스케줄 자동 시드 |

compose 기본값은 `SPRING_PROFILES_ACTIVE=docker,demo`다 (`docker/docker-compose.yml`). **운영 배포에서는 `demo`를 빼고 `docker`만** 쓴다 — demo는 localhost를 HTTP allowlist에 넣고 데모 전용 고정 크리덴셜 키를 시드하므로 운영에 부적합하다.

```bash
# 운영: demo 시드 없이 docker 프로파일만
SPRING_PROFILES_ACTIVE=docker docker compose -f docker/docker-compose.yml up -d
```

### docker compose 기동

```bash
# 앱 빌드 + MariaDB 기동 (백그라운드)
docker compose -f docker/docker-compose.yml up --build -d

# 로그 확인
docker compose -f docker/docker-compose.yml logs -f app
```

compose 라이프사이클(정리/캐시 무효화 매트릭스)의 자세한 표는 [Quickstart §8](../../QUICKSTART.md)에 있다. 운영에서 기억할 한 가지: **`down -v`는 이미지를 안 지운다.** 코드 변경 반영은 항상 `up --build`가 필요하다.

DB 데이터는 `mariadb_data` named volume에 영속화된다. `docker compose down`은 볼륨을 유지하고, `down -v`가 볼륨(사용자·스케줄·인스턴스 전부)을 삭제한다.

### 이미지 빌드

`docker/Dockerfile`이 멀티스테이지로 gradle 빌드 후 앱 jar를 담는다. compose의 `app` 서비스가 `context: ..`로 저장소 루트를 빌드 컨텍스트로 잡는다. 별도 레지스트리 없이 로컬 빌드가 기본이다.

## 설정

### DataSource (주 DB)

primary DataSource는 `spring.datasource.*`로 설정한다. docker 프로파일은 compose 환경변수로 override 가능하다.

| 키 | 기본값 (docker) | 비고 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mariadb://mariadb:3306/workflow?...&serverTimezone=Asia/Seoul` | compose가 override |
| `SPRING_DATASOURCE_USERNAME` | `wfuser` | |
| `SPRING_DATASOURCE_PASSWORD` | `wfpw` | **운영은 반드시 교체** |

> DataSource 비밀번호는 크리덴셜 vault와 다른 layer다. vault(`STATION8_CREDENTIAL_KEY`)는 라인 활동이 실행 중 쓰는 secret용이고, DataSource 비번은 부팅 시 커넥션 풀을 만드는 별도 secret이다. `.env`/`docker/.env`로 주입하고 이미지·git에 박지 않는다.

### 멀티 DataSource

액티비티가 외부 DB에 이름으로 R/W하려면 `station8.datasources.<이름>.*`를 선언한다 (`application.properties` 주석 참조).

```properties
station8.datasources.source-oracle.url=jdbc:oracle:thin:@oracle-prod:1521:ORCL
station8.datasources.source-oracle.username=etl_reader
station8.datasources.source-oracle.password=${DB_SOURCE_ORACLE_PASSWORD}
station8.datasources.source-oracle.driver-class-name=oracle.jdbc.OracleDriver
station8.datasources.source-oracle.dialect=oracle
station8.datasources.source-oracle.hikari.maximum-pool-size=5
```

액티비티에서 `DataSourceRegistry.jdbc("source-oracle")`로 꺼내 쓴다. `spring.datasource.*`가 있으면 자동으로 primary로 등록되고, `station8.datasources.primary.*`를 명시하면 그쪽이 primary가 된다.

### 커넥션 풀

Spring Boot 기본 HikariCP를 쓴다. 멀티 DataSource는 위처럼 `.hikari.maximum-pool-size` 등으로 개별 조정한다. primary 풀은 Spring 표준 키(`spring.datasource.hikari.*`)로 조정한다 — 저장소 프로퍼티에 명시 튜닝값은 없어 Boot 기본을 따른다.

### 스키마 초기화

`spring.sql.init`이 부팅 시 스키마를 적용한다. DataSource bean 직후·`@Scheduled` 시작 전에 실행되어 "Table doesn't exist" 레이스를 피한다.

| 키 | 값 | 의미 |
|---|---|---|
| `spring.sql.init.mode` | `always` | 매 부팅 스키마 적용 시도 |
| `spring.sql.init.platform` | `h2` (docker는 `mariadb`) | `schema-${platform}.sql` 선택 |
| `spring.sql.init.schema-locations` | `classpath:sql/schema-${...}.sql` | 스키마 위치 |
| `spring.sql.init.continue-on-error` | `true` | 이미 존재하는 객체 에러 무시 (재부팅 안전) |

스키마 원천은 `station8-engine/src/main/resources/sql/schema-{h2,mariadb,oracle}.sql`이다 (single source of truth). docker compose는 MariaDB init 스크립트를 쓰지 않고 앱이 부팅 시 스키마를 적용한다(drift 방지).

### Dialect

DB별 `LIMIT`/`SKIP LOCKED`/timestamp 문법 차이는 `DbDialect` 구현체가 흡수한다 (`MariaDbDialect`, `OracleDialect`, H2는 MariaDB 모드). 멀티 DataSource는 위 `.dialect` 키로 지정한다. 명명 규칙은 [DB 명명 규칙](../../DATABASE_RULE.md) 참조.

## 튜닝

폴링·스케줄러 간격은 프로퍼티로 조정 가능하다. 배치 상한은 코드 상수라 프로퍼티로 못 바꾼다.

| 파라미터 | 기본값 | 조정 | 설명 |
|---|---|---|---|
| `workflow.polling.interval-ms` | 1000 | 프로퍼티 | 워커(`LineWorker`)의 PENDING 활동 폴링 간격 |
| `workflow.scheduler.interval-ms` | 30000 | 프로퍼티 | 스케줄러(`LineScheduler`)의 cron 만료 폴링 간격 |
| `engine.sla.polling-interval-ms` | 60000 | 프로퍼티 | SLA 폴러(`SlaPoller`)의 RUNNING 인스턴스 위반 검사 간격 |
| POLL_BATCH_LIMIT | 10 | **코드 상수** | 워커가 한 사이클에 가져올 활동 수 (`LineWorker.POLL_BATCH_LIMIT`) |
| MAX_BATCH_SIZE | 20 | **코드 상수** | SLA 폴러가 한 사이클에 검사할 인스턴스 수 (`SlaPoller.MAX_BATCH_SIZE`) |

demo 프로파일은 폴링·스케줄러를 5000ms로 낮춰 반응을 빠르게 보여준다. 운영에서 폴링을 너무 짧게 하면 DB 부하가 오르니 트레이드오프를 본다.

### SKIP LOCKED 수평 확장

워커는 `SELECT ... FOR UPDATE SKIP LOCKED`로 PENDING 활동을 잠금·클레임한다 (`JdbcActivityRepository.findPendingActivitiesWithLock`). 두 워커가 같은 행을 잡지 않으므로 **앱 인스턴스를 여러 개 띄우면 그대로 수평 확장**된다 — 분산 락 서버가 따로 필요 없다. 스케줄러(`JdbcLineScheduleRepository.findDueWithLock`)도 같은 방식이라 cron 중복 트리거가 없다.

Oracle 12c+ / MariaDB 10.6+ / H2(MySQL 모드)에서 동작한다. H2는 SKIP LOCKED를 부분 지원하므로 진짜 동시성은 MariaDB/Oracle에서만 보증된다.

## 시크릿 관리

### 크리덴셜 vault 키 (STATION8_CREDENTIAL_KEY)

라인 활동이 쓰는 secret은 `U_LINE_CREDENTIAL.VALUE_ENC`에 AES-GCM 256비트로 암호화 저장된다. 마스터 키는 앱 안에 없고 `STATION8_CREDENTIAL_KEY` 환경변수(프로퍼티 `station8.credential.key`)로 주입한다.

**생성:**

```bash
openssl rand -base64 32
```

Base64로 인코딩된 정확히 32바이트여야 한다. 길이가 틀리면 부팅 시 WARN 로그가 찍히고, vault API 호출 시 `IllegalStateException`으로 실패한다(앱 자체는 뜬다 — vault 미사용 환경 부팅을 막지 않으려는 의도).

**주입:**

- 로컬 jar: `STATION8_CREDENTIAL_KEY=... java -jar station8-app.jar`
- docker compose: `.env` 또는 `docker/.env`에 `STATION8_CREDENTIAL_KEY=...` 한 줄 (compose `env_file`이 주입)
- Kubernetes: `Secret` + `envFrom`/`secretKeyRef`

**부팅 로그로 확인:** 정상이면 INFO `CredentialCrypto initialized — AES-GCM 256-bit key loaded`. 미설정이면 WARN `STATION8_CREDENTIAL_KEY 미설정 — ...`.

폐쇄망 운반·분실 대비 등 자세한 절차는 [Credential Vault 가이드](../../SECRETS.md).

> demo 프로파일은 데모 전용 고정 키(32-byte all-zero)를 시드한다. **이 키를 운영에 쓰지 말 것.**

### 키 로테이션

현재 코드는 키 하나만 본다. **무중단 로테이션(dual-key)은 미구현**이다. 지금 가능한 안전한 방법은 cold swap(앱 정지 → 키 교체 → 재기동)뿐이고, 실행 중 인스턴스가 없을 때 해야 한다. 절차와 향후 무중단 계획은 [SECRETS.md §키 rotation](../../SECRETS.md) 참조. 로테이션 audit 로그 테이블은 아직 없어 운영팀 외부 도구로 기록한다.

### 초기 ADMIN 계정

`InitialAdminSeeder`가 첫 부팅(DB에 사용자 0명)에 ADMIN을 시드한다.

| 항목 | 프로퍼티 | 환경변수 | 기본 |
|---|---|---|---|
| username | `station8.initial-admin.username` | `STATION8_INITIAL_ADMIN_USERNAME` | `admin` |
| password | `station8.initial-admin.password` | `STATION8_INITIAL_ADMIN_PASSWORD` | 비어있음 |

비밀번호를 비워두면 첫 부팅 시 정책(16자, 숫자+특수 포함)을 만족하는 **랜덤 비밀번호를 자동 생성해 콘솔에 1회 출력**한다. 이 로그를 놓치면 다시 볼 수 없으니 부팅 로그를 캡처한다. 운영에서는 `STATION8_INITIAL_ADMIN_PASSWORD`로 명시 주입을 권장하고 커밋하지 않는다. 이미 사용자가 있으면 시드를 건너뛴다(WARN 로그).

## 백업

상태 원천 테이블 3개가 라인 실행 상태를 보관한다. 이것만 백업하면 인스턴스 진행 상황과 실패 이력이 복원된다.

| 테이블 | 내용 |
|---|---|
| `U_LINE_INSTANCE` | 라인 인스턴스 (상태·소속 정의·입력) |
| `H_LINE_ACTIVITY_EXECUTION` | 활동 실행 이력 (폴링 키 `REG_DT` 포함, 상태·재시도·입출력) |
| `H_LINE_DLQ` | Dead Letter Queue (재시도 초과 항목) |

정의·스케줄·사용자·크리덴셜(`U_LINE_DEFINITION`, `U_LINE_SCHEDULE`, `U_LINE_USER`, `U_LINE_CREDENTIAL` 등)도 함께 dump하는 것이 안전하다. mariadb 볼륨 전체를 dump하는 예:

```bash
# 논리 백업 (전체 DB)
docker exec swe-mariadb mariadb-dump -uwfuser -pwfpw workflow > backup.sql
```

> **크리덴셜 vault 주의:** `U_LINE_CREDENTIAL`은 암호화된 값만 담는다. DB 백업과 `STATION8_CREDENTIAL_KEY`를 **다른 위치에 분리 보관**해야 한다. 같은 매체에 두면 유출·분실이 함께 일어난다.

## 관측성

**Micrometer / Actuator는 아직 연동되어 있지 않다.** `/actuator/*` 메트릭 엔드포인트나 Prometheus 익스포터가 없다. 현재는 아래로 대체한다.

- **로그** — `logging.level.com.station8` 조정(demo는 DEBUG). 폴링·게이트·DLQ 발생이 로그로 남는다.
- **DLQ webhook 알림** — `engine.dlq.webhook-url`(환경변수 `ENGINE_DLQ_WEBHOOK_URL`)에 Slack incoming webhook 등을 넣으면 DLQ 적재 시 알림이 간다. 비어있으면 콘솔 로그로 대체.
- **UI 대시보드** — `/line/dashboard`(인스턴스·통계), `/line/dlq`(DLQ 목록)로 상태를 눈으로 확인.

메트릭·트레이싱을 붙이려면 별도 작업이 필요하다. 지금은 로그 수집(파일/컨테이너 stdout)과 DLQ 알림을 관측성 축으로 운영한다.

## 장애 대응 런북

각 항목은 증상 → 확인 → 조치 순서다.

### DLQ 항목 재처리

**증상**: 활동이 재시도를 초과해 `H_LINE_DLQ`에 적재됨. DLQ webhook 알림이 오거나 `/line/dlq`에 항목이 쌓인다.

**확인**: `/line/dlq`에서 항목 상세(`/line/dlq/{id}`)를 열어 `errorMessage`·`stackTrace`로 근본 원인을 본다. 원인이 일시적(네트워크·외부 시스템 다운)인지, 코드/입력 문제인지 구분한다.

**조치**:
- 일시적 원인이고 해소됨 → **Requeue** (`POST /line/dlq/{id}/requeue`). 동일 액티비티를 새 PENDING으로 만들고 DLQ 상태를 `REQUEUED`로 바꾼다. 워커가 다음 폴링에서 다시 집는다.
- 재처리 무의미(잘못된 입력 등) → **Discard** (`POST /line/dlq/{id}/discard`). DLQ 상태만 `DISCARDED`로.
- 반복 적재되면 액티비티 로직/입력을 고친 뒤 requeue.

### stuck RUNNING 인스턴스 (이슈 #381)

**증상**: DAG 인스턴스의 모든 노드가 `COMPLETED`인데 `U_LINE_INSTANCE.STATUS_ST`가 계속 `RUNNING`으로 남는다. 대시보드에서 완료된 인스턴스가 영구히 RUNNING으로 표시된다.

**원인 (확인됨, [#381](https://github.com/devyoon91/station8/issues/381) OPEN)**: `DagInterpreter.onNodeCompleted()`가 terminal 노드 완료 시 인스턴스를 `COMPLETED`로 마킹하지 않는다. 코드베이스에 `U_LINE_INSTANCE`를 성공 종결로 `UPDATE`하는 경로 자체가 없다(실패는 `FAILED`로 전이되지만 성공 종결 경로 부재). `DagInterpreter`로 구동되는 DAG 인스턴스에 한한 문제이며 레거시 `@Activity` 라인(`LineAspect`)은 정상 종결된다.

**확인**: 해당 인스턴스의 활동이 전부 종결 상태인지 본다.

```sql
SELECT STATUS_ST, COUNT(*) FROM H_LINE_ACTIVITY_EXECUTION
WHERE INSTANCE_ID = '<id>' GROUP BY STATUS_ST;
```

`PENDING`/`RUNNING`/`WAITING_DEPENDENCIES`가 0건이고 `COMPLETED`만 있으면 이 버그에 해당한다.

**조치**: 근본 수정은 #381에서 진행 중(terminal 노드 완료 시 미종결 활동 0건이면 인스턴스를 COMPLETED로 마킹). 그 전까지 운영 우회는 수동 상태 정정이다.

```sql
-- 모든 활동이 COMPLETED임을 위 쿼리로 확인한 인스턴스만 정정
UPDATE U_LINE_INSTANCE SET STATUS_ST = 'COMPLETED', EDIT_DT = CURRENT_TIMESTAMP
WHERE ID = '<id>' AND STATUS_ST = 'RUNNING';
```

> `SKIP_IF_RUNNING` 동시성 정책이 이 인스턴스를 "활성"으로 오판해 이후 실행을 영구 skip할 수 있다(#381에서 요확인 항목). 정의가 재실행 안 되면 이 버그를 의심한다.

### 크리덴셜 키 분실

**증상**: `STATION8_CREDENTIAL_KEY`를 잃어버려 기존 `U_LINE_CREDENTIAL`을 복호화할 수 없다.

**확인**: vault 호출 시 `CredentialCryptoException`. 부팅 로그의 CredentialCrypto INFO/WARN으로 현재 키 로드 상태를 본다.

**조치**: **복구 불가능하다** — AES-GCM은 키 없이 평문을 얻을 수 없다(알고리즘 설계). 우회로가 아니라 재발급이 정답이다:
1. 외부 서비스(Slack/AWS 등)에서 secret을 **재발급**한다(옛 secret 폐기 효과).
2. vault에 **같은 이름**으로 다시 등록한다. credential은 이름으로 참조되니 라인 정의는 손대지 않는다.
3. 옛 행은 soft delete.

예방은 키를 한 군데만 두지 않는 것이다. 키/DB 백업 분리, Shamir 분할, 봉인 봉투 등 — [SECRETS.md](../../SECRETS.md) 참조.

### 폴링 정지 진단

**증상**: PENDING 활동이 처리되지 않고 쌓인다. 인스턴스가 진행하지 않는다.

**확인**:
1. 앱이 살아있는지 — `docker compose ... logs -f app`. `@Scheduled` 폴링은 `log.trace`라 기본 로그엔 안 보이니 `logging.level.com.station8=DEBUG`로 올려 게이트 차단·클레임을 확인한다.
2. DB 연결 — 커넥션 풀 고갈/DB 다운 시 폴링 쿼리가 실패한다. DataSource 로그·MariaDB healthcheck를 본다.
3. 인스턴스 상태 — 인스턴스가 `PAUSED`/`TERMINATED`면 활동이 픽업되지 않는다(설계). `SELECT STATUS_ST FROM U_LINE_INSTANCE WHERE ID='<id>'`.
4. 활동이 미래 `NEXT_RETRY_DT`로 지연됐는지 — 게이트 차단·backoff로 밀렸을 수 있다.

**조치**:
- 앱이 죽었으면 재기동(`up -d`). SKIP LOCKED라 재기동 후 미처리 PENDING을 이어서 집는다.
- DB 문제면 커넥션 풀·MariaDB 컨테이너를 복구.
- PAUSED면 `/line/instance/{id}/unpause`로 재개.
- 여러 앱 인스턴스를 띄우면 처리량이 는다(SKIP LOCKED 수평 확장).

## 업그레이드·마이그레이션

신규 설치는 `schema-{h2,mariadb,oracle}.sql`에 최신 스키마가 이미 포함된다. **기존 운영 DB만** 아래 마이그레이션 스크립트를 수동으로 1회 적용한다. 자동 마이그레이션 러너(Flyway/Liquibase)는 없다 — 순서대로 직접 실행한다.

### 적용 순서 (assets/sql/migrations/)

날짜순으로 적용한다. 각 마이그레이션은 dialect별 `.sql`과 배경 설명 `.md`가 짝을 이룬다.

| 날짜 | 마이그레이션 | 스크립트 (dialect별) |
|---|---|---|
| 2026-05-09 | WF → LINE 테이블/컬럼 rename | `rename-wf-to-line-{h2,mariadb,oracle}.sql` |
| 2026-05-16 | `USE_FL`/`VIEW_FL` 공통 플래그 제거 | `drop-use-fl-view-fl-{mariadb,oracle}.sql` |
| 2026-06-09 | `ITEM_INDEX` 컬럼 추가 (M22) | `add-item-index-{h2,mariadb,oracle}.sql` |
| 2026-06-09 | `STREAM_MODE` 컬럼 추가 (M22) | `add-stream-mode-{h2,mariadb,oracle}.sql` |
| 2026-06-19 | `U_LINE_STATION`/`U_LINE_TRACK` composite PK | `composite-station-pk-{h2,mariadb,oracle}.sql` |

각 `.md`(예: [composite-station-pk](../../../assets/sql/migrations/2026-06-19-composite-station-pk.md))에 배경·변경 내용·하위 호환·주의사항이 있으니 적용 전에 읽는다.

### 적용 예시 (MariaDB)

```bash
# 적용 전 백업 (위 백업 섹션)
docker exec swe-mariadb mariadb-dump -uwfuser -pwfpw workflow > pre-migration.sql

# 스크립트 실행
docker exec -i swe-mariadb mariadb -uwfuser -pwfpw workflow \
  < assets/sql/migrations/composite-station-pk-mariadb.sql
```

### in-flight 인스턴스 주의

일부 마이그레이션(composite PK 등)은 실행 중 인스턴스에 영향을 준다. composite PK 변경은 마이그레이션 시점 RUNNING이던 DAG 인스턴스의 `DEFINITION_ID`가 NULL로 남아 fan-out이 멈출 수 있다. **가능하면 in-flight 인스턴스를 drain한 뒤 적용**하고, 부득이하면 스크립트 말미의 best-effort backfill을 주석 해제해 실행한다. 스크립트는 FK 제거 → PK 교체 → FK 재생성 순서라 **중간에 중단하지 말 것.**

## 관련 문서

- [Quickstart](../../QUICKSTART.md) — 기동·정리·캐시 무효화
- [Credential Vault](../../SECRETS.md) — 키 로테이션·폐쇄망 운반·분실 대비
- [에러 코드](../../ERROR_CODES.md) — `WF-E*` 카탈로그
- [DB 명명 규칙](../../DATABASE_RULE.md) — 스키마 컨벤션
