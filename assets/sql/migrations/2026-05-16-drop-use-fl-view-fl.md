# Migration: USE_FL / VIEW_FL 컬럼 일괄 제거

대상 PR: 본 PR
적용일: 2026-05-16 (PR 머지일 기준 적용)

## 요약

모든 `U_LINE_*` / `H_LINE_*` 테이블에 들어있던 `USE_FL`, `VIEW_FL` 두 공통 컬럼을 일괄 제거. **`DEL_FL`은 soft-delete 기반으로 active 사용 중이라 유지**.

## 배경

`docs/DATABASE_RULE.md`에 정의된 3개 공통 플래그 (USE_FL = "사용 여부", VIEW_FL = "표시 여부", DEL_FL = "삭제 여부") 중 USE_FL/VIEW_FL은 도입 이후 어떤 코드 경로에서도 의미 있게 사용되지 않음:

- **WHERE 필터 (USE_FL)**: 단 1곳 — `JdbcActivityRepository.findPendingActivitiesWithLock` 의 `AND USE_FL = 'Y'`. 그러나 INSERT는 항상 `'Y'`만 박고 토글하는 코드가 없어 실효성 0.
- **WHERE 필터 (VIEW_FL)**: 0곳.
- **UPDATE (둘 다)**: 0곳.
- **INSERT 기본값**: 모든 곳에서 `'Y'`로 고정 — 의미 없는 storage cost.

`U_LINE_USER` / `U_LINE_DATASOURCE`의 enable/disable 토글은 별도 `ENABLED_FL`이 담당하므로 USE_FL과 의미 중복이었던 것도 사용 안 한 원인.

## 영향 테이블 (12종)

| 테이블 | 비고 |
|---|---|
| U_LINE_INSTANCE | 라인 인스턴스 |
| H_LINE_ACTIVITY_EXECUTION | 활동 실행 이력 |
| H_LINE_DLQ | DLQ |
| U_LINE_PROJECT | 프로젝트 |
| U_LINE_DEFINITION | 라인 정의 |
| U_LINE_STATION | 노드(역) |
| U_LINE_TRACK | 엣지(트랙) |
| U_LINE_SCHEDULE | cron 스케줄 |
| U_LINE_USER | 사용자 |
| U_LINE_USER_ROLE | 사용자 역할 |
| U_LINE_DEFINITION_ACL | 라인 정의 ACL |
| U_LINE_DATASOURCE | 동적 DataSource |

## 적용 방법

### 1. 운영 데이터가 없는 단계 (테스트/CI/dev)

`DROP TABLE` 후 새 `schema-{dialect}.sql` 적용. 본 PR의 schema 파일이 이미 두 컬럼 없이 정의되어 있다.

### 2. 운영 환경 (기존 데이터 유지)

dialect별 ALTER 스크립트 실행:

- **MariaDB**: `assets/sql/migrations/drop-use-fl-view-fl-mariadb.sql`
- **Oracle**: `assets/sql/migrations/drop-use-fl-view-fl-oracle.sql`

H2는 dev/test 전용이라 별도 스크립트 없음 — 새 스키마로 재생성하면 됨.

본 ALTER는 idempotent하지 않음 (다시 실행하면 "column does not exist"로 에러). 한 번만 실행한 뒤 적용 이력을 별도 관리.

## 코드 동시 변경

본 PR에 포함된 코드 변경 요약:

- **Schema files (4)**: `schema-{h2,mariadb,oracle}.sql` + `docker/init/mariadb/01-schema.sql` 에서 컬럼 정의 제거
- **Entity records (11)**: ActivityExecution, DataSourceDefinition, DlqEntry, LineDefinition, LineInstance, LineProject, LineSchedule, LineStation, LineTrack, LineUser, LineAclEntry — `useFl` / `viewFl` 필드 제거
- **JDBC repos (8)**: INSERT/RowMapper에서 컬럼 제거. JdbcActivityRepository와 JdbcLineScheduleRepository의 `WHERE ... AND USE_FL = 'Y'` 필터도 제거
- **Tests (24)**: fixture / inline schema / assertion 정리

## 회귀 가드

`./gradlew test` 전체 모듈 그린 (569 passed, 4 testcontainers IT skipped).

## Breaking change

본 변경 이전 또는 이후의 코드에서 entity record를 `new EntityType(..., "Y", "Y", "N", ...)`로 생성하던 호출은 모두 컴파일 에러로 surface됨. 컴파일러가 잡아주므로 누락된 호출처 없음.

외부 통합 (e.g. 사용자가 직접 SELECT 쿼리를 작성한 경우) 에서 `USE_FL` / `VIEW_FL`을 참조했다면 본 PR 이후 컬럼 부재로 실패. 운영 가이드: PR 머지 전 외부 ETL/BI 쿼리에서 두 컬럼 참조 확인 + 제거.

## Backout

- 코드: revert
- DB: 컬럼 다시 추가
  ```sql
  -- MariaDB
  ALTER TABLE <테이블>
    ADD COLUMN USE_FL VARCHAR(1) DEFAULT 'Y' NOT NULL,
    ADD COLUMN VIEW_FL VARCHAR(1) DEFAULT 'Y' NOT NULL;
  -- Oracle
  ALTER TABLE <테이블> ADD (USE_FL VARCHAR2(1) DEFAULT 'Y' NOT NULL, VIEW_FL VARCHAR2(1) DEFAULT 'Y' NOT NULL);
  ```
  기존 행은 DEFAULT로 `'Y'` 채워짐.
