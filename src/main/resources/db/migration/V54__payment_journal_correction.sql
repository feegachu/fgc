-- #548 Preserve payment correction lineage and immutable reconciliation snapshots.
CREATE TABLE fgc.payment_journal_correction (
    correction_group_key varchar(100) PRIMARY KEY REFERENCES fgc.journal_correction_group(correction_group_key),
    original_payment_id bigint NOT NULL UNIQUE REFERENCES fgc.commission_transaction(commission_transaction_id),
    replacement_payment_id bigint UNIQUE REFERENCES fgc.commission_transaction(commission_transaction_id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (original_payment_id IS DISTINCT FROM replacement_payment_id)
);
ALTER TABLE fgc.reconciliation_run
    ADD COLUMN superseded_yn boolean NOT NULL DEFAULT false,
    ADD COLUMN corrects_reconciliation_run_id bigint REFERENCES fgc.reconciliation_run(reconciliation_run_id);
CREATE UNIQUE INDEX uq_reconciliation_run_correction
    ON fgc.reconciliation_run (corrects_reconciliation_run_id)
    WHERE corrects_reconciliation_run_id IS NOT NULL;
DROP INDEX fgc.uq_reconciliation_run;
CREATE UNIQUE INDEX uq_reconciliation_run ON fgc.reconciliation_run
    (settlement_month, payment_stage, COALESCE(insurer_id,0), COALESCE(validation_run_id,0))
    WHERE NOT superseded_yn;

CREATE TRIGGER trg_payment_journal_correction_append_only
BEFORE UPDATE OR DELETE ON fgc.payment_journal_correction
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

-- Existing source/revision rules remain in force; only a linked, confirmed replacement may use revision 1.
CREATE OR REPLACE FUNCTION fgc.guard_journal_correction_membership()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  v_original_id bigint;
  v_original_type varchar(35);
  v_original_source_type varchar(60);
  v_original_source_id varchar(100);
  v_original_revision integer;
  v_original_status varchar(15);
BEGIN
  IF NEW.correction_group_key IS NULL THEN
    RETURN NEW;
  END IF;

  SELECT g.original_journal_header_id,
         o.journal_type,
         o.source_entity_type,
         o.source_entity_id,
         o.revision_no,
         o.status
    INTO v_original_id,
         v_original_type,
         v_original_source_type,
         v_original_source_id,
         v_original_revision,
         v_original_status
    FROM journal_correction_group g
    JOIN journal_header o
      ON o.journal_header_id = g.original_journal_header_id
   WHERE g.correction_group_key = NEW.correction_group_key
   FOR SHARE OF g, o;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Unknown journal correction group %', NEW.correction_group_key;
  END IF;

  IF NEW.journal_type = 'REVERSAL' THEN
    IF NEW.reversal_of_id IS DISTINCT FROM v_original_id
       OR NEW.source_entity_type <> 'JOURNAL_HEADER'
       OR NEW.source_entity_id <> v_original_id::text
       OR NEW.revision_no <> 1 THEN
      RAISE EXCEPTION 'Reversal journal does not match correction group %',
        NEW.correction_group_key;
    END IF;
    IF v_original_status <> 'POSTED' THEN
      RAISE EXCEPTION 'Original journal % is no longer POSTED', v_original_id;
    END IF;
  ELSE
    IF NEW.reversal_of_id IS NOT NULL
       OR NEW.journal_type <> v_original_type
       OR NEW.source_entity_type <> v_original_source_type
       OR NOT (
         (NEW.source_entity_id = v_original_source_id AND NEW.revision_no = v_original_revision + 1)
         OR (
           v_original_type IN ('CONFIRMED_FC_PAYOUT', 'ACTUAL_INSURER_STATEMENT')
           AND v_original_source_type = 'COMMISSION_TRANSACTION' AND NEW.revision_no = 1
           AND EXISTS (
             SELECT 1 FROM fgc.payment_journal_correction pc
             JOIN fgc.commission_transaction o ON o.commission_transaction_id = pc.original_payment_id
             JOIN fgc.commission_transaction n ON n.commission_transaction_id = pc.replacement_payment_id
             WHERE pc.correction_group_key = NEW.correction_group_key
               AND pc.original_payment_id::text = v_original_source_id
               AND pc.replacement_payment_id::text = NEW.source_entity_id
               AND o.status = 'CANCELLED' AND n.status = 'CONFIRMED'
               AND o.payment_stage = n.payment_stage AND o.cashflow_type = n.cashflow_type
           )
         )
       ) THEN
      RAISE EXCEPTION 'Reposted journal does not match correction group %',
        NEW.correction_group_key;
    END IF;
    IF NOT EXISTS (
      SELECT 1
        FROM journal_header r
       WHERE r.correction_group_key = NEW.correction_group_key
         AND r.journal_type = 'REVERSAL'
         AND r.reversal_of_id = v_original_id
         AND r.status = 'POSTED'
    ) THEN
      RAISE EXCEPTION 'Reposted journal requires a POSTED reversal in correction group %',
        NEW.correction_group_key;
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

