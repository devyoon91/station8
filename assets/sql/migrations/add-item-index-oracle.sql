-- #368 (M22) : H_LINE_ACTIVITY_EXECUTION.ITEM_INDEX 추가 (Oracle)
-- fan-out 레인 인덱스. 비-fan-out/레거시 실행은 0. 기존 행은 DEFAULT 0으로 흡수.
-- Oracle은 ADD COLUMN IF NOT EXISTS를 지원하지 않으므로, 재실행 전 컬럼 존재 여부를 확인할 것.
ALTER TABLE H_LINE_ACTIVITY_EXECUTION ADD (ITEM_INDEX NUMBER DEFAULT 0 NOT NULL);
