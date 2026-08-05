-- ============================================================================
-- FGC 검증 시나리오 시드 (V3 위에 얹습니다)
-- 기준: 정규화 입력데이터·시드데이터 명세서 v1.0 / 스키마 v2.1.4
-- 작성 기준일: 2026-08-03
--
-- ── 이 파일은 무엇인가 ─────────────────────────────────────────────────────
--   V3 는 "정상적으로 잘 도는 데이터"였습니다.
--   V4 는 **일부러 틀어 놓은 데이터**입니다.
--
--   여러분이 만들 검증 엔진이 이 틀어진 것들을 제대로 잡아내는지 보려고
--   상황을 하나씩 만들어 둔 것입니다. 정답은 파일 맨 아래 정답표에 있습니다.
--
--   ★ 여기서도 결과(cap_check · arbitrage_check · reconciliation_result ·
--     exception_case)는 넣지 않습니다. 그건 여러분 코드가 만들어야 합니다.
--     이 파일은 "그 결과가 나오게 만드는 입력"만 넣습니다.
--
-- ── 언제 쓰나 ─────────────────────────────────────────────────────────────
--   단계 3 (08/10~13) 1,200%·차익거래   → 제2부
--   단계 4 (08/14~17) 복식원장·대사      → 제1부
--   단계 5 (08/18~19) 월 통합검증        → 전체
--
-- ── 계약 14건 요약 ────────────────────────────────────────────────────────
--   제1부 · 대사(對査) 8건
--     R1 일치 / R2 금액차이 / R3 실제없음 / R4 중복지급
--     R5 설계사불일치 / R6 회차어긋남 / R7 비유효계약지급 / R8 예상없음
--   제2부 · 차익거래 3건
--     A1 해약환급금 합산 / A2 미합산 / A3 자료부족
--   제3부 · 분급체계 경계 3건 (전부 "검토필요"가 정답)
--     G1 계약일 2027·상품 현행 / G2 계약일 2026·상품 4년분급 / G3 TM 채널
--
-- ── 실행 전제 ─────────────────────────────────────────────────────────────
--   V3 가 먼저 들어가 있어야 합니다. 정책·요율·설계사·수수료항목을 그대로 씁니다.
--   V4 는 새 정책을 만들지 않습니다 (G2·G3 는 "정책이 없는 상황" 자체가 시험입니다).
--   여러 번 실행해도 안전합니다.
-- ============================================================================

SET search_path TO fgc, public;

-- ============================================================================
-- 0. 추가 기준정보
--    경계 시험에 필요한 상품 판매버전을 더 만듭니다.
--    ※ 분급 체계는 계약연도가 아니라 판매개시일·기초서류 버전으로 정합니다 (REG-19).
--       그래서 "계약일과 상품버전이 엇갈리는" 상황을 만들 수 있습니다.
-- ============================================================================
INSERT INTO product(insurer_id, insurer_product_code, standard_product_code,
                    product_name, product_group_code, protection_type)
SELECT i.insurer_id,'P-S-001','STD-SAV-A','가상 연금저축보험','SAVINGS','SAVINGS'
  FROM insurer i WHERE i.insurer_code='FGL04'
ON CONFLICT (standard_product_code) DO NOTHING;

INSERT INTO product_offering(product_id, offering_version, sales_start_date,
                             basic_document_version, basic_document_date,
                             channel_code, channel_special_rule_yn, fee_regime_code,
                             standard_deduction_80_yn, governance_status, committee_approval_date)
SELECT p.product_id, v.ver, v.sales_start, v.bd_ver, v.bd_date,
       v.channel, v.special, v.regime, v.ded80,'APPROVED', v.bd_date
  FROM (VALUES
    ('STD-LIFE-B','2027-FOUR-YEAR', DATE '2027-01-01','BD-2027-01', DATE '2027-01-01',
     'FACE_TO_FACE', false,'FOUR_YEAR_2027', true),
    ('STD-TERM-A','2026-TM-A',      DATE '2026-01-01','BD-2026-01', DATE '2026-01-01',
     'TM',          true, 'TM_SPECIAL',     true),
    ('STD-SAV-A', '2026-CURRENT-A', DATE '2026-01-01','BD-2026-01', DATE '2026-01-01',
     'FACE_TO_FACE', false,'CURRENT',        false)
  ) AS v(std_code, ver, sales_start, bd_ver, bd_date, channel, special, regime, ded80)
  JOIN product p ON p.standard_product_code = v.std_code
ON CONFLICT DO NOTHING;

INSERT INTO agent_insurer_code(agent_id, insurer_id, insurer_agent_code, effective_from, code_status)
SELECT a.agent_id, i.insurer_id, v.ins_code, DATE '2026-01-01','ACTIVE'
  FROM (VALUES
    ('A-FC-001','FGL03','L03-55011'),
    ('A-FC-002','FGL03','L03-55012'),
    ('A-FC-001','FGL04','L04-33021'),
    ('A-FC-004','FGL02','L02-11029')
  ) AS v(agent_code, insurer_code, ins_code)
  JOIN agent   a ON a.agent_code   = v.agent_code
  JOIN insurer i ON i.insurer_code = v.insurer_code
ON CONFLICT DO NOTHING;

INSERT INTO statement_batch(insurer_id, settlement_month, statement_type,
                            received_on, source_ref, status, data_origin)
SELECT i.insurer_id, v.month,'INSURER_COMMISSION',
       v.month + 35, i.insurer_code || '-' || to_char(v.month,'YYYYMM') || '-COMMISSION',
       'AVAILABLE','SEED'
  FROM insurer i
  CROSS JOIN (VALUES (DATE '2026-01-01'), (DATE '2026-06-01'), (DATE '2026-08-01')) AS v(month)
 WHERE i.insurer_code IN ('FGL01','FGL02','FGL03','FGN01')
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 계약 14건
--
--   V3 의 요율 규칙(GA-CUR-2026-V1)대로면 월납환산 100,000원 계약의
--   1회차 예상 지급은 이렇게 나옵니다.
--
--     FC 기본수수료        650%  →  650,000
--     팀장 수수료           40%  →   40,000
--     지사장 수수료         30%  →   30,000
--     본부장 수수료         20%  →   20,000
--     시책수수료           100%  →  100,000
--     ─────────────────────────────────────
--     1회차 합계                     840,000   (사용률 70% · 1,200% 정상)
--     2~12회차 유지수수료   5%  →    5,000 씩
--
--   ★ 정착지원금(210,000원)이 이 표에 없는 이유
--     정착지원금은 "계약마다 얼마"가 아니라 "설계사에게 그 달에 얼마"를 주고
--     그 달 신계약들에 나눠 붙이는 돈입니다 (REG-20).
--     그래서 요율표(commission_rule)에 없고, 예상 스케줄도 만들지 않습니다.
--     계약별 정액 규칙으로 넣으면 계약 수만큼 지원금이 불어납니다.
--     (V3 §9 · §19 참고)
--
--   아래 계약들은 이 "예상"에서 하나씩만 틀어 놓은 것입니다.
-- ============================================================================
INSERT INTO insurance_contract(insurer_id, product_offering_id, contract_no, contract_date,
                               agent_id, organization_id,
                               premium_per_cycle_amount, first_premium_amount,
                               monthly_equivalent_first_premium, premium_conversion_rule_code,
                               payment_cycle_code, payment_term_months,
                               standard_surrender_deduction_amount, current_status, data_origin)
SELECT i.insurer_id, po.product_offering_id, v.contract_no, v.contract_date,
       a.agent_id, o.organization_id,
       v.premium, v.premium, v.monthly_equiv,'MONTHLY_AS_IS','MONTHLY',
       v.term, v.std_ded, v.status,'SEED'
  FROM (VALUES
    -- ── 제1부 : 대사 ────────────────────────────────────────────────────
    -- R1 전부 규칙대로 지급
    ('FGC-FGL01-202608-0001','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-08-03',
     'A-FC-001','FGC-T010101', 100000, 100000, 240, NULL::numeric,'ACTIVE'),
    -- R2 기본수수료가 20,000원 적게 지급됨
    ('FGC-FGL01-202608-0002','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-08-04',
     'A-FC-001','FGC-T010101', 100000, 100000, 240, NULL::numeric,'ACTIVE'),
    -- R3 기본수수료만 지급하고 나머지 4개 항목(관리자 3 · 시책 1)은 안 줌
    ('FGC-FGL01-202608-0003','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-08-05',
     'A-FC-001','FGC-T010101', 100000, 100000, 240, NULL::numeric,'ACTIVE'),
    -- R4 같은 항목·회차를 두 번 지급
    ('FGC-FGL01-202608-0004','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-08-06',
     'A-FC-001','FGC-T010101', 100000, 100000, 240, NULL::numeric,'ACTIVE'),
    -- R5 모집설계사는 A-FC-002 인데 해촉자 A-FC-004 에게 지급
    ('FGC-FGL02-202608-0001','FGL02','STD-LIFE-B','2026-CURRENT-B', DATE '2026-08-07',
     'A-FC-002','FGC-T010101', 100000, 100000, 240, 520000::numeric,'ACTIVE'),
    -- R6 5월 계약. 8월이면 유지수수료 2·3회차가 도래해 있는데
    --    2회차(7/25 예상)를 건너뛰고 3회차(8/25)만 지급했습니다.
    --    ★ 계약일이 5/10 이므로 상반기 판매버전(2026-H1-B · 판매 1/01~6/30)을 씁니다.
    --      2026-CURRENT-B(7/01~)를 쓰면 "아직 팔지 않던 상품을 팔았다"가 되어
    --      상품버전 오류로 잡히고, 정작 보려던 "회차 누락"이 가려집니다.
    ('FGC-FGL02-202605-0001','FGL02','STD-LIFE-B','2026-H1-B',      DATE '2026-05-10',
     'A-FC-002','FGC-T010101', 100000, 100000, 240, 520000::numeric,'ACTIVE'),
    -- R7 7/15 해지된 계약에 8월 수수료를 지급
    ('FGC-FGN01-202603-0001','FGN01','STD-NL-A',  '2026-CURRENT-A', DATE '2026-03-05',
     'A-FC-001','FGC-T010101', 100000, 100000, 120, NULL::numeric,'TERMINATED'),
    -- R8 요율표에 없는 항목(조정)을 지급 → 예상 스케줄에 그 줄이 없음
    ('FGC-FGL01-202608-0005','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-08-12',
     'A-FC-001','FGC-T010101', 100000, 100000, 240, NULL::numeric,'ACTIVE'),

    -- ── 제2부 : 차익거래 ────────────────────────────────────────────────
    -- A1 표준해약공제액 80% 이상 공제 상품 + 36개월 이내 해지 → 환급금 합산 (REG-12)
    --    ★ 1월 계약이므로 상반기 판매버전(2026-H1-B)을 씁니다. R6와 같은 이유입니다.
    ('FGC-FGL02-202601-0001','FGL02','STD-LIFE-B','2026-H1-B',      DATE '2026-01-15',
     'A-FC-002','FGC-T010101', 100000, 100000, 240, 520000::numeric,'TERMINATED'),
    -- A2 일반 상품이라 해약환급금을 더하지 않음 → 기본식만
    ('FGC-FGL01-202601-0001','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2026-01-20',
     'A-FC-001','FGC-T010101', 100000, 100000, 240, NULL::numeric,'TERMINATED'),
    -- A3 납입기간 240개월인데 이 상품 환급률표는 120개월짜리뿐 → 조합이 없음
    --    ★ 없는 값을 추정해서 억지로 계산하지 마세요. 검토대상으로 둡니다 (REG-23)
    ('FGC-FGL03-202608-0001','FGL03','STD-TERM-A','2026-CURRENT-A', DATE '2026-08-15',
     'A-FC-001','FGC-T010101', 100000, 100000, 240, 480000::numeric,'ACTIVE'),

    -- ── 제3부 : 분급 체계 경계 (전부 REVIEW_REQUIRED 가 정답) ───────────
    -- G1 계약일은 2027년인데 상품 판매버전은 2026년 현행 체계
    ('FGC-FGL01-202703-0001','FGL01','STD-LIFE-A','2026-CURRENT-A', DATE '2027-03-02',
     'A-FC-001','FGC-T010101', 100000, 100000, 240, NULL::numeric,'ACTIVE'),
    -- G2 계약일은 2026년인데 상품 판매버전은 4년 분급(2027~)
    --    ★★ 이 어긋남은 **일부러 만든 것입니다. 고치지 마세요.**
    --      계약일 2026-11-10 < 판매시작일 2027-01-01 인 유일한 계약입니다.
    --      "계약일과 상품 판매기간이 맞지 않으면 시스템이 임의로 정하지 않고
    --       사람에게 넘긴다"를 확인하는 시나리오라서 그렇습니다 (REG-19).
    --      A1·R6 는 이런 의도가 없었던 실수라서 2026-H1-B 로 고쳤습니다.
    ('FGC-FGL02-202611-0001','FGL02','STD-LIFE-B','2027-FOUR-YEAR', DATE '2026-11-10',
     'A-FC-002','FGC-T010101', 100000, 100000, 240, 520000::numeric,'ACTIVE'),
    -- G3 TM(전화판매) 채널 — 시행일이 대면채널과 다름
    ('FGC-FGL03-202608-0002','FGL03','STD-TERM-A','2026-TM-A',      DATE '2026-08-20',
     'A-FC-002','FGC-T010102', 100000, 100000, 120, 480000::numeric,'ACTIVE')
  ) AS v(contract_no, insurer_code, std_code, offering_ver, contract_date,
         agent_code, org_code, premium, monthly_equiv, term, std_ded, status)
  JOIN insurer i           ON i.insurer_code = v.insurer_code
  JOIN product p           ON p.standard_product_code = v.std_code
  JOIN product_offering po ON po.product_id = p.product_id AND po.offering_version = v.offering_ver
  JOIN agent a             ON a.agent_code = v.agent_code
  JOIN organization o      ON o.organization_code = v.org_code
ON CONFLICT (insurer_id, contract_no) DO NOTHING;

-- ============================================================================
-- 계약상태 사건 — 해지 계약이 "언제" 해지됐는지
--   차익거래와 비유효 계약 판정은 **효력일** 기준입니다. 수신일이 아닙니다.
-- ============================================================================
INSERT INTO contract_status_event(contract_id, event_seq, previous_status, new_status,
                                  effective_at, received_at, processed_at,
                                  source_system, source_event_key, reason_code, data_origin)
SELECT c.contract_id, v.seq, v.prev, v.new_st, v.eff, v.recv, NULL::timestamptz,
       'INSURER_FEED', v.evt_key, v.reason,'SEED'
  FROM (VALUES
    ('FGC-FGN01-202603-0001', 1, NULL,     'ACTIVE',
     TIMESTAMPTZ '2026-03-05 00:00+09', TIMESTAMPTZ '2026-03-06 06:00+09','EVT-R7-A','NEW_CONTRACT'),
    ('FGC-FGN01-202603-0001', 2, 'ACTIVE', 'TERMINATED',
     TIMESTAMPTZ '2026-07-15 00:00+09', TIMESTAMPTZ '2026-07-16 06:00+09','EVT-R7-T','SURRENDER'),
    ('FGC-FGL02-202601-0001', 1, NULL,     'ACTIVE',
     TIMESTAMPTZ '2026-01-15 00:00+09', TIMESTAMPTZ '2026-01-16 06:00+09','EVT-A1-A','NEW_CONTRACT'),
    ('FGC-FGL02-202601-0001', 2, 'ACTIVE', 'TERMINATED',
     TIMESTAMPTZ '2026-08-20 00:00+09', TIMESTAMPTZ '2026-08-21 06:00+09','EVT-A1-T','SURRENDER'),
    ('FGC-FGL01-202601-0001', 1, NULL,     'ACTIVE',
     TIMESTAMPTZ '2026-01-20 00:00+09', TIMESTAMPTZ '2026-01-21 06:00+09','EVT-A2-A','NEW_CONTRACT'),
    ('FGC-FGL01-202601-0001', 2, 'ACTIVE', 'TERMINATED',
     TIMESTAMPTZ '2026-08-22 00:00+09', TIMESTAMPTZ '2026-08-23 06:00+09','EVT-A2-T','SURRENDER')
  ) AS v(contract_no, seq, prev, new_st, eff, recv, evt_key, reason)
  JOIN insurance_contract c ON c.contract_no = v.contract_no
ON CONFLICT DO NOTHING;

-- 나머지 계약은 정상 전환 1건씩
INSERT INTO contract_status_event(contract_id, event_seq, previous_status, new_status,
                                  effective_at, received_at, processed_at,
                                  source_system, source_event_key, reason_code, data_origin)
SELECT c.contract_id, 1, NULL,'ACTIVE',
       c.contract_date::timestamptz,
       (c.contract_date + 1)::timestamptz,
       (c.contract_date + 1)::timestamptz,
       'INSURER_FEED','EVT-' || c.contract_no || '-A','NEW_CONTRACT','SEED'
  FROM insurance_contract c
 WHERE c.data_origin='SEED'
   AND NOT EXISTS (SELECT 1 FROM contract_status_event e WHERE e.contract_id = c.contract_id)
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 계약 재무 스냅샷 — 차익거래 판정의 입력값
--
--   차익거래 판정식 (REG-12)
--     기본        = max(0, 지급수수료 − 환수금 − 실제 납입보험료)
--     환급금 합산 = max(0, 지급수수료 + 해약환급금 − 환수금 − 실제 납입보험료)
--
--   해약환급금을 더하는 조건 : 표준해약공제액 80% 이상 공제 상품 AND 36개월 이내 해지
-- ============================================================================
INSERT INTO contract_financial_snapshot(contract_id, as_of_date, contract_month_no,
                                        cumulative_paid_premium, surrender_value, surrender_value_type)
SELECT c.contract_id, v.as_of, v.month_no, v.cum, v.surr, v.surr_type
  FROM (VALUES
    -- A1 : 890,000 + 120,000 − 800,000 = 210,000  > 0 → CANDIDATE
    ('FGC-FGL02-202601-0001', DATE '2026-08-20', 8, 800000::numeric, 120000::numeric,'ACTUAL'),
    -- A2 : 890,000 − 800,000 = 90,000  > 0 → CANDIDATE (기본식 · 환급금 미합산)
    ('FGC-FGL01-202601-0001', DATE '2026-08-22', 8, 800000::numeric, 150000::numeric,'ACTUAL'),
    -- R7 : 5회 납입 후 해지
    ('FGC-FGN01-202603-0001', DATE '2026-07-15', 5, 500000::numeric,  60000::numeric,'ACTUAL'),
    -- A3 : 환급률표가 없어 예상 환급금을 못 구합니다 → NULL 로 둡니다
    ('FGC-FGL03-202608-0001', DATE '2026-08-31', 1, 100000::numeric, NULL::numeric,'ESTIMATED'),
    -- R6 : 5월 계약이라 8월이면 4차월
    ('FGC-FGL02-202605-0001', DATE '2026-08-31', 4, 400000::numeric, NULL::numeric,'EXPECTED_TABLE')
  ) AS v(contract_no, as_of, month_no, cum, surr, surr_type)
  JOIN insurance_contract c ON c.contract_no = v.contract_no
ON CONFLICT DO NOTHING;

INSERT INTO contract_financial_snapshot(contract_id, as_of_date, contract_month_no,
                                        cumulative_paid_premium, surrender_value, surrender_value_type)
SELECT c.contract_id,
       (date_trunc('month', c.contract_date) + INTERVAL '1 month - 1 day')::date,
       1, c.first_premium_amount, NULL::numeric,'EXPECTED_TABLE'
  FROM insurance_contract c
 WHERE c.data_origin='SEED'
   AND NOT EXISTS (SELECT 1 FROM contract_financial_snapshot s WHERE s.contract_id = c.contract_id)
ON CONFLICT DO NOTHING;

-- ============================================================================
-- 실제 지급 건 + 귀속행
--   V3 와 똑같은 순서로 갑니다 : DRAFT 로 넣고 → 귀속행 넣고 → CONFIRMED
--
--   ★ 회차(installment)를 담는 컬럼이 transaction_attribution 에 없습니다.
--     실제 쪽 회차는 귀속일·정산월로 유추해야 합니다.
--     대사 매칭키를 어떻게 잡을지가 단계 4의 설계 과제입니다.
-- ============================================================================
CREATE TEMP TABLE seed_tx4 (
  biz_key        text PRIMARY KEY,
  stage          text,
  source_type    text,
  contract_no    text,
  recipient_code text,
  item_code      text,
  amount         numeric(15,2),
  attr_date      date,
  src_agent_code text,
  note           text
);

INSERT INTO seed_tx4 VALUES
-- 컬럼 순서: biz_key, stage, source_type, contract_no, recipient_code,
--            item_code, amount, attr_date, src_agent_code, note

-- ── R1 : 전부 규칙대로 ──────────────────────────────────────────────────
('GA-2026-08-1101','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0001','A-FC-001','BASE_COMMISSION',       650000, DATE '2026-08-25', NULL,'R1 일치'),
('GA-2026-08-1102','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0001','A-TL-001','MANAGEMENT_COMMISSION',  40000, DATE '2026-08-25', NULL,'R1 일치'),
('GA-2026-08-1103','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0001','A-BM-001','MANAGEMENT_COMMISSION',  30000, DATE '2026-08-25', NULL,'R1 일치'),
('GA-2026-08-1104','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0001','A-DH-001','MANAGEMENT_COMMISSION',  20000, DATE '2026-08-25', NULL,'R1 일치'),
('GA-2026-08-1105','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0001','A-FC-001','INCENTIVE',             100000, DATE '2026-08-25', NULL,'R1 일치'),

-- ── R2 : 기본수수료만 630,000 (예상 650,000) → 차액 −20,000 ─────────────
('GA-2026-08-1201','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0002','A-FC-001','BASE_COMMISSION',       630000, DATE '2026-08-25', NULL,'R2 금액차이'),
('GA-2026-08-1202','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0002','A-TL-001','MANAGEMENT_COMMISSION',  40000, DATE '2026-08-25', NULL,'R2'),
('GA-2026-08-1203','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0002','A-BM-001','MANAGEMENT_COMMISSION',  30000, DATE '2026-08-25', NULL,'R2'),
('GA-2026-08-1204','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0002','A-DH-001','MANAGEMENT_COMMISSION',  20000, DATE '2026-08-25', NULL,'R2'),
('GA-2026-08-1205','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0002','A-FC-001','INCENTIVE',             100000, DATE '2026-08-25', NULL,'R2'),

-- ── R3 : 기본수수료만 지급. 나머지 4건(관리자 3 · 시책 1)은 실제가 없음 ─
('GA-2026-08-1301','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0003','A-FC-001','BASE_COMMISSION',       650000, DATE '2026-08-25', NULL,'R3 나머지 미지급'),

-- ── R4 : 기본수수료를 두 번 지급 ────────────────────────────────────────
--    ★ 두 건의 날짜가 같아야 합니다.
--      대사 허용오차가 TOLERANCE_DAYS = 0 (V3 의 ASM-RECON-ZERO-2026-V1) 이라
--      하루만 달라도 "같은 회차를 두 번 준 것"이 아니라 "서로 다른 지급 건"이 됩니다.
--      그러면 보려던 DUPLICATE 대신 엉뚱한 판정이 나옵니다.
--      계약 · 항목 · 수취인 · 회차 · 귀속일 · 지급예정일 · 금액이 전부 같고
--      source_business_key 만 다른 것 — 이게 중복의 정의입니다.
('GA-2026-08-1401','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0004','A-FC-001','BASE_COMMISSION',       650000, DATE '2026-08-25', NULL,'R4 중복 1/2'),
('GA-2026-08-1402','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0004','A-FC-001','BASE_COMMISSION',       650000, DATE '2026-08-25', NULL,'R4 중복 2/2'),

-- ── R5 : 해촉자 A-FC-004 에게 지급 (모집설계사는 A-FC-002) ──────────────
('GA-2026-08-1501','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL02-202608-0001','A-FC-004','BASE_COMMISSION',       650000, DATE '2026-08-25','L02-11029','R5 설계사 불일치'),

-- ── R6 : 5월 계약. 2회차(7/25 예상) 건너뛰고 3회차(8/25)만 지급 ─────────
('GA-2026-06-1601','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL02-202605-0001','A-FC-002','BASE_COMMISSION',       650000, DATE '2026-06-25', NULL,'R6 1회차 정상'),
('GA-2026-08-1602','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL02-202605-0001','A-FC-002','MAINTENANCE_COMMISSION',  5000, DATE '2026-08-25', NULL,'R6 2회차 건너뜀'),

-- ── R7 : 7/15 해지된 계약에 8월 수수료 ──────────────────────────────────
('GA-2026-08-1701','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGN01-202603-0001','A-FC-001','BASE_COMMISSION',       650000, DATE '2026-08-25', NULL,'R7 해지 계약에 지급'),

-- ── R8 : 요율표에 없는 항목 지급 ────────────────────────────────────────
('GA-2026-08-1801','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202608-0005','A-FC-001','BASE_COMMISSION',       650000, DATE '2026-08-25', NULL,'R8'),
('GA-2026-08-1802','GA_TO_FC','GA_MANUAL_PAYMENT',   'FGC-FGL01-202608-0005','A-FC-001','ADJUSTMENT',             80000, DATE '2026-08-25', NULL,'R8 규칙에 없는 항목'),

-- ── A1 : 1월 계약. 이미 890,000 지급됨 ──────────────────────────────────
('GA-2026-01-2101','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL02-202601-0001','A-FC-002','BASE_COMMISSION',       650000, DATE '2026-01-25', NULL,'A1'),
('GA-2026-01-2102','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL02-202601-0001','A-TL-001','MANAGEMENT_COMMISSION',  90000, DATE '2026-01-25', NULL,'A1 관리자 합산'),
('GA-2026-01-2103','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL02-202601-0001','A-FC-002','INCENTIVE',             100000, DATE '2026-01-25', NULL,'A1'),
('GA-2026-01-2104','GA_TO_FC','ALLOCATION_POOL',     'FGC-FGL02-202601-0001','A-FC-002','COMMON_COST',            50000, DATE '2026-01-25', NULL,'A1 공통비 안분'),

-- ── A2 : 환급금 미합산 ──────────────────────────────────────────────────
('GA-2026-01-2201','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202601-0001','A-FC-001','BASE_COMMISSION',       650000, DATE '2026-01-25', NULL,'A2'),
('GA-2026-01-2202','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202601-0001','A-TL-001','MANAGEMENT_COMMISSION',  90000, DATE '2026-01-25', NULL,'A2 관리자 합산'),
('GA-2026-01-2203','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL01-202601-0001','A-FC-001','INCENTIVE',             100000, DATE '2026-01-25', NULL,'A2'),
('GA-2026-01-2204','GA_TO_FC','ALLOCATION_POOL',     'FGC-FGL01-202601-0001','A-FC-001','COMMON_COST',            50000, DATE '2026-01-25', NULL,'A2 공통비 안분'),

-- ── A3 : 환급률표 없음. 지급 자체는 정상 ────────────────────────────────
('GA-2026-08-2301','GA_TO_FC','GA_CONFIRMED_PAYMENT','FGC-FGL03-202608-0001','A-FC-001','BASE_COMMISSION',       650000, DATE '2026-08-25', NULL,'A3 환급률표 없음'),

-- ── 원수사 → GA 실제 명세 ───────────────────────────────────────────────
--    R2 는 원수사 쪽에서도 20,000 적게 들어왔다고 두어 양방향 대사를 시험합니다
('INS-FGL01-202608-0001','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202608-0001', NULL,'BASE_COMMISSION',    900000, DATE '2026-08-31','L01-77881','R1'),
('INS-FGL01-202608-0002','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202608-0002', NULL,'BASE_COMMISSION',    880000, DATE '2026-08-31','L01-77881','R2 원수사도 20,000 적음'),
('INS-FGL01-202608-0003','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202608-0003', NULL,'BASE_COMMISSION',    900000, DATE '2026-08-31','L01-77881','R3'),
('INS-FGL01-202608-0004','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202608-0004', NULL,'BASE_COMMISSION',    900000, DATE '2026-08-31','L01-77881','R4'),
('INS-FGL02-202608-0001','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL02-202608-0001', NULL,'BASE_COMMISSION',    900000, DATE '2026-08-31','L02-99999','R5 매핑 안 되는 설계사코드'),
('INS-FGL02-202606-0001','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL02-202605-0001', NULL,'BASE_COMMISSION',    900000, DATE '2026-06-30','L02-11028','R6 1회차'),
('INS-FGL01-202608-0005','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL01-202608-0005', NULL,'BASE_COMMISSION',    900000, DATE '2026-08-31','L01-77881','R8'),
('INS-FGL03-202608-0001','INSURER_TO_GA','INSURER_STATEMENT','FGC-FGL03-202608-0001', NULL,'BASE_COMMISSION',    900000, DATE '2026-08-31','L03-55011','A3');

-- ① DRAFT 로 지급 건 생성
INSERT INTO commission_transaction(payment_stage, source_type, source_business_key,
                                   statement_batch_id, insurer_id, recipient_agent_id,
                                   commission_item_id, policy_version_id,
                                   settlement_month, due_date, amount, cashflow_type, note)
SELECT t.stage, t.source_type, t.biz_key,
       sb.statement_batch_id, c.insurer_id, ra.agent_id,
       ci.commission_item_id, pv.policy_version_id,
       date_trunc('month', t.attr_date)::date,
       t.attr_date, t.amount,'PAYMENT', t.note
  FROM seed_tx4 t
  JOIN commission_item ci      ON ci.item_code = t.item_code
  JOIN insurance_contract c    ON c.contract_no = t.contract_no
  LEFT JOIN agent ra           ON ra.agent_code = t.recipient_code
  LEFT JOIN statement_batch sb ON sb.insurer_id = c.insurer_id
                              AND sb.settlement_month = date_trunc('month', t.attr_date)::date
                              AND t.source_type = 'INSURER_STATEMENT'
  LEFT JOIN policy_version pv  ON pv.policy_code = CASE t.stage
                                    WHEN 'INSURER_TO_GA' THEN 'INS-CUR-2026-V1'
                                    ELSE 'GA-CUR-2026-V1' END
ON CONFLICT (source_type, source_business_key) DO NOTHING;

-- ② 귀속행 (전부 계약 귀속 · 산입)
INSERT INTO transaction_attribution(commission_transaction_id, attribution_scope,
                                    contract_id, agent_id, source_agent_code,
                                    attribution_date, attribution_month, attributed_amount,
                                    cap_rule_item_id, inclusion_status_snapshot,
                                    attribution_method, allocation_policy_id, evidence_ref)
SELECT ct.commission_transaction_id,'CONTRACT',
       c.contract_id, COALESCE(ra.agent_id, c.agent_id), t.src_agent_code,
       t.attr_date, date_trunc('month', t.attr_date)::date, t.amount,
       cri.cap_rule_item_id,'INCLUDED',
       -- 공통비는 승인된 안분정책을 근거로 배부합니다 (REG-06A·REG-06C).
       -- 귀속방법이 APPROVED_ALLOCATION 이면 안분정책이 반드시 있어야 합니다 (DB CHECK).
       CASE WHEN t.item_code='COMMON_COST' THEN 'APPROVED_ALLOCATION' ELSE 'DIRECT' END,
       CASE WHEN t.item_code='COMMON_COST'
            THEN (SELECT allocation_policy_id FROM allocation_policy
                   WHERE allocation_code='GA-COMMON-PREMIUM-PROPORTIONAL')
            ELSE NULL END,
       t.note
  FROM seed_tx4 t
  JOIN commission_transaction ct ON ct.source_business_key = t.biz_key
                                AND ct.source_type = t.source_type
  JOIN commission_item ci        ON ci.item_code = t.item_code
  JOIN insurance_contract c      ON c.contract_no = t.contract_no
  LEFT JOIN agent ra             ON ra.agent_code = t.recipient_code
  -- 원수사→GA는 2021~2026(공제 0%)·2027~(공제 3%) 룰셋 두 개라 stage만으로는 계약을 특정 못 한다.
  -- 1,200%룰 적용 룰셋은 계약 체결일 기준이므로(REG-19) c.contract_date로도 좁힌다.
  LEFT JOIN cap_rule_set crs     ON crs.payment_stage = t.stage
                                AND crs.contract_date_from <= c.contract_date
                                AND (crs.contract_date_to IS NULL OR crs.contract_date_to >= c.contract_date)
  LEFT JOIN cap_rule_item cri    ON cri.cap_rule_set_id = crs.cap_rule_set_id
                                AND cri.commission_item_id = ci.commission_item_id
 WHERE NOT EXISTS (
        SELECT 1 FROM transaction_attribution ta
         WHERE ta.commission_transaction_id = ct.commission_transaction_id);

-- ③ 확정
UPDATE commission_transaction ct
   SET status='CONFIRMED', paid_on = ct.due_date
  FROM seed_tx4 t
 WHERE ct.source_business_key = t.biz_key
   AND ct.source_type = t.source_type
   AND ct.status='DRAFT';

DROP TABLE seed_tx4;

-- ============================================================================
-- 정답표 — 여러분의 엔진이 이렇게 판정해야 합니다
-- ============================================================================
--
-- 【제1부 · 대사】
--   FGC-FGL01-202608-0001   5줄 모두 MATCHED (기본 · 관리자3 · 시책)
--   FGC-FGL01-202608-0002   기본수수료 AMOUNT_DIFFERENCE 차액 −20,000
--   FGC-FGL01-202608-0003   4줄 ACTUAL_MISSING (관리자3 · 시책)
--   FGC-FGL01-202608-0004   기본수수료 DUPLICATE (같은 항목·회차 2건)
--                           두 건 모두 08/25 · 650,000원 · A-FC-001.
--                           source_business_key 만 1401 / 1402 로 다릅니다
--   FGC-FGL02-202608-0001   AGENT_MISMATCH (예상 A-FC-002 / 실제 A-FC-004)
--                           원수사 명세의 L02-99999 는 매핑되는 설계사가 없음
--   FGC-FGL02-202605-0001   유지수수료 2회차(7/25 예상)를 건너뜀
--                             07월 대사 → ACTUAL_MISSING
--                             08월 대사 → 3회차만 존재
--                           이 둘을 INSTALLMENT_MISMATCH 한 건으로 묶을지는
--                           매칭키 설계에 달렸습니다 (단계 4의 과제)
--   FGC-FGN01-202603-0001   INVALID_CONTRACT_PAYMENT (7/15 해지 · 8월 지급)
--   FGC-FGL01-202608-0005   조정 80,000 EXPECTED_MISSING (요율표에 없는 항목)
--
-- 【제2부 · 차익거래】
--   지급수수료 890,000 = 기본 650,000 + 관리자 90,000 + 시책 100,000 + 공통비 50,000
--
--   FGC-FGL02-202601-0001   CANDIDATE · 환급금 합산 O
--                             890,000 + 120,000 − 800,000 = 210,000
--                             (80% 공제 상품 + 계약 후 36개월 이내 해지라 환급금을 더함)
--   FGC-FGL01-202601-0001   CANDIDATE · 환급금 합산 X
--                             890,000 − 800,000 = 90,000
--                             (일반 상품이라 해약환급금 150,000 을 더하지 않음)
--   FGC-FGL03-202608-0001   REVIEW_REQUIRED · 환급률표 조합 없음
--                             (납입 240개월인데 이 상품 표는 120개월짜리뿐)
--                             → REFUND_TABLE_MISSING 예외 생성
--
-- 【제3부 · 분급 체계 경계 — 전부 REVIEW_REQUIRED】
--   FGC-FGL01-202703-0001   계약일 2027 · 상품은 현행(CURRENT)
--                             계약연도만 보고 4년분급으로 정하면 오답
--   FGC-FGL02-202611-0001   계약일 2026 · 상품은 FOUR_YEAR_2027
--                             적용할 4년분급 정책이 없음 → POLICY_MISSING 예외
--                             ★ 계약일(2026-11-10) < 판매시작일(2027-01-01) 인
--                               유일한 계약입니다. 일부러 그렇게 둔 것입니다.
--                               "계약일과 판매기간이 어긋나면 사람에게 넘긴다"를
--                               확인하는 시나리오이므로 데이터를 고치지 마세요
--   FGC-FGL03-202608-0002   TM 채널 → 시행일이 대면채널과 다름
--
--   ★ 제3부의 정답이 전부 "검토필요"인 이유
--     분급 체계는 계약 체결연도만으로 정하지 않습니다.
--     상품 판매개시일 · 기초서류 버전 · 판매채널을 함께 봐야 합니다 (REG-19).
--     엇갈리면 시스템이 임의로 정하지 말고 사람에게 넘깁니다.
--
-- ── 확인용 쿼리 ────────────────────────────────────────────────────────────
-- SELECT c.contract_no, c.contract_date, c.current_status,
--        po.offering_version, po.fee_regime_code, po.channel_code,
--        po.standard_deduction_80_yn AS ded80,
--        COALESCE(SUM(ta.attributed_amount),0)::bigint AS ga_to_fc_paid
--   FROM insurance_contract c
--   JOIN product_offering po ON po.product_offering_id = c.product_offering_id
--   LEFT JOIN transaction_attribution ta ON ta.contract_id = c.contract_id
--   LEFT JOIN commission_transaction ct  ON ct.commission_transaction_id = ta.commission_transaction_id
--                                       AND ct.payment_stage = 'GA_TO_FC'
--  GROUP BY 1,2,3,4,5,6,7 ORDER BY 1;
-- ============================================================================
