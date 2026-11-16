ALTER TABLE fgc.cap_check_detail ADD COLUMN payment_status_snapshot varchar(20)
    CHECK (payment_status_snapshot IN ('DRAFT', 'CONFIRMED', 'CANCELLED'));

UPDATE fgc.cap_check_detail d SET payment_status_snapshot = p.status
FROM fgc.transaction_attribution a JOIN fgc.commission_transaction p USING (commission_transaction_id)
WHERE a.transaction_attribution_id = d.transaction_attribution_id;

CREATE FUNCTION fgc.snapshot_cap_detail_payment_status() RETURNS trigger
LANGUAGE plpgsql SET search_path=fgc,pg_temp AS $$
BEGIN
    IF NEW.transaction_attribution_id IS NOT NULL THEN
        SELECT p.status INTO NEW.payment_status_snapshot
          FROM fgc.transaction_attribution a JOIN fgc.commission_transaction p USING (commission_transaction_id)
         WHERE a.transaction_attribution_id=NEW.transaction_attribution_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_cap_detail_payment_status_snapshot BEFORE INSERT ON fgc.cap_check_detail
FOR EACH ROW EXECUTE FUNCTION fgc.snapshot_cap_detail_payment_status();
