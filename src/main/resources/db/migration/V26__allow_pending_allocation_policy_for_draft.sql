-- 승인배부 귀속은 원칙적으로 승인 배부정책을 참조해야 한다.
-- 다만 정책 자동 조회가 실패한 지급 건도 DRAFT로 보존해야 하므로,
-- 서비스가 정책 조회 보류를 스냅샷에 명시한 경우에만 NULL을 허용한다.
-- 확정 단계는 앱의 TRAN-007 게이트와 아래 DB 트리거에서 모두 차단한다.
ALTER TABLE fgc.transaction_attribution
    DROP CONSTRAINT ck_attribution_allocation;

ALTER TABLE fgc.transaction_attribution
    ADD CONSTRAINT ck_attribution_allocation CHECK (
        attribution_method <> 'APPROVED_ALLOCATION'
        OR allocation_policy_id IS NOT NULL
        OR allocation_basis_snapshot @> '{"policyVersionResolutionPending": true}'::jsonb
    );

-- 승인배부 정책이 아직 해석되지 않은 귀속행은 DRAFT 보존만 허용한다.
-- 앱 우회 SQL로 상태를 CONFIRMED로 바꾸더라도 DB에서 확정을 막는다.
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
        FROM transaction_attribution
       WHERE commission_transaction_id = NEW.commission_transaction_id
         AND inclusion_status_snapshot = 'REVIEW_REQUIRED'
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
