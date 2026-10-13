-- #477. 보장성보험이 아닌 계약(저축성·일반손해·자동차 등)은 1,200%·차익거래 규제 대상이 아니다
-- (보험업감독규정 제4-32조⑤⑥, FAQ 2차 Q11). 위반·검토필요가 아니라 "적용대상 아님"으로 남기려고
-- 결과 상태 NOT_APPLICABLE 을 두 판정 테이블에 추가한다.
--
-- cap_check.cap_rule_set_id 는 NOT NULL 이었지만, 대상이 아닌 계약은 적용할 룰셋이 없을 수 있다.
-- NOT_APPLICABLE 행에 한해 NULL 을 허용한다.

DO $$
DECLARE
  constraint_name text;
BEGIN
  SELECT con.conname INTO constraint_name
    FROM pg_constraint con
   WHERE con.conrelid = 'fgc.cap_check'::regclass
     AND con.contype = 'c'
     AND pg_get_constraintdef(con.oid) LIKE '%result_status%'
   LIMIT 1;
  IF constraint_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE fgc.cap_check DROP CONSTRAINT %I', constraint_name);
  END IF;

  SELECT con.conname INTO constraint_name
    FROM pg_constraint con
   WHERE con.conrelid = 'fgc.arbitrage_check'::regclass
     AND con.contype = 'c'
     AND pg_get_constraintdef(con.oid) LIKE '%result_status%'
   LIMIT 1;
  IF constraint_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE fgc.arbitrage_check DROP CONSTRAINT %I', constraint_name);
  END IF;
END $$;

ALTER TABLE fgc.cap_check
  ADD CONSTRAINT ck_cap_check_result_status
  CHECK (result_status IN ('NORMAL','WARNING','VIOLATION','REVIEW_REQUIRED','NOT_APPLICABLE'));

ALTER TABLE fgc.cap_check ALTER COLUMN cap_rule_set_id DROP NOT NULL;

ALTER TABLE fgc.cap_check
  ADD CONSTRAINT ck_cap_check_rule_set_required
  CHECK (cap_rule_set_id IS NOT NULL OR result_status = 'NOT_APPLICABLE');

ALTER TABLE fgc.arbitrage_check
  ADD CONSTRAINT ck_arbitrage_check_result_status
  CHECK (result_status IN ('CLEAR','CANDIDATE','REVIEW_REQUIRED','NOT_APPLICABLE'));
