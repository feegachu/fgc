-- CONT-W02 수동 한도 재검증 이력을 계약 등록·수정의 REALTIME 이력과 구분한다.
-- V27은 다른 브랜치에서 먼저 병합되므로 이 변경은 V28로 추가한다.
DO $$
DECLARE
  constraint_name text;
BEGIN
  SELECT con.conname
    INTO constraint_name
    FROM pg_constraint con
   WHERE con.conrelid = 'fgc.cap_check'::regclass
     AND con.contype = 'c'
     AND pg_get_constraintdef(con.oid) LIKE '%check_kind%'
   LIMIT 1;

  IF constraint_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE fgc.cap_check DROP CONSTRAINT %I', constraint_name);
  END IF;
END $$;

ALTER TABLE fgc.cap_check
  ADD CONSTRAINT ck_cap_check_kind
  CHECK (check_kind IN ('REALTIME', 'MANUAL', 'MONTHLY', 'PRE_CONFIRM'));
