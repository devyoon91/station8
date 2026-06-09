# Migration: H_LINE_ACTIVITY_EXECUTION.ITEM_INDEX 추가

대상 이슈: #368 (M22 item-level streaming)
적용일: 2026-06-09 (PR 머지일 기준)

## 요약

`H_LINE_ACTIVITY_EXECUTION`에 `ITEM_INDEX` 컬럼을 추가한다. M22 fan-out에서 한 노드가 배열 원소마다 실행될 때, 같은 `(INSTANCE_ID, NODE_ID)`에 속하는 실행 행을 레인 인덱스로 구분하는 컬럼이다.

## 배경

[M22 RFC](../../docs/decisions/m22-item-streaming.md) 참조. 노드 출력이 배열이면 다음 노드를 원소당 한 번씩 실행한다. 실행 행 한 줄 = 노드 1회 실행 모델 위에 올리므로, item당 행을 만들고 `ITEM_INDEX`로 0..K-1을 매긴다. 비-fan-out 노드와 레거시 선형 실행은 전부 index 0.

## 하위 호환

`DEFAULT 0 NOT NULL`이라 기존 행은 마이그레이션 시 전부 0으로 채워진다. fan-out을 쓰지 않는 라인은 length-1 레인(index 0)으로 자연 흡수되어 동작이 바뀌지 않는다.

이번 PR(#368)은 컬럼 추가까지만 다룬다. 실제 fan-out materialize / fan-in 재판정은 후속 #369(REFACTOR)에서 이 컬럼을 채운다.

## 적용

| Dialect | 스크립트 |
|---|---|
| H2 | [add-item-index-h2.sql](add-item-index-h2.sql) |
| MariaDB | [add-item-index-mariadb.sql](add-item-index-mariadb.sql) |
| Oracle | [add-item-index-oracle.sql](add-item-index-oracle.sql) |

신규 설치는 `schema-*.sql`에 이미 컬럼이 포함되어 별도 적용이 필요 없다. 기존 운영 DB만 위 스크립트를 1회 적용한다. Oracle은 `ADD COLUMN IF NOT EXISTS`가 없으므로 재실행 전 컬럼 존재를 확인할 것.
