-- #651 FUN-062·ACQC-W01: 계약체결비용 한도 검증 결과를 요청 단위로 저장한다.
-- 왜: V1·V6의 표는 월 검증 Step을 전제로 실행과 금액을 필수로 뒀다. 그대로 쓰면 자료 부족을 0원으로 적어야 하고,
--     적용 대상이 아닌 상품을 구분할 수 없다(REG-19). 이 PR 전에는 이 두 표에 쓰는 코드가 없어 행이 없다.
SET search_path TO fgc, public;

-- 요청 검증은 월 검증 실행에 속하지 않는다(cap_check.validation_run_id와 같은 구조). 지급단계는 합치지 않는다(운영정책서 제17조·제46조).
-- 자료 없음은 0이 아니라 NULL이다(#482 cap_check와 같은 원칙).
ALTER TABLE acquisition_cost_check
    ALTER COLUMN validation_run_id DROP NOT NULL,
    ALTER COLUMN policy_version_id DROP NOT NULL,
    ALTER COLUMN upfront_limit_amount DROP NOT NULL,
    ALTER COLUMN upfront_total_amount DROP NOT NULL,
    ALTER COLUMN maintenance_limit_amount DROP NOT NULL,
    ALTER COLUMN maintenance_total_amount DROP NOT NULL,
    ALTER COLUMN indirect_support_amount DROP NOT NULL,
    ALTER COLUMN indirect_support_amount DROP DEFAULT,
    ADD COLUMN payment_stage varchar(20) NOT NULL CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC')),
    ADD COLUMN requested_by bigint REFERENCES app_user(user_id),
    ADD COLUMN idempotency_key varchar(100),
    ADD COLUMN request_hash varchar(64);

ALTER TABLE acquisition_cost_check DROP CONSTRAINT acquisition_cost_check_result_status_check;
ALTER TABLE acquisition_cost_check
    ADD CONSTRAINT acquisition_cost_check_result_status_check
        CHECK (result_status IN ('NORMAL','VIOLATION','REVIEW_REQUIRED','NOT_APPLICABLE')),
    -- 금액이 비었는데 '정상'이거나, 적용 대상이 아닌데 금액이 있는 결과를 저장할 수 없게 한다.
    ADD CONSTRAINT ck_acquisition_cost_check_shape CHECK (
        (result_status <> 'NORMAL' OR (policy_version_id IS NOT NULL
            AND upfront_limit_amount IS NOT NULL AND upfront_total_amount IS NOT NULL
            AND maintenance_limit_amount IS NOT NULL AND maintenance_total_amount IS NOT NULL
            AND indirect_support_amount IS NOT NULL))
        AND (result_status <> 'NOT_APPLICABLE' OR (upfront_limit_amount IS NULL AND upfront_total_amount IS NULL
            AND maintenance_limit_amount IS NULL AND maintenance_total_amount IS NULL
            AND indirect_support_amount IS NULL))
    );

ALTER TABLE acquisition_cost_check_detail
    ALTER COLUMN actual_amount DROP NOT NULL,
    ALTER COLUMN limit_amount DROP NOT NULL;

CREATE UNIQUE INDEX uq_acquisition_cost_check_idempotency
    ON acquisition_cost_check (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_acquisition_cost_check_contract
    ON acquisition_cost_check (contract_id, payment_stage, acquisition_cost_check_id DESC);

-- 실행에 묶이지 않은 행은 V6의 확정 잠금 트리거가 지키지 못한다. 검증 이력은 고치지 않고 새 행으로 쌓는다.
CREATE TRIGGER trg_acquisition_cost_check_append_only
BEFORE UPDATE OR DELETE ON acquisition_cost_check
FOR EACH ROW EXECUTE FUNCTION reject_update_delete();
CREATE TRIGGER trg_acquisition_cost_check_detail_append_only
BEFORE UPDATE OR DELETE ON acquisition_cost_check_detail
FOR EACH ROW EXECUTE FUNCTION reject_update_delete();

COMMENT ON COLUMN acquisition_cost_check.validation_run_id IS '월 검증 실행. FUN-062 요청 검증은 NULL(cap_check와 같은 구조)';
COMMENT ON COLUMN acquisition_cost_check.policy_version_id IS '한도율을 읽은 룰셋의 정책버전. 룰셋을 고르지 못하면 NULL';
COMMENT ON COLUMN acquisition_cost_check.payment_stage IS '지급단계. 두 단계의 한도 검증을 합치지 않는다(운영정책서 제46조)';
COMMENT ON COLUMN acquisition_cost_check.indirect_support_amount IS '유지관리 총액에 포함된 간접지원경비(REG-06B). NULL은 자료 없음, 0은 실제 0원';
COMMENT ON COLUMN acquisition_cost_check.idempotency_key IS '검증 요청 Idempotency-Key. 같은 키·같은 사용자·같은 요청은 최초 결과를 돌려준다';
COMMENT ON COLUMN acquisition_cost_check.request_hash IS 'contractId·paymentStage의 SHA-256';
