-- ============================================================================
-- V6_1. 원수사→GA 1,200% 룰셋 보정 — 준법경영비 3% 공제 시행일(REG-10)
--
--   V6 다음 번호를 V6_1로 잡은 이유: V6__integrity_hardening.sql(팀원 작업)이 먼저
--   병합돼 있어 번호 충돌을 피했다(V2_1__policy_parameter_and_fixes.sql과 같은 서브버전
--   명명 관례). 이 파일은 db/demo 시드 데이터 보정이라 db/migration의 V6/V7과는 무관하게
--   독립적으로 적용된다 — 실제로 두 관계에서 상호 의존하는 테이블·트리거가 없음을 확인했다.
--
--   문제: V3에 이미 적용(배포)된 REG-CAP-INS-2026-V1(policy_version ACTIVE, 2026-08-05
--   커밋 8ede1b1로 develop에 병합)이 준법경영비 3% 공제(REG-10, 제4-32조제14항)를
--   2021-01-01부터 적용하고 있다. 규제조문표 v0.2.1 시행 타임라인 기준 REG-10의 실제
--   시행일은 일반채널 2027.1.1이다 — REG-08(1,200%룰 자체, 제5항)의 보험사 2021.1.1
--   시행일과는 별개 조항이다(코드리뷰 지적, PR #18).
--
--   V3/V4는 이미 적용된 versioned migration이라 직접 고치면 기존 DB의 checksum이
--   깨진다(AGENTS.md: "이미 develop/main에 병합된 마이그레이션 파일은 절대 수정하지
--   않는다"). cap_rule_set/cap_rule_item은 정책이 ACTIVE가 되는 순간 트리거
--   (trg_cap_rule_set_policy_lock)가 수정을 막아 기존 행 자체를 고칠 수도 없다.
--
--   해결: 기존 REG-CAP-INS-2026-V1 행은 그대로 두고(과거 이력으로 남긴다), 계약
--   체결일 기준으로 그 행을 대체하는 새 룰셋 두 개를 추가한다.
--     - REG-CAP-INS-2021-V2 : 2021.1.1~2026.12.31, 공제 0%(REG-10 시행 전)
--     - REG-CAP-INS-2027-V1 : 2027.1.1~,          공제 3%(REG-10 시행)
--   CapRuleMapper.findApplicableRuleSet은 (구체성, contract_date_from DESC,
--   cap_rule_set_id DESC) 순으로 하나를 고른다. 2021~2026 계약은 새 2021-V2(같은
--   contract_date_from이지만 cap_rule_set_id가 더 큼)가 기존 행을 이긴다. 2027년 이후
--   계약은 새 2027-V1(contract_date_from이 더 늦음)이 이긴다. 기존 행은 더 이상
--   선택되지 않지만 물리적으로는 남아 감사 이력이 보존된다 — cap_check가 append-only인
--   것과 같은 설계다.
--
--   TM 채널(REG-10 시행일 2028.1.1)은 이번 보정 범위에 포함하지 않는다 — 현재 시드
--   데이터는 TM 계약을 이 시나리오에서 다루지 않기로 했다.
-- ============================================================================

-- 1) 새 정책 버전 2개 — DRAFT로 넣고 자식 행(cap_rule_set/cap_rule_item)을 채운 뒤 승격한다
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class,
                           version_no, effective_from, effective_to, status,
                           regulation_refs, source_refs, created_by)
SELECT v.code, v.name, v.ptype, v.src, 1, v.eff, v.eff_to, 'DRAFT', v.regs, v.srcs,
       (SELECT user_id FROM app_user WHERE login_id='settle01')
  FROM (VALUES
    ('REG-CAP-INS-2021-V2','원수사→GA 초년도 1,200% 룰셋(준법경영비 공제 시행 전, 보정판)',
     'CAP_1200','REGULATORY', DATE '2021-01-01', DATE '2026-12-31',
     ARRAY['REG-08','REG-11'], ARRAY['보험업감독규정 제4-32조']),
    ('REG-CAP-INS-2027-V1','원수사→GA 초년도 1,200% 룰셋(준법경영비 3% 공제 포함)',
     'CAP_1200','REGULATORY', DATE '2027-01-01', NULL::date,
     ARRAY['REG-08','REG-10','REG-11'], ARRAY['보험업감독규정 제4-32조'])
  ) AS v(code, name, ptype, src, eff, eff_to, regs, srcs)
ON CONFLICT (policy_code, version_no) DO NOTHING;

-- 2) 룰셋 — 기존 REG-CAP-INS-2026-V1(2021~, 공제 3%)는 그대로 두고, 아래 두 행이
--    계약 체결일 범위별로 그 행을 대체한다(§0 설명 참고).
INSERT INTO cap_rule_set(policy_version_id, payment_stage, contract_date_from, contract_date_to,
                         first_year_months, premium_multiplier,
                         compliance_deduction_pct, refund_addition_condition, warning_usage_pct)
SELECT pv.policy_version_id, 'INSURER_TO_GA', v.date_from, v.date_to, 12, 12.0000,
       v.deduction, 'STANDARD_DEDUCTION_80', 90.0000
  FROM (VALUES
    ('REG-CAP-INS-2021-V2', DATE '2021-01-01', DATE '2026-12-31', 0.0000),
    ('REG-CAP-INS-2027-V1', DATE '2027-01-01', NULL::date,        3.0000)
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
     '경력설계사 정착지원금은 수수료등에 포함하며 지급월 신계약에 월 단위로 나눠 귀속한다 (REG-20)'),
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
   AND pvc.policy_code IN ('REG-CAP-INS-2021-V2','REG-CAP-INS-2027-V1')
ON CONFLICT DO NOTHING;

-- 4) 승격 — DRAFT → APPROVED → ACTIVE (V3와 동일한 2단계 패턴)
UPDATE policy_version pv
   SET status='APPROVED',
       approved_at = TIMESTAMPTZ '2026-08-06 09:00:00+09',
       approved_by = (SELECT user_id FROM app_user WHERE login_id='gaadmin'),
       approval_evidence_ref = 'REG-10 시행일 보정 (코드리뷰 PR #18 반영)'
 WHERE pv.status='DRAFT'
   AND pv.policy_code IN ('REG-CAP-INS-2021-V2','REG-CAP-INS-2027-V1');

UPDATE policy_version pv
   SET status='ACTIVE'
 WHERE pv.status='APPROVED'
   AND pv.policy_code IN ('REG-CAP-INS-2021-V2','REG-CAP-INS-2027-V1');
