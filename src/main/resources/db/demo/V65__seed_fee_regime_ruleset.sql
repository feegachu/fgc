-- #538 / FUN-014-05: 4년·7년 분급 규제 상한 룰셋 데모 시드 (REGULATORY).
-- 근거: 운영정책서 제46·47조, 시드명세 §34·35, REG-02·03·04·05·19, SRC-003·022.
-- GA 지급률(GA_POLICY)·계약체결비용 기준(policy_parameter)은 #649·#651에서 넣는다. 여기에는 규제 상한만 둔다.
-- 7년 판매버전(2029-SEVEN-YEAR)은 적재하지 않는다(#650에서 결정). 7년 룰셋은 판매버전과 무관하게 정책 아래에 둔다.
SET search_path TO fgc, public;

-- ① 정책버전 DRAFT 생성 (작성자 settle01)
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class, version_no,
                           effective_from, effective_to, status, fee_regime_code,
                           regulation_refs, source_refs, approval_evidence_ref, created_by)
SELECT v.code, v.name, v.ptype, 'REGULATORY', 1,
       v.eff_from, NULL::date, 'DRAFT', v.regime,
       v.reg_refs, v.src_refs,
       '데모 승인 — #538, 운영 정책 승인을 대체하지 않음',
       (SELECT user_id FROM app_user WHERE login_id = 'settle01')
  FROM (VALUES
    ('REG-FOUR-YEAR-2027-V1', '4년 분급 규제 상한(2027 특례기간)', 'FOUR_YEAR_COMMISSION', 'FOUR_YEAR_2027',
     DATE '2027-01-01', ARRAY['REG-02','REG-03','REG-04','REG-19'],
     ARRAY['SRC-003','SRC-022','docs/FGC_가상_GA_운영정책서_v1_0.md#제46조']),
    ('REG-SEVEN-YEAR-2029-V1', '7년 분급 규제 상한(2029 이후)', 'SEVEN_YEAR_COMMISSION', 'SEVEN_YEAR_2029',
     DATE '2029-01-01', ARRAY['REG-02','REG-03','REG-05','REG-19'],
     ARRAY['SRC-022','docs/FGC_가상_GA_운영정책서_v1_0.md#제47조'])
  ) AS v(code, name, ptype, regime, eff_from, reg_refs, src_refs)
 WHERE NOT EXISTS (SELECT 1 FROM policy_version p WHERE p.policy_code = v.code AND p.version_no = 1);

-- ② 룰셋 (4년: 판매개시일 2027-01-01~2028-12-31·기초서류 BD-2027-01, 7년: 2029-01-01부터·기초서류 버전 무관)
--    61차월 시작은 법문이 정하지 않았다 — 운영정책서 제47조의 PROJECT_ASSUMPTION
INSERT INTO fee_regime_ruleset(policy_version_id, fee_regime_code, sales_start_date_from, sales_start_date_to,
                               basic_document_version, max_installments,
                               long_term_maintenance_from, long_term_maintenance_to, source_refs)
SELECT pv.policy_version_id, v.regime, v.s_from, v.s_to, v.bd_ver, v.max_inst, v.lt_from, v.lt_to, v.refs
  FROM (VALUES
    ('REG-FOUR-YEAR-2027-V1', 'FOUR_YEAR_2027', DATE '2027-01-01', DATE '2028-12-31', 'BD-2027-01',
     48, NULL::int, NULL::int,
     ARRAY['REG-04','부칙 제3조①','SRC-022','운영정책서 제46조: 지급 1~48차월(최초 지급회차는 PROJECT_ASSUMPTION)']),
    ('REG-SEVEN-YEAR-2029-V1', 'SEVEN_YEAR_2029', DATE '2029-01-01', NULL::date, NULL::varchar,
     84, 61, 84,
     ARRAY['REG-03','REG-05','SRC-022','운영정책서 제47조: 장기유지 61~84차월은 PROJECT_ASSUMPTION(5년 이상 유지 요건을 회차로 옮긴 시드값)'])
  ) AS v(code, regime, s_from, s_to, bd_ver, max_inst, lt_from, lt_to, refs)
  JOIN policy_version pv ON pv.policy_code = v.code AND pv.version_no = 1 AND pv.status = 'DRAFT'
 WHERE NOT EXISTS (SELECT 1 FROM fee_regime_ruleset r WHERE r.policy_version_id = pv.policy_version_id);

-- ③ 한도 행. 한도율은 계약체결비용 대비 %이며 '계약체결비용 이내'는 100으로 옮긴다.
INSERT INTO fee_regime_ruleset_limit(fee_regime_ruleset_id, limit_code, limit_pct)
SELECT r.fee_regime_ruleset_id, v.limit_code, v.limit_pct
  FROM (VALUES
    ('REG-FOUR-YEAR-2027-V1', 'UPFRONT_TOTAL',        100.0),
    ('REG-FOUR-YEAR-2027-V1', 'MAINTENANCE_MONTHLY',    1.5),
    ('REG-FOUR-YEAR-2027-V1', 'MAINTENANCE_TOTAL',    100.0),
    ('REG-SEVEN-YEAR-2029-V1', 'UPFRONT_TOTAL',       100.0),
    ('REG-SEVEN-YEAR-2029-V1', 'MAINTENANCE_MONTHLY',   0.8),
    ('REG-SEVEN-YEAR-2029-V1', 'MAINTENANCE_TOTAL',   100.0),
    ('REG-SEVEN-YEAR-2029-V1', 'LONG_TERM_MONTHLY',     0.4)
  ) AS v(code, limit_code, limit_pct)
  JOIN policy_version pv ON pv.policy_code = v.code AND pv.version_no = 1 AND pv.status = 'DRAFT'
  JOIN fee_regime_ruleset r ON r.policy_version_id = pv.policy_version_id
ON CONFLICT (fee_regime_ruleset_id, limit_code) DO NOTHING;

-- ④ DRAFT → APPROVED → ACTIVE (작성자 settle01 ≠ 승인자 gaadmin · REG-22)
UPDATE policy_version
   SET status = 'APPROVED', approved_at = TIMESTAMPTZ '2026-10-23 09:00:00+09',
       approved_by = (SELECT user_id FROM app_user WHERE login_id = 'gaadmin')
 WHERE policy_code IN ('REG-FOUR-YEAR-2027-V1', 'REG-SEVEN-YEAR-2029-V1')
   AND version_no = 1 AND status = 'DRAFT';
UPDATE policy_version SET status = 'ACTIVE'
 WHERE policy_code IN ('REG-FOUR-YEAR-2027-V1', 'REG-SEVEN-YEAR-2029-V1')
   AND version_no = 1 AND status = 'APPROVED';
