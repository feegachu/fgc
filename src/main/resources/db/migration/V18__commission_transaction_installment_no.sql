-- 2026-08-13 yslee - 실제 명세·지급 건의 회차 원천값 저장
-- 기존 코드: 예상 schedule_line에만 installment_no가 있고 실제 commission_transaction에는 회차가 없음
-- 문제: 예상 13회차와 실제 14회차를 비교할 수 없어 INSTALLMENT_MISMATCH 인수조건을 재현하지 못함
-- 개선: 정규화된 실제 원천 회차를 nullable 컬럼으로 저장하고 확정 이후에는 변경하지 못하도록 보호

ALTER TABLE fgc.commission_transaction
    ADD COLUMN installment_no integer;

ALTER TABLE fgc.commission_transaction
    ADD CONSTRAINT ck_commission_transaction_installment_no
    CHECK (installment_no IS NULL OR installment_no > 0);

COMMENT ON COLUMN fgc.commission_transaction.installment_no IS
    '정규화된 실제 원수사 명세 또는 지급 건의 회차. 원천에 회차가 없으면 NULL이며 대사에서 REVIEW_REQUIRED로 처리';

CREATE OR REPLACE FUNCTION fgc.guard_commission_transaction_installment_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.status IN ('CONFIRMED', 'CANCELLED')
     AND NEW.installment_no IS DISTINCT FROM OLD.installment_no THEN
    RAISE EXCEPTION 'Installment number of finalized transaction % is immutable',
      OLD.commission_transaction_id;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_commission_transaction_installment_guard
BEFORE UPDATE OF installment_no ON fgc.commission_transaction
FOR EACH ROW EXECUTE FUNCTION fgc.guard_commission_transaction_installment_write();
