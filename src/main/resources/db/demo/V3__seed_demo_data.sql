-- ============================================================================
-- FGC 시연용 시드 데이터 (GOLDEN 프로파일 축소본)
-- 기준: 정규화 입력데이터·시드데이터 명세서 v1.0 / 스키마 v2.1.4
-- 작성 기준일: 2026-08-03
--
-- ── 이 파일이 넣는 것 / 넣지 않는 것 ────────────────────────────────────────
--   넣는다   : 기준정보(REFERENCE) · 정책(POLICY) · 입력사실(INPUT_FACT)
--   넣지않는다: 스케줄 · 1,200% 판정 · 차익거래 · 원장 · 대사 · 예외
--
--   왜 안 넣나 — 그건 **여러분이 만들 코드가 계산해서 만드는 것**입니다.
--   미리 넣어 두면 "엔진이 제대로 도는지"를 확인할 수 없습니다.
--   (시드명세서 §0 원칙 5)
--
-- ── 반드시 지킨 순서 (이걸 어기면 통째로 실패합니다) ──────────────────────
--   ① 정책은 DRAFT로 넣고 → 자식 행(요율·룰셋·파라미터)을 넣고 → APPROVED → ACTIVE
--      ACTIVE 정책의 자식 행은 DB 트리거가 INSERT/UPDATE를 거부합니다.
--   ② 지급 건은 DRAFT로 넣고 → 귀속행을 넣고 → CONFIRMED 로 변경
--      CONFIRMED 상태로 바로 INSERT 하면 트리거가 거부합니다.
--      귀속금액 합계 ≠ 지급액이거나 REVIEW_REQUIRED 귀속이 있으면 확정도 거부됩니다.
--
-- ── 다시 돌려도 안전합니다 ────────────────────────────────────────────────
--   모든 INSERT에 ON CONFLICT DO NOTHING, 모든 승격 UPDATE에 상태 조건이 있습니다.
--
-- ── 계약번호 읽는 법 ──────────────────────────────────────────────────────
--   FGC-{보험회사코드}-{계약월YYYYMM}-{일련번호4자리}
--   화면 목업(FGC_화면_MVP)의 C001~C006 은 와이어프레임용 짧은 별칭입니다.
--
--     별칭  contract_no                  시나리오
--     C001  FGC-FGL01-202607-0001        1,200% 정상   (확정 산입 890,000)
--     C002  FGC-FGL01-202607-0002        1,200% 주의   (확정 산입 1,100,000)
--     C003  FGC-FGL01-202607-0004        1,200% 주의   (확정 산입 1,200,000 · 잔여 0)
--     C004  FGC-FGL01-202607-0005    ★  1,200% 위반   (확정 1,000,000 + 미확정 250,000)
--     C005  FGC-FGN01-202605-0001        미납 → 실효 → 차익거래 검토대상
--     C006  FGC-FGL01-202607-0003        신인 설계사 · 검토필요
--
--   ★ C004 가 발표 ⑤번 컷입니다.
--     확정된 산입액이 1,000,000원이고, 시책 250,000원이 **작성중(DRAFT)** 으로 남아 있습니다.
--     이걸 확정하려고 하면 1,000,000 + 250,000 = 1,250,000 > 한도 1,200,000 이 되어
--     여러분이 만들 확정 게이트가 막아야 합니다. 사용률 104.166667%.
--     시드에 미리 "위반" 결과를 넣어두지 않은 이유가 이것입니다.
--
--   ※ C003·C004 는 일부러 **표준해약공제액 80% 이상 공제 상품이 아닌** STD-LIFE-A 를 씁니다.
--     80% 공제 상품이면 REG-08 에 따라 한도에 12차월 예상 해약환급금이 더해져
--     한도가 1,200,000원이 아니게 되고, 위 발표 논리가 깨지기 때문입니다.
--     환급금 가산 시연은 V4 의 FGC-FGL02-202601-0001 이 맡습니다.
--
-- ── 시연 계정 ─────────────────────────────────────────────────────────────
--   admin / gaadmin / settle01 / audit01   ·  비밀번호 4개 모두 fgc1234!
--   BCrypt(cost 10) 해시로 저장합니다. 평문은 DB에 없습니다.
--   Spring Security 설정에 BCryptPasswordEncoder 를 등록해야 로그인됩니다.
-- ============================================================================

SET search_path TO fgc, public;

-- ============================================================================
-- 1. 역할 · 사용자
--    ★ 정책보다 먼저 넣습니다. 정책의 작성자·승인자를 채우려면 사용자가 필요합니다.
-- ============================================================================
INSERT INTO app_role(role_code, role_name) VALUES
  ('SYSTEM_ADMIN','시스템관리자'),
  ('GA_ADMIN',    'GA관리자'),
  ('SETTLEMENT',  '정산담당자'),
  ('COMPLIANCE',  '준법·감사 조회자')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO app_user(login_id, password_hash, user_name, role_id)
SELECT v.login_id, v.pw, v.user_name, r.role_id
  FROM (VALUES
    ('admin',    '$2a$10$/jkdGqN06Aij0Lcj2pnaxOENaiDthnT/tTtnZmlQuqsQ0Ck5xSt/u','시스템관리자','SYSTEM_ADMIN'),
    ('gaadmin',  '$2a$10$8IQzJI8hXKAQaD785utM4.uk7/DRGgd79ASyOxX67303tJ5s5SsKO','GA관리자',    'GA_ADMIN'),
    ('settle01', '$2a$10$3VhRIpYzNmTU.gMF45BO5egd1RJWwb7lKDwbeR.jOzgTaO.Ks7iVi','정산담당자',  'SETTLEMENT'),
    ('audit01',  '$2a$10$dQZ427qhm6v6ZBxnioYy8u0NndbURDw5bWCbgl8MOmJrodPvdBhzC','준법감사자',  'COMPLIANCE')
  ) AS v(login_id, pw, user_name, role_code)
  JOIN app_role r ON r.role_code = v.role_code
ON CONFLICT (login_id) DO NOTHING;

-- ============================================================================
-- 1-1. V2 가 절차를 건너뛰고 만든 정책을 폐기하고 절차대로 다시 냅니다
--
--   V2 는 STATUS-MONTH-RULE-2026 을 status='ACTIVE' 로 바로 INSERT 했습니다.
--   상태전이 트리거가 UPDATE·DELETE 에만 걸려 있어 기술적으로는 들어갔지만,
--   우리가 정한 DRAFT → APPROVED → ACTIVE 절차를 건너뛴 것이고
--   created_by · approved_by 도 NULL 이라 "누가 만들고 누가 승인했나"가 없습니다.
--
--   ACTIVE 행은 created_by 조차 UPDATE 할 수 없으므로(불변 트리거),
--   되돌리는 유일한 방법은 폐기하고 새 버전을 절차대로 내는 것입니다.
--   실제 운영에서도 이렇게 합니다.
-- ============================================================================

-- ① v1 폐기 (ACTIVE → RETIRED 는 허용된 전이)
--    ★ status 만 바꿉니다. effective_to 는 불변 대상이라 건드리면 트리거가 막습니다.
--      유효기간이 v2 와 겹치지만 상태가 RETIRED 라 계산에 쓰이지 않습니다.
--      정책 조회 쿼리에는 반드시 status='ACTIVE' 조건을 함께 거세요.
UPDATE policy_version SET status='RETIRED'
 WHERE policy_code='STATUS-MONTH-RULE-2026' AND version_no=1 AND status='ACTIVE';

-- ② v2 를 DRAFT 로 생성
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class,
                           version_no, effective_from, effective_to, status,
                           regulation_refs, source_refs, approval_evidence_ref, created_by)
SELECT 'STATUS-MONTH-RULE-2026','월중 실효·부활 시 그 달 수수료 처리 기준',
       'SCHEDULE_ELIGIBILITY','PROJECT_ASSUMPTION',   -- 법정 기준 아님 → 화면에 주황 배지
       2, DATE '2026-01-01', NULL,'DRAFT',
       ARRAY['REG-03','REG-13'],
       ARRAY['docs/05_인터페이스정의서_v2_0.md#9-5'],
       'PROJECT_ASSUMPTION — 2026년 말 최종 FAQ 확정 시 재검증 필요',
       (SELECT user_id FROM app_user WHERE login_id='settle01')
 WHERE NOT EXISTS (SELECT 1 FROM policy_version
                    WHERE policy_code='STATUS-MONTH-RULE-2026' AND version_no=2);

-- ③ 파라미터 3개를 **실제 값으로** 저장 (DRAFT 상태에서만 들어갑니다)
INSERT INTO policy_parameter(policy_version_id, parameter_key, parameter_value, description)
SELECT pv.policy_version_id, v.k, v.val::jsonb, v.memo
  FROM (VALUES
    ('STATUS_MONTH_RULE',     '"PAY_IF_ACTIVE_ON_DUE_DATE"',
     '그 회차 지급예정일 시점에 계약이 유효했으면 지급한다. 대사 매칭키도 회차+지급예정일이라 검증과 대사가 같은 기준을 쓴다'),
    ('PRORATION_RULE',        '"NO_PRORATION"',
     '일할계산하지 않는다. 유지관리수수료는 매월 동일 금액이어야 한다 (REG-03)'),
    ('REVIVAL_BACKFILL_RULE', '"NO_RETROACTIVE"',
     '부활해도 실효 기간에 건너뛴 회차를 소급 지급하지 않는다. 그 기간에는 유지관리 서비스가 없었다 (REG-03)')
  ) AS v(k, val, memo)
  JOIN policy_version pv ON pv.policy_code='STATUS-MONTH-RULE-2026'
                        AND pv.version_no=2 AND pv.status='DRAFT'
ON CONFLICT (policy_version_id, parameter_key) DO NOTHING;

-- ④ DRAFT → APPROVED → ACTIVE (작성자 settle01 ≠ 승인자 gaadmin · REG-22)
UPDATE policy_version
   SET status='APPROVED', approved_at=TIMESTAMPTZ '2026-08-03 09:00:00+09',
       approved_by=(SELECT user_id FROM app_user WHERE login_id='gaadmin')
 WHERE policy_code='STATUS-MONTH-RULE-2026' AND version_no=2 AND status='DRAFT';

UPDATE policy_version SET status='ACTIVE'
 WHERE policy_code='STATUS-MONTH-RULE-2026' AND version_no=2 AND status='APPROVED';

-- ============================================================================
-- 2. 조직  (GA > 본사 > 본부 > 지사 > 팀)
--    상위조직을 먼저 넣어야 하므로 계층 순서대로 나눠 넣습니다.
-- ============================================================================
INSERT INTO organization(organization_code, organization_name, organization_type, effective_from)
VALUES ('FGC-GA','FGC금융서비스','GA', DATE '2026-01-01')
ON CONFLICT (organization_code) DO NOTHING;

INSERT INTO organization(organization_code, organization_name, organization_type, parent_id, effective_from)
SELECT v.code, v.name, v.otype, p.organization_id, DATE '2026-01-01'
  FROM (VALUES ('FGC-HQ','FGC 본사','HQ','FGC-GA')) AS v(code,name,otype,parent)
  JOIN organization p ON p.organization_code = v.parent
ON CONFLICT (organization_code) DO NOTHING;

INSERT INTO organization(organization_code, organization_name, organization_type, parent_id, effective_from)
SELECT v.code, v.name, v.otype, p.organization_id, DATE '2026-01-01'
  FROM (VALUES
    ('FGC-D01','동부본부','DIVISION','FGC-HQ'),
    ('FGC-D02','서부본부','DIVISION','FGC-HQ')
  ) AS v(code,name,otype,parent)
  JOIN organization p ON p.organization_code = v.parent
ON CONFLICT (organization_code) DO NOTHING;

INSERT INTO organization(organization_code, organization_name, organization_type, parent_id, effective_from)
SELECT v.code, v.name, v.otype, p.organization_id, DATE '2026-01-01'
  FROM (VALUES
    ('FGC-B0101','강동지사','BRANCH','FGC-D01'),
    ('FGC-B0201','마포지사','BRANCH','FGC-D02')
  ) AS v(code,name,otype,parent)
  JOIN organization p ON p.organization_code = v.parent
ON CONFLICT (organization_code) DO NOTHING;

INSERT INTO organization(organization_code, organization_name, organization_type, parent_id, effective_from)
SELECT v.code, v.name, v.otype, p.organization_id, DATE '2026-01-01'
  FROM (VALUES
    ('FGC-T010101','강동1팀','TEAM','FGC-B0101'),
    ('FGC-T010102','강동2팀','TEAM','FGC-B0101'),
    ('FGC-T020101','마포1팀','TEAM','FGC-B0201')
  ) AS v(code,name,otype,parent)
  JOIN organization p ON p.organization_code = v.parent
ON CONFLICT (organization_code) DO NOTHING;

-- ============================================================================
-- 3. 보험회사 (원수사) — 실제 회사명을 쓰지 않습니다
-- ============================================================================
INSERT INTO insurer(insurer_code, insurer_name, insurer_type) VALUES
  ('FGL01','미래가상생명','LIFE'),
  ('FGL02','한빛가상생명','LIFE'),
  ('FGL03','새봄가상생명','LIFE'),
  ('FGL04','온누리가상생명','LIFE'),
  ('FGN01','안전가상손해보험','NON_LIFE'),
  ('FGN02','믿음가상손해보험','NON_LIFE')
ON CONFLICT (insurer_code) DO NOTHING;

-- ============================================================================
-- 4. 설계사
--    A-FC-002 = 경력 정착지원금 대상 (REG-20)   ← 정착지원금은 이 사람만 받습니다
--    A-FC-003 = 신인 (직전 3년 모집경력 없음 · REG-21)
--    A-FC-004 = 해촉자 (설계사 불일치 시나리오용 · V4에서 사용)
-- ============================================================================
INSERT INTO agent(organization_id, agent_code, agent_name, rank_code,
                  appointment_date, termination_date, agent_status,
                  latest_registration_date, prior_three_year_experience_yn, experience_checked_on,
                  newcomer_support_eligible_yn, newcomer_support_end_date)
SELECT o.organization_id, v.code, v.name, v.rank,
       v.appoint, v.term, v.status,
       v.reg_date, v.prior3y, v.checked_on, v.newcomer_yn, v.newcomer_end
  FROM (VALUES
    ('A-FC-001','김정산','FC',              'FGC-T010101', DATE '2023-03-02', NULL::date,        'ACTIVE',
     DATE '2023-03-02', true,  DATE '2023-03-01', false, NULL::date),
    ('A-FC-002','이보험','FC',              'FGC-T010101', DATE '2024-05-13', NULL::date,        'ACTIVE',
     DATE '2024-05-13', true,  DATE '2024-05-12', false, NULL::date),
    ('A-FC-003','박신인','FC',              'FGC-T010102', DATE '2026-07-01', NULL::date,        'ACTIVE',
     DATE '2026-07-01', false, DATE '2026-06-30', true,  DATE '2027-06-30'),
    ('A-FC-004','최해촉','FC',              'FGC-T020101', DATE '2022-09-01', DATE '2026-06-30', 'TERMINATED',
     DATE '2022-09-01', true,  DATE '2022-08-31', false, NULL::date),
    ('A-TL-001','정팀장','TEAM_LEADER',     'FGC-T010101', DATE '2021-04-01', NULL::date,        'ACTIVE',
     DATE '2021-04-01', true,  DATE '2021-03-31', false, NULL::date),
    ('A-BM-001','한지사','BRANCH_MANAGER',  'FGC-B0101',   DATE '2020-02-03', NULL::date,        'ACTIVE',
     DATE '2020-02-03', true,  DATE '2020-02-02', false, NULL::date),
    ('A-DH-001','서본부','DIVISION_HEAD',   'FGC-D01',     DATE '2019-01-02', NULL::date,        'ACTIVE',
     DATE '2019-01-02', true,  DATE '2019-01-01', false, NULL::date)
  ) AS v(code,name,rank,org,appoint,term,status,reg_date,prior3y,checked_on,newcomer_yn,newcomer_end)
  JOIN organization o ON o.organization_code = v.org
ON CONFLICT (agent_code) DO NOTHING;

-- 원수사별 설계사코드 (대사에서 설계사 불일치를 잡는 근거)
INSERT INTO agent_insurer_code(agent_id, insurer_id, insurer_agent_code, effective_from, code_status)
SELECT a.agent_id, i.insurer_id, v.ins_code, DATE '2026-01-01', 'ACTIVE'
  FROM (VALUES
    ('A-FC-001','FGL01','L01-77881'),
    ('A-FC-001','FGL02','L02-11027'),
    ('A-FC-001','FGN01','N01-99315'),
    ('A-FC-002','FGL01','L01-77882'),
    ('A-FC-002','FGL02','L02-11028'),
    ('A-FC-003','FGL01','L01-77883')
  ) AS v(agent_code, insurer_code, ins_code)
  JOIN agent   a ON a.agent_code   = v.agent_code
  JOIN insurer i ON i.insurer_code = v.insurer_code
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 5. 상품 · 상품 판매버전
--    분급 체계는 계약연도가 아니라 판매개시일·기초서류 버전으로 정합니다 (REG-19).
--    그래서 상품이 아니라 "판매버전"에 fee_regime_code 가 붙습니다.
-- ============================================================================
INSERT INTO product(insurer_id, insurer_product_code, standard_product_code,
                    product_name, product_group_code, protection_type)
SELECT i.insurer_id, v.ins_prod, v.std_code, v.name, v.grp, v.ptype
  FROM (VALUES
    ('FGL01','P-A-001','STD-LIFE-A','가상 건강보장보험 A',  'HEALTH_PROTECTION','PROTECTION'),
    ('FGL02','P-B-001','STD-LIFE-B','가상 저해지 건강보험 B','HEALTH_PROTECTION','PROTECTION'),
    ('FGL03','P-T-001','STD-TERM-A','가상 경영인정기보험',  'TERM_PROTECTION',  'PROTECTION'),
    ('FGN01','P-N-001','STD-NL-A',  '가상 장기상해보험 A',  'LONG_TERM_NONLIFE','PROTECTION')
  ) AS v(insurer_code, ins_prod, std_code, name, grp, ptype)
  JOIN insurer i ON i.insurer_code = v.insurer_code
ON CONFLICT (standard_product_code) DO NOTHING;

-- standard_deduction_80_yn = true 인 상품은
--   · 1,200% 한도에 12차월 예상 해약환급금을 더하고 (REG-08)
--   · 차익거래 판정에서 해약환급금을 합산합니다 (REG-12)
-- ★ 그래서 발표 ⑤번 컷(C004)에는 이 값이 false 인 STD-LIFE-A 를 씁니다.
--
-- ★ 판매기간(sales_start_date ~ sales_end_date)을 꼭 지켜야 합니다.
--   계약일이 판매시작일보다 이르면 "그때는 아직 팔지 않던 상품"이 됩니다.
--   그래서 STD-LIFE-B 는 상반기·하반기 판매버전을 나눠 둡니다.
--     2026-H1-B      2026-01-01 ~ 2026-06-30   ← V4 의 A1(1/15)·R6(5/10) 이 씁니다
--     2026-CURRENT-B 2026-07-01 ~ (계속)       ← V4 의 R5(8/07) 가 씁니다
--   두 버전 모두 기초서류가 다르므로 basic_document_version 도 다릅니다 (REG-19).
INSERT INTO product_offering(product_id, offering_version, sales_start_date, sales_end_date,
                             basic_document_version, basic_document_date,
                             channel_code, fee_regime_code, standard_deduction_80_yn,
                             governance_status, committee_approval_date)
SELECT p.product_id, v.ver, v.sales_start, v.sales_end, v.bd_ver, v.bd_date,
       v.channel, v.regime, v.ded80, 'APPROVED', v.bd_date
  FROM (VALUES
    ('STD-LIFE-A','2026-CURRENT-A', DATE '2026-01-01', NULL::date,        'BD-2026-01', DATE '2026-01-01','FACE_TO_FACE','CURRENT', false),
    ('STD-LIFE-B','2026-H1-B',      DATE '2026-01-01', DATE '2026-06-30', 'BD-2026-01', DATE '2026-01-01','FACE_TO_FACE','CURRENT', true),
    ('STD-LIFE-B','2026-CURRENT-B', DATE '2026-07-01', NULL::date,        'BD-2026-07', DATE '2026-07-01','FACE_TO_FACE','CURRENT', true),
    ('STD-TERM-A','2026-CURRENT-A', DATE '2026-01-01', NULL::date,        'BD-2026-01', DATE '2026-01-01','FACE_TO_FACE','CURRENT', true),
    ('STD-NL-A',  '2026-CURRENT-A', DATE '2026-01-01', NULL::date,        'BD-2026-01', DATE '2026-01-01','FACE_TO_FACE','CURRENT', false)
  ) AS v(std_code, ver, sales_start, sales_end, bd_ver, bd_date, channel, regime, ded80)
  JOIN product p ON p.standard_product_code = v.std_code
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 6. 수수료 항목 — "돈의 종류"
-- ============================================================================
INSERT INTO commission_item(item_code, item_name, cashflow_type, item_category, effective_from) VALUES
  ('BASE_COMMISSION',       'FC 기본수수료',    'PAYMENT',  'SALES',       DATE '2026-01-01'),
  ('MAINTENANCE_COMMISSION','유지수수료(현행)', 'PAYMENT',  'MAINTENANCE', DATE '2026-01-01'),
  ('INCENTIVE',             '시책수수료',       'PAYMENT',  'INCENTIVE',   DATE '2026-01-01'),
  ('MANAGEMENT_COMMISSION', '관리자수수료',     'PAYMENT',  'MANAGEMENT',  DATE '2026-01-01'),
  ('SETTLEMENT_SUPPORT',    '정착지원금(경력)', 'PAYMENT',  'SUPPORT',     DATE '2026-01-01'),
  ('NEWCOMER_SUPPORT',      '신인활동지원비',   'PAYMENT',  'SUPPORT',     DATE '2026-01-01'),
  ('COMMON_COST',           '공통비 귀속분',    'PAYMENT',  'COST',        DATE '2026-01-01'),
  ('ADJUSTMENT',            '정정·조정',        'PAYMENT',  'ADJUSTMENT',  DATE '2026-01-01'),
  ('CLAWBACK',              '환수',             'DEDUCTION','CLAWBACK',    DATE '2026-01-01'),
  ('RECOVERY',              '회수',             'DEDUCTION','RECOVERY',    DATE '2026-01-01'),
  ('LONG_TERM_MAINTENANCE', '장기유지수수료',   'PAYMENT',  'MAINTENANCE', DATE '2027-01-01')
ON CONFLICT (item_code) DO NOTHING;

-- ============================================================================
-- 7. 정책 버전 — ★ 전부 DRAFT 로 넣습니다
--    자식 행(요율·룰셋·환급률·파라미터)을 다 넣은 뒤 §13에서 한꺼번에 승격합니다.
--
--    정책코드 접두어  REG-=규제 / INS-=원수사 / GA-=우리 회사 / ASM-=프로젝트 가정
-- ============================================================================
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class,
                           version_no, effective_from, status,
                           regulation_refs, source_refs, created_by)
SELECT v.code, v.name, v.ptype, v.src, 1, v.eff, 'DRAFT', v.regs, v.srcs,
       (SELECT user_id FROM app_user WHERE login_id='settle01')   -- 작성자
  FROM (VALUES
    ('REG-CAP-INS-2026-V1','원수사→GA 초년도 1,200% 룰셋','CAP_1200','REGULATORY',
     DATE '2021-01-01', ARRAY['REG-08','REG-10','REG-11'], ARRAY['보험업감독규정 제4-32조']),
    ('REG-CAP-GA-2026-V1','GA→설계사 초년도 1,200% 룰셋','CAP_1200','REGULATORY',
     DATE '2026-07-01', ARRAY['REG-08','REG-09','REG-11'], ARRAY['보험업감독규정 제4-32조']),
    ('INS-CUR-2026-V1','원수사→GA 현행 수입기준','CURRENT_COMMISSION','INSURER_RULE',
     DATE '2026-01-01', ARRAY['REG-09'], ARRAY['원수사 판매수수료율표 2026']),
    ('GA-CUR-2026-V1','GA→설계사 현행 지급기준','CURRENT_COMMISSION','GA_POLICY',
     DATE '2026-01-01', ARRAY['REG-09','REG-22'], ARRAY['가상 GA 운영정책서 v1.0 제19~20조']),
    ('GA-ALLOC-COMMON-2026-V1','공통비 안분정책','ALLOCATION','GA_POLICY',
     DATE '2026-01-01', ARRAY['REG-06A','REG-06C'], ARRAY['가상 GA 운영정책서 v1.0']),
    ('INS-REFUND-2026-V1','예상 해약환급률표','REFUND_RATE','INSURER_RULE',
     DATE '2026-01-01', ARRAY['REG-23'], ARRAY['원수사 예상 해약환급률표 2026']),
    ('ASM-RECON-ZERO-2026-V1','대사 허용오차 정책(1차 0원)','RECONCILIATION_TOLERANCE','PROJECT_ASSUMPTION',
     DATE '2026-01-01', ARRAY[]::text[], ARRAY['docs/05_인터페이스정의서_v2_0.md'])
  ) AS v(code, name, ptype, src, eff, regs, srcs)
ON CONFLICT (policy_code, version_no) DO NOTHING;

-- ┌───────────────────────────────────────────────────────────────────────────┐
-- │ ★ 왜 자식 행 INSERT 마다 pv.status='DRAFT' 를 붙이나                       │
-- │                                                                           │
-- │  ON CONFLICT DO NOTHING 만으로는 부족합니다.                              │
-- │  DB 트리거(guard_policy_child_mutation)는 INSERT 를 "시도"하는 순간        │
-- │  이미 실행됩니다. 충돌을 확인하기 전에 먼저 터집니다.                     │
-- │                                                                           │
-- │    Rules under locked policy version 6 are immutable                      │
-- │                                                                           │
-- │  그래서 정책이 이미 ACTIVE 면 아예 한 건도 안 고르도록 막습니다.          │
-- │  → 처음 실행: 정책이 DRAFT 라 자식 행이 들어감                            │
-- │  → 다시 실행: 정책이 ACTIVE 라 0건 선택 → 트리거가 아예 안 돎             │
-- └───────────────────────────────────────────────────────────────────────────┘

-- ============================================================================
-- 8. 공통비 안분정책
--    "여러 계약에 걸친 공통비를 계약별로 어떻게 나눌지"를 정해 둔 규칙입니다.
--    기준을 모르면 임의로 나누지 말고 검토필요로 둡니다 (REG-06C).
-- ============================================================================
INSERT INTO allocation_policy(policy_version_id, allocation_code, allocation_name,
                              pool_type, allocation_basis_code,
                              rounding_scale, rounding_mode, residual_treatment, approved_basis_ref)
SELECT pv.policy_version_id,'GA-COMMON-PREMIUM-PROPORTIONAL','공통비 월납환산보험료 비례배부',
       'RECRUITING_COMMON_COST','MONTHLY_EQUIVALENT_PREMIUM_PROPORTIONAL',
       0,'HALF_UP','LARGEST_REMAINDER','가상 GA 운영정책서 v1.0 별표3 (승인본)'
  FROM policy_version pv
 WHERE pv.policy_code='GA-ALLOC-COMMON-2026-V1' AND pv.status='DRAFT'
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 9. 현행 수수료 규칙 (commission_rule)
--    ScheduleGenerator 가 이 표를 읽어 회차별 예상금액을 만듭니다.
--    ★ 요율을 자바 코드에 적지 마세요. 반드시 이 표에서 읽습니다 (COR-004).
--
--    ┌─ 정착지원금이 여기 없는 이유 ─────────────────────────────────────┐
--    │ 정착지원금은 "계약마다 얼마"가 아니라                              │
--    │ "설계사에게 그 달에 얼마"를 주고 그 달 신계약들에 나눠 붙이는 돈입니다.│
--    │ (REG-20 : 지급월 신계약에 월 단위로 귀속·배분)                      │
--    │                                                                   │
--    │ 여기에 정액 규칙으로 넣으면 ScheduleGenerator 가                    │
--    │ 모든 FC 계약마다 210,000원을 만들어 버립니다.                       │
--    │ 그래서 공통비와 똑같이 "지급 건 → 계약 안분"으로만 다룹니다.        │
--    └───────────────────────────────────────────────────────────────────┘
--
--    basis_code = MONTHLY_EQUIVALENT_FIRST_PREMIUM (월납환산 초회보험료)
--    반올림은 줄마다 원 단위 HALF_UP (rounding_scale=0) 으로 고정되어 있습니다.
-- ============================================================================
-- 원수사 → GA
INSERT INTO commission_rule(policy_version_id, payment_stage, commission_item_id,
                            fee_component_type, installment_from, installment_to,
                            calculation_type, basis_code, rate_pct, priority_no)
SELECT pv.policy_version_id,'INSURER_TO_GA', ci.commission_item_id,
       v.comp, v.inst_from, v.inst_to,'RATE','MONTHLY_EQUIVALENT_FIRST_PREMIUM', v.rate, 100
  FROM (VALUES
    ('BASE_COMMISSION',       'CURRENT',     1,  1, 900.000000),
    ('MAINTENANCE_COMMISSION','CURRENT',     2, 12,   8.000000)
  ) AS v(item_code, comp, inst_from, inst_to, rate)
  JOIN commission_item ci ON ci.item_code = v.item_code
  CROSS JOIN policy_version pv
 WHERE pv.policy_code='INS-CUR-2026-V1' AND pv.status='DRAFT'
ON CONFLICT DO NOTHING;

-- GA → 설계사 (직급별로 행이 나뉩니다)
INSERT INTO commission_rule(policy_version_id, payment_stage, agent_rank_code, commission_item_id,
                            fee_component_type, installment_from, installment_to,
                            calculation_type, basis_code, rate_pct, priority_no)
SELECT pv.policy_version_id,'GA_TO_FC', v.rank_code, ci.commission_item_id,
       v.comp, v.inst_from, v.inst_to,'RATE','MONTHLY_EQUIVALENT_FIRST_PREMIUM', v.rate, 100
  FROM (VALUES
    ('FC',             'BASE_COMMISSION',       'CURRENT',   1,  1, 650.000000),
    ('TEAM_LEADER',    'MANAGEMENT_COMMISSION', 'CURRENT',   1,  1,  40.000000),
    ('BRANCH_MANAGER', 'MANAGEMENT_COMMISSION', 'CURRENT',   1,  1,  30.000000),
    ('DIVISION_HEAD',  'MANAGEMENT_COMMISSION', 'CURRENT',   1,  1,  20.000000),
    ('FC',             'INCENTIVE',             'INCENTIVE', 1,  1, 100.000000),
    ('FC',             'MAINTENANCE_COMMISSION','CURRENT',   2, 12,   5.000000)
  ) AS v(rank_code, item_code, comp, inst_from, inst_to, rate)
  JOIN commission_item ci ON ci.item_code = v.item_code
  CROSS JOIN policy_version pv
 WHERE pv.policy_code='GA-CUR-2026-V1' AND pv.status='DRAFT'
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 10. 1,200% 룰셋 (cap_rule_set + cap_rule_item)
--
--     ★ 지급단계별로 룰셋이 따로 있습니다. 절대 합치지 않습니다.
--       준법경영비 3% 공제는 원수사→GA 에만 적용됩니다 (REG-10).
--       GA→설계사 룰셋의 공제율은 DB CHECK 로도 0이 강제됩니다.
-- ============================================================================
INSERT INTO cap_rule_set(policy_version_id, payment_stage, contract_date_from,
                         first_year_months, premium_multiplier,
                         compliance_deduction_pct, refund_addition_condition, warning_usage_pct)
SELECT pv.policy_version_id, v.stage, v.date_from, 12, 12.0000,
       v.deduction, 'STANDARD_DEDUCTION_80', 90.0000
  FROM (VALUES
    ('REG-CAP-INS-2026-V1','INSURER_TO_GA', DATE '2021-01-01', 3.0000),
    ('REG-CAP-GA-2026-V1', 'GA_TO_FC',      DATE '2026-07-01', 0.0000)
  ) AS v(policy_code, stage, date_from, deduction)
  JOIN policy_version pv ON pv.policy_code = v.policy_code
 WHERE pv.status='DRAFT'
ON CONFLICT DO NOTHING;

-- 항목별 산입·제외 분류
--   ★ 이름이 아니라 "실질이 모집·판매촉진의 대가인가"로 판단합니다 (REG-11 · REG-22).
--   ★ EXCLUDED 는 반드시 exclusion_type + evidence_required_yn=true 가 있어야 합니다 (DB CHECK).
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
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 11. 예상 해약환급률표 (1~36차월)
--     12차월 값이 1,200% 한도 가산에 쓰이고 (REG-08),
--     1~36차월 전체가 차익거래 판정에 쓰입니다 (REG-12 · REG-23).
--     이 표가 없는 계약은 억지로 계산하지 말고 검토필요로 둡니다.
--
--     ★ standard_deduction_80_yn 과 product_offering_id 를 반드시 명시합니다.
--       빼면 헤더는 기본값 false 인데 상세 환급률은 판매버전의 true 를 보고
--       저해지형으로 만들어져 헤더와 상세가 어긋납니다.
--
--     ┌─ ★★ 조회할 때 product_offering_id 로 조인하지 마세요 ──────────────┐
--     │ 환급률표의 범위는 판매버전이 아니라                                 │
--     │   (보험사 · 상품 · 납입기간 · 채널 · 적용시작일)                    │
--     │ 입니다. DB 제약이 그렇게 걸려 있습니다.                             │
--     │   uq_refund_table_scope UNIQUE                                      │
--     │     (insurer_id, product_id, payment_term_months,                   │
--     │      channel_code, effective_from)                                  │
--     │                                                                     │
--     │ 즉 한 상품에 판매버전이 여러 개여도 환급률표는 **하나**입니다.      │
--     │ product_offering_id 는 "대표로 어느 판매버전 것인가"를 적어 둔      │
--     │ 참고 표시일 뿐입니다.                                               │
--     │                                                                     │
--     │ 그래서 STD-LIFE-B 는 판매버전이 2개(2026-H1-B · 2026-CURRENT-B)     │
--     │ 여도 표는 1개이고, 두 판매버전 계약이 그 표를 같이 씁니다.          │
--     │ V4 의 A1·R6(2026-H1-B)도 아래 STD-LIFE-B 표를 찾아 씁니다.          │
--     └─────────────────────────────────────────────────────────────────────┘
--
--     ★ 아래 VALUES 에 offering_ver 를 넣어 조인 대상을 못 박습니다.
--       예전처럼 po.product_id 로만 조인하면 판매버전 2개가 각각 1행씩 만들려다
--       위 유니크 제약에 걸리고, ON CONFLICT DO NOTHING 때문에
--       **어느 쪽이 남을지 실행마다 달라집니다.**
-- ============================================================================
INSERT INTO refund_rate_table(policy_version_id, insurer_id, product_id, product_offering_id,
                              payment_term_months, channel_code, effective_from,
                              standard_deduction_80_yn, source_product_code, source_document_ref)
SELECT pv.policy_version_id, p.insurer_id, p.product_id, po.product_offering_id,
       v.term, po.channel_code, DATE '2026-01-01',
       po.standard_deduction_80_yn,                    -- ← 판매버전과 같은 값으로 맞춘다
       v.std_code, '원수사 예상 해약환급률표 2026 (합성값)'
  FROM (VALUES
    ('STD-LIFE-A','2026-CURRENT-A', 240),
    ('STD-LIFE-B','2026-CURRENT-B', 240),   -- 2026-H1-B 계약도 이 표를 씁니다
    ('STD-TERM-A','2026-CURRENT-A', 120),
    ('STD-NL-A',  '2026-CURRENT-A', 240)
  ) AS v(std_code, offering_ver, term)
  JOIN product p           ON p.standard_product_code = v.std_code
  JOIN product_offering po ON po.product_id = p.product_id
                          AND po.offering_version = v.offering_ver
  CROSS JOIN policy_version pv
 WHERE pv.policy_code='INS-REFUND-2026-V1' AND pv.status='DRAFT'
ON CONFLICT DO NOTHING;

-- 36개월 × 4표 = 144행. 합성값이지만 0 이상 · 누락 없음 · 12차월 존재 조건을 지킵니다.
INSERT INTO refund_rate_line(refund_rate_table_id, contract_month_no, refund_rate_pct)
SELECT rrt.refund_rate_table_id, m,
       CASE WHEN rrt.standard_deduction_80_yn
            THEN GREATEST(0, (m - 2) * 2.4)::numeric(9,6)   -- 저해지형: 초기 환급률이 낮음
            ELSE (m * 2.9)::numeric(9,6)
       END
  FROM refund_rate_table rrt
  JOIN policy_version pvr ON pvr.policy_version_id = rrt.policy_version_id
  CROSS JOIN generate_series(1, 36) AS m
 WHERE pvr.status='DRAFT'
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 12. 정책 파라미터 — 전용 테이블을 두기엔 작은 값들
--
--     ★ 여기 없으면 애플리케이션이 DB에서 못 읽어 코드에 박게 됩니다 (COR-004 위반).
-- ============================================================================
INSERT INTO policy_parameter(policy_version_id, parameter_key, parameter_value, description)
SELECT pv.policy_version_id, v.k, v.val::jsonb, v.memo
  FROM (VALUES
    ('INS-REFUND-2026-V1','REFUND_RATE_BASIS','"MONTHLY_EQUIVALENT_FIRST_PREMIUM_X12"',
     '환급률(%)을 곱할 기준 금액. 월납환산 초회보험료 × 12 에 환급률을 곱해 예상 해약환급금을 구한다. 이 값이 없으면 "무엇의 몇 %인지"를 알 수 없다'),
    ('ASM-RECON-ZERO-2026-V1','TOLERANCE_AMOUNT_KRW','0',
     '대사 허용오차(원). 1차는 0원 — 1원만 달라도 불일치로 본다'),
    ('ASM-RECON-ZERO-2026-V1','TOLERANCE_DAYS','0',
     '대사 허용오차(일). 1차는 0일 — 지급예정일이 하루만 달라도 불일치로 본다')
  ) AS v(policy_code, k, val, memo)
  JOIN policy_version pv ON pv.policy_code = v.policy_code AND pv.status='DRAFT'
ON CONFLICT (policy_version_id, parameter_key) DO NOTHING;

-- ============================================================================
-- 13. 정책 승격 — DRAFT → APPROVED → ACTIVE
--     ★ 여기가 마지막입니다. 위의 자식 행을 다 넣은 뒤에만 실행해야 합니다.
--       ACTIVE 가 되는 순간 자식 행은 더 이상 넣거나 고칠 수 없습니다.
--     ★ DRAFT → ACTIVE 직행은 트리거가 막으므로 반드시 두 단계로 갑니다.
--     ★ 작성자(settle01)와 승인자(gaadmin)를 분리합니다 (REG-22).
-- ============================================================================
UPDATE policy_version pv
   SET status='APPROVED',
       approved_at = TIMESTAMPTZ '2026-06-30 09:00:00+09',
       approved_by = (SELECT user_id FROM app_user WHERE login_id='gaadmin'),
       approval_evidence_ref = '시드 승인 (GOLDEN 프로파일)'
 WHERE pv.status='DRAFT'
   AND pv.policy_code IN ('REG-CAP-INS-2026-V1','REG-CAP-GA-2026-V1','INS-CUR-2026-V1',
                          'GA-CUR-2026-V1','GA-ALLOC-COMMON-2026-V1','INS-REFUND-2026-V1',
                          'ASM-RECON-ZERO-2026-V1');

UPDATE policy_version pv
   SET status='ACTIVE'
 WHERE pv.status='APPROVED'
   AND pv.policy_code IN ('REG-CAP-INS-2026-V1','REG-CAP-GA-2026-V1','INS-CUR-2026-V1',
                          'GA-CUR-2026-V1','GA-ALLOC-COMMON-2026-V1','INS-REFUND-2026-V1',
                          'ASM-RECON-ZERO-2026-V1');

-- ============================================================================
-- 14. 보험계약 6건
--     계약일이 적용 규칙의 기준일입니다 (1,200%·차익거래는 계약 체결일 기준 · REG-19).
--
--     ★ C003·C004 는 STD-LIFE-A(80% 공제 아님)를 씁니다.
--       80% 공제 상품이면 한도에 12차월 예상 해약환급금이 더해져
--       "1,250,000 > 1,200,000" 이라는 발표 논리가 성립하지 않습니다.
-- ============================================================================
INSERT INTO insurance_contract(insurer_id, product_offering_id, contract_no, contract_date,
                               agent_id, organization_id,
                               premium_per_cycle_amount, first_premium_amount,
                               monthly_equivalent_first_premium, premium_conversion_rule_code,
                               payment_cycle_code, payment_term_months,
                               standard_surrender_deduction_amount, current_status, data_origin)
SELECT i.insurer_id, po.product_offering_id, v.contract_no, v.contract_date,
       a.agent_id, o.organization_id,
       v.premium_cycle, v.first_premium, v.monthly_equiv, v.conv_rule,
       v.cycle_code, v.term_months, v.std_deduction, v.status, 'SEED'
--
--     ★ 판매버전(offering_ver)을 반드시 적습니다.
--       상품코드만으로 조인하면 그 상품에 판매버전이 2개 이상일 때
--       어느 버전이 붙을지 실행마다 달라집니다. 6건 모두 계약일이
--       2026-CURRENT-A 의 판매기간(2026-01-01~) 안에 있습니다.
  FROM (VALUES
    -- C001 : 1,200% 정상 (74.166667%)
    ('FGC-FGL01-202607-0001','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-07-10','A-FC-001','FGC-T010101',
     100000, 100000, 100000,'MONTHLY_AS_IS','MONTHLY', 240, NULL::numeric,'ACTIVE'),
    -- C002 : 1,200% 주의 (91.666667%) · 경력 정착지원금 대상 설계사
    ('FGC-FGL01-202607-0002','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-07-12','A-FC-002','FGC-T010101',
     100000, 100000, 100000,'MONTHLY_AS_IS','MONTHLY', 240, NULL::numeric,'ACTIVE'),
    -- C006 : 신인 설계사 → 검토필요
    ('FGC-FGL01-202607-0003','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-07-22','A-FC-003','FGC-T010102',
     100000, 100000, 100000,'MONTHLY_AS_IS','MONTHLY', 240, NULL::numeric,'ACTIVE'),
    -- C003 : 3개월납 30만원 → 월납환산 10만원. 주의(잔여 0)
    ('FGC-FGL01-202607-0004','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-07-15','A-FC-001','FGC-T010101',
     300000, 300000, 100000,'QUARTERLY_DIV_3','QUARTERLY', 240, NULL::numeric,'ACTIVE'),
    -- C004 : ★ 발표 ⑤번 컷. 확정 1,000,000 + 미확정 250,000 → 확정 시도하면 차단
    ('FGC-FGL01-202607-0005','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-07-18','A-FC-002','FGC-T010101',
     100000, 100000, 100000,'MONTHLY_AS_IS','MONTHLY', 240, NULL::numeric,'ACTIVE'),
    -- C005 : 미납 → 실효 → 차익거래 검토대상
    ('FGC-FGN01-202605-0001','FGN01','STD-NL-A',  '2026-CURRENT-A', DATE '2026-05-02','A-FC-001','FGC-T010101',
     100000, 100000, 100000,'MONTHLY_AS_IS','MONTHLY', 240, NULL::numeric,'LAPSED')
  ) AS v(contract_no, insurer_code, std_code, offering_ver, contract_date, agent_code, org_code,
         premium_cycle, first_premium, monthly_equiv, conv_rule, cycle_code,
         term_months, std_deduction, status)
  JOIN insurer i          ON i.insurer_code = v.insurer_code
  JOIN product p          ON p.standard_product_code = v.std_code
  JOIN product_offering po ON po.product_id = p.product_id
                          AND po.offering_version = v.offering_ver
  JOIN agent a            ON a.agent_code = v.agent_code
  JOIN organization o     ON o.organization_code = v.org_code
ON CONFLICT (insurer_id, contract_no) DO NOTHING;

-- ============================================================================
-- 15. 계약상태 사건
--     ★ 세 날짜를 반드시 구분합니다 (인터페이스정의서 §9).
--       effective_at = 실제로 그 일이 일어난 때   ← 수수료 자격·차익거래는 이걸로 판단
--       received_at  = 우리 DB가 그 사실을 받은 때
--       processed_at = 우리가 검증을 돌린 때
--
--     C005 이야기 : **1회만 납입하고 그 뒤로 미납 → 실효**
--       5/02 계약 성립 후 초회보험료 100,000원만 냈습니다.
--       6/02부터 미납이고 7/02에 실효됐습니다.
--       그래서 누적 납입보험료는 계속 100,000원입니다 (§16 참조).
--
--     C005 는 "늦게 도착"과 "순서역전" 사례도 함께 담고 있습니다.
--       실효는 7/02에 일어났는데 8/02에야 도착했고(31일 지연),
--       그보다 앞선 미납(6/02 효력)이 실효보다 나중에 도착했습니다.
--     → 이력을 다시 짤 때는 수신 순서가 아니라 effective_at 순서로 정렬해야 합니다.
-- ============================================================================
INSERT INTO contract_status_event(contract_id, event_seq, previous_status, new_status,
                                  effective_at, received_at, processed_at,
                                  source_system, source_event_key, reason_code, data_origin)
SELECT c.contract_id, v.seq, v.prev, v.new_st,
       v.eff, v.recv, v.proc, 'INSURER_FEED', v.evt_key, v.reason, 'SEED'
  FROM (VALUES
    ('FGC-FGL01-202607-0001', 1, NULL,     'ACTIVE',
     TIMESTAMPTZ '2026-07-10 00:00+09', TIMESTAMPTZ '2026-07-11 06:00+09', TIMESTAMPTZ '2026-07-11 06:30+09',
     'EVT-FGL01-0001-A','NEW_CONTRACT'),
    ('FGC-FGL01-202607-0002', 1, NULL,     'ACTIVE',
     TIMESTAMPTZ '2026-07-12 00:00+09', TIMESTAMPTZ '2026-07-13 06:00+09', TIMESTAMPTZ '2026-07-13 06:30+09',
     'EVT-FGL01-0002-A','NEW_CONTRACT'),
    ('FGC-FGL01-202607-0003', 1, NULL,     'ACTIVE',
     TIMESTAMPTZ '2026-07-22 00:00+09', TIMESTAMPTZ '2026-07-23 06:00+09', TIMESTAMPTZ '2026-07-23 06:30+09',
     'EVT-FGL01-0003-A','NEW_CONTRACT'),
    ('FGC-FGL01-202607-0004', 1, NULL,     'ACTIVE',
     TIMESTAMPTZ '2026-07-15 00:00+09', TIMESTAMPTZ '2026-07-16 06:00+09', TIMESTAMPTZ '2026-07-16 06:30+09',
     'EVT-FGL01-0004-A','NEW_CONTRACT'),
    ('FGC-FGL01-202607-0005', 1, NULL,     'ACTIVE',
     TIMESTAMPTZ '2026-07-18 00:00+09', TIMESTAMPTZ '2026-07-19 06:00+09', TIMESTAMPTZ '2026-07-19 06:30+09',
     'EVT-FGL01-0005-A','NEW_CONTRACT'),
    -- C005 : 정상 → 미납 → 실효
    ('FGC-FGN01-202605-0001', 1, NULL,     'ACTIVE',
     TIMESTAMPTZ '2026-05-02 00:00+09', TIMESTAMPTZ '2026-05-03 06:00+09', TIMESTAMPTZ '2026-05-03 06:30+09',
     'EVT-FGN01-0001-A','NEW_CONTRACT'),
    -- 미납(6/02 효력)이 실효보다 나중(8/03)에 도착 = 순서역전
    ('FGC-FGN01-202605-0001', 2, 'ACTIVE', 'UNPAID',
     TIMESTAMPTZ '2026-06-02 00:00+09', TIMESTAMPTZ '2026-08-03 06:00+09', NULL::timestamptz,
     'EVT-FGN01-0001-U','PREMIUM_UNPAID'),
    -- 실효(7/02 효력)가 8/02에 도착 = 31일 지연
    ('FGC-FGN01-202605-0001', 3, 'UNPAID', 'LAPSED',
     TIMESTAMPTZ '2026-07-02 00:00+09', TIMESTAMPTZ '2026-08-02 06:00+09', NULL::timestamptz,
     'EVT-FGN01-0001-L','LAPSE_BY_UNPAID')
  ) AS v(contract_no, seq, prev, new_st, eff, recv, proc, evt_key, reason)
  JOIN insurance_contract c ON c.contract_no = v.contract_no
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 16. 계약 재무 스냅샷 — 차익거래 판정의 입력값
--     "이 시점까지 계약자가 낸 보험료가 얼마인가"를 기준일별로 적어 둡니다.
--
--     ★ C005 는 6/02부터 미납이므로 누적보험료가 **늘지 않습니다.**
--       초회 100,000원에서 멈춘 채 7/02에 실효됐습니다.
--       (미납인데 보험료가 계속 늘어나면 앞뒤가 안 맞습니다)
-- ============================================================================
INSERT INTO contract_financial_snapshot(contract_id, as_of_date, contract_month_no,
                                        cumulative_paid_premium, surrender_value, surrender_value_type)
SELECT c.contract_id, v.as_of, v.month_no, v.cum_premium, v.surrender, v.surr_type
  FROM (VALUES
    -- C005 : 1회 납입 후 미납 → 실효. 차익거래 검토대상이 됩니다
    --   지급수수료 640,000 − 납입 100,000 = 540,000 초과 → CANDIDATE
    ('FGC-FGN01-202605-0001', DATE '2026-05-31', 1, 100000::numeric, 0::numeric,   'ACTUAL'),
    ('FGC-FGN01-202605-0001', DATE '2026-06-30', 2, 100000::numeric, 0::numeric,   'ACTUAL'),
    ('FGC-FGN01-202605-0001', DATE '2026-07-31', 3, 100000::numeric, 0::numeric,   'ACTUAL'),
    ('FGC-FGL01-202607-0001', DATE '2026-07-31', 1, 100000::numeric, NULL::numeric,'EXPECTED_TABLE'),
    ('FGC-FGL01-202607-0002', DATE '2026-07-31', 1, 100000::numeric, NULL::numeric,'EXPECTED_TABLE'),
    ('FGC-FGL01-202607-0003', DATE '2026-07-31', 1, 100000::numeric, NULL::numeric,'EXPECTED_TABLE'),
    ('FGC-FGL01-202607-0004', DATE '2026-07-31', 1, 300000::numeric, NULL::numeric,'EXPECTED_TABLE'),
    ('FGC-FGL01-202607-0005', DATE '2026-07-31', 1, 100000::numeric, NULL::numeric,'EXPECTED_TABLE')
  ) AS v(contract_no, as_of, month_no, cum_premium, surrender, surr_type)
  JOIN insurance_contract c ON c.contract_no = v.contract_no
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 17. 원수사 지급명세 묶음 (statement_batch)
--     ※ 파일을 올리는 화면이 아닙니다. 이미 정규화되어 들어온 자료의 "묶음 단위"입니다 (COR-010).
-- ============================================================================
INSERT INTO statement_batch(insurer_id, settlement_month, statement_type,
                            received_on, source_ref, status, data_origin)
SELECT i.insurer_id, DATE '2026-07-01','INSURER_COMMISSION',
       DATE '2026-08-05', i.insurer_code || '-202607-COMMISSION','AVAILABLE','SEED'
  FROM insurer i WHERE i.insurer_code IN ('FGL01','FGL02','FGN01')
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 18. 실제 지급 건 + 귀속행
--
--     ★ 순서가 중요합니다.
--       ① DRAFT 로 지급 건을 넣고
--       ② 귀속행을 넣어 합계를 맞추고
--       ③ CONFIRMED 로 바꿉니다
--     CONFIRMED 로 바로 INSERT 하면 트리거가 거부합니다.
--
--     아래 임시 표는 **귀속행이 1개인** 지급 건만 담습니다.
--     정착지원금처럼 여러 계약에 나눠 붙는 건은 §19에서 따로 넣습니다.
--     confirm_yn=false 인 건은 **작성중으로 남깁니다** (일부러 그렇게 둔 것입니다).
-- ============================================================================
CREATE TEMP TABLE seed_tx (
  biz_key         text PRIMARY KEY,
  stage           text,
  source_type     text,
  contract_no     text,
  recipient_code  text,          -- 수령 설계사 (GA→설계사만)
  item_code       text,
  amount          numeric(15,2),
  cashflow        text,
  attr_scope      text,
  inclusion       text,
  method          text,
  evidence        text,
  attr_date       date,
  src_agent_code  text,          -- 원수사 명세상 설계사코드 (대사에서 씀)
  confirm_yn      boolean
);

INSERT INTO seed_tx VALUES
-- ── 원수사 → GA (실제 명세) ───────────────────────────────────────────────
('INS-FGL01-202607-0001','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202607-0001', NULL,
 'BASE_COMMISSION', 1010000,'PAYMENT','CONTRACT','INCLUDED','DIRECT', NULL, DATE '2026-07-31','L01-77881', true),
('INS-FGL01-202607-0002','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202607-0002', NULL,
 'BASE_COMMISSION', 1150000,'PAYMENT','CONTRACT','INCLUDED','DIRECT', NULL, DATE '2026-07-31','L01-77882', true),
('INS-FGL01-202607-0003','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202607-0003', NULL,
 'BASE_COMMISSION', 1005000,'PAYMENT','CONTRACT','INCLUDED','DIRECT', NULL, DATE '2026-07-31','L01-77883', true),
('INS-FGL01-202607-0004','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202607-0004', NULL,
 'BASE_COMMISSION', 1120000,'PAYMENT','CONTRACT','INCLUDED','DIRECT', NULL, DATE '2026-07-31','L01-77881', true),
('INS-FGL01-202607-0005','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202607-0005', NULL,
 'BASE_COMMISSION', 1090000,'PAYMENT','CONTRACT','INCLUDED','DIRECT', NULL, DATE '2026-07-31','L01-77882', true),
('INS-FGN01-202607-0001','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGN01-202605-0001', NULL,
 'BASE_COMMISSION',  700000,'PAYMENT','CONTRACT','INCLUDED','DIRECT', NULL, DATE '2026-07-31','N01-99315', true),

-- ── C001 : 확정 산입 890,000 (정상 74.166667%) ───────────────────────────
--    기본 650,000 + 관리자 90,000 + 시책 100,000 + 공통비 50,000
('GA-2026-07-0101','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0001','A-FC-001',
 'BASE_COMMISSION',       650000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0102','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0001','A-TL-001',
 'MANAGEMENT_COMMISSION',  40000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0103','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0001','A-BM-001',
 'MANAGEMENT_COMMISSION',  30000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0104','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0001','A-DH-001',
 'MANAGEMENT_COMMISSION',  20000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0105','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0001','A-FC-001',
 'INCENTIVE',             100000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0106','GA_TO_FC','ALLOCATION_POOL',     'FGC-FGL01-202607-0001','A-FC-001',
 'COMMON_COST',            50000,'PAYMENT','CONTRACT','INCLUDED','APPROVED_ALLOCATION', NULL, DATE '2026-07-25', NULL, true),

-- ── C002 : 확정 산입 1,100,000 (주의 91.666667%) ─────────────────────────
--    기본 650,000 + 관리자 90,000 + 시책 205,000 + 공통비 50,000 + 정착지원금 안분 105,000(§19)
('GA-2026-07-0201','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0002','A-FC-002',
 'BASE_COMMISSION',       650000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0202','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0002','A-TL-001',
 'MANAGEMENT_COMMISSION',  40000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0203','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0002','A-BM-001',
 'MANAGEMENT_COMMISSION',  30000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0204','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0002','A-DH-001',
 'MANAGEMENT_COMMISSION',  20000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0205','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0002','A-FC-002',
 'INCENTIVE',             205000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0206','GA_TO_FC','ALLOCATION_POOL',     'FGC-FGL01-202607-0002','A-FC-002',
 'COMMON_COST',            50000,'PAYMENT','CONTRACT','INCLUDED','APPROVED_ALLOCATION', NULL, DATE '2026-07-25', NULL, true),

-- ── C006 : 확정 산입 980,000 + 검토필요 150,000 ──────────────────────────
--    A-FC-003 은 신인이라 경력 정착지원금 대상이 아닙니다
('GA-2026-07-0301','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0003','A-FC-003',
 'BASE_COMMISSION',       650000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0302','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0003','A-FC-003',
 'INCENTIVE',             330000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
-- 검토필요 귀속이 있으므로 확정할 수 없습니다 (트리거가 막습니다). 일부러 DRAFT 로 둡니다.
('GA-2026-07-0303','GA_TO_FC','GA_MANUAL_PAYMENT',   'FGC-FGL01-202607-0003','A-FC-003',
 'ADJUSTMENT',            150000,'PAYMENT','CONTRACT','REVIEW_REQUIRED','MANUAL_REVIEW',
 NULL, DATE '2026-07-26', NULL, false),

-- ── C003 : 확정 산입 1,200,000 (주의 · 잔여 0원) ─────────────────────────
--    기본 650,000 + 관리자 90,000 + 시책 410,000 + 공통비 50,000
('GA-2026-07-0401','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0004','A-FC-001',
 'BASE_COMMISSION',       650000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0402','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0004','A-TL-001',
 'MANAGEMENT_COMMISSION',  40000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0403','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0004','A-BM-001',
 'MANAGEMENT_COMMISSION',  30000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0404','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0004','A-DH-001',
 'MANAGEMENT_COMMISSION',  20000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0405','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0004','A-FC-001',
 'INCENTIVE',             410000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0406','GA_TO_FC','ALLOCATION_POOL',     'FGC-FGL01-202607-0004','A-FC-001',
 'COMMON_COST',            50000,'PAYMENT','CONTRACT','INCLUDED','APPROVED_ALLOCATION', NULL, DATE '2026-07-25', NULL, true),

-- ── C004 ★ : 확정 1,000,000 + 미확정 250,000 ─────────────────────────────
--    확정 = 기본 650,000 + 관리자 90,000 + 시책 105,000 + 공통비 50,000
--           + 정착지원금 안분 105,000(§19)   →  합계 1,000,000
--    미확정 = 시책 250,000 (GA-2026-07-0001)
--    확정하려 하면 1,000,000 + 250,000 = 1,250,000 > 1,200,000 → 여러분의 게이트가 막아야 합니다
('GA-2026-07-0501','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0005','A-FC-002',
 'BASE_COMMISSION',       650000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0502','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0005','A-TL-001',
 'MANAGEMENT_COMMISSION',  40000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0503','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0005','A-BM-001',
 'MANAGEMENT_COMMISSION',  30000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0504','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0005','A-DH-001',
 'MANAGEMENT_COMMISSION',  20000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0505','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202607-0005','A-FC-002',
 'INCENTIVE',             105000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0506','GA_TO_FC','ALLOCATION_POOL',     'FGC-FGL01-202607-0005','A-FC-002',
 'COMMON_COST',            50000,'PAYMENT','CONTRACT','INCLUDED','APPROVED_ALLOCATION', NULL, DATE '2026-07-25', NULL, true),
('GA-2026-07-0001','GA_TO_FC','GA_MANUAL_PAYMENT',   'FGC-FGL01-202607-0005','A-FC-002',
 'INCENTIVE',             250000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-07-28', NULL, false),

-- ── C005 : 실효 계약. 확정 산입 640,000 ──────────────────────────────────
('GA-2026-05-0601','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGN01-202605-0001','A-FC-001',
 'BASE_COMMISSION',       640000,'PAYMENT','CONTRACT','INCLUDED','DIRECT',              NULL, DATE '2026-05-25', NULL, true),

-- ── 계약에 귀속되지 않는 신인활동지원비 (REG-21) ──────────────────────────
--    scope=AGENT 이므로 contract_id 가 없고, 증빙(evidence_ref)이 반드시 있어야 합니다.
('GA-2026-07-0701','GA_TO_FC','GA_MANUAL_PAYMENT', NULL,'A-FC-003',
 'NEWCOMER_SUPPORT',      500000,'PAYMENT','AGENT','EXCLUDED','NEWCOMER_NON_CONTRACT',
 '등록일 2026-07-01 · 직전3년 경력조회 2026-06-30 · 신인 지원기준 GA-NEW-2026 v1', DATE '2026-07-25', NULL, true);

-- ① 지급 건을 DRAFT 로 넣습니다 (상태를 안 적으면 기본값이 DRAFT 입니다)
INSERT INTO commission_transaction(payment_stage, source_type, source_business_key,
                                   statement_batch_id, insurer_id, recipient_agent_id,
                                   commission_item_id, policy_version_id,
                                   settlement_month, due_date, amount, cashflow_type, evidence_ref,
                                   created_by)
SELECT t.stage, t.source_type, t.biz_key,
       sb.statement_batch_id, i.insurer_id, ra.agent_id,
       ci.commission_item_id, pv.policy_version_id,
       date_trunc('month', t.attr_date)::date, t.attr_date + 31, t.amount, t.cashflow, t.evidence,
       (SELECT user_id FROM app_user WHERE login_id='settle01')
  FROM seed_tx t
  JOIN commission_item ci ON ci.item_code = t.item_code
  LEFT JOIN insurance_contract c ON c.contract_no = t.contract_no
  LEFT JOIN insurer i            ON i.insurer_id  = c.insurer_id
  LEFT JOIN agent ra             ON ra.agent_code = t.recipient_code
  LEFT JOIN statement_batch sb   ON sb.insurer_id = c.insurer_id
                                AND sb.settlement_month = date_trunc('month', t.attr_date)::date
                                AND t.source_type = 'INSURER_STATEMENT'
  LEFT JOIN policy_version pv    ON pv.policy_code = CASE t.stage
                                      WHEN 'INSURER_TO_GA' THEN 'INS-CUR-2026-V1'
                                      ELSE 'GA-CUR-2026-V1' END
                                AND pv.status='ACTIVE'
ON CONFLICT (source_type, source_business_key) DO NOTHING;

-- ② 귀속행 — "이 돈이 어느 계약 몫인가"
INSERT INTO transaction_attribution(commission_transaction_id, attribution_scope,
                                    contract_id, agent_id, source_agent_code,
                                    attribution_date, attribution_month, attributed_amount,
                                    cap_rule_item_id, inclusion_status_snapshot,
                                    exclusion_type_snapshot, attribution_method,
                                    allocation_policy_id, evidence_ref)
SELECT ct.commission_transaction_id, t.attr_scope,
       c.contract_id,
       COALESCE(ra.agent_id, c.agent_id),
       t.src_agent_code,
       t.attr_date,
       date_trunc('month', t.attr_date)::date,
       t.amount,
       cri.cap_rule_item_id, t.inclusion,
       CASE WHEN t.inclusion='EXCLUDED' THEN 'NEWCOMER_SUPPORT' ELSE NULL END,
       t.method,
       CASE WHEN t.method='APPROVED_ALLOCATION'
            THEN (SELECT allocation_policy_id FROM allocation_policy
                   WHERE allocation_code='GA-COMMON-PREMIUM-PROPORTIONAL')
            ELSE NULL END,
       t.evidence
  FROM seed_tx t
  JOIN commission_transaction ct ON ct.source_business_key = t.biz_key
                                AND ct.source_type = t.source_type
  JOIN commission_item ci        ON ci.item_code = t.item_code
  LEFT JOIN insurance_contract c ON c.contract_no = t.contract_no
  LEFT JOIN agent ra             ON ra.agent_code = t.recipient_code
  LEFT JOIN cap_rule_set crs     ON crs.payment_stage = t.stage
  LEFT JOIN cap_rule_item cri    ON cri.cap_rule_set_id = crs.cap_rule_set_id
                                AND cri.commission_item_id = ci.commission_item_id
 WHERE NOT EXISTS (
        SELECT 1 FROM transaction_attribution ta
         WHERE ta.commission_transaction_id = ct.commission_transaction_id);

-- ============================================================================
-- 19. 정착지원금 — 월 1건 지급 → 그 달 신계약들에 안분
--
--     ★ 계약마다 210,000원을 따로 주는 게 아닙니다.
--       설계사에게 그 달에 210,000원을 한 번 주고,
--       그 달에 그 사람이 모집한 계약들에 나눠 붙입니다 (REG-20).
--
--       계약마다 별도 지급 건으로 만들면 계약 수만큼 지원금이 불어납니다.
--       (계약 2건이면 420,000원이 되어 버립니다)
--
--     A-FC-002 는 2026년 7월에 C002·C004 두 건을 모집했으므로
--     210,000원을 105,000원씩 나눠 붙입니다.
--
--     ※ A-FC-001 은 경력 정착지원금 대상이 아니고,
--       A-FC-003 은 신인이라 정착지원금 대신 신인활동지원비를 받습니다.
-- ============================================================================
INSERT INTO commission_transaction(payment_stage, source_type, source_business_key,
                                   recipient_agent_id, commission_item_id, policy_version_id,
                                   settlement_month, due_date, amount, cashflow_type,
                                   note, created_by)
SELECT 'GA_TO_FC','GA_CONFIRMED_PAYMENT','GA-2026-07-9001',
       a.agent_id, ci.commission_item_id, pv.policy_version_id,
       DATE '2026-07-01', DATE '2026-08-25', 210000,'PAYMENT',
       '경력설계사 정착지원금 2026-07 월 지급분. 그 달 신계약에 안분 (REG-20)',
       (SELECT user_id FROM app_user WHERE login_id='settle01')
  FROM agent a, commission_item ci, policy_version pv
 WHERE a.agent_code='A-FC-002' AND ci.item_code='SETTLEMENT_SUPPORT'
   AND pv.policy_code='GA-CUR-2026-V1' AND pv.status='ACTIVE'
ON CONFLICT (source_type, source_business_key) DO NOTHING;

-- 안분 2행 (합계 210,000 = 지급액). attribution_seq 를 1·2 로 나눠야 합니다.
INSERT INTO transaction_attribution(commission_transaction_id, attribution_seq, attribution_scope,
                                    contract_id, agent_id,
                                    attribution_date, attribution_month, attributed_amount,
                                    cap_rule_item_id, inclusion_status_snapshot,
                                    attribution_method, allocation_basis_snapshot, evidence_ref)
SELECT ct.commission_transaction_id, v.seq,'CONTRACT',
       c.contract_id, a.agent_id,
       DATE '2026-07-25', DATE '2026-07-01', v.amt,
       cri.cap_rule_item_id,'INCLUDED','SETTLEMENT_SUPPORT_MONTHLY',
       jsonb_build_object('basis','EQUAL_SPLIT_BY_NEW_CONTRACT',
                          'monthly_amount', 210000,
                          'contract_count', 2),
       '2026-07 신계약 2건 균등 안분'
  FROM (VALUES
    (1,'FGC-FGL01-202607-0002', 105000::numeric),
    (2,'FGC-FGL01-202607-0005', 105000::numeric)
  ) AS v(seq, contract_no, amt)
  JOIN insurance_contract c ON c.contract_no = v.contract_no
  JOIN agent a              ON a.agent_code = 'A-FC-002'
  JOIN commission_item ci   ON ci.item_code = 'SETTLEMENT_SUPPORT'
  JOIN commission_transaction ct ON ct.source_business_key='GA-2026-07-9001'
                                AND ct.source_type='GA_CONFIRMED_PAYMENT'
  LEFT JOIN cap_rule_set crs  ON crs.payment_stage='GA_TO_FC'
  LEFT JOIN cap_rule_item cri ON cri.cap_rule_set_id = crs.cap_rule_set_id
                             AND cri.commission_item_id = ci.commission_item_id
 WHERE NOT EXISTS (
        SELECT 1 FROM transaction_attribution ta
         WHERE ta.commission_transaction_id = ct.commission_transaction_id
           AND ta.attribution_seq = v.seq);

-- ============================================================================
-- 20. 확정 — 귀속합계 = 지급액이고 검토필요 귀속이 없는 건만 CONFIRMED 가 됩니다
--     (조건을 어기면 DB 트리거가 여기서 오류를 냅니다)
-- ============================================================================
UPDATE commission_transaction ct
   SET status='CONFIRMED', paid_on = ct.due_date
  FROM seed_tx t
 WHERE ct.source_business_key = t.biz_key
   AND ct.source_type = t.source_type
   AND t.confirm_yn
   AND ct.status='DRAFT';

UPDATE commission_transaction
   SET status='CONFIRMED', paid_on = due_date
 WHERE source_business_key='GA-2026-07-9001' AND source_type='GA_CONFIRMED_PAYMENT'
   AND status='DRAFT';

DROP TABLE seed_tx;

-- ============================================================================
-- 21. 일일 배치 워터마크를 **시드 적재가 끝난 시각**으로 맞춥니다
--
--     ★ 고정 날짜를 쓰면 안 됩니다.
--       계약의 updated_at 은 이 파일이 실제로 실행된 시각(clock_timestamp)입니다.
--       워터마크를 그보다 이른 고정 날짜로 두면
--       첫 일일 배치가 시드 계약을 전부 다시 읽습니다. 의도와 반대입니다.
-- ============================================================================
UPDATE batch_watermark
   SET last_processed_at = clock_timestamp(),
       note = note || ' (V3 시드 적재 직후로 초기화)'
 WHERE job_name IN ('DailyChangedContractJob','MonthlyValidationJob')
   AND note NOT LIKE '%V3 시드 적재 직후로 초기화%';

-- ============================================================================
-- 확인용 쿼리 (앱을 띄운 뒤 psql 에서 돌려 보세요)
-- ============================================================================
-- -- 계약별 GA→설계사 산입액 — 1,200% 검증 엔진이 내야 할 값
-- SELECT c.contract_no,
--        (c.monthly_equivalent_first_premium * 12)::bigint                AS "한도",
--        SUM(ta.attributed_amount) FILTER (
--          WHERE ct.status='CONFIRMED' AND ta.inclusion_status_snapshot='INCLUDED') AS "확정 산입액",
--        SUM(ta.attributed_amount) FILTER (WHERE ct.status='DRAFT')       AS "미확정(작성중)"
--   FROM insurance_contract c
--   JOIN transaction_attribution ta ON ta.contract_id = c.contract_id
--   JOIN commission_transaction ct  ON ct.commission_transaction_id = ta.commission_transaction_id
--  WHERE ct.payment_stage='GA_TO_FC'
--  GROUP BY c.contract_no, c.monthly_equivalent_first_premium
--  ORDER BY c.contract_no;
--
--   기대 결과 (한도는 전부 1,200,000원 — 80% 공제 상품이 아니라 환급금 가산 없음)
--     FGC-FGL01-202607-0001   C001     890,000        (없음)   → 정상 74.166667%
--     FGC-FGL01-202607-0002   C002   1,100,000        (없음)   → 주의 91.666667%
--     FGC-FGL01-202607-0003   C006     980,000       150,000   → 검토필요
--     FGC-FGL01-202607-0004   C003   1,200,000        (없음)   → 주의 100.000000%
--     FGC-FGL01-202607-0005   C004   1,000,000       250,000   → ★ 확정 시도하면 104.166667% 차단
--     FGC-FGN01-202605-0001   C005     640,000        (없음)   → 정상 · 실효 계약
--
-- -- 정착지원금이 월 1건으로만 나가는지
-- SELECT ct.source_business_key, ct.amount::bigint, count(ta.*) AS "안분 계약수"
--   FROM commission_transaction ct
--   JOIN commission_item ci USING (commission_item_id)
--   LEFT JOIN transaction_attribution ta USING (commission_transaction_id)
--  WHERE ci.item_code='SETTLEMENT_SUPPORT'
--  GROUP BY 1,2;
--   →  GA-2026-07-9001 | 210000 | 2     (이 1건만 나와야 정상)
--
-- -- 정책 파라미터가 값으로 읽히는지
-- SELECT pv.policy_code, pp.parameter_key, pp.parameter_value
--   FROM policy_parameter pp JOIN policy_version pv USING (policy_version_id)
--  WHERE pv.status='ACTIVE' ORDER BY 1,2;
-- ============================================================================
