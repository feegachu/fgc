-- #480: 데모 ARBITRAGE 정책만 새 버전으로 승계한다. 기존 승인 정책·계산 이력은 보존한다.
-- 운영 정책에는 같은 두 파라미터의 별도 등록·승인이 필요하다. 정밀 경계는 프로젝트 정책값이다.
SET search_path TO fgc, public;
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class, version_no,
                           effective_from, effective_to, status, regulation_refs, source_refs, created_by)
SELECT policy_code, policy_name, policy_type, source_class, 2,
       effective_from, effective_to, 'DRAFT', regulation_refs, source_refs, created_by
FROM policy_version WHERE policy_code='ARB-APPLICABILITY-2026' AND version_no=1 AND status='ACTIVE';
INSERT INTO policy_parameter(policy_version_id, parameter_key, parameter_value, description)
SELECT next.policy_version_id, p.parameter_key, p.parameter_value, p.description
FROM policy_version old JOIN policy_parameter p USING(policy_version_id)
JOIN policy_version next ON next.policy_code=old.policy_code AND next.version_no=2 AND next.status='DRAFT'
WHERE old.policy_code='ARB-APPLICABILITY-2026' AND old.version_no=1;
INSERT INTO policy_parameter(policy_version_id, parameter_key, parameter_value, description)
SELECT pv.policy_version_id, v.key, v.value, v.description
FROM policy_version pv CROSS JOIN (VALUES
 ('ARBITRAGE_REFUND_ADDITION_MONTHS', '36'::jsonb, '해약환급금 합산 기간: 계약일로부터 경과 개월'),
 ('ARBITRAGE_REFUND_ADDITION_BOUNDARY_INCLUSIVE', 'false'::jsonb, '기간 경계일 포함 여부: 데모는 3주년 당일 제외, 정밀 경계는 프로젝트 해석')
) v(key,value,description)
WHERE pv.policy_code='ARB-APPLICABILITY-2026' AND pv.version_no=2 AND pv.status='DRAFT';
UPDATE policy_version SET status='APPROVED', approved_at=clock_timestamp(),
 approved_by=(SELECT user_id FROM app_user WHERE login_id='gaadmin'),
 approval_evidence_ref='데모 승인 — #480, 운영 정책 승인을 대체하지 않음'
WHERE policy_code='ARB-APPLICABILITY-2026' AND version_no=2 AND status='DRAFT';
UPDATE policy_version SET status='RETIRED'
WHERE policy_code='ARB-APPLICABILITY-2026' AND version_no=1 AND status='ACTIVE'
 AND EXISTS(SELECT 1 FROM policy_version p WHERE p.policy_code='ARB-APPLICABILITY-2026' AND p.version_no=2 AND p.status='APPROVED');
UPDATE policy_version SET status='ACTIVE'
WHERE policy_code='ARB-APPLICABILITY-2026' AND version_no=2 AND status='APPROVED';
