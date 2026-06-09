# Migration: U_LINE_STATION.STREAM_MODE 추가

대상 이슈: #371 (M22 item-level streaming)
적용일: 2026-06-09 (PR 머지일 기준)

## 요약

`U_LINE_STATION`에 `STREAM_MODE` 컬럼을 추가한다. 노드가 선행 배열 출력을 어떻게 다루는지 결정하는 fan-out 모드다.

- `NONE` (기본) — 선행 출력을 통째로 받는다. 기존 동작 그대로.
- `FAN_OUT` — 선행 출력이 배열이면 원소마다 한 번씩 실행 (item-scoped).
- `COLLECT` — 선행 fan-out 레인의 모든 원소 출력을 배열로 모아 1회 실행.

## 하위 호환

`DEFAULT 'NONE' NOT NULL`이라 기존 노드는 전부 `NONE`으로 채워져 동작이 바뀌지 않는다. fan-out은 노드에서 명시적으로 `FAN_OUT`/`COLLECT`를 골라야만 활성화되는 opt-in이다 — 배열을 통째로 넘기던 기존 라인이 갑자기 fan-out되는 일은 없다.

본 PR(#371)은 노드 속성(컬럼 + 엔티티 + 영속화 + API DTO)까지 다룬다. 실제 fan-out materialize / fan-in collect 실행은 후속 #369에서 이 값을 읽어 동작한다.

## 적용

| Dialect | 스크립트 |
|---|---|
| H2 | [add-stream-mode-h2.sql](add-stream-mode-h2.sql) |
| MariaDB | [add-stream-mode-mariadb.sql](add-stream-mode-mariadb.sql) |
| Oracle | [add-stream-mode-oracle.sql](add-stream-mode-oracle.sql) |

신규 설치는 `schema-*.sql`에 이미 포함. 기존 운영 DB만 위 스크립트를 1회 적용한다.
