-- 원지급을 보존하고, 검증된 환수 차감만 해당 지급의 처리 근거로 연결한다.
CREATE TABLE fgc.clawback_notice_payment_scope (
    generated_document_id bigint PRIMARY KEY REFERENCES fgc.generated_document(generated_document_id),
    original_payment_id bigint NOT NULL REFERENCES fgc.commission_transaction(commission_transaction_id)
);

CREATE TABLE fgc.exception_payment_recovery (
    original_payment_id bigint NOT NULL REFERENCES fgc.commission_transaction(commission_transaction_id),
    contract_id bigint NOT NULL REFERENCES fgc.insurance_contract(contract_id),
    deduction_payment_id bigint NOT NULL UNIQUE REFERENCES fgc.commission_transaction(commission_transaction_id),
    clawback_calculation_id bigint NOT NULL REFERENCES fgc.clawback_calculation(clawback_calculation_id),
    generated_document_id bigint NOT NULL REFERENCES fgc.generated_document(generated_document_id),
    exception_case_id bigint NOT NULL REFERENCES fgc.exception_case(exception_case_id),
    PRIMARY KEY (original_payment_id, contract_id),
    CHECK (original_payment_id <> deduction_payment_id)
);

CREATE FUNCTION fgc.reconciliation_recovery_complete(p_result_id bigint) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(bool_and(recovery.deduction_payment_id IS NOT NULL AND deduction.status = 'CONFIRMED'
        AND EXISTS (SELECT 1 FROM jsonb_array_elements(calculation.result_snapshot->'lines') line
            WHERE (line->>'transactionAttributionId')::bigint=attribution.transaction_attribution_id)), false)
      FROM fgc.reconciliation_match rm
      JOIN fgc.transaction_attribution attribution USING (transaction_attribution_id)
      LEFT JOIN fgc.exception_payment_recovery recovery
        ON recovery.original_payment_id = attribution.commission_transaction_id AND recovery.contract_id = attribution.contract_id
      LEFT JOIN fgc.commission_transaction deduction ON deduction.commission_transaction_id = recovery.deduction_payment_id
      LEFT JOIN fgc.clawback_calculation calculation USING (clawback_calculation_id)
     WHERE rm.reconciliation_result_id = p_result_id;
$$;
