-- ============================================================================
-- V59: 분급 룰셋 데이터 모델 (FUN-014-01, #534)
--
-- 왜: 4년(2027)·7년(2029) 분급의 회차 수·월 요율·매월 동일 금액 조건·장기유지수수료·
--     계약체결비용 한도·경과조치를 코드 상수가 아니라 버전 데이터로 둔다(COR-004).
--     계약일이 같아도 판매개시일·기초서류 버전·작성/변경일·수수료체계가 다르면 적용 체계가 달라지므로
--     (REG-19) 룰셋 행이 이 키의 적용 범위를 직접 가진다. 판매채널은 대면 하나라 판정 기준이 아니다(SRC-036).
--
-- 무엇을
--   fee_regime_ruleset             정책버전(policy_version) 아래의 룰셋 헤더. 회차 수·월 요율·장기유지 구간
--   fee_regime_ruleset_limit       계약체결비용 한도(선지급 총액·유지관리 월액·유지관리 총액·장기유지 월액)
--   fee_regime_transition_rule     경과조치(적용 구간·조건)
--
-- 정책버전이 APPROVED/ACTIVE/RETIRED 가 되면 아래 세 테이블도 변경할 수 없다.
-- (V2_1 이 policy_parameter 를 더한 것과 같은 방식으로 guard_policy_child_mutation 을 교체한다.)
--
-- 이 파일은 구조만 만든다. 요율·한도 수치는 승인된 정책 근거(FUN-016)로 등록하며 여기서 임의로 넣지 않는다.
-- ============================================================================

CREATE TABLE fgc.fee_regime_ruleset (
    fee_regime_ruleset_id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_version_id               bigint       NOT NULL REFERENCES fgc.policy_version(policy_version_id),
    fee_regime_code                 varchar(40)  NOT NULL
                                        CHECK (fee_regime_code IN ('FOUR_YEAR_2027', 'SEVEN_YEAR_2029')),
    -- 룰셋 선택 키. 기초서류 버전은 필수(상품 판매버전에는 항상 있다). 판매개시일·작성/변경일 구간의 NULL 경계는 무제한.
    sales_start_date_from           date         NOT NULL,
    sales_start_date_to             date,
    basic_document_version          varchar(80)  NOT NULL,
    basic_document_date_from        date,
    basic_document_date_to          date,
    -- 분급 파라미터
    total_installments              integer      NOT NULL CHECK (total_installments > 0),
    monthly_rate_pct                numeric(9,6) NOT NULL CHECK (monthly_rate_pct >= 0),
    equal_monthly_amount_yn         boolean      NOT NULL DEFAULT true,
    long_term_maintenance_from      integer,
    long_term_maintenance_to        integer,
    review_required_yn              boolean      NOT NULL DEFAULT false,
    source_refs                     text[]       NOT NULL DEFAULT '{}',
    created_at                      timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_fee_regime_ruleset_sales_period
        CHECK (sales_start_date_to IS NULL OR sales_start_date_to >= sales_start_date_from),
    CONSTRAINT ck_fee_regime_ruleset_document_period
        CHECK (basic_document_date_to IS NULL OR basic_document_date_from IS NULL
               OR basic_document_date_to >= basic_document_date_from),
    CONSTRAINT ck_fee_regime_ruleset_long_term CHECK (
        (long_term_maintenance_from IS NULL AND long_term_maintenance_to IS NULL)
        OR (long_term_maintenance_from IS NOT NULL AND long_term_maintenance_to IS NOT NULL
            AND long_term_maintenance_from > 0
            AND long_term_maintenance_to >= long_term_maintenance_from
            AND long_term_maintenance_to <= total_installments)
    ),
    -- 체계별 법정 상한(REG-03·04·05): 4년은 최대 48회차·장기유지 없음, 7년은 최대 84회차.
    -- 장기유지 시작회차(49/61)는 미결(P1-01)이라 고정하지 않는다.
    CONSTRAINT ck_fee_regime_ruleset_installments CHECK (
        (fee_regime_code = 'FOUR_YEAR_2027'
            AND total_installments <= 48 AND long_term_maintenance_from IS NULL)
        OR (fee_regime_code = 'SEVEN_YEAR_2029' AND total_installments <= 84)
    )
);
-- 같은 정책·체계·기초서류 버전에서 판매개시일 구간과 작성/변경일 구간이 겹치는 룰셋은 둘 수 없다
-- (선택기가 후보를 두 개 받지 않게 한다). 구간만 다른 룰셋은 등록할 수 있다. 날짜 구간의 NULL 경계는 무제한.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE fgc.fee_regime_ruleset
    ADD CONSTRAINT ex_fee_regime_ruleset_scope EXCLUDE USING gist (
        policy_version_id WITH =,
        fee_regime_code WITH =,
        basic_document_version WITH =,
        daterange(sales_start_date_from, sales_start_date_to, '[]') WITH &&,
        daterange(basic_document_date_from, basic_document_date_to, '[]') WITH &&
    );
CREATE INDEX ix_fee_regime_ruleset_lookup
    ON fgc.fee_regime_ruleset (fee_regime_code, sales_start_date_from);
COMMENT ON TABLE fgc.fee_regime_ruleset IS
    '4년·7년 분급 룰셋 헤더. 판매개시일·기초서류 버전·작성/변경일 키로 선택(REG-19, 판매채널은 기준 아님), 값은 정책버전 소속';
COMMENT ON COLUMN fgc.fee_regime_ruleset.monthly_rate_pct IS
    '참고용 FGC 월 지급률(GA_POLICY). 규제 상한이 아니다 — 상한은 fee_regime_ruleset_limit 에만 두고, 실제 지급률은 commission_rule 이 정한다';
COMMENT ON COLUMN fgc.fee_regime_ruleset.equal_monthly_amount_yn IS
    '참고용 매월 동일 금액 조건. 실제 지급 방식은 commission_rule.equal_monthly_yn 이 정한다';
COMMENT ON COLUMN fgc.fee_regime_ruleset.review_required_yn IS
    '규정만으로 적용 여부를 확정할 수 없는 경계 룰셋. true 면 선택기는 자동 확정하지 않고 REVIEW_REQUIRED 로 돌려준다';

CREATE TABLE fgc.fee_regime_ruleset_limit (
    fee_regime_ruleset_limit_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fee_regime_ruleset_id           bigint       NOT NULL REFERENCES fgc.fee_regime_ruleset(fee_regime_ruleset_id),
    limit_code                      varchar(40)  NOT NULL CHECK (limit_code IN (
                                        'UPFRONT_TOTAL', 'MAINTENANCE_MONTHLY',
                                        'MAINTENANCE_TOTAL', 'LONG_TERM_MONTHLY')),
    limit_pct                       numeric(9,6) NOT NULL CHECK (limit_pct >= 0),
    includes_indirect_expense_yn    boolean      NOT NULL DEFAULT false,
    created_at                      timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_fee_regime_ruleset_limit UNIQUE (fee_regime_ruleset_id, limit_code)
);
COMMENT ON TABLE fgc.fee_regime_ruleset_limit IS
    '계약체결비용 한도율(REGULATORY 상한의 유일한 저장 위치). 한도마다 따로 사용률을 계산하므로 한 행이 한 한도다(고정 19% 같은 상수 금지)';

CREATE TABLE fgc.fee_regime_transition_rule (
    fee_regime_transition_rule_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fee_regime_ruleset_id           bigint       NOT NULL REFERENCES fgc.fee_regime_ruleset(fee_regime_ruleset_id),
    transition_code                 varchar(60)  NOT NULL,
    applies_from                    date         NOT NULL,
    applies_to                      date,
    rule_expression                 jsonb        NOT NULL DEFAULT '{}'::jsonb,
    description                     varchar(500),
    created_at                      timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_fee_regime_transition_rule UNIQUE (fee_regime_ruleset_id, transition_code, applies_from),
    CONSTRAINT ck_fee_regime_transition_period CHECK (applies_to IS NULL OR applies_to >= applies_from)
);
COMMENT ON TABLE fgc.fee_regime_transition_rule IS '룰셋 경과조치. 적용 구간 안의 계약에만 rule_expression 을 적용한다';

-- ----------------------------------------------------------------------------
-- 정책 잠금: V2_1 판에 분급 룰셋 세 테이블을 더한 판으로 교체 (나머지는 한 글자도 다르지 않다)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fgc.guard_policy_child_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  v_old_policy_id bigint;
  v_new_policy_id bigint;
  v_policy_id bigint;
  v_status varchar(20);
BEGIN
  CASE
    WHEN TG_TABLE_NAME IN ('commission_rule','cap_rule_set','allocation_policy',
                           'refund_rate_table','policy_parameter','fee_regime_ruleset') THEN
      IF TG_OP <> 'INSERT' THEN v_old_policy_id := OLD.policy_version_id; END IF;
      IF TG_OP <> 'DELETE' THEN v_new_policy_id := NEW.policy_version_id; END IF;
    WHEN TG_TABLE_NAME = 'cap_rule_item' THEN
      IF TG_OP <> 'INSERT' THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM fgc.cap_rule_set WHERE cap_rule_set_id = OLD.cap_rule_set_id
          FOR SHARE;
      END IF;
      IF TG_OP <> 'DELETE' THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM fgc.cap_rule_set WHERE cap_rule_set_id = NEW.cap_rule_set_id
          FOR SHARE;
      END IF;
    WHEN TG_TABLE_NAME = 'refund_rate_line' THEN
      IF TG_OP <> 'INSERT' THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM fgc.refund_rate_table WHERE refund_rate_table_id = OLD.refund_rate_table_id
          FOR SHARE;
      END IF;
      IF TG_OP <> 'DELETE' THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM fgc.refund_rate_table WHERE refund_rate_table_id = NEW.refund_rate_table_id
          FOR SHARE;
      END IF;
    -- 분급 룰셋 자식: 한도·경과조치는 룰셋 헤더를 거쳐 정책버전을 찾는다
    WHEN TG_TABLE_NAME IN ('fee_regime_ruleset_limit','fee_regime_transition_rule') THEN
      IF TG_OP <> 'INSERT' THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = OLD.fee_regime_ruleset_id
          FOR SHARE;
      END IF;
      IF TG_OP <> 'DELETE' THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = NEW.fee_regime_ruleset_id
          FOR SHARE;
      END IF;
    ELSE
      RAISE EXCEPTION 'Unsupported policy child table: %', TG_TABLE_NAME;
  END CASE;

  FOR v_policy_id, v_status IN
    SELECT policy_version_id, status
      FROM fgc.policy_version
     WHERE policy_version_id = ANY (
       array_remove(ARRAY[v_old_policy_id, v_new_policy_id]::bigint[], NULL)
     )
     ORDER BY policy_version_id
     FOR SHARE
  LOOP
    IF v_status IN ('APPROVED','ACTIVE','RETIRED') THEN
      RAISE EXCEPTION 'Rules under locked policy version % are immutable; create a new policy version', v_policy_id;
    END IF;
  END LOOP;

  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_fee_regime_ruleset_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON fgc.fee_regime_ruleset
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();

CREATE TRIGGER trg_fee_regime_ruleset_limit_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON fgc.fee_regime_ruleset_limit
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();

CREATE TRIGGER trg_fee_regime_transition_rule_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON fgc.fee_regime_transition_rule
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();

-- ----------------------------------------------------------------------------
-- 룰셋 체계 코드는 소속 정책버전의 체계와 같아야 한다.
--   FOUR_YEAR_COMMISSION  -> FOUR_YEAR_2027
--   SEVEN_YEAR_COMMISSION -> SEVEN_YEAR_2029
--   그 밖의 정책 유형 아래에는 룰셋을 둘 수 없다.
--   정책버전에 fee_regime_code·basic_document_version 이 있으면 룰셋 값과 같아야 한다.
-- 한 계약에 두 체계가 섞이지 않게 한다(스케줄 생성은 policy_type 으로 체계를 정한다).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fgc.check_fee_regime_ruleset_policy()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  v_type varchar(40);
  v_regime varchar(40);
  v_document varchar(80);
BEGIN
  SELECT policy_type, fee_regime_code, basic_document_version
    INTO v_type, v_regime, v_document
    FROM fgc.policy_version WHERE policy_version_id = NEW.policy_version_id
    FOR SHARE;

  IF NOT ((v_type = 'FOUR_YEAR_COMMISSION'  AND NEW.fee_regime_code = 'FOUR_YEAR_2027')
       OR (v_type = 'SEVEN_YEAR_COMMISSION' AND NEW.fee_regime_code = 'SEVEN_YEAR_2029')) THEN
    RAISE EXCEPTION 'Fee regime ruleset % does not match policy type % of policy version %',
      NEW.fee_regime_code, v_type, NEW.policy_version_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF v_regime IS NOT NULL AND v_regime <> NEW.fee_regime_code THEN
    RAISE EXCEPTION 'Fee regime ruleset % does not match fee_regime_code % of policy version %',
      NEW.fee_regime_code, v_regime, NEW.policy_version_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF v_document IS NOT NULL AND v_document <> NEW.basic_document_version THEN
    RAISE EXCEPTION 'Fee regime ruleset document version % does not match basic_document_version % of policy version %',
      NEW.basic_document_version, v_document, NEW.policy_version_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_fee_regime_ruleset_policy_match
BEFORE INSERT OR UPDATE ON fgc.fee_regime_ruleset
FOR EACH ROW EXECUTE FUNCTION fgc.check_fee_regime_ruleset_policy();

-- ----------------------------------------------------------------------------
-- 반대 방향 재검사: 룰셋이 달린 정책버전의 체계 값(policy_type·fee_regime_code·basic_document_version)을
-- 바꿀 때도 같은 일치 조건을 지킨다. DRAFT/REVIEW 에서는 이 값을 고칠 수 있으므로,
-- 고친 뒤 룰셋과 어긋난 채로 승인·잠금되는 일이 없게 한다.
-- 룰셋 쪽 트리거가 정책 행을 FOR SHARE 로 읽으므로 두 방향은 행 잠금으로 직렬화된다.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fgc.check_policy_version_fee_regime_ruleset()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  v_ruleset_id bigint;
BEGIN
  SELECT r.fee_regime_ruleset_id
    INTO v_ruleset_id
    FROM fgc.fee_regime_ruleset r
   WHERE r.policy_version_id = NEW.policy_version_id
     AND NOT (
           ((NEW.policy_type = 'FOUR_YEAR_COMMISSION'  AND r.fee_regime_code = 'FOUR_YEAR_2027')
         OR (NEW.policy_type = 'SEVEN_YEAR_COMMISSION' AND r.fee_regime_code = 'SEVEN_YEAR_2029'))
         AND (NEW.fee_regime_code IS NULL OR NEW.fee_regime_code = r.fee_regime_code)
         AND (NEW.basic_document_version IS NULL OR NEW.basic_document_version = r.basic_document_version)
         )
   ORDER BY r.fee_regime_ruleset_id
   LIMIT 1;

  IF FOUND THEN
    RAISE EXCEPTION 'Policy version % cannot change type/fee regime/document version: fee regime ruleset % no longer matches',
      NEW.policy_version_id, v_ruleset_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_policy_version_fee_regime_ruleset_match
BEFORE UPDATE OF policy_type, fee_regime_code, basic_document_version ON fgc.policy_version
FOR EACH ROW EXECUTE FUNCTION fgc.check_policy_version_fee_regime_ruleset();
