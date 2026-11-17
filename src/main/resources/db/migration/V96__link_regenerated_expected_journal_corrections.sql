-- 재생성된 운영 스케줄의 원본·역분개·대체 분개를 같은 정정 그룹에 연결한다.
-- 기존 지급 원천 정정 제한과 POSTED 역분개 선행 조건은 유지한다.
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
           v_original_type IN ('EXPECTED_INSURER_INCOME', 'EXPECTED_FC_PAYOUT')
           AND v_original_source_type = 'SCHEDULE_LINE' AND NEW.revision_no = 1
           AND EXISTS (
             SELECT 1 FROM fgc.schedule_line previous_line
             JOIN fgc.schedule_header previous_header USING(schedule_header_id)
             JOIN fgc.schedule_line replacement_line ON replacement_line.schedule_line_id::text = NEW.source_entity_id
             JOIN fgc.schedule_header replacement_header ON replacement_header.schedule_header_id = replacement_line.schedule_header_id
             JOIN fgc.journal_header original ON original.journal_header_id = v_original_id
             WHERE previous_line.schedule_line_id::text = v_original_source_id
               AND previous_line.schedule_line_id <> replacement_line.schedule_line_id
               AND previous_header.contract_id = replacement_header.contract_id
               AND previous_header.payment_stage = replacement_header.payment_stage
               AND previous_header.schedule_purpose = 'OPERATIONAL'
               AND replacement_header.schedule_purpose = 'OPERATIONAL'
               AND NOT previous_header.active_yn
               AND previous_line.installment_no = replacement_line.installment_no
               AND previous_line.commission_item_id = replacement_line.commission_item_id
               AND previous_line.beneficiary_agent_id IS NOT DISTINCT FROM replacement_line.beneficiary_agent_id
               AND previous_line.due_month = replacement_line.due_month
               AND NEW.contract_id = replacement_header.contract_id
               AND NEW.policy_version_id = replacement_header.policy_version_id
               AND EXISTS (
                 SELECT 1 FROM fgc.validation_run current_run
                 WHERE current_run.validation_run_id=NEW.validation_run_id
                   AND current_run.validation_month=replacement_line.due_month AND current_run.status <> 'FINALIZED'
                   AND (original.validation_run_id IS NULL OR EXISTS (
                     SELECT 1 FROM fgc.validation_run previous_run
                     WHERE previous_run.validation_run_id=original.validation_run_id
                       AND previous_run.validation_month=previous_line.due_month AND previous_run.status <> 'FINALIZED'
                   ))
               )
               AND NEW.journal_date = replacement_line.due_date
               AND (replacement_header.active_yn OR (
                 TG_OP = 'UPDATE' AND OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
                 AND EXISTS (
                   SELECT 1 FROM fgc.journal_correction_group successor
                   JOIN fgc.journal_header successor_reversal ON successor_reversal.correction_group_key = successor.correction_group_key
                   WHERE successor.original_journal_header_id = NEW.journal_header_id
                     AND successor_reversal.reversal_of_id = NEW.journal_header_id
                     AND successor_reversal.journal_type = 'REVERSAL' AND successor_reversal.status = 'POSTED'
                 )
               ))
           )
         )
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
               AND o.status = 'CANCELLED'
               AND (n.status = 'CONFIRMED' OR (
                 TG_OP = 'UPDATE' AND OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
                 AND n.status = 'CANCELLED' AND EXISTS (
                   SELECT 1 FROM fgc.payment_journal_correction successor
                   JOIN fgc.journal_correction_group next_group
                     ON next_group.correction_group_key = successor.correction_group_key
                   JOIN fgc.journal_header next_reversal
                     ON next_reversal.correction_group_key = next_group.correction_group_key
                   WHERE successor.original_payment_id = n.commission_transaction_id
                     AND next_group.original_journal_header_id = NEW.journal_header_id
                     AND next_reversal.reversal_of_id = NEW.journal_header_id
                     AND next_reversal.journal_type = 'REVERSAL' AND next_reversal.status = 'POSTED'
                 )
               ))
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
