-- ============================================================================
-- V45. 정착지원금 귀속 사유 문구 교정 — 지급월 → 모집월 신계약 (#476, REG-20)
--
--   문제: V3·V6_1 의 원수사→GA 룰셋은 SETTLEMENT_SUPPORT 사유를 "지급월 신계약에 월 단위로
--   나눠 귀속한다"로 적었다. 규정(FAQ 2차 Q7-1)은 지급월이 아니라 모집월 기준이고,
--   settlement_month 는 실적(모집)월이다 (코드 CommissionPaymentServiceImpl 검증과 동일).
--
--   V3/V6_1 은 이미 적용된 마이그레이션이고, ACTIVE 룰셋 자식 행은 트리거
--   (trg_cap_rule_set_policy_lock)로 잠겨 있어 고칠 수 없다. V6_1 과 같은 방식으로 같은
--   계약일 범위를 갖는 새 정책 버전을 추가한다. findApplicableRuleSet 이
--   (구체성, contract_date_from DESC, cap_rule_set_id DESC) 순으로 고르므로 id 가 더 큰
--   새 룰셋이 V6_1 행을 대체한다. 기존 행은 감사 이력으로 남긴다.
--
--   확정된 시드 귀속행(V3 §19)의 allocation_basis_snapshot 은 확정 후 잠금 대상이라
--   건드리지 않는다. 두 계약 보험료가 같아 금액은 보험료 비례와 일치한다.
-- ============================================================================

-- 1) 새 정책 버전 2개 — DRAFT로 넣고 자식 행(cap_rule_set/cap_rule_item)을 채운 뒤 승격한다
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class,
                           version_no, effective_from, effective_to, status,
                           regulation_refs, source_refs, created_by)
SELECT v.code, v.name, v.ptype, v.src, 1, v.eff, v.eff_to, 'DRAFT', v.regs, v.srcs,
       (SELECT user_id FROM app_user WHERE login_id='settle01')
  FROM (VALUES
    ('REG-CAP-INS-2021-V3','원수사→GA 초년도 1,200% 룰셋(준법경영비 공제 시행 전, 보정판)',
     'CAP_1200','REGULATORY', DATE '2021-01-01', DATE '2026-12-31',
     ARRAY['REG-08','REG-11'], ARRAY['보험업감독규정 제4-32조']),
    ('REG-CAP-INS-2027-V2','원수사→GA 초년도 1,200% 룰셋(준법경영비 3% 공제 포함)',
     'CAP_1200','REGULATORY', DATE '2027-01-01', NULL::date,
     ARRAY['REG-08','REG-10','REG-11'], ARRAY['보험업감독규정 제4-32조'])
  ) AS v(code, name, ptype, src, eff, eff_to, regs, srcs)
ON CONFLICT (policy_code, version_no) DO NOTHING;

-- 2) 룰셋 — 기존 REG-CAP-INS-2026-V1(2021~, 공제 3%)는 그대로 두고(V6_1 의 2021-V2·2027-V1 도 이력으로 남김), 아래 두 행이
--    계약 체결일 범위별로 그 행을 대체한다(머리말 참고).
INSERT INTO cap_rule_set(policy_version_id, payment_stage, contract_date_from, contract_date_to,
                         first_year_months, premium_multiplier,
                         compliance_deduction_pct, refund_addition_condition, warning_usage_pct)
SELECT pv.policy_version_id, 'INSURER_TO_GA', v.date_from, v.date_to, 12, 12.0000,
       v.deduction, 'STANDARD_DEDUCTION_80', 90.0000
  FROM (VALUES
    ('REG-CAP-INS-2021-V3', DATE '2021-01-01', DATE '2026-12-31', 0.0000),
    ('REG-CAP-INS-2027-V2', DATE '2027-01-01', NULL::date,        3.0000)
  ) AS v(policy_code, date_from, date_to, deduction)
  JOIN policy_version pv ON pv.policy_code = v.policy_code
 WHERE pv.status='DRAFT'
ON CONFLICT DO NOTHING;

-- 3) 항목별 산입·제외 분류 — V3 원본과 동일한 분류를 새 룰셋 두 개에도 그대로 적용한다.
--    (CROSS JOIN이 status='DRAFT'인 policy_version에 걸린 cap_rule_set만 잡으므로,
--     이미 ACTIVE인 기존 룰셋에는 중복 삽입되지 않는다)
INSERT INTO cap_rule_item(cap_rule_set_id, commission_item_id, inclusion_status,
                          exclusion_type, evidence_required_yn, attribution_method,
                          allocation_policy_id, decision_reason)
SELECT crs.cap_rule_set_id, ci.commission_item_id, v.inclusion,
       v.excl_type, v.evidence_req, v.method,
       CASE WHEN v.method='APPROVED_ALLOCATION'
            THEN (SELECT allocation_policy_id FROM allocation_policy
                   WHERE allocation_code='GA-COMMON-PREMIUM-PROPORTIONAL')
            ELSE NULL END,
       v.reason
  FROM (VALUES
    ('BASE_COMMISSION',       'INCLUDED',       NULL,               false,'DIRECT',
     '모집의 대가로 계약에 직접 귀속된다'),
    ('MANAGEMENT_COMMISSION', 'INCLUDED',       NULL,               false,'DIRECT',
     '모집 조직 보상으로 계약에 귀속된다'),
    ('INCENTIVE',             'INCLUDED',       NULL,               false,'DIRECT',
     '판매촉진의 대가이므로 수수료등에 포함된다'),
    ('SETTLEMENT_SUPPORT',    'INCLUDED',       NULL,               false,'SETTLEMENT_SUPPORT_MONTHLY',
     '경력설계사 정착지원금은 수수료등에 포함하며 모집월 신계약에 월 단위로 나눠 귀속·배분한다 (REG-20)'),
    ('COMMON_COST',           'INCLUDED',       NULL,               false,'APPROVED_ALLOCATION',
     '승인된 안분정책에 따라 계약별로 배부한다 (REG-06A·REG-06C)'),
    ('MAINTENANCE_COMMISSION','INCLUDED',       NULL,               false,'DIRECT',
     '초년도 구간에 지급된 유지수수료는 산입한다'),
    ('NEWCOMER_SUPPORT',      'EXCLUDED',       'NEWCOMER_SUPPORT', true, 'MANUAL_REVIEW',
     '직전 3년 모집경력이 없는 자에게 등록 후 1년 이내 지급한 활동지원 금액은 요건과 증빙을 갖추면 제외한다 (REG-21). 제외할 수 있다는 뜻이지 자동으로 빠진다는 뜻이 아니다'),
    ('ADJUSTMENT',            'REVIEW_REQUIRED',NULL,               false,'MANUAL_REVIEW',
     '조정 사유에 따라 산입 여부가 달라지므로 사람이 판단한다'),
    ('CLAWBACK',              'INCLUDED',       NULL,               false,'DIRECT',
     '환수는 차감 항목이므로 산입 누계에서 빼는 방향으로 반영한다')
  ) AS v(item_code, inclusion, excl_type, evidence_req, method, reason)
  JOIN commission_item ci ON ci.item_code = v.item_code
  CROSS JOIN cap_rule_set crs
  JOIN policy_version pvc ON pvc.policy_version_id = crs.policy_version_id
 WHERE pvc.status='DRAFT'
   AND pvc.policy_code IN ('REG-CAP-INS-2021-V3','REG-CAP-INS-2027-V2')
ON CONFLICT DO NOTHING;

-- 4) 승격 — DRAFT → APPROVED → ACTIVE (V3와 동일한 2단계 패턴)
UPDATE policy_version pv
   SET status='APPROVED',
       approved_at = TIMESTAMPTZ '2026-10-13 09:00:00+09',
       approved_by = (SELECT user_id FROM app_user WHERE login_id='gaadmin'),
       approval_evidence_ref = '정착지원금 귀속 사유 모집월 교정 (#476)'
 WHERE pv.status='DRAFT'
   AND pv.policy_code IN ('REG-CAP-INS-2021-V3','REG-CAP-INS-2027-V2');

UPDATE policy_version pv
   SET status='ACTIVE'
 WHERE pv.status='APPROVED'
   AND pv.policy_code IN ('REG-CAP-INS-2021-V3','REG-CAP-INS-2027-V2');
