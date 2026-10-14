-- ============================================================================
-- V52: 분급 룰셋 데이터 모델 (FUN-014-01, #534)
--
-- 왜: 4년(2027)·7년(2029) 분급의 회차 수·월 요율·매월 동일 금액 조건·장기유지수수료·
--     계약체결비용 한도·경과조치를 코드 상수가 아니라 버전 데이터로 둔다(COR-004).
--     계약일이 같아도 판매개시일·기초서류·작성/변경일·판매채널이 다르면 적용 체계가 달라지므로
--     (REG-19) 룰셋 행이 이 4개 키의 적용 범위를 직접 가진다.
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
    -- 룰셋 선택 키 4개. NULL 은 "제한 없음".
    sales_start_date_from           date         NOT NULL,
    sales_start_date_to             date,
    basic_document_version          varchar(80),
    basic_document_date_from        date,
    basic_document_date_to          date,
    channel_code                    varchar(30),
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
    )
);
CREATE UNIQUE INDEX uq_fee_regime_ruleset_scope
    ON fgc.fee_regime_ruleset (
        policy_version_id, fee_regime_code, sales_start_date_from,
        COALESCE(basic_document_version, ''), COALESCE(channel_code, ''));
CREATE INDEX ix_fee_regime_ruleset_lookup
    ON fgc.fee_regime_ruleset (fee_regime_code, sales_start_date_from);
COMMENT ON TABLE fgc.fee_regime_ruleset IS
    '4년·7년 분급 룰셋 헤더. 판매개시일·기초서류·작성/변경일·판매채널 키로 선택(REG-19), 값은 정책버전 소속';
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
    '계약체결비용 한도율. 한도마다 따로 사용률을 계산하므로 한 행이 한 한도다(고정 19% 같은 상수 금지)';

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
