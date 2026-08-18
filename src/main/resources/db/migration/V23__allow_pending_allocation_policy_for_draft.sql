-- 승인배부 귀속은 원칙적으로 승인 배부정책을 참조해야 한다.
-- 다만 정책 자동 조회가 실패한 지급 건도 DRAFT로 보존해야 하므로,
-- 서비스가 정책 조회 보류를 스냅샷에 명시한 경우에만 NULL을 허용한다.
-- 확정 단계는 TRAN-007 정책 버전 게이트로 반드시 차단한다.
ALTER TABLE fgc.transaction_attribution
    DROP CONSTRAINT ck_attribution_allocation;

ALTER TABLE fgc.transaction_attribution
    ADD CONSTRAINT ck_attribution_allocation CHECK (
        attribution_method <> 'APPROVED_ALLOCATION'
        OR allocation_policy_id IS NOT NULL
        OR allocation_basis_snapshot @> '{"policyVersionResolutionPending": true}'::jsonb
    );
