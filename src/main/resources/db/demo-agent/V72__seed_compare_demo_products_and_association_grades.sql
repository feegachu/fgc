-- 상품 추천 AI(FUN-079) 시연용 예시 데이터 (시연 서버·로컬 전용).
--  1) 협회 등급(commission_grade)과 비교 상품군(compare_product_group) — 등급이 "자료없음"으로만 나오지 않게 한다.
--  2) 카테고리마다 3개 이상 보험사가 비교되도록 가상 보험사·상품·판매버전과 예상 해약환급률표(REG-23 상품 자료)를 추가한다.
--     해약환급률표는 계약 등록·한도 검증이 쓰는 상품 자료이며 순위의 환수율이 아니다. 순위의 환수율은 환수 정책(CLAWBACK)의
--     규칙에서 읽는다(D-01, 인터페이스정의서 §4-4-6). 환급률표가 없는 상품('가상 신상품 건강보험 H')도 하나 둔다.
--  3) 가상 보험사 3곳의 보험사 전용 환수 정책(CLAWBACK) — 보험사마다 환수 조건이 달라 순수령액이 갈리는 시연용 가정.
-- 수수료 규칙은 건드리지 않는다. 적용 중인 GA 수수료 정책(GA-CUR-2026-V1)은 잠겨 있고, 새 정책 버전을 더하면
-- 유효 정책이 둘이 되어 모든 상품이 "정책 버전 모호"로 제외되기 때문이다. 새 상품도 공통 수수료율을 그대로 쓴다.
-- 환급률표는 V42 와 같은 방식으로 새 정책 버전을 DRAFT 로 만들어 적재한 뒤 승격한다.
-- db/demo 는 신한 prd 도 읽으므로 이 파일은 db/demo-agent 에 둔다. 모든 값은 합성이며 여러 번 실행해도 중복되지 않는다.
SET search_path TO fgc, public;

-- ---------------------------------------------------------------- 가상 보험사
INSERT INTO insurer (insurer_code, insurer_name, insurer_type)
SELECT v.code, v.name, v.type
  FROM (VALUES ('FGL05', '하늘가상생명', 'LIFE'),
               ('FGL06', '푸른가상생명', 'LIFE'),
               ('FGN03', '다온가상손해보험', 'NON_LIFE')) AS v(code, name, type)
 WHERE NOT EXISTS (SELECT 1 FROM insurer i WHERE i.insurer_code = v.code);

-- ---------------------------------------------------------------- 해약환급률표용 새 정책 버전 (DRAFT)
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class, version_no, effective_from, effective_to, status,
                           regulation_refs, source_refs, approval_evidence_ref, created_by)
SELECT 'INS-REFUND-2026-DEMO-V1', '예상 해약환급률표(시연 상품 보강)', 'REFUND_RATE', 'INSURER_RULE', 1, DATE '2026-01-01', NULL, 'DRAFT',
       ARRAY['REG-23'], ARRAY['원수사 예상 해약환급률표 2026'], 'PROJECT_ASSUMPTION — 합성값, 상품 추천 AI 시연 보강',
       (SELECT user_id FROM app_user WHERE login_id = 'settle01')
 WHERE NOT EXISTS (SELECT 1 FROM policy_version WHERE policy_code = 'INS-REFUND-2026-DEMO-V1' AND version_no = 1);

INSERT INTO policy_parameter(policy_version_id, parameter_key, parameter_value, description)
SELECT pv.policy_version_id, 'REFUND_RATE_BASIS', '"MONTHLY_EQUIVALENT_FIRST_PREMIUM_X12"'::jsonb,
       '환급률(%)을 곱할 기준 금액. 월납환산 초회보험료 × 12 에 환급률을 곱해 예상 해약환급금을 구한다'
  FROM policy_version pv
 WHERE pv.policy_code = 'INS-REFUND-2026-DEMO-V1' AND pv.status = 'DRAFT'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 상품·판매버전·해약환급률표
-- spec: 상품 코드, 보험사, 상품명, 상품군, 환급률 증가폭(%p/월; 12개월에 slope×12 %), 환급률표 유무
DO $seed$
DECLARE
    s record;
    v_template record;
    v_product_id bigint;
    v_offering_id bigint;
    v_table_id bigint;
    v_pv bigint;
BEGIN
    SELECT policy_version_id INTO v_pv FROM policy_version WHERE policy_code = 'INS-REFUND-2026-DEMO-V1' AND version_no = 1;
    FOR s IN
        SELECT * FROM (VALUES
            ('P-H-101', 'FGL05', '가상 든든 건강보험 C',       'HEALTH_PROTECTION', 3.2, true),
            ('P-H-102', 'FGL06', '가상 무해지 건강보험 D',     'HEALTH_PROTECTION', 1.1, true),
            ('P-H-103', 'FGN02', '가상 장기 건강보험 E',       'HEALTH_PROTECTION', 2.6, true),
            ('P-H-104', 'FGN03', '가상 간편 건강보험 F',       'HEALTH_PROTECTION', 3.2, true),
            ('P-H-105', 'FGL03', '가상 암보장 건강보험 G',     'HEALTH_PROTECTION', 2.0, true),
            ('P-H-106', 'FGL04', '가상 신상품 건강보험 H',     'HEALTH_PROTECTION', 0.0, false),
            ('P-T-101', 'FGL05', '가상 정기보험 K',           'TERM_PROTECTION',   1.5, true),
            ('P-T-102', 'FGL06', '가상 CEO 정기보험 L',        'TERM_PROTECTION',   2.2, true),
            ('P-N-101', 'FGN02', '가상 장기상해보험 M',        'LONG_TERM_NONLIFE', 2.4, true),
            ('P-N-102', 'FGN03', '가상 운전자보험 N',          'LONG_TERM_NONLIFE', 1.2, true),
            ('P-S-101', 'FGL05', '가상 연금저축보험 O',        'SAVINGS',           3.5, true),
            ('P-S-102', 'FGL06', '가상 변액 연금저축보험 P',   'SAVINGS',           2.8, true)
        ) AS t(code, insurer_code, name, group_code, slope, has_refund)
    LOOP
        CONTINUE WHEN EXISTS (SELECT 1 FROM product WHERE insurer_product_code = s.code);

        -- 같은 상품군의 기존 상품을 본떠 보호 유형과 환급률표 납입기간을 맞춘다.
        SELECT p.protection_type, rt.payment_term_months INTO v_template
          FROM product p JOIN refund_rate_table rt ON rt.product_id = p.product_id
         WHERE p.product_group_code = s.group_code
         ORDER BY p.product_id LIMIT 1;

        INSERT INTO product (insurer_id, insurer_product_code, standard_product_code, product_name, product_group_code, protection_type)
        SELECT i.insurer_id, s.code, 'STD-' || s.code, s.name, s.group_code, v_template.protection_type
          FROM insurer i WHERE i.insurer_code = s.insurer_code
        RETURNING product_id INTO v_product_id;

        INSERT INTO product_offering (product_id, offering_version, sales_start_date, basic_document_version, basic_document_date,
                                      channel_code, fee_regime_code, standard_deduction_80_yn, committee_approval_date, governance_status)
        VALUES (v_product_id, '2026-CURRENT-A', DATE '2026-01-01', 'BD-2026-01', DATE '2026-01-01',
                'FACE_TO_FACE', 'CURRENT', false, DATE '2026-01-01', 'APPROVED')
        RETURNING product_offering_id INTO v_offering_id;

        IF s.has_refund THEN
            INSERT INTO refund_rate_table (policy_version_id, insurer_id, product_id, product_offering_id, payment_term_months,
                                           channel_code, standard_deduction_80_yn, effective_from, source_product_code, source_document_ref)
            SELECT v_pv, p.insurer_id, p.product_id, v_offering_id, v_template.payment_term_months,
                   'FACE_TO_FACE', false, DATE '2026-01-01', p.standard_product_code, '원수사 예상 해약환급률표 2026 (합성값)'
              FROM product p WHERE p.product_id = v_product_id
            RETURNING refund_rate_table_id INTO v_table_id;

            INSERT INTO refund_rate_line (refund_rate_table_id, contract_month_no, refund_rate_pct)
            SELECT v_table_id, m, LEAST(100.0, round((m * s.slope)::numeric, 1))
              FROM generate_series(1, 36) AS m;
        END IF;
    END LOOP;
END
$seed$;

-- 승격 DRAFT → APPROVED → ACTIVE (직행은 트리거가 막는다). 재실행 시 해당 상태의 행이 없어 아무 일도 하지 않는다.
UPDATE policy_version
   SET status = 'APPROVED', approved_at = TIMESTAMPTZ '2026-06-30 09:00:00+09',
       approved_by = (SELECT user_id FROM app_user WHERE login_id = 'gaadmin'),
       approval_evidence_ref = '시드 승인 (상품 추천 AI 시연 보강)'
 WHERE status = 'DRAFT' AND policy_code = 'INS-REFUND-2026-DEMO-V1';

UPDATE policy_version SET status = 'ACTIVE' WHERE status = 'APPROVED' AND policy_code = 'INS-REFUND-2026-DEMO-V1';

-- ---------------------------------------------------------------- 보험사별 가상 환수율 (CLAWBACK, D-01 시연 가정)
-- 실제 환수율은 보험사↔GA 위탁계약·GA↔설계사 위촉계약마다 다르다(REG-14, 법정 단일 환수율 없음). D-02(운영정책서 제48조의3 4항)는
-- 보험사마다 환수율이 다르면 정책을 나눠 등록한다. 이 파일이 만든 가상 보험사 3곳에 그 구조대로 보험사 전용 정책을 둔다.
-- 형식은 #766 V70_1(D-02 가상 환수율표)과 같고(기본·인센티브 수수료, 경과 0~11·12~23·24개월 이후) 첫해 환수율만 다르다.
-- 업무 규칙 아님 — 가상 GA 정책으로 정함(PROJECT_ASSUMPTION). 세 보험사는 계약이 없어 FUN-054·055 환수 후보에는 영향이 없다.
-- 상품 비교는 보험사 전용 정책을 전체 정책(V70_1)보다 먼저 쓴다(인터페이스정의서 §4-4-6).
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class, version_no, effective_from, effective_to, status,
                           regulation_refs, source_refs, created_by)
SELECT v.code, v.name, 'CLAWBACK', 'GA_POLICY', 1, DATE '2026-01-01', NULL, 'DRAFT',
       ARRAY['REG-13', 'REG-14'], ARRAY['D-01 시연 가정', 'FGC 운영정책서 제48조의3'],
       (SELECT user_id FROM app_user WHERE login_id = 'settle01')
  FROM (VALUES ('CLAW-FGN03-DEMO-2026', '다온가상손해보험 환수율(시연 가정)'),
               ('CLAW-FGL06-DEMO-2026', '푸른가상생명 환수율(시연 가정)'),
               ('CLAW-FGL05-DEMO-2026', '하늘가상생명 환수율(시연 가정)')) AS v(code, name)
 WHERE NOT EXISTS (SELECT 1 FROM policy_version pv WHERE pv.policy_code = v.code AND pv.version_no = 1);

-- 첫해(경과 0~11개월) 환수율: 다온 40% · 푸른 60% · 하늘 80%, 둘째 해는 그 절반, 24개월 이후 0%(실제 0원).
INSERT INTO clawback_rule(policy_version_id, insurer_id, product_offering_id, commission_item_id,
                          elapsed_month_from, elapsed_month_to, clawback_basis_code, clawback_rate_pct)
SELECT pv.policy_version_id, i.insurer_id, NULL, item.commission_item_id, band.month_from, band.month_to,
       'PAID_ATTRIBUTED_AMOUNT', CASE band.year WHEN 1 THEN v.first_year WHEN 2 THEN v.first_year / 2 ELSE 0 END
  FROM (VALUES ('CLAW-FGN03-DEMO-2026', 'FGN03', 40.000000),
               ('CLAW-FGL06-DEMO-2026', 'FGL06', 60.000000),
               ('CLAW-FGL05-DEMO-2026', 'FGL05', 80.000000)) AS v(code, insurer_code, first_year)
  JOIN policy_version pv ON pv.policy_code = v.code AND pv.version_no = 1 AND pv.status = 'DRAFT'
  JOIN insurer i ON i.insurer_code = v.insurer_code
  JOIN commission_item item ON item.item_code IN ('BASE_COMMISSION', 'INCENTIVE')
 CROSS JOIN (VALUES (1, 0, 11), (2, 12, 23), (3, 24, 1199)) AS band(year, month_from, month_to);

UPDATE policy_version
   SET status = 'APPROVED', approved_at = TIMESTAMPTZ '2026-06-30 09:00:00+09',
       approved_by = (SELECT user_id FROM app_user WHERE login_id = 'gaadmin'),
       approval_evidence_ref = '시드 승인 (상품 추천 AI 시연 — 보험사별 가상 환수율, PROJECT_ASSUMPTION)'
 WHERE status = 'DRAFT' AND policy_code IN ('CLAW-FGN03-DEMO-2026', 'CLAW-FGL06-DEMO-2026', 'CLAW-FGL05-DEMO-2026');

UPDATE policy_version SET status = 'ACTIVE'
 WHERE status = 'APPROVED' AND policy_code IN ('CLAW-FGN03-DEMO-2026', 'CLAW-FGL06-DEMO-2026', 'CLAW-FGL05-DEMO-2026');

-- ---------------------------------------------------------------- 협회 등급 (상품군 분류 + 상품별 등급·순위)
INSERT INTO compare_product_group (group_code, insurance_sector, major_class, minor_class_name)
SELECT v.code, v.sector, v.major, v.name
  FROM (VALUES ('LIFE_HEALTH',      'LIFE',    'PROTECTION', '생명보험 건강보장'),
               ('NONLIFE_HEALTH',   'NONLIFE', 'PROTECTION', '손해보험 건강보장'),
               ('LIFE_TERM',        'LIFE',    'PROTECTION', '생명보험 정기보장'),
               ('NONLIFE_LONGTERM', 'NONLIFE', 'PROTECTION', '손해보험 장기보장'),
               ('LIFE_PENSION',     'LIFE',    'SAVINGS',    '생명보험 연금저축')) AS v(code, sector, major, name)
 WHERE NOT EXISTS (SELECT 1 FROM compare_product_group g WHERE g.group_code = v.code);

-- 상품 이름으로 짝지어 모든 판매버전에 같은 등급을 준다. '가상 신상품 건강보험 H' 는 일부러 빼서 "자료없음"도 보이게 한다.
INSERT INTO commission_grade (product_offering_id, compare_product_group_id, underwriting_type, refund_type, grade_code,
                              rank_no, rank_set_key, data_as_of, self_calc_grade_code)
SELECT o.product_offering_id, g.compare_product_group_id, v.uw, v.refund, v.grade, v.rank_no, v.set_key, DATE '2026-01-01', v.grade
  FROM (VALUES
        ('가상 건강보장보험 A',      'LIFE_HEALTH',      'STANDARD',   'STANDARD',        'HIGH',      2, 'LIFE_HEALTH:2026Q1'),
        ('가상 저해지 건강보험 B',   'LIFE_HEALTH',      'STANDARD',   'LOW_REFUND',      'NORMAL',    4, 'LIFE_HEALTH:2026Q1'),
        ('가상 든든 건강보험 C',     'LIFE_HEALTH',      'STANDARD',   'STANDARD',        'HIGH',      1, 'LIFE_HEALTH:2026Q1'),
        ('가상 무해지 건강보험 D',   'LIFE_HEALTH',      'SIMPLIFIED', 'NO_REFUND',       'LOW',       6, 'LIFE_HEALTH:2026Q1'),
        ('가상 장기 건강보험 E',     'NONLIFE_HEALTH',   'STANDARD',   'STANDARD',        'VERY_HIGH', 1, 'NONLIFE_HEALTH:2026Q1'),
        ('가상 간편 건강보험 F',     'NONLIFE_HEALTH',   'SIMPLIFIED', 'STANDARD',        'NORMAL',    2, 'NONLIFE_HEALTH:2026Q1'),
        ('가상 암보장 건강보험 G',   'LIFE_HEALTH',      'STANDARD',   'MATURITY_REFUND', 'VERY_HIGH', 3, 'LIFE_HEALTH:2026Q1'),
        ('가상 경영인정기보험',      'LIFE_TERM',        'STANDARD',   'PURE_PROTECTION', 'NORMAL',    2, 'LIFE_TERM:2026Q1'),
        ('가상 정기보험 K',          'LIFE_TERM',        'STANDARD',   'PURE_PROTECTION', 'LOW',       3, 'LIFE_TERM:2026Q1'),
        ('가상 CEO 정기보험 L',      'LIFE_TERM',        'STANDARD',   'PURE_PROTECTION', 'HIGH',      1, 'LIFE_TERM:2026Q1'),
        ('가상 장기상해보험 A',      'NONLIFE_LONGTERM', 'STANDARD',   'STANDARD',        'NORMAL',    2, 'NONLIFE_LONGTERM:2026Q1'),
        ('가상 장기상해보험 M',      'NONLIFE_LONGTERM', 'STANDARD',   'STANDARD',        'HIGH',      1, 'NONLIFE_LONGTERM:2026Q1'),
        ('가상 운전자보험 N',        'NONLIFE_LONGTERM', 'SIMPLIFIED', 'LOW_REFUND',      'VERY_LOW',  3, 'NONLIFE_LONGTERM:2026Q1'),
        ('가상 연금저축보험',        'LIFE_PENSION',     'STANDARD',   'STANDARD',        'NORMAL',    2, 'LIFE_PENSION:2026Q1'),
        ('가상 연금저축보험 O',      'LIFE_PENSION',     'STANDARD',   'STANDARD',        'HIGH',      1, 'LIFE_PENSION:2026Q1'),
        ('가상 변액 연금저축보험 P', 'LIFE_PENSION',     'STANDARD',   'LOW_REFUND',      'LOW',       3, 'LIFE_PENSION:2026Q1')
       ) AS v(product_name, group_code, uw, refund, grade, rank_no, set_key)
  JOIN compare_product_group g ON g.group_code = v.group_code
  JOIN product p ON p.product_name = v.product_name
  JOIN product_offering o ON o.product_id = p.product_id
 WHERE NOT EXISTS (SELECT 1 FROM commission_grade c
                    WHERE c.product_offering_id = o.product_offering_id AND c.data_as_of = DATE '2026-01-01'
                      AND COALESCE(c.rank_set_key, '') = v.set_key);
