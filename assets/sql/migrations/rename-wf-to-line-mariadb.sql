-- #88 : U_WF_* / H_WF_* → U_LINE_* / H_LINE_* (MariaDB)
-- 자세한 배경/매핑은 본 디렉터리의 README 격 .md 참조.
-- MariaDB는 RENAME TABLE이 동일 트랜잭션 내에서 다중 테이블을 원자적으로 처리한다.
RENAME TABLE
    U_WF_DEFINITION         TO U_LINE_DEFINITION,
    U_WF_INSTANCE           TO U_LINE_INSTANCE,
    U_WF_NODE               TO U_LINE_STATION,
    U_WF_EDGE               TO U_LINE_TRACK,
    U_WF_SCHEDULE           TO U_LINE_SCHEDULE,
    H_WF_ACTIVITY_EXECUTION TO H_LINE_ACTIVITY_EXECUTION,
    H_WF_DLQ                TO H_LINE_DLQ;
