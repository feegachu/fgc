-- 로컬 시연 전용: 기존 가상 환수율을 관리자수수료에도 적용하는 새 가상 정책판.
-- 운영 환경의 실제 위촉계약이나 승인된 정책을 수정하는 스크립트가 아니다.
BEGIN;
SET LOCAL search_path TO fgc, public;

DO $$
DECLARE
    old_policy bigint;
    new_policy bigint;
    approver bigint;
BEGIN
    SELECT policy_version_id, approved_by INTO old_policy, approver FROM policy_version
      WHERE policy_code='CLAW-GA-VIRTUAL-2026' AND version_no=1 AND status='ACTIVE';
    IF old_policy IS NULL THEN
        IF EXISTS (SELECT 1 FROM policy_version WHERE policy_code='CLAW-GA-VIRTUAL-2026' AND version_no=2 AND status='ACTIVE') THEN RETURN; END IF;
        RAISE EXCEPTION '활성 시연용 환수 정책이 없습니다';
    END IF;
    INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class, version_no, effective_from,
        effective_to, status, regulation_refs, source_refs, created_by)
    SELECT policy_code, '가상 GA 표준 환수율(로컬 데모)', policy_type, source_class, 2, effective_from,
        effective_to, 'DRAFT', regulation_refs, source_refs, created_by
    FROM policy_version WHERE policy_version_id=old_policy RETURNING policy_version_id INTO new_policy;

    INSERT INTO clawback_rule(policy_version_id, insurer_id, product_offering_id, commission_item_id,
        elapsed_month_from, elapsed_month_to, clawback_basis_code, clawback_rate_pct)
    SELECT new_policy, insurer_id, product_offering_id, commission_item_id, elapsed_month_from, elapsed_month_to,
        clawback_basis_code, clawback_rate_pct FROM clawback_rule WHERE policy_version_id=old_policy
    UNION ALL
    SELECT new_policy, r.insurer_id, r.product_offering_id, manager.commission_item_id, r.elapsed_month_from,
        r.elapsed_month_to, r.clawback_basis_code, r.clawback_rate_pct
    FROM clawback_rule r JOIN commission_item base USING(commission_item_id)
    CROSS JOIN commission_item manager
    WHERE r.policy_version_id=old_policy AND base.item_code='BASE_COMMISSION' AND manager.item_code='MANAGEMENT_COMMISSION';

    UPDATE policy_version SET status='RETIRED' WHERE policy_version_id=old_policy;
    UPDATE policy_version SET status='APPROVED', approved_by=approver, approved_at=clock_timestamp() WHERE policy_version_id=new_policy;
    UPDATE policy_version SET status='ACTIVE' WHERE policy_version_id=new_policy;
END $$;
COMMIT;
