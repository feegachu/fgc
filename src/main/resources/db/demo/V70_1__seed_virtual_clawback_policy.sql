-- #669 D-02: 데모용 가상 GA 환수율표.
-- 업무 규칙 아님 — 가상 GA 정책으로 정함(운영정책서 제48조의3). 실제 환수율은 보험사↔GA 위탁계약,
-- GA↔설계사 위촉계약의 사적 약정이며(REG-14) 운영 환경에는 별도 등록·승인이 필요하다.
-- 보험회사·판매버전을 비워 전체 계약에 적용하고, 수수료 항목별로 경과월 구간을 빈틈없이 덮는다.
-- 24개월 이후 0%는 "환수 없음"을 명시한 실제 0원이며, 규칙이 없는 항목(유지·관리자·정착지원 등)과 다르다.
SET search_path TO fgc, public;

INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class, version_no,
                           effective_from, effective_to, status, regulation_refs, source_refs, created_by)
VALUES ('CLAW-GA-VIRTUAL-2026', '가상 GA 표준 환수율(데모)', 'CLAWBACK', 'GA_POLICY', 1,
        DATE '2026-01-01', NULL, 'DRAFT', ARRAY['REG-13', 'REG-14'], ARRAY['D-02', 'FGC 운영정책서 제48조의3'],
        (SELECT user_id FROM app_user WHERE login_id = 'settle01'));

INSERT INTO clawback_rule(policy_version_id, insurer_id, product_offering_id, commission_item_id,
                          elapsed_month_from, elapsed_month_to, clawback_basis_code, clawback_rate_pct)
SELECT pv.policy_version_id, NULL, NULL, item.commission_item_id, band.month_from, band.month_to,
       'PAID_ATTRIBUTED_AMOUNT', band.rate_pct
  FROM policy_version pv
  JOIN commission_item item ON item.item_code IN ('BASE_COMMISSION', 'INCENTIVE')
 CROSS JOIN (VALUES (0, 11, 100.000000), (12, 23, 50.000000), (24, 1199, 0.000000))
       AS band(month_from, month_to, rate_pct)
 WHERE pv.policy_code = 'CLAW-GA-VIRTUAL-2026' AND pv.version_no = 1;

-- 정책 승인 흐름(DRAFT → APPROVED → ACTIVE)을 그대로 따른다. 작성자와 승인자는 다르다.
UPDATE policy_version SET status = 'APPROVED', approved_at = clock_timestamp(),
       approved_by = (SELECT user_id FROM app_user WHERE login_id = 'gaadmin')
 WHERE policy_code = 'CLAW-GA-VIRTUAL-2026' AND version_no = 1 AND status = 'DRAFT';
UPDATE policy_version SET status = 'ACTIVE'
 WHERE policy_code = 'CLAW-GA-VIRTUAL-2026' AND version_no = 1 AND status = 'APPROVED';
