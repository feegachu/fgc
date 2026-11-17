ALTER TABLE fgc.insurance_contract
    ADD COLUMN IF NOT EXISTS workflow_demo_excluded boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN fgc.insurance_contract.workflow_demo_excluded IS
    '전체 업무 가이드 시연 계약. 시연 환경에서만 지정하며 월 통합검증·대사 대상에서 제외한다.';

CREATE OR REPLACE FUNCTION fgc.is_workflow_demo_contract(p_contract_id bigint)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM fgc.insurance_contract
                    WHERE contract_id = p_contract_id AND workflow_demo_excluded)
$$;
