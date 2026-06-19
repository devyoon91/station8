# Migration: U_LINE_STATION PK → (DEFINITION_ID, ID) composite

대상 이슈: #364 (M22 item-level streaming에서 분리된 디자인 정정)
적용일: 2026-06-19 (PR 머지일 기준)

## 배경

`U_LINE_STATION`의 PK가 `ID`(nodeId) 단일 컬럼이라 **라인 간에도 글로벌 unique**였다. 빌더는 정의마다 `n-1`,`n-2`... 를 재발급하므로, 두 번째 정의를 저장하면 `Duplicate entry 'n-1' for key 'PRIMARY'`로 실패한다. 데모는 #363에서 nodeId prefix로 우회했지만, 사용자가 라인을 작성할 때마다 부딪히는 함정이었다.

`U_LINE_TRACK`의 PK(`ID`=edgeId)도 동일하다 — 빌더가 정의마다 `e-1`,`e-2`... 를 재발급하므로 엣지가 있는 두 번째 정의를 저장하면 `Duplicate entry 'e-1'`로 실패한다.

nodeId/edgeId는 본래 **라인 정의 내부**의 식별자다. 두 PK 모두 `(DEFINITION_ID, ID)` composite로 바꿔 정의 간 독립을 보장한다.

## 변경 내용

| 객체 | Before | After |
|---|---|---|
| `U_LINE_STATION` PK | `(ID)` | `(DEFINITION_ID, ID)` |
| `U_LINE_TRACK` PK | `(ID)` | `(DEFINITION_ID, ID)` |
| `U_LINE_TRACK` FK02/FK03 | `(FROM/TO_NODE_ID) → STATION(ID)` | `(DEFINITION_ID, FROM/TO_NODE_ID) → STATION(DEFINITION_ID, ID)` |
| `U_LINE_TRACK` U01 | `UNIQUE (FROM_NODE_ID, TO_NODE_ID)` | `UNIQUE (DEFINITION_ID, FROM_NODE_ID, TO_NODE_ID)` |
| `H_LINE_ACTIVITY_EXECUTION` FK02 | `(NODE_ID) → STATION(ID)` | **제거** (composite PK를 단일 컬럼으로 참조 불가) |
| `U_LINE_INSTANCE` | — | `DEFINITION_ID VARCHAR(50)` 컬럼 추가 |

### 왜 실행 테이블 FK는 제거하나

`H_LINE_ACTIVITY_EXECUTION`에는 `DEFINITION_ID`가 없어 composite PK를 참조할 수 없다. 대신 실행 행은 `INSTANCE_ID → U_LINE_INSTANCE.DEFINITION_ID`로 소속 정의가 특정되므로, nodeId 단독으로 정의 테이블을 조회할 필요가 사라진다(런타임은 instance에서 definitionId를 읽어 `(DEFINITION_ID, NODE_ID)`로 역/엣지를 조회한다). 정합성은 인스턴스 스코프로 보장된다.

## 하위 호환

기존 `ID`는 이미 글로벌 unique였으므로 `(DEFINITION_ID, ID)` 도 자명하게 unique — composite로 바꿔도 데이터 충돌이 없다. 기존 정의/실행은 그대로 동작한다.

`U_LINE_INSTANCE.DEFINITION_ID`는 nullable이며, 레거시/선형(`@Activity`) 인스턴스는 NULL로 남는다(DAG 노드 테이블을 쓰지 않으므로 무관).

### in-flight 인스턴스 주의

마이그레이션 시점에 **RUNNING이던 DAG 인스턴스**는 `DEFINITION_ID`가 NULL이라, 그 시점 이후 fan-out 활성화가 정의를 못 찾아 멈출 수 있다. 안전하게는 in-flight 인스턴스를 drain한 뒤 적용한다. 부득이하면 각 스크립트 말미의 **best-effort backfill**(WORKFLOW_NAME → 최신 active 정의)을 주석 해제해 실행한다. 신규 인스턴스는 시작 시 항상 `DEFINITION_ID`가 채워진다.

## 적용

| Dialect | 스크립트 |
|---|---|
| H2 | [composite-station-pk-h2.sql](composite-station-pk-h2.sql) |
| MariaDB | [composite-station-pk-mariadb.sql](composite-station-pk-mariadb.sql) |
| Oracle | [composite-station-pk-oracle.sql](composite-station-pk-oracle.sql) |

신규 설치는 `schema-*.sql`에 이미 포함. 기존 운영 DB만 위 스크립트를 1회 적용한다. 스크립트는 PK를 참조하는 FK를 먼저 제거하고 PK를 교체한 뒤 composite FK를 재생성하는 순서이므로, 중간 단계에서 중단하지 말 것.
