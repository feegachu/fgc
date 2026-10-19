-- #525: 정착지원금 사유를 "지급월 신계약"에서 "모집월 신계약"으로 정정한다 (REG-20, SRC-025 FAQ 2차 Q7-1).
-- V46 과 같은 방식이다. ACTIVE 정책 자식은 수정하지 않고, 옛 문구가 남은 현행 CAP 정책만 새 승인 버전으로 정정한다.
-- 기존 룰셋·귀속 스냅샷(transaction_attribution.cap_rule_item_id)·검증 이력은 보존하며, 다음 판정부터 새 버전을 조회한다.
SET search_path TO fgc, public;
-- 중복 활성 버전은 임의 선택/복제하지 않는다. Flyway 트랜잭션 전체를 중단한다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM policy_version WHERE policy_type='CAP_1200' AND status='ACTIVE'
               GROUP BY policy_code HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION '#525: duplicate ACTIVE CAP policy versions; resolve policy approval before migration';
    END IF;
END;
$$;
CREATE TEMP TABLE cap525_versions(old_id bigint, new_id bigint) ON COMMIT DROP;
WITH targets AS (
    SELECT pv.* FROM policy_version pv
    WHERE pv.policy_type='CAP_1200' AND pv.status='ACTIVE'
      AND EXISTS (SELECT 1 FROM cap_rule_set r
                  JOIN cap_rule_item i ON i.cap_rule_set_id=r.cap_rule_set_id
                  JOIN commission_item ci ON ci.commission_item_id=i.commission_item_id
                  WHERE r.policy_version_id=pv.policy_version_id AND ci.item_code='SETTLEMENT_SUPPORT'
                    AND i.decision_reason LIKE '%지급월 신계약%')
), created AS (
    INSERT INTO policy_version(policy_code,policy_name,policy_type,source_class,version_no,
        effective_from,effective_to,status,fee_regime_code,basic_document_version,regulation_refs,
        source_refs,created_by)
    SELECT t.policy_code,t.policy_name,t.policy_type,t.source_class,
        (SELECT MAX(v.version_no)+1 FROM policy_version v WHERE v.policy_code=t.policy_code),
        t.effective_from,t.effective_to,'DRAFT',t.fee_regime_code,t.basic_document_version,
        t.regulation_refs,t.source_refs,(SELECT user_id FROM app_user WHERE login_id='settle01')
    FROM targets t
    RETURNING policy_version_id,policy_code
)
INSERT INTO cap525_versions
SELECT t.policy_version_id,new.policy_version_id FROM targets t
JOIN created new ON new.policy_code=t.policy_code;
INSERT INTO cap_rule_set(policy_version_id,payment_stage,contract_date_from,contract_date_to,
 insurer_id,product_group_code,channel_code,first_year_months,premium_multiplier,
 compliance_deduction_pct,refund_addition_condition,warning_usage_pct)
SELECT m.new_id,r.payment_stage,r.contract_date_from,r.contract_date_to,r.insurer_id,
 r.product_group_code,r.channel_code,r.first_year_months,r.premium_multiplier,
 r.compliance_deduction_pct,r.refund_addition_condition,r.warning_usage_pct
FROM cap_rule_set r JOIN cap525_versions m ON m.old_id=r.policy_version_id
ORDER BY r.cap_rule_set_id;
INSERT INTO cap_rule_item(cap_rule_set_id,commission_item_id,inclusion_status,exclusion_type,
 evidence_required_yn,attribution_method,allocation_policy_id,decision_reason)
SELECT new.cap_rule_set_id,i.commission_item_id,i.inclusion_status,i.exclusion_type,
 i.evidence_required_yn,i.attribution_method,i.allocation_policy_id,
 CASE WHEN ci.item_code='SETTLEMENT_SUPPORT' AND i.decision_reason LIKE '%지급월 신계약%'
 THEN '경력설계사 정착지원금은 수수료등에 포함하며 모집월 신계약에 월 단위로 귀속·배분한다 (REG-20)'
 ELSE i.decision_reason END
FROM cap525_versions m JOIN cap_rule_set old ON old.policy_version_id=m.old_id
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
FROM policy_parameter p JOIN cap525_versions m ON m.old_id=p.policy_version_id;
UPDATE policy_version SET status='APPROVED', approved_at=TIMESTAMPTZ '2026-10-14 09:00+09',
 approved_by=(SELECT user_id FROM app_user WHERE login_id='gaadmin'),
 approval_evidence_ref='데모 승인 — #525 정착지원금 모집월 귀속 사유 교정, 운영 승인과 별개'
WHERE policy_version_id IN (SELECT new_id FROM cap525_versions) AND status='DRAFT';
UPDATE policy_version SET status='RETIRED'
WHERE policy_version_id IN (SELECT old_id FROM cap525_versions) AND status='ACTIVE';
UPDATE policy_version SET status='ACTIVE'
WHERE policy_version_id IN (SELECT new_id FROM cap525_versions) AND status='APPROVED';
