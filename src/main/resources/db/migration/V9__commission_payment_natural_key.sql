-- 2026-08-07 yslee - FUN-065 수기 지급 건 자연키 제약을 Flyway V9로 적용
-- 기존 구조: 지급 원천 업무키만 중복 방지하고 요구사항의 지급 순번을 별도로 저장하지 않음
-- 문제: 계약+설계사+수수료 항목+귀속월+순번이 같은 수기 지급 건을 중복 저장 가능
-- 개선: 수기 지급 원장에 자연키 계약과 순번을 저장하고 GA_MANUAL_PAYMENT에만 UNIQUE 적용

ALTER TABLE fgc.commission_transaction
    ADD COLUMN source_contract_id bigint
        REFERENCES fgc.insurance_contract (contract_id),
    ADD COLUMN source_sequence integer NOT NULL DEFAULT 1
        CHECK (source_sequence > 0);

-- 확정 지급 건 불변성 트리거는 운영 변경을 막기 위한 것이므로,
-- 기존 수기 지급 건 백필 구간에서만 중지하고 같은 Flyway 트랜잭션에서 즉시 복구한다.
ALTER TABLE fgc.commission_transaction
    DISABLE TRIGGER trg_commission_transaction_guard;

UPDATE fgc.commission_transaction ct
   SET source_contract_id = COALESCE(
           NULLIF(ta.allocation_basis_snapshot ->> 'sourceContractId', '')::bigint,
           ta.contract_id
       )
  FROM fgc.transaction_attribution ta
 WHERE ta.commission_transaction_id = ct.commission_transaction_id
   AND ta.attribution_seq = 1
   AND ct.source_type = 'GA_MANUAL_PAYMENT';

ALTER TABLE fgc.commission_transaction
    ENABLE TRIGGER trg_commission_transaction_guard;

CREATE UNIQUE INDEX uq_commission_payment_natural
    ON fgc.commission_transaction (
        source_contract_id,
        recipient_agent_id,
        commission_item_id,
        settlement_month,
        source_sequence
    ) NULLS NOT DISTINCT
    WHERE source_type = 'GA_MANUAL_PAYMENT';

COMMENT ON COLUMN fgc.commission_transaction.source_contract_id IS
    'FUN-065 자연키 계약. 원계약이 없으면 실제 귀속계약을 사용';
COMMENT ON COLUMN fgc.commission_transaction.source_sequence IS
    'FUN-065 동일 계약·설계사·항목·귀속월 내 지급 순번';
