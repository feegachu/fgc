-- 초과분 이연의 지급 예정일과 계약 2차년도 귀속을 함께 기록한다.
ALTER TABLE fgc.transaction_attribution DROP CONSTRAINT transaction_attribution_attribution_method_check;
ALTER TABLE fgc.transaction_attribution ADD CONSTRAINT transaction_attribution_attribution_method_check
    CHECK (attribution_method IN ('DIRECT', 'SETTLEMENT_SUPPORT_MONTHLY', 'FIRST_CONTRACT_CARRY_FORWARD',
                                 'DEFERRED_CONTRACT', 'APPROVED_ALLOCATION', 'MANUAL_REVIEW', 'NEWCOMER_NON_CONTRACT'));
