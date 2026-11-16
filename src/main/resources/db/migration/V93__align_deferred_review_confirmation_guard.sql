-- FGC-FUN-053 / #477: 일반 비보장성 지급의 산입 분류와 이연 초안의 근거 검토를 구분한다.
-- 일반 지급의 비대상 분류는 앱과 동일하게 처리하되 DEFERRED_CONTRACT는 항상 검토를 요구한다.
-- 계약/상품을 알 수 없는 귀속은 계속 차단한다. 금액 합계·승인배부·확정 불변 조건은 유지한다.
CREATE OR REPLACE FUNCTION fgc.guard_commission_transaction_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_sum numeric(15,2);
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'A transaction must be inserted as DRAFT, attributed, and then confirmed by UPDATE';
    END IF;
    RETURN NEW;
  ELSIF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'CONFIRMED/CANCELLED transaction % cannot be deleted; use a reversal or adjustment', OLD.commission_transaction_id;
    END IF;
    RETURN OLD;
  END IF;

  IF OLD.status = 'CONFIRMED' THEN
    IF NEW.status = 'CANCELLED' THEN
      IF ROW(NEW.payment_stage, NEW.source_type, NEW.source_business_key, NEW.insurer_id,
             NEW.recipient_agent_id, NEW.commission_item_id, NEW.policy_version_id,
             NEW.settlement_month, NEW.due_date, NEW.paid_on, NEW.amount, NEW.cashflow_type,
             NEW.evidence_ref, NEW.note)
         IS DISTINCT FROM
         ROW(OLD.payment_stage, OLD.source_type, OLD.source_business_key, OLD.insurer_id,
             OLD.recipient_agent_id, OLD.commission_item_id, OLD.policy_version_id,
             OLD.settlement_month, OLD.due_date, OLD.paid_on, OLD.amount, OLD.cashflow_type,
             OLD.evidence_ref, OLD.note) THEN
        RAISE EXCEPTION 'Only status may change from CONFIRMED to CANCELLED; use an adjustment transaction for corrections';
      END IF;
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'CONFIRMED transaction % is immutable; cancel/reverse with a new transaction', OLD.commission_transaction_id;
  ELSIF OLD.status = 'CANCELLED' THEN
    RAISE EXCEPTION 'CANCELLED transaction % is immutable', OLD.commission_transaction_id;
  END IF;

  IF NEW.status = 'CONFIRMED' AND OLD.status <> 'CONFIRMED' THEN
    SELECT COALESCE(SUM(attributed_amount),0)
      INTO v_sum
      FROM transaction_attribution
     WHERE commission_transaction_id = NEW.commission_transaction_id;
    IF v_sum <> NEW.amount THEN
      RAISE EXCEPTION 'Transaction % cannot be confirmed: attribution total % differs from amount %',
        NEW.commission_transaction_id, v_sum, NEW.amount;
    END IF;
    IF EXISTS (
      SELECT 1
        FROM transaction_attribution a
       WHERE a.commission_transaction_id = NEW.commission_transaction_id
         AND a.inclusion_status_snapshot = 'REVIEW_REQUIRED'
         AND (a.attribution_method = 'DEFERRED_CONTRACT'
              OR NOT EXISTS (
                SELECT 1 FROM insurance_contract c
                JOIN product_offering po USING(product_offering_id)
                JOIN product p USING(product_id)
                WHERE c.contract_id = a.contract_id
                  AND p.protection_type IN ('SAVINGS', 'GENERAL', 'AUTO', 'OTHER')
              ))
    ) THEN
      RAISE EXCEPTION 'Transaction % cannot be confirmed: REVIEW_REQUIRED attribution exists',
        NEW.commission_transaction_id;
    END IF;
    IF EXISTS (
      SELECT 1
        FROM transaction_attribution
       WHERE commission_transaction_id = NEW.commission_transaction_id
         AND attribution_method = 'APPROVED_ALLOCATION'
         AND allocation_policy_id IS NULL
    ) THEN
      RAISE EXCEPTION 'Transaction % cannot be confirmed: APPROVED_ALLOCATION requires allocation_policy_id',
        NEW.commission_transaction_id;
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

-- CREATE OR REPLACE로 함수 수준 설정이 초기화되므로 search_path를 다시 고정한다.
ALTER FUNCTION fgc.guard_commission_transaction_write()
    SET search_path = fgc, pg_temp;
