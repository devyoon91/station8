# Migration: U_WF_* / H_WF_* → U_LINE_* / H_LINE_*

대상 이슈: [#88](https://github.com/devyoon91/station8/issues/88)
적용일: 2026-05-09 (PR 머지일 기준 적용)

## 요약

도메인 용어(`Line` / `Station` / `Track`)와 DB 스키마를 정렬하기 위해 7개 테이블을
일괄 RENAME 한다. **컬럼명은 변경하지 않는다** (`WORKFLOW_NAME`, `NODE_ID`,
`FROM_NODE_ID`/`TO_NODE_ID` 등 그대로). 컬럼 리네임은 영향 범위가 더 커
별도 후속 작업으로 미룬다.

## 매핑

| 기존 | 신규 |
|---|---|
| `U_WF_DEFINITION` | `U_LINE_DEFINITION` |
| `U_WF_INSTANCE` | `U_LINE_INSTANCE` |
| `U_WF_NODE` | `U_LINE_STATION` |
| `U_WF_EDGE` | `U_LINE_TRACK` |
| `U_WF_SCHEDULE` | `U_LINE_SCHEDULE` |
| `H_WF_ACTIVITY_EXECUTION` | `H_LINE_ACTIVITY_EXECUTION` |
| `H_WF_DLQ` | `H_LINE_DLQ` |

## 적용 방법

운영 데이터가 없는 단계라면 다음 둘 중 편한 쪽:

1. **DROP & 재생성** — `DROP TABLE` 후 새 `schema-{dialect}.sql` 적용. 테스트
   환경/CI에 가장 단순.
2. **RENAME 스크립트** — 본 디렉터리의 `rename-{dialect}.sql` 실행.
   기존 데이터를 보존하면서 테이블명만 바꾼다. FK 참조는 RDBMS가 자동 갱신.

PK/FK/UQ/IDX **제약조건/인덱스 이름**은 RENAME 후에도 옛 이름을 유지한다
(예: `U_WF_NODE_PK`가 `U_LINE_STATION` 위에 그대로 남음). 동작에는 문제없으나
표면적으로 거슬린다면 dialect별 ALTER로 수동 재명명 가능. 본 저장소의 새
``schema-*.sql``은 새 이름 기준으로 정의되어 있으므로, 신규 환경은 첫 부팅
시점부터 일관된 이름을 가진다.

## 대상 dialect

- H2 (개발/테스트 기본)
- MariaDB
- Oracle
