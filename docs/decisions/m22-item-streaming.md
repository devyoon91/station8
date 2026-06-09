# M22 — Item-level streaming (array fan-out)

**상태**: 결정됨 (2026-06-09)
**관련 이슈**: [#253](https://github.com/devyoon91/station8/issues/253) (M22), [#364](https://github.com/devyoon91/station8/issues/364) (composite PK — 동반 처리)

## 결정

노드 출력이 배열이면 다음 노드가 각 원소마다 한 번씩 실행된다. n8n의 핵심 의미론이고, M22가 채우려는 자리다.

핵심은 이걸 새 실행 모델로 만들지 않는다는 것이다. station8은 이미 "`H_LINE_ACTIVITY_EXECUTION` 한 행 = 노드 1회 실행, 워커가 `FOR UPDATE SKIP LOCKED`로 집어감"이라는 모델을 쓴다. fan-out은 그 위에 자연스럽게 얹힌다 — 노드가 배열 `[a, b, c]`를 내면 다음 노드의 실행 행을 **원소당 하나씩** 만든다 (`ITEM_INDEX` 0, 1, 2). 그러면:

- **병렬성이 공짜로 떨어진다** — item 행 3개가 전부 PENDING이면 워커 풀이 동시에 집어간다. 새 동시성 메커니즘이 필요 없다.
- **부분 재시도가 공짜로 떨어진다** — item 1번만 실패하면 그 행만 PENDING으로 남아 재시도되고, 성공한 0·2번은 그대로 COMPLETED.
- **DLQ가 그대로 작동한다** — item 단위로 max retry 초과 시 DLQ 적재.

정리하면:

- **fan-out 모델**: 배열 출력 → 다음 노드를 item당 1행으로 확장. `H_LINE_ACTIVITY_EXECUTION`에 `ITEM_INDEX` 컬럼 추가.
- **실행 모드**: 기본 **병렬**(item 행이 독립적으로 폴링됨). 노드별 **sequential 토글** 제공 — 하류 API·DataSource를 한 번에 하나씩 때려야 할 때.
- **바인딩**: `$item`(현재 원소), `$items`(전체 배열), `$itemIndex`(인덱스) 노출. `LineContextBindings`에 이미 `$prev.binary`가 M22 placeholder로 박혀 있다 — 같은 자리.
- **fan-in collector**: 하류 노드가 단일 입력을 요구하면 item 출력 K개를 배열로 모아 1회 실행. 노드 속성으로 구분.
- **하위 호환**: 단일 객체 출력은 length-1 배열의 degenerate case로 흡수. 기존 라인은 `ITEM_INDEX` 기본값으로 동작 무변.
- **#364 동반**: composite PK 변경을 같은 마이그레이션 PR에 묶는다 (아래 별도 절).

## 왜 지금

M16~M21에서 expression·credential·HTTP·file·webhook·form을 다 깔았다. 이제 노드 하나가 "여러 건"을 다루는 게 흔해졌다 — HTTP로 레코드 배열을 받고, 파일에서 CSV 행을 읽고, LLM에 N개를 돌린다. 지금까지는 노드 안에서 루프를 직접 돌려야 했다. 그러면 durable 모델의 이점이 전부 사라진다: 중간에 죽으면 처음부터, item 하나 실패하면 전체 실패, 병렬도 수동.

M23 LLM 노드가 이 한계를 특히 세게 만난다. RFC #335에서 streaming을 "M22 도입 후 재검토"로 미뤄둔 이유다. 배열을 모델에 fan-out하는 게 LLM 노드의 핵심 유스케이스인데 M22 없이는 막혀 있다.

## 현재 모델에서 깨지는 가정

지금은 한 인스턴스 안에서 `(INSTANCE_ID, NODE_ID)`가 유일하다. `findByInstanceAndNode`가 행 하나를 돌려주고, `allPredecessorsCompleted`가 "선행 노드의 그 한 행이 COMPLETED인가"로 fan-in을 판정한다. DAG 시작 시 노드마다 행 하나를 `WAITING_DEPENDENCIES`로 만들어 두고, 선행이 끝나면 `PENDING`으로 promote한다.

fan-out은 이 유일성을 깬다. 노드 M이 item당 실행되면 `(INSTANCE_ID, NODE_ID)`에 행이 K개 생긴다. 유일 키가 **`(INSTANCE_ID, NODE_ID, ITEM_INDEX)`**로 바뀐다. 두 가지가 따라온다.

**행 생성 시점이 동적이 된다.** K는 선행 노드가 끝나기 전엔 모른다. 그래서 fan-out 대상 노드의 item 행은 시작 시 미리 만들 수 없고, 선행이 배열을 내는 순간 `DagInterpreter.onNodeCompleted`에서 K개로 확장(materialize)한다. 정적 DAG 골격(fan-out 없는 노드)은 지금처럼 미리 생성하고, fan-out 가지만 lazy 확장하는 혼합 방식으로 간다.

**fan-in 판정이 "행 하나"에서 "행 K개 전부"로 바뀐다.** `allPredecessorsCompleted`가 선행 노드의 모든 item 행이 COMPLETED인지를 봐야 한다. collector 노드는 K개가 다 끝나야 1회 promote된다.

## item lane — fan-out은 어디까지 전파되나

노드 M이 item 단위로 갈라지면, M 다음 노드도 item 단위인가 아니면 다시 합쳐지는가? 이걸 노드 속성으로 정한다.

- **item-scoped 노드** (기본): 선행이 item 단위면 자기도 item 단위. M#i의 출력을 받아 P#i를 만든다. fan-out "레인"이 인덱스를 달고 하류로 계속 흐른다.
- **aggregate(collector) 노드**: 선행 item 출력 K개를 `$items` 배열로 모아 1회 실행. 레인이 여기서 닫힌다.

즉 fan-out은 한 번 열리면 collector를 만날 때까지 하류로 전파된다. 이 레인 전파 규칙(특히 중첩 fan-out — item 노드가 또 배열을 내는 경우)의 정확한 의미론은 RFC에서 모델만 고정하고, 경계 케이스는 구현 sub-issue(REFACTOR)에서 확정한다. v0.x이므로 1단계 fan-out + 명시적 collector를 먼저 지원하고, 중첩은 후속으로 연다.

## 실행 모드 — 병렬 기본, 노드별 순차

item 행이 전부 PENDING이면 워커가 동시에 집어가므로 **병렬이 기본값이고 별도 구현이 필요 없다**. 다만 하류 외부 시스템을 한 번에 하나씩 호출해야 하는 경우(rate limit, 순서 의존)가 있어 노드에 `sequential` 토글을 둔다.

sequential은 `ITEM_INDEX` 순서로 한 번에 하나만 PENDING이 되도록 게이트를 건다. 동시성 자체의 상한은 이미 있는 PIPELINE_* 정책(#164)이 담당하므로, sequential은 "이 노드의 item들 사이"에만 적용되는 국소 제약으로 구현한다. 전역 동시성과 노드 내 순서를 분리해서, 둘이 겹쳐 동작해도 충돌하지 않게 한다.

## #364 composite PK를 같이 가져가는 이유

`U_LINE_STATION`의 PK가 nodeId 단일 컬럼이라 라인 간에도 글로벌 유일하다(#364). M22는 `H_LINE_ACTIVITY_EXECUTION`에 `ITEM_INDEX`를 더하는 스키마 마이그레이션을 어차피 친다. 두 변경 모두 h2/mariadb/oracle 3개 dialect SQL을 손대고, 마이그레이션 스크립트와 메타 문서를 새로 쓴다. 마이그레이션 라운드를 두 번 도는 대신 한 PR에 묶어 dialect SQL 검토를 1회로 끝낸다.

단 두 변경은 **다른 테이블·다른 callsite**다(composite PK는 정의 테이블과 30~50개 조회 지점, item_index는 실행 테이블). 기술적 결합이 없으므로 PR 안에서 논리적으로 분리된 커밋으로 쌓는다 — 먼저 composite PK 정정을 독립 커밋으로 올리고 그 위에 item streaming을 얹어, 리뷰어가 둘을 따로 읽을 수 있게 한다. 한 PR이 너무 커지면 composite PK를 선행 PR로 떼는 것도 검토한다.

composite PK 쪽 영향: `findStationById`·`findIncomingEdges`·`findOutgoingEdges` 등 nodeId 단독 조회를 `(DEFINITION_ID, ID)`로 바꾸고, `U_LINE_TRACK`·`H_LINE_ACTIVITY_EXECUTION`의 FK를 정정한다. 기존 데이터는 nodeId가 이미 유일하므로 composite로 바꿔도 충돌 없다.

## 스키마 변경

raw JDBC + record 엔티티 + dialect별 SQL 구조라, 컬럼 하나에 손이 여러 군데 간다.

- `schema-{h2,mariadb,oracle}.sql` 3개에 `H_LINE_ACTIVITY_EXECUTION.ITEM_INDEX INT DEFAULT 0` 추가, `U_LINE_STATION` PK를 `(DEFINITION_ID, ID)`로.
- `ActivityExecution` record에 `itemIndex` 필드, RowMapper·INSERT 갱신.
- `assets/sql/migrations/`에 dialect별 ALTER 스크립트 + 메타 `.md`.
- 유일 키 변경에 맞춰 `findByInstanceAndNode` 류를 `(instance, node)` → item 행 목록 반환 형태로 정정.

`ITEM_INDEX DEFAULT 0`이 하위 호환의 핵심이다. 기존 행과 fan-out 안 하는 노드는 전부 index 0인 length-1 레인으로 자연 흡수된다.

## 후속 sub-issue (진행 순서)

1. **`[SCHEMA]`** `ITEM_INDEX` 마이그레이션 + #364 composite PK (3 dialect) — 이 PR의 1차 토대
2. **`[REFACTOR]`** `DagInterpreter` fan-out 확장 + fan-in 재판정 (유일 키 `(instance, node, item_index)`)
3. **`[FEAT]`** `$item` / `$items` / `$itemIndex` 바인딩 (`LineContextBindings`)
4. **`[FEAT]`** 노드 속성 — item-scoped vs collector, sequential 토글
5. **`[UI]`** DAG Builder fan-out 시각화 (레인 표시, collector 마커)
6. **`[TEST]`** 하위 호환 — 기존 단일 객체 라인 무영향 + 부분 실패/재시도 시나리오

## 미해결 — 후속에서 확정

- **중첩 fan-out**: item-scoped 노드가 또 배열을 낼 때의 레인 의미론. 1단계 + 명시적 collector를 먼저, 중첩은 후속.
- **collector 부분 실패**: K개 중 일부가 DLQ로 빠졌을 때 collector를 부분 배열로 진행할지 막을지. onFailure 정책(ABORT/PAUSE/CONTINUE)과 정렬 필요.
- **대량 fan-out 상한**: K가 수만일 때 행 폭증. 배치 materialize / 상한 경고를 동시성 정책(#164)과 맞춰 검토.
