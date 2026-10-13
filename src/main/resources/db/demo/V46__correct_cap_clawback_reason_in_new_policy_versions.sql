-- #478: ACTIVE 정책 자식은 수정하지 않는다. 각 현행 CAP 정책의 새 승인 버전으로 정정한다.
-- 기존 룰셋·귀속 스냅샷·검증 이력은 보존하며, 다음 재검증부터 새 버전을 조회한다.
SET search_path TO fgc, public;
CREATE TEMP TABLE cap478_versions(old_id bigint, new_id bigint) ON COMMIT DROP;
WITH created AS (
    INSERT INTO policy_version(policy_code,policy_name,policy_type,source_class,version_no,
        effective_from,effective_to,status,fee_regime_code,basic_document_version,regulation_refs,
        source_refs,created_by)
    SELECT pv.policy_code,pv.policy_name,pv.policy_type,pv.source_class,
        (SELECT MAX(v.version_no)+1 FROM policy_version v WHERE v.policy_code=pv.policy_code),
        pv.effective_from,pv.effective_to,'DRAFT',pv.fee_regime_code,pv.basic_document_version,
        pv.regulation_refs,pv.source_refs,(SELECT user_id FROM app_user WHERE login_id='settle01')
    FROM policy_version pv WHERE pv.policy_type='CAP_1200' AND pv.status='ACTIVE'
    RETURNING policy_version_id,policy_code
)
INSERT INTO cap478_versions
SELECT old.policy_version_id,new.policy_version_id FROM policy_version old
JOIN created new ON new.policy_code=old.policy_code
WHERE old.policy_type='CAP_1200' AND old.status='ACTIVE';
INSERT INTO cap_rule_set(policy_version_id,payment_stage,contract_date_from,contract_date_to,
 insurer_id,product_group_code,channel_code,first_year_months,premium_multiplier,
 compliance_deduction_pct,refund_addition_condition,warning_usage_pct)
SELECT m.new_id,r.payment_stage,r.contract_date_from,r.contract_date_to,r.insurer_id,
 r.product_group_code,r.channel_code,r.first_year_months,r.premium_multiplier,
 r.compliance_deduction_pct,r.refund_addition_condition,r.warning_usage_pct
FROM cap_rule_set r JOIN cap478_versions m ON m.old_id=r.policy_version_id;
INSERT INTO cap_rule_item(cap_rule_set_id,commission_item_id,inclusion_status,exclusion_type,
 evidence_required_yn,attribution_method,allocation_policy_id,decision_reason)
SELECT new.cap_rule_set_id,i.commission_item_id,i.inclusion_status,i.exclusion_type,
 i.evidence_required_yn,i.attribution_method,i.allocation_policy_id,
 CASE WHEN ci.item_code='CLAWBACK'
 THEN '환수는 1,200% 지급 누계에서 차감하지 않으며 산입·한도 판정 대상이 아니다. 차익거래 계산에서만 차감한다.'
 ELSE i.decision_reason END
FROM cap478_versions m JOIN cap_rule_set old ON old.policy_version_id=m.old_id
JOIN cap_rule_item i ON i.cap_rule_set_id=old.cap_rule_set_id
JOIN commission_item ci ON ci.commission_item_id=i.commission_item_id
JOIN cap_rule_set new ON new.policy_version_id=m.new_id
 AND new.payment_stage=old.payment_stage
 AND new.contract_date_from=old.contract_date_from
 AND new.contract_date_to IS NOT DISTINCT FROM old.contract_date_to
 AND new.insurer_id IS NOT DISTINCT FROM old.insurer_id
 AND new.product_group_code IS NOT DISTINCT FROM old.product_group_code
 AND new.channel_code IS NOT DISTINCT FROM old.channel_code;
INSERT INTO policy_parameter(policy_version_id,parameter_key,parameter_value,description)
SELECT m.new_id,p.parameter_key,p.parameter_value,p.description
FROM policy_parameter p JOIN cap478_versions m ON m.old_id=p.policy_version_id;
UPDATE policy_version SET status='APPROVED', approved_at=TIMESTAMPTZ '2026-10-13 09:00+09',
 approved_by=(SELECT user_id FROM app_user WHERE login_id='gaadmin'),
 approval_evidence_ref='데모 승인 — #478 환수 gross 산입 교정, 운영 승인과 별개'
WHERE policy_version_id IN (SELECT new_id FROM cap478_versions) AND status='DRAFT';
UPDATE policy_version SET status='RETIRED'
WHERE policy_version_id IN (SELECT old_id FROM cap478_versions) AND status='ACTIVE';
UPDATE policy_version SET status='ACTIVE'
WHERE policy_version_id IN (SELECT new_id FROM cap478_versions) AND status='APPROVED';
