-- ============================================================================
-- V42: STD-SAV-A 예상 해약환급률표 시드 (#332)
--
-- 왜: V4 가 만든 STD-SAV-A(가상 연금저축보험, FGL04) 판매버전은 CONT-W03 에서
--     선택 가능하지만 환급률표가 없어, 등록한 계약이 월 통합검증 대상 선별에서
--     "예상 해약환급률표 없음 → 검토필요" 로 빠진다. 발표 시연 컷③→⑧ 이음매가
--     이 상품을 고르면 끊어진다. 경고 토스트도 없다(저해지형이 아니라서).
--
-- 범위: STD-SAV-A(FACE_TO_FACE)만 보강한다.
--     STD-TERM-A 의 TM 채널은 시드데이터 명세서 §11 이 REVIEW_REQUIRED 를
--     경계시험 기대값으로 명시하므로 **의도적으로 추가하지 않는다.**
--
-- 방법: 기존 INS-REFUND-2026-V1 은 ACTIVE 라 guard_policy_child_mutation 트리거가
--     자식 행 INSERT 를 거부한다. 명세 §17 "변경본은 새 정책버전과 새 헤더로 적재"
--     에 따라 새 정책버전을 DRAFT 로 만들어 적재 후 두 단계로 승격한다 (V3 §13 선례).
--     환급률표 해석은 표별 policy_version_id 로 ACTIVE 를 확인하므로(uq_refund_table_scope,
--     ProductMapper·ValidationTargetSelectionMapper) 기존 4개 표에는 영향이 없다.
-- ============================================================================

-- ① 새 정책버전 DRAFT 생성 (REG-23: 36개월 표·상품코드 일치)
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class,
                           version_no, effective_from, effective_to, status,
                           regulation_refs, source_refs, approval_evidence_ref, created_by)
SELECT 'INS-REFUND-2026-SAV-V1','예상 해약환급률표(연금저축 보강)','REFUND_RATE','INSURER_RULE',
       1, DATE '2026-01-01', NULL,'DRAFT',
       ARRAY['REG-23'],
       ARRAY['원수사 예상 해약환급률표 2026'],
       'PROJECT_ASSUMPTION — 합성값, #332 시연 커버리지 보강',
       (SELECT user_id FROM app_user WHERE login_id='settle01')
 WHERE NOT EXISTS (SELECT 1 FROM policy_version
                    WHERE policy_code='INS-REFUND-2026-SAV-V1' AND version_no=1);

-- ② 정책 파라미터 — V3 §12 의 INS-REFUND-2026-V1 과 같은 기준 금액 정의 (COR-004)
INSERT INTO policy_parameter(policy_version_id, parameter_key, parameter_value, description)
SELECT pv.policy_version_id,'REFUND_RATE_BASIS',
       '"MONTHLY_EQUIVALENT_FIRST_PREMIUM_X12"'::jsonb,
       '환급률(%)을 곱할 기준 금액. 월납환산 초회보험료 × 12 에 환급률을 곱해 예상 해약환급금을 구한다'
  FROM policy_version pv
 WHERE pv.policy_code='INS-REFUND-2026-SAV-V1' AND pv.status='DRAFT'
ON CONFLICT DO NOTHING;

-- ③ 환급률표 헤더 — 범위는 (보험사·상품·납입기간·채널·적용시작일)이다.
--    product_offering_id 는 참고 표시일 뿐 조회 키가 아니다 (V3 §11 경고 참조).
INSERT INTO refund_rate_table(policy_version_id, insurer_id, product_id, product_offering_id,
                              payment_term_months, channel_code, effective_from,
                              standard_deduction_80_yn, source_product_code, source_document_ref)
SELECT pv.policy_version_id, p.insurer_id, p.product_id, po.product_offering_id,
       120, po.channel_code, DATE '2026-01-01',
       po.standard_deduction_80_yn,               -- STD-SAV-A 는 false (V4 §0)
       p.standard_product_code, '원수사 예상 해약환급률표 2026 (합성값·#332 보강)'
  FROM product p
  JOIN product_offering po ON po.product_id = p.product_id
                          AND po.offering_version = '2026-CURRENT-A'
                          AND po.channel_code = 'FACE_TO_FACE'
  CROSS JOIN policy_version pv
 WHERE p.standard_product_code = 'STD-SAV-A'
   AND pv.policy_code='INS-REFUND-2026-SAV-V1' AND pv.status='DRAFT'
ON CONFLICT DO NOTHING;

-- ④ 상세 36행 — V3 §11 과 같은 합성 곡선(일반형 m×2.9, 12차월 34.8%).
--    0 이상 · 1~36차월 누락 없음 · 12차월 존재 (§17 조건).
INSERT INTO refund_rate_line(refund_rate_table_id, contract_month_no, refund_rate_pct)
SELECT rrt.refund_rate_table_id, m, (m * 2.9)::numeric(9,6)
  FROM refund_rate_table rrt
  JOIN policy_version pvr ON pvr.policy_version_id = rrt.policy_version_id
  CROSS JOIN generate_series(1, 36) AS m
 WHERE pvr.policy_code='INS-REFUND-2026-SAV-V1' AND pvr.status='DRAFT'
ON CONFLICT DO NOTHING;

-- ⑤ 승격 — DRAFT → APPROVED → ACTIVE (직행은 트리거가 막는다. 작성자/승인자 분리, REG-22)
--    재실행 시 status 조건에 걸리는 행이 없어 아무 것도 하지 않는다.
UPDATE policy_version pv
   SET status='APPROVED',
       approved_at = TIMESTAMPTZ '2026-06-30 09:00:00+09',
       approved_by = (SELECT user_id FROM app_user WHERE login_id='gaadmin'),
       approval_evidence_ref = '시드 승인 (GOLDEN 프로파일·#332)'
 WHERE pv.status='DRAFT'
   AND pv.policy_code='INS-REFUND-2026-SAV-V1';

UPDATE policy_version pv
   SET status='ACTIVE'
 WHERE pv.status='APPROVED'
   AND pv.policy_code='INS-REFUND-2026-SAV-V1';
