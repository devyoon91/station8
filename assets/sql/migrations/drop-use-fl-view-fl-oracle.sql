-- Drop unused common-column flags USE_FL, VIEW_FL from all U_LINE_* / H_LINE_* tables (Oracle).
-- 본 두 컬럼은 도입 후 INSERT 'Y' 기본값만 박히고 어디서도 토글/필터되지 않아 dead weight.
-- DEL_FL은 soft-delete로 active 사용 중이라 유지.
-- 자세한 배경은 본 디렉터리의 .md README 참조.
--
-- Oracle은 ALTER TABLE ... DROP COLUMN을 컬럼 단위로 호출 (다중 컬럼 drop 가능하지만 가독성 위해 분리).

ALTER TABLE U_LINE_INSTANCE              DROP (USE_FL, VIEW_FL);
ALTER TABLE H_LINE_ACTIVITY_EXECUTION    DROP (USE_FL, VIEW_FL);
ALTER TABLE H_LINE_DLQ                   DROP (USE_FL, VIEW_FL);
ALTER TABLE U_LINE_PROJECT               DROP (USE_FL, VIEW_FL);
ALTER TABLE U_LINE_DEFINITION            DROP (USE_FL, VIEW_FL);
ALTER TABLE U_LINE_STATION               DROP (USE_FL, VIEW_FL);
ALTER TABLE U_LINE_TRACK                 DROP (USE_FL, VIEW_FL);
ALTER TABLE U_LINE_SCHEDULE              DROP (USE_FL, VIEW_FL);
ALTER TABLE U_LINE_USER                  DROP (USE_FL, VIEW_FL);
ALTER TABLE U_LINE_USER_ROLE             DROP (USE_FL, VIEW_FL);
ALTER TABLE U_LINE_DEFINITION_ACL        DROP (USE_FL, VIEW_FL);
ALTER TABLE U_LINE_DATASOURCE            DROP (USE_FL, VIEW_FL);

-- 인덱스는 영향 없음 — USE_FL/VIEW_FL은 어떤 인덱스에도 포함된 적 없음.
