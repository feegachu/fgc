-- ============================================================================
-- FGC PostgreSQL Schema v2.1.2
-- 기준 요구사항: FGC_요구사항명세서_v2_2_1_규제근거최신화
-- 기준 정책문서: FGC 가상 GA 운영정책서 v1.0
-- 작성 기준일: 2026-08-03
-- 대상 DBMS: PostgreSQL 17
--
-- 핵심 전제
--   1) 원수사 파일 수집·업로드·컬럼 매핑·스테이징은 범위에서 제외한다.
--   2) 원수사 자료는 FGC 표준 구조로 정규화되어 DB에 이미 적재되어 있다.
--   3) 1차는 2026년 현행 회사별 예상 스케줄, 초년도 1,200%룰,
--      차익거래 후보, 복식부기 검증원장, 예상/실제 대사, 예외를 구현한다.
--   4) 2차는 같은 키와 엔진을 재사용하여 4년·7년 법정 분급,
--      계약체결비용·유지관리수수료, 계약상태, 환수·상계를 확장한다.
--   5) 실제 송금·법정 회계마감은 수행하지 않는다.
--   6) v2.1.1은 정책 출처분류, 원 단위 반올림, 정확 귀속일,
--      계약 미귀속 신인활동지원비, 차익거래 지급단계, 원장 보고축을 보강한다.
--   7) v2.1.2는 확정된 예상 스케줄의 상태 되돌리기를 차단한다.
--      (schedule_line 가드는 헤더의 현재 상태만 보므로, 헤더 상태를
--       CONFIRMED -> PLANNED로 되돌리면 확정 금액을 수정할 수 있었다.)
--
-- 시드 적재 시 반드시 지켜야 할 순서
--   policy_version은 status='DRAFT'로 INSERT하고,
--   commission_rule / cap_rule_set / cap_rule_item / allocation_policy /
--   refund_rate_table / refund_rate_line 을 모두 적재한 다음
--   DRAFT -> APPROVED -> ACTIVE 두 번의 UPDATE로 승격한다.
--   ACTIVE 정책 아래에는 자식 행을 INSERT할 수 없고,
--   DRAFT -> ACTIVE 직행 전이는 허용되지 않는다.
-- ============================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS fgc;
SET search_path TO fgc, public;

-- ----------------------------------------------------------------------------
-- 공통 함수
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fgc.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fgc.reject_update_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION '% is append-only; UPDATE/DELETE is not allowed', TG_TABLE_NAME;
END;
$$;

CREATE OR REPLACE FUNCTION fgc.is_first_day_of_month(p_date date)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
  SELECT p_date = date_trunc('month', p_date)::date;
$$;

CREATE OR REPLACE FUNCTION fgc.round_krw_half_up(p_amount numeric)
RETURNS numeric
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
  SELECT round(p_amount, 0);
$$;
COMMENT ON FUNCTION fgc.round_krw_half_up(numeric)
  IS 'FGC 표준 금액 반올림: 양수 numeric을 원 단위(scale 0) HALF_UP 처리';

CREATE OR REPLACE FUNCTION fgc.is_within_first_year(p_contract_date date, p_attribution_date date)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
  SELECT p_attribution_date >= p_contract_date
     AND p_attribution_date < (p_contract_date + INTERVAL '1 year')::date;
$$;
COMMENT ON FUNCTION fgc.is_within_first_year(date,date)
  IS '초년도 경계: 계약일 이상, 계약 1주년일 미만';

-- ============================================================================
-- 1. 기준정보·사용자·감사 (1차)
-- ============================================================================

CREATE TABLE insurer (
  insurer_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  insurer_code        varchar(30)  NOT NULL,
  insurer_name        varchar(120) NOT NULL,
  insurer_type        varchar(10)  NOT NULL CHECK (insurer_type IN ('LIFE','NON_LIFE')),
  active_yn           boolean      NOT NULL DEFAULT true,
  created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_insurer_code UNIQUE (insurer_code)
);
COMMENT ON TABLE insurer IS '보험회사(원수사) 기준정보';

CREATE TABLE organization (
  organization_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  parent_id           bigint REFERENCES organization(organization_id),
  organization_code   varchar(40)  NOT NULL,
  organization_name   varchar(120) NOT NULL,
  organization_type   varchar(20)  NOT NULL CHECK (organization_type IN ('GA','HQ','DIVISION','BRANCH','TEAM')),
  effective_from      date         NOT NULL,
  effective_to        date,
  active_yn           boolean      NOT NULL DEFAULT true,
  created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_organization_code UNIQUE (organization_code),
  CONSTRAINT ck_organization_period CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
COMMENT ON TABLE organization IS 'GA 본사·본부·지사·지점·팀 계층';

CREATE TABLE agent (
  agent_id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  organization_id             bigint       NOT NULL REFERENCES organization(organization_id),
  agent_code                   varchar(40)  NOT NULL,
  agent_name                   varchar(100) NOT NULL,
  rank_code                    varchar(30),
  appointment_date             date         NOT NULL,
  termination_date             date,
  agent_status                 varchar(20)  NOT NULL CHECK (agent_status IN ('ACTIVE','INACTIVE','TERMINATED')),
  latest_registration_date     date,
  prior_three_year_experience_yn boolean,
  experience_checked_on        date,
  newcomer_support_eligible_yn boolean      NOT NULL DEFAULT false,
  newcomer_support_end_date    date,
  active_yn                    boolean      NOT NULL DEFAULT true,
  created_at                   timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at                   timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_agent_code UNIQUE (agent_code),
  CONSTRAINT ck_agent_term CHECK (termination_date IS NULL OR termination_date >= appointment_date),
  CONSTRAINT ck_agent_newcomer_end CHECK (
    newcomer_support_end_date IS NULL OR latest_registration_date IS NULL
    OR newcomer_support_end_date >= latest_registration_date
  )
);
COMMENT ON TABLE agent IS '보험설계사(FC) 현재 기준정보. 2차에서 agent_history로 이력 확장';

CREATE TABLE agent_insurer_code (
  agent_insurer_code_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  insurer_id            bigint       NOT NULL REFERENCES insurer(insurer_id),
  agent_id              bigint       NOT NULL REFERENCES agent(agent_id),
  insurer_agent_code    varchar(80)  NOT NULL,
  code_status           varchar(20)  NOT NULL DEFAULT 'ACTIVE'
                                      CHECK (code_status IN ('ACTIVE','INACTIVE','DELETED','REPRESENTATIVE')),
  effective_from        date         NOT NULL,
  effective_to          date,
  source_ref            varchar(500),
  created_at            timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at            timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_agent_insurer_code_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CONSTRAINT uq_agent_insurer_code UNIQUE (insurer_id, insurer_agent_code, effective_from)
);
COMMENT ON TABLE agent_insurer_code IS '원수사 설계사코드와 FGC 내부 agent_id의 유효기간 브리지. AGENT_MISMATCH 판정 근거';

CREATE TABLE product (
  product_id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  insurer_id             bigint       NOT NULL REFERENCES insurer(insurer_id),
  insurer_product_code   varchar(60)  NOT NULL,
  standard_product_code  varchar(60)  NOT NULL,
  product_name           varchar(200) NOT NULL,
  product_group_code     varchar(40)  NOT NULL,
  protection_type        varchar(20)  NOT NULL DEFAULT 'PROTECTION'
                                      CHECK (protection_type IN ('PROTECTION','SAVINGS','GENERAL','AUTO','OTHER')),
  active_yn              boolean      NOT NULL DEFAULT true,
  created_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_product_insurer_code UNIQUE (insurer_id, insurer_product_code),
  CONSTRAINT uq_product_standard_code UNIQUE (standard_product_code)
);
COMMENT ON TABLE product IS '보험상품의 안정적인 식별자. 판매버전·채널별 적용체계는 product_offering에서 관리';

CREATE TABLE product_offering (
  product_offering_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  product_id                bigint       NOT NULL REFERENCES product(product_id),
  offering_version          varchar(40)  NOT NULL,
  sales_start_date          date         NOT NULL,
  sales_end_date            date,
  basic_document_version    varchar(80)  NOT NULL,
  basic_document_date       date         NOT NULL,
  channel_code              varchar(30)  NOT NULL,
  channel_special_rule_yn   boolean      NOT NULL DEFAULT false,
  fee_regime_code           varchar(40)  NOT NULL,
  standard_deduction_80_yn  boolean      NOT NULL DEFAULT false,
  committee_approval_date   date,
  committee_document_ref    varchar(300),
  governance_status         varchar(20)  CHECK (governance_status IS NULL OR governance_status IN ('APPROVED','REVISED','SUSPENDED','STOPPED')),
  active_yn                 boolean      NOT NULL DEFAULT true,
  created_at                timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at                timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_product_offering UNIQUE (product_id, offering_version, channel_code),
  CONSTRAINT ck_product_offering_period CHECK (sales_end_date IS NULL OR sales_end_date >= sales_start_date)
);
COMMENT ON TABLE product_offering IS '상품 판매개시일·기초서류 버전·채널·적용 수수료체계. REG-19 적용체계 판정의 핵심';

CREATE TABLE commission_item (
  commission_item_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  item_code            varchar(50)  NOT NULL,
  item_name            varchar(120) NOT NULL,
  cashflow_type        varchar(15)  NOT NULL CHECK (cashflow_type IN ('PAYMENT','DEDUCTION')),
  item_category        varchar(40)  NOT NULL,
  effective_from       date         NOT NULL,
  effective_to         date,
  active_yn            boolean      NOT NULL DEFAULT true,
  created_at           timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at           timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_commission_item_code UNIQUE (item_code),
  CONSTRAINT ck_commission_item_period CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
COMMENT ON TABLE commission_item IS '수수료·시책·정착지원금·공통비·환수 등 돈의 종류. 1,200% 산입 여부는 cap_rule_item에서 결정';

CREATE TABLE app_role (
  role_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  role_code        varchar(40)  NOT NULL,
  role_name        varchar(100) NOT NULL,
  description      varchar(500),
  CONSTRAINT uq_app_role_code UNIQUE (role_code)
);
COMMENT ON TABLE app_role IS '시스템 역할: SYSTEM_ADMIN, GA_ADMIN, SETTLEMENT, COMPLIANCE 등';

CREATE TABLE app_user (
  user_id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  login_id          varchar(80)  NOT NULL,
  password_hash     varchar(255) NOT NULL,
  user_name         varchar(100) NOT NULL,
  role_id           bigint       NOT NULL REFERENCES app_role(role_id),
  organization_id   bigint REFERENCES organization(organization_id),
  agent_id          bigint REFERENCES agent(agent_id),
  account_status    varchar(20)  NOT NULL DEFAULT 'ACTIVE'
                                 CHECK (account_status IN ('ACTIVE','LOCKED','DISABLED')),
  last_login_at     timestamptz,
  created_at        timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at        timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_app_user_login UNIQUE (login_id)
);
COMMENT ON TABLE app_user IS '로그인 사용자. 1차는 사용자당 역할 1개';

CREATE TABLE audit_log (
  audit_log_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at       timestamptz  NOT NULL DEFAULT clock_timestamp(),
  user_id           bigint REFERENCES app_user(user_id),
  action_code       varchar(50)  NOT NULL,
  entity_type       varchar(60)  NOT NULL,
  entity_id         varchar(100) NOT NULL,
  before_value      jsonb,
  after_value       jsonb,
  reason            varchar(1000),
  request_id        varchar(80),
  client_ip         inet,
  policy_version_id bigint
);
COMMENT ON TABLE audit_log IS '핵심 변경 INSERT 전용 감사로그';
CREATE TRIGGER trg_audit_log_append_only
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

-- 공통 updated_at 트리거
CREATE TRIGGER trg_insurer_updated_at BEFORE UPDATE ON insurer
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
CREATE TRIGGER trg_organization_updated_at BEFORE UPDATE ON organization
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
CREATE TRIGGER trg_agent_updated_at BEFORE UPDATE ON agent
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
CREATE TRIGGER trg_agent_insurer_code_updated_at BEFORE UPDATE ON agent_insurer_code
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
CREATE TRIGGER trg_product_updated_at BEFORE UPDATE ON product
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
CREATE TRIGGER trg_product_offering_updated_at BEFORE UPDATE ON product_offering
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
CREATE TRIGGER trg_commission_item_updated_at BEFORE UPDATE ON commission_item
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
CREATE TRIGGER trg_app_user_updated_at BEFORE UPDATE ON app_user
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();

-- ============================================================================
-- 2. 정책·규칙·예상 해약환급률 (1차 핵심, 2차 재사용)
-- ============================================================================

CREATE TABLE policy_version (
  policy_version_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  policy_code            varchar(80)  NOT NULL,
  policy_name            varchar(200) NOT NULL,
  policy_type            varchar(40)  NOT NULL CHECK (policy_type IN (
                           'CURRENT_COMMISSION','FOUR_YEAR_COMMISSION','SEVEN_YEAR_COMMISSION',
                           'CAP_1200','ALLOCATION','REFUND_RATE','CLAWBACK','RECONCILIATION_TOLERANCE'
                         )),
  source_class          varchar(30)  NOT NULL CHECK (source_class IN ('REGULATORY','INSURER_RULE','GA_POLICY','PROJECT_ASSUMPTION')),
  version_no             integer      NOT NULL CHECK (version_no > 0),
  effective_from         date         NOT NULL,
  effective_to           date,
  status                 varchar(20)  NOT NULL CHECK (status IN ('DRAFT','REVIEW','APPROVED','ACTIVE','RETIRED')),
  fee_regime_code        varchar(40),
  basic_document_version varchar(80),
  regulation_refs        text[]       NOT NULL DEFAULT '{}',
  source_refs            text[]       NOT NULL DEFAULT '{}',
  approval_evidence_ref  varchar(500),
  approved_by            bigint REFERENCES app_user(user_id),
  approved_at            timestamptz,
  created_by             bigint REFERENCES app_user(user_id),
  created_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_policy_version UNIQUE (policy_code, version_no),
  CONSTRAINT ck_policy_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CONSTRAINT ck_policy_approval CHECK (
    status NOT IN ('APPROVED','ACTIVE','RETIRED') OR approved_at IS NOT NULL
  )
);
COMMENT ON TABLE policy_version IS '모든 계산 규칙의 불변 버전 헤더. 과거 결과는 당시 버전을 계속 참조';
CREATE TRIGGER trg_policy_version_updated_at BEFORE UPDATE ON policy_version
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
ALTER TABLE audit_log
  ADD CONSTRAINT fk_audit_log_policy_version
  FOREIGN KEY (policy_version_id) REFERENCES policy_version(policy_version_id);

CREATE TABLE commission_rule (
  commission_rule_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  policy_version_id        bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  payment_stage            varchar(20)  NOT NULL CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC')),
  insurer_id               bigint REFERENCES insurer(insurer_id),
  product_offering_id      bigint REFERENCES product_offering(product_offering_id),
  organization_id          bigint REFERENCES organization(organization_id),
  agent_rank_code          varchar(30),
  commission_item_id       bigint       NOT NULL REFERENCES commission_item(commission_item_id),
  fee_component_type       varchar(40)  NOT NULL CHECK (fee_component_type IN (
                              'CURRENT','UPFRONT','MAINTENANCE','LONG_TERM_MAINTENANCE',
                              'INCENTIVE','SETTLEMENT_SUPPORT','NEWCOMER_SUPPORT','COMMON_COST','OTHER'
                           )),
  installment_from         integer      NOT NULL CHECK (installment_from > 0),
  installment_to           integer      NOT NULL CHECK (installment_to >= installment_from),
  calculation_type         varchar(15)  NOT NULL CHECK (calculation_type IN ('RATE','FIXED')),
  basis_code               varchar(40)  NOT NULL,
  rate_pct                 numeric(9,6),
  fixed_amount             numeric(15,2),
  rounding_scale            smallint     NOT NULL DEFAULT 0 CHECK (rounding_scale = 0),
  rounding_mode             varchar(20)  NOT NULL DEFAULT 'HALF_UP' CHECK (rounding_mode = 'HALF_UP'),
  equal_monthly_yn         boolean      NOT NULL DEFAULT false,
  payment_condition_code   varchar(50),
  priority_no              integer      NOT NULL DEFAULT 100,
  rule_expression          jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at               timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_commission_rule_value CHECK (
    (calculation_type = 'RATE'  AND rate_pct IS NOT NULL AND rate_pct >= 0 AND fixed_amount IS NULL)
    OR
    (calculation_type = 'FIXED' AND fixed_amount IS NOT NULL AND fixed_amount >= 0 AND rate_pct IS NULL)
  )
);
CREATE UNIQUE INDEX uq_commission_rule
  ON commission_rule (
    policy_version_id, payment_stage, COALESCE(insurer_id, 0), commission_item_id,
    fee_component_type, installment_from, installment_to, priority_no,
    COALESCE(product_offering_id, 0), COALESCE(organization_id, 0), COALESCE(agent_rank_code, '')
  );
COMMENT ON TABLE commission_rule IS '현행 회사별 지급방식과 4년·7년 분급을 같은 엔진에서 읽는 규칙 행';

CREATE TABLE cap_rule_set (
  cap_rule_set_id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  policy_version_id           bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  payment_stage               varchar(20)  NOT NULL CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC')),
  contract_date_from          date         NOT NULL,
  contract_date_to            date,
  insurer_id                  bigint REFERENCES insurer(insurer_id),
  product_group_code          varchar(40),
  channel_code                varchar(30),
  first_year_months           integer      NOT NULL DEFAULT 12 CHECK (first_year_months = 12),
  premium_multiplier          numeric(7,4) NOT NULL DEFAULT 12.0000 CHECK (premium_multiplier > 0),
  compliance_deduction_pct    numeric(7,4) NOT NULL DEFAULT 0 CHECK (compliance_deduction_pct BETWEEN 0 AND 100),
  refund_addition_condition   varchar(40)  NOT NULL DEFAULT 'NONE'
                                           CHECK (refund_addition_condition IN ('NONE','STANDARD_DEDUCTION_80')),
  warning_usage_pct           numeric(7,4) NOT NULL DEFAULT 90.0000 CHECK (warning_usage_pct BETWEEN 0 AND 100),
  created_at                  timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_cap_rule_period CHECK (contract_date_to IS NULL OR contract_date_to >= contract_date_from),
  CONSTRAINT ck_cap_compliance_stage CHECK (
    payment_stage = 'INSURER_TO_GA' OR compliance_deduction_pct = 0
  )
);
COMMENT ON TABLE cap_rule_set IS '보험회사→GA와 GA→FC를 분리한 초년도 1,200% 룰셋';
CREATE UNIQUE INDEX uq_cap_rule_set_scope
  ON cap_rule_set (
    policy_version_id, payment_stage, contract_date_from,
    COALESCE(contract_date_to, 'infinity'::date), COALESCE(insurer_id, 0),
    COALESCE(product_group_code, ''), COALESCE(channel_code, '')
  );

CREATE TABLE allocation_policy (
  allocation_policy_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  policy_version_id      bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  allocation_code        varchar(80)  NOT NULL,
  allocation_name        varchar(200) NOT NULL,
  pool_type              varchar(40)  NOT NULL,
  allocation_basis_code  varchar(40)  NOT NULL,
  target_scope           jsonb        NOT NULL DEFAULT '{}'::jsonb,
  rounding_scale         integer      NOT NULL DEFAULT 2 CHECK (rounding_scale BETWEEN 0 AND 6),
  rounding_mode          varchar(20)  NOT NULL DEFAULT 'HALF_UP'
                                     CHECK (rounding_mode IN ('HALF_UP','HALF_EVEN','DOWN','UP')),
  residual_treatment     varchar(30)  NOT NULL DEFAULT 'LARGEST_REMAINDER'
                                     CHECK (residual_treatment IN ('LARGEST_REMAINDER','FIRST_TARGET','SEPARATE_ADJUSTMENT')),
  approved_basis_ref     varchar(500) NOT NULL,
  created_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_allocation_policy_code UNIQUE (allocation_code, policy_version_id)
);
COMMENT ON TABLE allocation_policy IS '일반 공통비·지원금 등을 계약별로 나누는 회사별 승인 안분정책';

CREATE TABLE cap_rule_item (
  cap_rule_item_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cap_rule_set_id         bigint       NOT NULL REFERENCES cap_rule_set(cap_rule_set_id),
  commission_item_id      bigint       NOT NULL REFERENCES commission_item(commission_item_id),
  inclusion_status        varchar(20)  NOT NULL CHECK (inclusion_status IN ('INCLUDED','EXCLUDED','REVIEW_REQUIRED')),
  exclusion_type          varchar(40),
  evidence_required_yn    boolean      NOT NULL DEFAULT false,
  attribution_method      varchar(40)  NOT NULL CHECK (attribution_method IN (
                             'DIRECT','SETTLEMENT_SUPPORT_MONTHLY','FIRST_CONTRACT_CARRY_FORWARD',
                             'APPROVED_ALLOCATION','NOT_APPLICABLE','MANUAL_REVIEW'
                           )),
  allocation_policy_id    bigint REFERENCES allocation_policy(allocation_policy_id),
  decision_reason         varchar(1000) NOT NULL,
  created_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_cap_rule_item UNIQUE (cap_rule_set_id, commission_item_id),
  CONSTRAINT ck_cap_exclusion_evidence CHECK (
    inclusion_status <> 'EXCLUDED' OR (exclusion_type IS NOT NULL AND evidence_required_yn = true)
  ),
  CONSTRAINT ck_cap_allocation_required CHECK (
    attribution_method <> 'APPROVED_ALLOCATION' OR allocation_policy_id IS NOT NULL
  )
);
COMMENT ON TABLE cap_rule_item IS '수수료 항목×1,200% 룰셋의 산입·제외·검토 분류와 귀속방법';

CREATE TABLE refund_rate_table (
  refund_rate_table_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  policy_version_id          bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  insurer_id                 bigint       NOT NULL REFERENCES insurer(insurer_id),
  product_id                 bigint       NOT NULL REFERENCES product(product_id),
  product_offering_id        bigint REFERENCES product_offering(product_offering_id),
  payment_term_months        integer      NOT NULL CHECK (payment_term_months > 0),
  channel_code               varchar(30)  NOT NULL,
  representative_attributes  jsonb        NOT NULL DEFAULT '{}'::jsonb,
  average_declared_rate_pct  numeric(9,6),
  standard_deduction_80_yn   boolean      NOT NULL DEFAULT false,
  effective_from             date         NOT NULL,
  effective_to               date,
  source_product_code        varchar(60)  NOT NULL,
  source_document_ref        varchar(500) NOT NULL,
  created_at                 timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_refund_table_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CONSTRAINT uq_refund_table_scope UNIQUE (
    insurer_id, product_id, payment_term_months, channel_code, effective_from
  )
);
COMMENT ON TABLE refund_rate_table IS '상품·납입기간·채널별 예상 해약환급률표 헤더(REG-23)';

CREATE TABLE refund_rate_line (
  refund_rate_line_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  refund_rate_table_id    bigint       NOT NULL REFERENCES refund_rate_table(refund_rate_table_id) ON DELETE RESTRICT,
  contract_month_no       integer      NOT NULL CHECK (contract_month_no BETWEEN 1 AND 36),
  refund_rate_pct         numeric(9,6) NOT NULL CHECK (refund_rate_pct >= 0),
  created_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_refund_rate_month UNIQUE (refund_rate_table_id, contract_month_no)
);
COMMENT ON TABLE refund_rate_line IS 'FAQ 제공범위인 예상 해약환급률 1~36차월 상세. 37차월 이후 차익거래는 실제 누계자료로 계속 계산';

-- 승인된 정책과 상세 규칙은 새 버전으로만 변경한다.
CREATE OR REPLACE FUNCTION fgc.policy_is_locked(p_policy_version_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT COALESCE((SELECT status IN ('APPROVED','ACTIVE','RETIRED')
                     FROM policy_version
                    WHERE policy_version_id = p_policy_version_id), false);
$$;

CREATE OR REPLACE FUNCTION fgc.guard_policy_version_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'Only DRAFT policy versions may be deleted; create a new version instead';
    END IF;
    RETURN OLD;
  END IF;

  IF OLD.status IN ('APPROVED','ACTIVE','RETIRED') THEN
    IF ROW(NEW.policy_code, NEW.policy_name, NEW.policy_type, NEW.version_no,
           NEW.source_class, NEW.effective_from, NEW.effective_to, NEW.fee_regime_code,
           NEW.basic_document_version, NEW.regulation_refs, NEW.source_refs,
           NEW.approval_evidence_ref, NEW.approved_by, NEW.approved_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.policy_code, OLD.policy_name, OLD.policy_type, OLD.version_no,
           OLD.source_class, OLD.effective_from, OLD.effective_to, OLD.fee_regime_code,
           OLD.basic_document_version, OLD.regulation_refs, OLD.source_refs,
           OLD.approval_evidence_ref, OLD.approved_by, OLD.approved_at, OLD.created_by) THEN
      RAISE EXCEPTION 'APPROVED/ACTIVE/RETIRED policy version is immutable; create a new version';
    END IF;
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
      (OLD.status = 'DRAFT'    AND NEW.status IN ('REVIEW','APPROVED')) OR
      (OLD.status = 'REVIEW'   AND NEW.status IN ('DRAFT','APPROVED')) OR
      (OLD.status = 'APPROVED' AND NEW.status IN ('ACTIVE','RETIRED')) OR
      (OLD.status = 'ACTIVE'   AND NEW.status = 'RETIRED')
    ) THEN
      RAISE EXCEPTION 'Invalid policy status transition: % -> %', OLD.status, NEW.status;
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_policy_version_immutable ON policy_version;
CREATE TRIGGER trg_policy_version_lifecycle
BEFORE UPDATE OR DELETE ON policy_version
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_version_lifecycle();

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
    WHEN TG_TABLE_NAME IN ('commission_rule','cap_rule_set','allocation_policy','refund_rate_table') THEN
      IF TG_OP <> 'INSERT' THEN v_old_policy_id := OLD.policy_version_id; END IF;
      IF TG_OP <> 'DELETE' THEN v_new_policy_id := NEW.policy_version_id; END IF;
    WHEN TG_TABLE_NAME = 'cap_rule_item' THEN
      IF TG_OP <> 'INSERT' THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM cap_rule_set WHERE cap_rule_set_id = OLD.cap_rule_set_id
          FOR SHARE;
      END IF;
      IF TG_OP <> 'DELETE' THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM cap_rule_set WHERE cap_rule_set_id = NEW.cap_rule_set_id
          FOR SHARE;
      END IF;
    WHEN TG_TABLE_NAME = 'refund_rate_line' THEN
      IF TG_OP <> 'INSERT' THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM refund_rate_table WHERE refund_rate_table_id = OLD.refund_rate_table_id
          FOR SHARE;
      END IF;
      IF TG_OP <> 'DELETE' THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM refund_rate_table WHERE refund_rate_table_id = NEW.refund_rate_table_id
          FOR SHARE;
      END IF;
    ELSE
      RAISE EXCEPTION 'Unsupported policy child table: %', TG_TABLE_NAME;
  END CASE;

  -- 정책 활성화와 상세행 변경이 동시에 커밋되는 경쟁조건을 막는다.
  -- 동일 문장에서 두 정책을 옮기는 경우에도 작은 ID부터 잠가 교착 가능성을 낮춘다.
  FOR v_policy_id, v_status IN
    SELECT policy_version_id, status
      FROM policy_version
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

CREATE TRIGGER trg_commission_rule_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON commission_rule
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();
CREATE TRIGGER trg_cap_rule_set_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON cap_rule_set
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();
CREATE TRIGGER trg_allocation_policy_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON allocation_policy
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();
CREATE TRIGGER trg_cap_rule_item_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON cap_rule_item
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();
CREATE TRIGGER trg_refund_rate_table_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON refund_rate_table
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();
CREATE TRIGGER trg_refund_rate_line_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON refund_rate_line
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();

-- ============================================================================
-- 3. 계약·실제 돈·귀속 (1차)
-- ============================================================================

CREATE TABLE insurance_contract (
  contract_id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  insurer_id                        bigint       NOT NULL REFERENCES insurer(insurer_id),
  product_offering_id               bigint       NOT NULL REFERENCES product_offering(product_offering_id),
  contract_no                       varchar(80)  NOT NULL,
  contract_date                     date         NOT NULL,
  agent_id                          bigint       NOT NULL REFERENCES agent(agent_id),
  organization_id                   bigint       NOT NULL REFERENCES organization(organization_id),
  premium_per_cycle_amount          numeric(15,2) NOT NULL CHECK (premium_per_cycle_amount >= 0),
  first_premium_amount              numeric(15,2) NOT NULL CHECK (first_premium_amount >= 0),
  monthly_equivalent_first_premium  numeric(15,2) NOT NULL CHECK (monthly_equivalent_first_premium >= 0),
  premium_conversion_rule_code      varchar(40)  NOT NULL DEFAULT 'MONTHLY_AS_IS',
  payment_cycle_code                varchar(20)  NOT NULL DEFAULT 'MONTHLY'
                                                 CHECK (payment_cycle_code IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL','SINGLE','OTHER')),
  payment_term_months               integer      NOT NULL CHECK (payment_term_months > 0),
  standard_surrender_deduction_amount numeric(15,2)
                                                 CHECK (standard_surrender_deduction_amount IS NULL OR standard_surrender_deduction_amount >= 0),
  current_status                    varchar(20)  NOT NULL DEFAULT 'ACTIVE'
                                                 CHECK (current_status IN ('APPLIED','ACTIVE','UNPAID','LAPSED','REVIVED','CANCELLED','TERMINATED','MATURED')),
  data_origin                       varchar(20)  NOT NULL DEFAULT 'SEED'
                                                 CHECK (data_origin IN ('NORMALIZED_DB','MANUAL','SEED')),
  created_by                        bigint REFERENCES app_user(user_id),
  created_at                        timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at                        timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_contract_no UNIQUE (insurer_id, contract_no)
);
COMMENT ON TABLE insurance_contract IS '정산·규제검증 원자 단위. 원주기보험료와 월납환산 초회보험료를 분리 저장';
COMMENT ON COLUMN insurance_contract.monthly_equivalent_first_premium IS '1,200% 한도 기준으로 사용하는 월납환산 초회보험료 스냅샷';
COMMENT ON COLUMN insurance_contract.standard_surrender_deduction_amount IS '계약별 표준해약공제액 입력값. 80% 적용 판정과 계약체결비용 검증에 사용';
CREATE TRIGGER trg_insurance_contract_updated_at BEFORE UPDATE ON insurance_contract
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();

CREATE TABLE contract_status_event (
  contract_status_event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  contract_id              bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  event_seq                integer      NOT NULL CHECK (event_seq > 0),
  previous_status          varchar(20)  CHECK (previous_status IS NULL OR previous_status IN ('APPLIED','ACTIVE','UNPAID','LAPSED','REVIVED','CANCELLED','TERMINATED','MATURED')),
  new_status               varchar(20)  NOT NULL CHECK (new_status IN ('APPLIED','ACTIVE','UNPAID','LAPSED','REVIVED','CANCELLED','TERMINATED','MATURED')),
  effective_at             timestamptz  NOT NULL,
  received_at              timestamptz  NOT NULL,
  processed_at             timestamptz,
  reason_code              varchar(40),
  source_system            varchar(60)  NOT NULL,
  source_event_key         varchar(160) NOT NULL,
  source_version           varchar(60),
  data_origin              varchar(20)  NOT NULL DEFAULT 'NORMALIZED_DB'
                                      CHECK (data_origin IN ('NORMALIZED_DB','MANUAL','SEED')),
  source_ref               varchar(500),
  registered_by            bigint REFERENCES app_user(user_id),
  created_at               timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_contract_status_event_seq UNIQUE (contract_id, event_seq),
  CONSTRAINT uq_contract_status_event_source UNIQUE (source_system, source_event_key),
  CONSTRAINT ck_contract_status_processed CHECK (processed_at IS NULL OR processed_at >= received_at)
);
COMMENT ON TABLE contract_status_event IS '미납·실효·부활·해지 등 상태 사건의 효력시점·수신시점·처리시점. 차익거래 1차 필수';
CREATE TRIGGER trg_contract_status_event_append_only
BEFORE UPDATE OR DELETE ON contract_status_event
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

CREATE TABLE contract_financial_snapshot (
  contract_financial_snapshot_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  contract_id                    bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  as_of_date                     date         NOT NULL,
  contract_month_no              integer      NOT NULL CHECK (contract_month_no > 0),
  cumulative_paid_premium        numeric(15,2) NOT NULL CHECK (cumulative_paid_premium >= 0),
  surrender_value                numeric(15,2) CHECK (surrender_value IS NULL OR surrender_value >= 0),
  surrender_value_type           varchar(20)  NOT NULL DEFAULT 'ACTUAL'
                                             CHECK (surrender_value_type IN ('ACTUAL','EXPECTED_TABLE','ESTIMATED')),
  refund_rate_table_id           bigint REFERENCES refund_rate_table(refund_rate_table_id),
  source_ref                     varchar(500),
  created_at                     timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_contract_financial_snapshot UNIQUE (contract_id, as_of_date, surrender_value_type)
);
COMMENT ON TABLE contract_financial_snapshot IS '계약별 기준일 누적보험료·해약환급금 시계열';

CREATE TABLE statement_batch (
  statement_batch_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  insurer_id            bigint       NOT NULL REFERENCES insurer(insurer_id),
  settlement_month      date         NOT NULL CHECK (fgc.is_first_day_of_month(settlement_month)),
  statement_type        varchar(30)  NOT NULL CHECK (statement_type IN ('INSURER_COMMISSION','INSURER_ADJUSTMENT','INSURER_CLAWBACK')),
  external_statement_no varchar(100),
  received_on           date         NOT NULL,
  source_ref            varchar(500) NOT NULL,
  status                varchar(20)  NOT NULL DEFAULT 'AVAILABLE'
                                    CHECK (status IN ('AVAILABLE','VALIDATED','RECONCILED','REJECTED')),
  data_origin           varchar(20)  NOT NULL DEFAULT 'NORMALIZED_DB'
                                    CHECK (data_origin IN ('NORMALIZED_DB','SEED')),
  created_at            timestamptz  NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX uq_statement_batch
  ON statement_batch (insurer_id, settlement_month, statement_type, COALESCE(external_statement_no, ''));
COMMENT ON TABLE statement_batch IS '정규화되어 적재된 원수사 실제 지급명세 묶음';

CREATE TABLE commission_transaction (
  commission_transaction_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  statement_batch_id        bigint REFERENCES statement_batch(statement_batch_id),
  payment_stage             varchar(20)  NOT NULL CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC')),
  source_type               varchar(35)  NOT NULL CHECK (source_type IN (
                               'INSURER_STATEMENT','GA_MANUAL_PAYMENT','GA_CONFIRMED_PAYMENT',
                               'ADJUSTMENT','ALLOCATION_POOL','CLAWBACK','RECOVERY'
                             )),
  source_business_key       varchar(160) NOT NULL,
  insurer_id                bigint REFERENCES insurer(insurer_id),
  recipient_agent_id        bigint REFERENCES agent(agent_id),
  commission_item_id        bigint       NOT NULL REFERENCES commission_item(commission_item_id),
  policy_version_id         bigint REFERENCES policy_version(policy_version_id),
  settlement_month          date         NOT NULL CHECK (fgc.is_first_day_of_month(settlement_month)),
  due_date                  date,
  paid_on                   date,
  amount                    numeric(15,2) NOT NULL CHECK (amount >= 0),
  cashflow_type             varchar(15)  NOT NULL CHECK (cashflow_type IN ('PAYMENT','DEDUCTION')),
  status                    varchar(20)  NOT NULL DEFAULT 'DRAFT'
                                       CHECK (status IN ('DRAFT','CONFIRMED','CANCELLED')),
  evidence_ref              varchar(500),
  note                      varchar(1000),
  created_by                bigint REFERENCES app_user(user_id),
  created_at                timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at                timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_commission_transaction_source UNIQUE (source_type, source_business_key),
  CONSTRAINT ck_transaction_recipient CHECK (
    payment_stage <> 'GA_TO_FC' OR recipient_agent_id IS NOT NULL
  )
);
COMMENT ON TABLE commission_transaction IS '실제 원수사 명세행·GA 지급건·공통비 풀·정정 등 실제 돈 사실의 단일 원장';
CREATE TRIGGER trg_commission_transaction_updated_at BEFORE UPDATE ON commission_transaction
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();

CREATE TABLE transaction_attribution (
  transaction_attribution_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  commission_transaction_id  bigint       NOT NULL REFERENCES commission_transaction(commission_transaction_id),
  attribution_seq            integer      NOT NULL DEFAULT 1 CHECK (attribution_seq > 0),
  attribution_scope          varchar(20)  NOT NULL DEFAULT 'CONTRACT' CHECK (attribution_scope IN ('CONTRACT','AGENT')),
  contract_id                bigint REFERENCES insurance_contract(contract_id),
  agent_id                   bigint REFERENCES agent(agent_id),
  source_agent_code          varchar(80),
  attribution_date           date         NOT NULL,
  attribution_month          date         NOT NULL CHECK (fgc.is_first_day_of_month(attribution_month)),
  attributed_amount          numeric(15,2) NOT NULL CHECK (attributed_amount >= 0),
  cap_rule_item_id           bigint REFERENCES cap_rule_item(cap_rule_item_id),
  inclusion_status_snapshot  varchar(20)  NOT NULL CHECK (inclusion_status_snapshot IN ('INCLUDED','EXCLUDED','REVIEW_REQUIRED')),
  exclusion_type_snapshot    varchar(40),
  attribution_method         varchar(40)  NOT NULL CHECK (attribution_method IN (
                               'DIRECT','SETTLEMENT_SUPPORT_MONTHLY','FIRST_CONTRACT_CARRY_FORWARD',
                               'APPROVED_ALLOCATION','MANUAL_REVIEW','NEWCOMER_NON_CONTRACT'
                             )),
  allocation_policy_id       bigint REFERENCES allocation_policy(allocation_policy_id),
  allocation_basis_snapshot jsonb        NOT NULL DEFAULT '{}'::jsonb,
  evidence_ref               varchar(500),
  created_at                 timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_transaction_attribution UNIQUE (commission_transaction_id, attribution_seq),

CONSTRAINT ck_attribution_allocation CHECK (
  attribution_method <> 'APPROVED_ALLOCATION' OR allocation_policy_id IS NOT NULL
),
CONSTRAINT ck_attribution_month_date CHECK (
  attribution_month = attribution_date - (EXTRACT(DAY FROM attribution_date)::integer - 1)
),
CONSTRAINT ck_attribution_scope CHECK (
  (attribution_scope = 'CONTRACT'
    AND contract_id IS NOT NULL
    AND attribution_method <> 'NEWCOMER_NON_CONTRACT')
  OR
  (attribution_scope = 'AGENT'
    AND contract_id IS NULL
    AND agent_id IS NOT NULL
    AND attribution_method = 'NEWCOMER_NON_CONTRACT'
    AND inclusion_status_snapshot IN ('EXCLUDED','REVIEW_REQUIRED')
    AND evidence_ref IS NOT NULL)
)
);
COMMENT ON TABLE transaction_attribution IS '지급건의 계약 또는 신인설계사 귀속. 계약 귀속은 1,200% 합산 원자행, AGENT 귀속은 적격 신인활동지원비 전용';

CREATE OR REPLACE FUNCTION fgc.guard_commission_transaction_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_sum numeric(15,2);
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'A transaction must be inserted as DRAFT, attributed, and then confirmed by UPDATE';
    END IF;
    RETURN NEW;
  ELSIF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'CONFIRMED/CANCELLED transaction % cannot be deleted; use a reversal or adjustment', OLD.commission_transaction_id;
    END IF;
    RETURN OLD;
  END IF;

  IF OLD.status = 'CONFIRMED' THEN
    IF NEW.status = 'CANCELLED' THEN
      IF ROW(NEW.payment_stage, NEW.source_type, NEW.source_business_key, NEW.insurer_id,
             NEW.recipient_agent_id, NEW.commission_item_id, NEW.policy_version_id,
             NEW.settlement_month, NEW.due_date, NEW.paid_on, NEW.amount, NEW.cashflow_type,
             NEW.evidence_ref, NEW.note)
         IS DISTINCT FROM
         ROW(OLD.payment_stage, OLD.source_type, OLD.source_business_key, OLD.insurer_id,
             OLD.recipient_agent_id, OLD.commission_item_id, OLD.policy_version_id,
             OLD.settlement_month, OLD.due_date, OLD.paid_on, OLD.amount, OLD.cashflow_type,
             OLD.evidence_ref, OLD.note) THEN
        RAISE EXCEPTION 'Only status may change from CONFIRMED to CANCELLED; use an adjustment transaction for corrections';
      END IF;
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'CONFIRMED transaction % is immutable; cancel/reverse with a new transaction', OLD.commission_transaction_id;
  ELSIF OLD.status = 'CANCELLED' THEN
    RAISE EXCEPTION 'CANCELLED transaction % is immutable', OLD.commission_transaction_id;
  END IF;

  IF NEW.status = 'CONFIRMED' AND OLD.status <> 'CONFIRMED' THEN
    SELECT COALESCE(SUM(attributed_amount),0)
      INTO v_sum
      FROM transaction_attribution
     WHERE commission_transaction_id = NEW.commission_transaction_id;
    IF v_sum <> NEW.amount THEN
      RAISE EXCEPTION 'Transaction % cannot be confirmed: attribution total % differs from amount %',
        NEW.commission_transaction_id, v_sum, NEW.amount;
    END IF;
    IF EXISTS (
      SELECT 1
        FROM transaction_attribution
       WHERE commission_transaction_id = NEW.commission_transaction_id
         AND inclusion_status_snapshot = 'REVIEW_REQUIRED'
    ) THEN
      RAISE EXCEPTION 'Transaction % cannot be confirmed: REVIEW_REQUIRED attribution exists',
        NEW.commission_transaction_id;
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_commission_transaction_guard
BEFORE INSERT OR UPDATE OR DELETE ON commission_transaction
FOR EACH ROW EXECUTE FUNCTION fgc.guard_commission_transaction_write();

CREATE OR REPLACE FUNCTION fgc.guard_transaction_attribution_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_old_transaction_id bigint;
  v_new_transaction_id bigint;
  v_transaction_id bigint;
  v_status varchar(20);
BEGIN
  IF TG_OP <> 'INSERT' THEN v_old_transaction_id := OLD.commission_transaction_id; END IF;
  IF TG_OP <> 'DELETE' THEN v_new_transaction_id := NEW.commission_transaction_id; END IF;

  -- 귀속 변경과 지급 확정이 동시에 진행되지 않도록 부모 지급행을 행 잠금한다.
  FOR v_transaction_id, v_status IN
    SELECT commission_transaction_id, status
      FROM commission_transaction
     WHERE commission_transaction_id = ANY (
       array_remove(ARRAY[v_old_transaction_id, v_new_transaction_id]::bigint[], NULL)
     )
     ORDER BY commission_transaction_id
     FOR UPDATE
  LOOP
    IF v_status <> 'DRAFT' THEN
      RAISE EXCEPTION 'Attributions of transaction % are immutable unless the transaction is DRAFT', v_transaction_id;
    END IF;
  END LOOP;

  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_transaction_attribution_guard
BEFORE INSERT OR UPDATE OR DELETE ON transaction_attribution
FOR EACH ROW EXECUTE FUNCTION fgc.guard_transaction_attribution_write();

-- ============================================================================
-- 4. 예상 스케줄 (1차 현행, 2차 4년·7년 재사용)
-- ============================================================================

CREATE TABLE schedule_header (
  schedule_header_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  contract_id             bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  payment_stage           varchar(20)  NOT NULL CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC')),
  policy_version_id       bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  schedule_version_no     integer      NOT NULL CHECK (schedule_version_no > 0),
  schedule_purpose        varchar(20)  NOT NULL DEFAULT 'OPERATIONAL'
                                      CHECK (schedule_purpose IN ('OPERATIONAL','COMPARISON','SIMULATION')),
  scenario_code           varchar(60),
  schedule_regime         varchar(30)  NOT NULL CHECK (schedule_regime IN ('CURRENT','FOUR_YEAR_2027','SEVEN_YEAR_2029','TM_SPECIAL')),
  status                  varchar(20)  NOT NULL DEFAULT 'PLANNED'
                                      CHECK (status IN ('PLANNED','CONFIRMED','MATCHED','ADJUSTED','HOLD','CANCELLED','RESTARTED')),
  active_yn               boolean      NOT NULL DEFAULT true,
  generation_reason       varchar(40)  NOT NULL DEFAULT 'CONTRACT_CREATED',
  regenerated_from_id     bigint REFERENCES schedule_header(schedule_header_id),
  calculation_input       jsonb        NOT NULL DEFAULT '{}'::jsonb,
  generated_by            bigint REFERENCES app_user(user_id),
  generated_at            timestamptz  NOT NULL DEFAULT clock_timestamp(),
  created_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_schedule_scenario CHECK (
    (schedule_purpose = 'OPERATIONAL' AND scenario_code IS NULL) OR
    (schedule_purpose <> 'OPERATIONAL' AND scenario_code IS NOT NULL)
  )
);
COMMENT ON TABLE schedule_header IS '계약별·지급단계별 예상 수수료 스케줄 버전 헤더. 운영본과 비교/시뮬레이션을 분리';
CREATE UNIQUE INDEX uq_schedule_header_version
  ON schedule_header(contract_id, payment_stage, schedule_purpose, COALESCE(scenario_code,''), schedule_version_no);
CREATE UNIQUE INDEX uq_schedule_header_active_operational
  ON schedule_header(contract_id, payment_stage)
  WHERE active_yn = true AND schedule_purpose = 'OPERATIONAL';
CREATE UNIQUE INDEX uq_schedule_header_active_scenario
  ON schedule_header(contract_id, payment_stage, schedule_purpose, scenario_code)
  WHERE active_yn = true AND schedule_purpose <> 'OPERATIONAL';

CREATE TABLE schedule_line (
  schedule_line_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  schedule_header_id     bigint       NOT NULL REFERENCES schedule_header(schedule_header_id),
  line_no                integer      NOT NULL CHECK (line_no > 0),
  installment_no         integer      NOT NULL CHECK (installment_no > 0),
  contract_month_no      integer      NOT NULL CHECK (contract_month_no > 0),
  due_date               date         NOT NULL,
  due_month              date         GENERATED ALWAYS AS (due_date - (EXTRACT(DAY FROM due_date)::integer - 1)) STORED,
  commission_item_id     bigint       NOT NULL REFERENCES commission_item(commission_item_id),
  beneficiary_agent_id   bigint REFERENCES agent(agent_id),
  basis_code             varchar(40)  NOT NULL,
  basis_amount           numeric(15,2) NOT NULL CHECK (basis_amount >= 0),
  calculation_type       varchar(15)  NOT NULL CHECK (calculation_type IN ('RATE','FIXED')),
  rate_pct               numeric(9,6),
  fixed_amount           numeric(15,2),
  expected_amount        numeric(15,2) NOT NULL CHECK (expected_amount >= 0),
  rounding_scale          smallint     NOT NULL DEFAULT 0 CHECK (rounding_scale = 0),
  rounding_mode           varchar(20)  NOT NULL DEFAULT 'HALF_UP' CHECK (rounding_mode = 'HALF_UP'),
  payment_condition_code varchar(50),
  line_status            varchar(20)  NOT NULL DEFAULT 'PLANNED'
                                     CHECK (line_status IN ('PLANNED','CONFIRMED','MATCHED','ADJUSTED','HOLD','CANCELLED','RESTARTED')),
  source_commission_rule_id bigint REFERENCES commission_rule(commission_rule_id),
  created_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_schedule_line_no UNIQUE (schedule_header_id, line_no),
  CONSTRAINT ck_schedule_line_value CHECK (
    (calculation_type = 'RATE'  AND rate_pct IS NOT NULL AND fixed_amount IS NULL)
    OR
    (calculation_type = 'FIXED' AND fixed_amount IS NOT NULL AND rate_pct IS NULL)
  )
);
CREATE UNIQUE INDEX uq_schedule_line_business
  ON schedule_line (schedule_header_id, commission_item_id, installment_no, COALESCE(beneficiary_agent_id,0));
COMMENT ON TABLE schedule_line IS '회차별 예상 수입·지급액. 별도 예상수수료 테이블을 두지 않고 대사의 기대값 원천으로 사용';

CREATE OR REPLACE FUNCTION fgc.guard_schedule_line_insert_stmt()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM new_rows n
      JOIN schedule_header h ON h.schedule_header_id = n.schedule_header_id
     WHERE h.status IN ('CONFIRMED','MATCHED','ADJUSTED','CANCELLED')
  ) THEN
    RAISE EXCEPTION 'Cannot insert lines into confirmed/matched/adjusted/cancelled schedule headers';
  END IF;
  RETURN NULL;
END;
$$;
CREATE TRIGGER trg_schedule_line_insert_guard
AFTER INSERT ON schedule_line
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION fgc.guard_schedule_line_insert_stmt();

CREATE OR REPLACE FUNCTION fgc.guard_schedule_line_update_stmt()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM (
        SELECT schedule_header_id FROM old_rows
        UNION
        SELECT schedule_header_id FROM new_rows
      ) x
      JOIN schedule_header h ON h.schedule_header_id = x.schedule_header_id
     WHERE h.status IN ('CONFIRMED','MATCHED','ADJUSTED','CANCELLED')
  ) THEN
    RAISE EXCEPTION 'Lines of confirmed/matched/adjusted/cancelled schedules are immutable; create a new version';
  END IF;
  RETURN NULL;
END;
$$;
CREATE TRIGGER trg_schedule_line_update_guard
AFTER UPDATE ON schedule_line
REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION fgc.guard_schedule_line_update_stmt();

CREATE OR REPLACE FUNCTION fgc.guard_schedule_line_delete_stmt()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM old_rows o
      JOIN schedule_header h ON h.schedule_header_id = o.schedule_header_id
     WHERE h.status IN ('CONFIRMED','MATCHED','ADJUSTED','CANCELLED')
  ) THEN
    RAISE EXCEPTION 'Lines of confirmed/matched/adjusted/cancelled schedules are immutable; create a new version';
  END IF;
  RETURN NULL;
END;
$$;
CREATE TRIGGER trg_schedule_line_delete_guard
AFTER DELETE ON schedule_line
REFERENCING OLD TABLE AS old_rows
FOR EACH STATEMENT EXECUTE FUNCTION fgc.guard_schedule_line_delete_stmt();

-- v2.1.2: 확정된 스케줄 헤더의 상태 되돌리기 차단
-- schedule_line 가드는 헤더의 "현재" 상태만 확인한다. 따라서 헤더 상태를
-- CONFIRMED에서 PLANNED로 되돌리면 확정된 예상금액을 그대로 수정할 수 있었다.
-- 운영정책서 제35조 "확정된 스케줄은 새 버전을 만든다"를 DB에서 강제한다.
CREATE OR REPLACE FUNCTION fgc.guard_schedule_header_status()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_locked constant text[] := ARRAY['CONFIRMED','MATCHED','ADJUSTED','CANCELLED'];
BEGIN
  IF OLD.status = ANY (v_locked) AND NOT (NEW.status = ANY (v_locked)) THEN
    RAISE EXCEPTION
      'Schedule header % is % and cannot be reopened; create a new schedule_version_no instead',
      OLD.schedule_header_id, OLD.status;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_schedule_header_status
BEFORE UPDATE OF status ON schedule_header
FOR EACH ROW EXECUTE FUNCTION fgc.guard_schedule_header_status();

-- ============================================================================
-- 5. 월 통합검증·1,200%·차익거래 (1차)
-- ============================================================================

CREATE TABLE validation_run (
  validation_run_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_month       date         NOT NULL CHECK (fgc.is_first_day_of_month(validation_month)),
  run_no                 integer      NOT NULL CHECK (run_no > 0),
  run_type               varchar(20)  NOT NULL DEFAULT 'MONTHLY'
                                     CHECK (run_type IN ('MONTHLY','MANUAL_CONTRACT','PRE_CONFIRM')),
  status                 varchar(20)  NOT NULL DEFAULT 'CREATED'
                                     CHECK (status IN ('CREATED','RUNNING','COMPLETED','FAILED','FINALIZED')),
  policy_snapshot        jsonb        NOT NULL DEFAULT '{}'::jsonb,
  started_at             timestamptz,
  completed_at           timestamptz,
  finalized_at           timestamptz,
  triggered_by           bigint REFERENCES app_user(user_id),
  finalized_by           bigint REFERENCES app_user(user_id),
  failure_message        varchar(2000),
  created_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_validation_run UNIQUE (validation_month, run_no),
  CONSTRAINT ck_validation_finalized CHECK (status <> 'FINALIZED' OR finalized_at IS NOT NULL)
);
COMMENT ON TABLE validation_run IS '1,200%·원장·대사·차익거래를 묶는 월 검증 실행. 공식 회계마감이 아님';
CREATE UNIQUE INDEX uq_validation_run_active_month
  ON validation_run(validation_month)
  WHERE run_type = 'MONTHLY' AND status IN ('CREATED','RUNNING');

CREATE TABLE validation_target (
  validation_target_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_run_id      bigint       NOT NULL REFERENCES validation_run(validation_run_id),
  contract_id            bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  product_offering_id    bigint       NOT NULL REFERENCES product_offering(product_offering_id),
  refund_rate_table_id   bigint REFERENCES refund_rate_table(refund_rate_table_id),
  selection_status       varchar(20)  NOT NULL CHECK (selection_status IN ('SELECTED','EXCLUDED','REVIEW_REQUIRED')),
  selection_reason       varchar(1000),
  snapshot               jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_validation_target UNIQUE (validation_run_id, contract_id)
);
COMMENT ON TABLE validation_target IS '검증 실행에 포함·제외·검토된 계약과 당시 상품·환급률표 선택 스냅샷';

CREATE TABLE cap_check (
  cap_check_id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_run_id       bigint REFERENCES validation_run(validation_run_id),
  contract_id             bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  payment_stage           varchar(20)  NOT NULL CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC')),
  cap_rule_set_id         bigint       NOT NULL REFERENCES cap_rule_set(cap_rule_set_id),
  refund_rate_table_id    bigint REFERENCES refund_rate_table(refund_rate_table_id),
  candidate_transaction_id bigint REFERENCES commission_transaction(commission_transaction_id),
  check_kind              varchar(20)  NOT NULL CHECK (check_kind IN ('REALTIME','MONTHLY','PRE_CONFIRM')),
  as_of_date              date         NOT NULL,
  base_premium_amount     numeric(15,2) NOT NULL CHECK (base_premium_amount >= 0),
  refund_12m_amount       numeric(15,2) NOT NULL DEFAULT 0 CHECK (refund_12m_amount >= 0),
  compliance_deduction_amount numeric(15,2) NOT NULL DEFAULT 0 CHECK (compliance_deduction_amount >= 0),
  limit_amount            numeric(15,2) NOT NULL CHECK (limit_amount >= 0),
  included_amount         numeric(15,2) NOT NULL CHECK (included_amount >= 0),
  remaining_amount        numeric(15,2) NOT NULL,
  usage_pct               numeric(12,6),
  result_status           varchar(20)  NOT NULL CHECK (result_status IN ('NORMAL','WARNING','VIOLATION','REVIEW_REQUIRED')),
  calculation_snapshot    jsonb        NOT NULL DEFAULT '{}'::jsonb,
  checked_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  created_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_cap_check_monthly UNIQUE (validation_run_id, contract_id, payment_stage),
  CONSTRAINT ck_cap_stage_deduction CHECK (
    payment_stage = 'INSURER_TO_GA' OR compliance_deduction_amount = 0
  )
);
COMMENT ON TABLE cap_check IS '계약별·지급단계별 초년도 1,200% 판정과 계산 스냅샷';

CREATE TABLE cap_check_detail (
  cap_check_detail_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cap_check_id               bigint       NOT NULL REFERENCES cap_check(cap_check_id),
  detail_seq                 integer      NOT NULL CHECK (detail_seq > 0),
  commission_item_id         bigint       NOT NULL REFERENCES commission_item(commission_item_id),
  transaction_attribution_id bigint REFERENCES transaction_attribution(transaction_attribution_id),
  schedule_line_id           bigint REFERENCES schedule_line(schedule_line_id),
  classification_snapshot    varchar(20)  NOT NULL CHECK (classification_snapshot IN ('INCLUDED','EXCLUDED','REVIEW_REQUIRED')),
  amount                     numeric(15,2) NOT NULL CHECK (amount >= 0),
  decision_reason            varchar(1000) NOT NULL,
  evidence_ref               varchar(500),
  created_at                 timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_cap_check_detail UNIQUE (cap_check_id, detail_seq),
  CONSTRAINT ck_cap_detail_source CHECK (
    NOT (transaction_attribution_id IS NOT NULL AND schedule_line_id IS NOT NULL)
  )
);
COMMENT ON TABLE cap_check_detail IS '1,200% 산입·제외 항목별 계산근거';

CREATE TABLE arbitrage_check (
  arbitrage_check_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_run_id         bigint       NOT NULL REFERENCES validation_run(validation_run_id),
  contract_id               bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  payment_stage             varchar(20)  NOT NULL DEFAULT 'GA_TO_FC' CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC')),
  as_of_date                date         NOT NULL,
  contract_month_no         integer      NOT NULL CHECK (contract_month_no > 0),
  cumulative_paid_premium   numeric(15,2) NOT NULL CHECK (cumulative_paid_premium >= 0),
  paid_commission_amount    numeric(15,2) NOT NULL DEFAULT 0 CHECK (paid_commission_amount >= 0),
  planned_commission_amount numeric(15,2) NOT NULL DEFAULT 0 CHECK (planned_commission_amount >= 0),
  included_surrender_value_amount numeric(15,2) NOT NULL DEFAULT 0 CHECK (included_surrender_value_amount >= 0),
  refund_addition_applied_yn boolean      NOT NULL DEFAULT false,
  surrender_value_source_type varchar(20) NOT NULL DEFAULT 'NOT_APPLICABLE'
                                           CHECK (surrender_value_source_type IN ('ACTUAL','EXPECTED_TABLE','NOT_APPLICABLE')),
  net_difference_amount     numeric(15,2) NOT NULL,
  refund_rate_table_id      bigint REFERENCES refund_rate_table(refund_rate_table_id),
  standard_deduction_80_yn  boolean      NOT NULL,
  result_status             varchar(20)  NOT NULL CHECK (result_status IN ('CLEAR','CANDIDATE','REVIEW_REQUIRED')),
  calculation_snapshot      jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at                timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_arbitrage_check UNIQUE (validation_run_id, contract_id, payment_stage, as_of_date),
  CONSTRAINT ck_arbitrage_refund_scope CHECK (
    (refund_addition_applied_yn = false
      AND included_surrender_value_amount = 0
      AND surrender_value_source_type = 'NOT_APPLICABLE'
      AND refund_rate_table_id IS NULL)
    OR
    (refund_addition_applied_yn = true
      AND contract_month_no BETWEEN 1 AND 36
      AND surrender_value_source_type IN ('ACTUAL','EXPECTED_TABLE')
      AND (surrender_value_source_type <> 'EXPECTED_TABLE' OR refund_rate_table_id IS NOT NULL))
  )
);
COMMENT ON TABLE arbitrage_check IS '지급단계별 차익거래 결과. 법규준수 기본은 GA_TO_FC이며 단계 간 합산 금지';

-- ============================================================================
-- 6. 복식부기 검증원장 (1차)
-- ============================================================================

CREATE TABLE journal_account (
  journal_account_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  account_code          varchar(40)  NOT NULL,
  account_name          varchar(120) NOT NULL,
  normal_balance        varchar(10)  NOT NULL CHECK (normal_balance IN ('DEBIT','CREDIT')),
  active_yn             boolean      NOT NULL DEFAULT true,
  CONSTRAINT uq_journal_account_code UNIQUE (account_code)
);
COMMENT ON TABLE journal_account IS 'FGC 내부 검증원장 계정과목. 법정 회계계정과목과 구분';

CREATE TABLE journal_header (
  journal_header_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  journal_no              varchar(80)  NOT NULL,
  journal_date            date         NOT NULL,
  journal_type            varchar(35)  NOT NULL CHECK (journal_type IN (
                               'EXPECTED_INSURER_INCOME','ACTUAL_INSURER_STATEMENT',
                               'EXPECTED_FC_PAYOUT','CONFIRMED_FC_PAYOUT',
                               'ADJUSTMENT','CLAWBACK','RECOVERY','REVERSAL'
                             )),
  source_entity_type      varchar(60)  NOT NULL,
  source_entity_id        varchar(100) NOT NULL,
  revision_no             integer      NOT NULL DEFAULT 1 CHECK (revision_no > 0),
  validation_run_id       bigint REFERENCES validation_run(validation_run_id),
  contract_id             bigint REFERENCES insurance_contract(contract_id),
  policy_version_id       bigint REFERENCES policy_version(policy_version_id),
  reversal_of_id          bigint REFERENCES journal_header(journal_header_id),
  correction_group_key    varchar(80),
  status                  varchar(15)  NOT NULL DEFAULT 'DRAFT'
                                      CHECK (status IN ('DRAFT','POSTED','REVERSED')),
  description             varchar(1000),
  created_by              bigint REFERENCES app_user(user_id),
  posted_by               bigint REFERENCES app_user(user_id),
  posted_at               timestamptz,
  created_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_journal_no UNIQUE (journal_no),
  CONSTRAINT uq_journal_source_revision UNIQUE (journal_type, source_entity_type, source_entity_id, revision_no),
  CONSTRAINT ck_journal_reversal_ref CHECK (
    (journal_type = 'REVERSAL' AND reversal_of_id IS NOT NULL) OR
    (journal_type <> 'REVERSAL' AND reversal_of_id IS NULL)
  )
);
COMMENT ON TABLE journal_header IS '복식부기 검증 분개 헤더. 확정 후 정정은 역분개+재기표';
CREATE UNIQUE INDEX uq_journal_current_posted_source
  ON journal_header(journal_type, source_entity_type, source_entity_id)
  WHERE status = 'POSTED' AND journal_type <> 'REVERSAL';
CREATE UNIQUE INDEX uq_journal_single_reversal
  ON journal_header(reversal_of_id)
  WHERE reversal_of_id IS NOT NULL;

CREATE TABLE journal_line (
  journal_line_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  journal_header_id     bigint       NOT NULL REFERENCES journal_header(journal_header_id),
  line_no               integer      NOT NULL CHECK (line_no > 0),
  journal_account_id    bigint       NOT NULL REFERENCES journal_account(journal_account_id),
  debit_amount          numeric(15,2) NOT NULL DEFAULT 0 CHECK (debit_amount >= 0),
  credit_amount         numeric(15,2) NOT NULL DEFAULT 0 CHECK (credit_amount >= 0),
  contract_id           bigint REFERENCES insurance_contract(contract_id),
  agent_id              bigint REFERENCES agent(agent_id),
  payment_stage         varchar(20) CHECK (payment_stage IS NULL OR payment_stage IN ('INSURER_TO_GA','GA_TO_FC')),
  commission_item_id    bigint REFERENCES commission_item(commission_item_id),
  memo                   varchar(500),
  created_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_journal_line UNIQUE (journal_header_id, line_no),
  CONSTRAINT ck_journal_line_one_side CHECK (
    (debit_amount > 0 AND credit_amount = 0) OR
    (credit_amount > 0 AND debit_amount = 0)
  )
);
COMMENT ON TABLE journal_line IS '차변 또는 대변 한쪽만 가진 검증 분개 상세';

CREATE VIEW vw_journal_imbalance AS
SELECT h.journal_header_id,
       h.journal_no,
       h.status,
       SUM(l.debit_amount)  AS debit_total,
       SUM(l.credit_amount) AS credit_total,
       SUM(l.debit_amount) - SUM(l.credit_amount) AS difference_amount
  FROM journal_header h
  JOIN journal_line l ON l.journal_header_id = h.journal_header_id
 GROUP BY h.journal_header_id, h.journal_no, h.status
HAVING SUM(l.debit_amount) <> SUM(l.credit_amount);

COMMENT ON VIEW vw_journal_imbalance IS '차변·대변 불균형 분개. POSTED 전 0건이어야 함';

CREATE VIEW vw_journal_posted_totals_by_basis AS
SELECT CASE
         WHEN h.journal_type IN ('EXPECTED_INSURER_INCOME','EXPECTED_FC_PAYOUT') THEN 'EXPECTED'
         WHEN h.journal_type = 'REVERSAL' THEN 'REVERSAL'
         ELSE 'ACTUAL'
       END AS ledger_basis,
       h.journal_date,
       l.payment_stage,
       SUM(l.debit_amount)  AS debit_total,
       SUM(l.credit_amount) AS credit_total
  FROM journal_header h
  JOIN journal_line l ON l.journal_header_id = h.journal_header_id
 WHERE h.status = 'POSTED'
 GROUP BY 1, h.journal_date, l.payment_stage;
COMMENT ON VIEW vw_journal_posted_totals_by_basis
  IS '예상과 실제를 합산하지 않고 별도 보고축으로 집계하는 검증원장 뷰';


CREATE OR REPLACE FUNCTION fgc.guard_journal_header_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_debit numeric(15,2);
  v_credit numeric(15,2);
  v_run_status varchar(20);
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'A journal must be inserted as DRAFT, populated with lines, and then POSTED by UPDATE';
    END IF;
    IF NEW.validation_run_id IS NOT NULL THEN
      SELECT status INTO v_run_status FROM validation_run WHERE validation_run_id = NEW.validation_run_id FOR SHARE;
      IF v_run_status = 'FINALIZED' THEN
        RAISE EXCEPTION 'Cannot add a journal to finalized validation run %', NEW.validation_run_id;
      END IF;
    END IF;
    RETURN NEW;
  END IF;

  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'POSTED/REVERSED journal % cannot be deleted', OLD.journal_header_id;
    END IF;
    IF OLD.validation_run_id IS NOT NULL THEN
      SELECT status INTO v_run_status FROM validation_run WHERE validation_run_id = OLD.validation_run_id FOR SHARE;
      IF v_run_status = 'FINALIZED' THEN
        RAISE EXCEPTION 'Cannot delete a journal from finalized validation run %', OLD.validation_run_id;
      END IF;
    END IF;
    RETURN OLD;
  END IF;

  IF OLD.validation_run_id IS NOT NULL THEN
    SELECT status INTO v_run_status FROM validation_run WHERE validation_run_id = OLD.validation_run_id FOR SHARE;
    IF v_run_status = 'FINALIZED' THEN
      RAISE EXCEPTION 'Journals of finalized validation run % are immutable', OLD.validation_run_id;
    END IF;
  END IF;
  IF NEW.validation_run_id IS NOT NULL AND NEW.validation_run_id IS DISTINCT FROM OLD.validation_run_id THEN
    SELECT status INTO v_run_status FROM validation_run WHERE validation_run_id = NEW.validation_run_id FOR SHARE;
    IF v_run_status = 'FINALIZED' THEN
      RAISE EXCEPTION 'Cannot move a journal into finalized validation run %', NEW.validation_run_id;
    END IF;
  END IF;

  IF OLD.status IN ('POSTED','REVERSED') THEN
    IF ROW(NEW.journal_no, NEW.journal_date, NEW.journal_type, NEW.source_entity_type,
           NEW.source_entity_id, NEW.revision_no, NEW.validation_run_id, NEW.contract_id,
           NEW.policy_version_id, NEW.reversal_of_id, NEW.correction_group_key,
           NEW.description, NEW.created_by, NEW.posted_by, NEW.posted_at)
       IS DISTINCT FROM
       ROW(OLD.journal_no, OLD.journal_date, OLD.journal_type, OLD.source_entity_type,
           OLD.source_entity_id, OLD.revision_no, OLD.validation_run_id, OLD.contract_id,
           OLD.policy_version_id, OLD.reversal_of_id, OLD.correction_group_key,
           OLD.description, OLD.created_by, OLD.posted_by, OLD.posted_at) THEN
      RAISE EXCEPTION 'Posted/reversed journal % business fields are immutable', OLD.journal_header_id;
    END IF;
  END IF;

  IF NEW.status = 'POSTED' AND OLD.status = 'DRAFT' THEN
    SELECT COALESCE(SUM(debit_amount),0), COALESCE(SUM(credit_amount),0)
      INTO v_debit, v_credit
      FROM journal_line
     WHERE journal_header_id = NEW.journal_header_id;
    IF v_debit = 0 OR v_debit <> v_credit THEN
      RAISE EXCEPTION 'Journal % is not balanced: debit %, credit %', NEW.journal_no, v_debit, v_credit;
    END IF;
    NEW.posted_at := COALESCE(NEW.posted_at, clock_timestamp());
  ELSIF OLD.status = 'POSTED' AND NEW.status = 'REVERSED' THEN
    IF NOT EXISTS (
      SELECT 1 FROM journal_header r
       WHERE r.reversal_of_id = OLD.journal_header_id
         AND r.status = 'POSTED'
    ) THEN
      RAISE EXCEPTION 'Journal % may be marked REVERSED only after its reversal journal is POSTED', OLD.journal_header_id;
    END IF;
  ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
    RAISE EXCEPTION 'Invalid journal status transition: % -> %', OLD.status, NEW.status;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_journal_header_guard
BEFORE INSERT OR UPDATE OR DELETE ON journal_header
FOR EACH ROW EXECUTE FUNCTION fgc.guard_journal_header_write();

CREATE OR REPLACE FUNCTION fgc.guard_journal_line_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_old_header_id bigint;
  v_new_header_id bigint;
  v_header_id bigint;
  v_status varchar(15);
  v_run_id bigint;
  v_run_status varchar(20);
BEGIN
  IF TG_OP <> 'INSERT' THEN v_old_header_id := OLD.journal_header_id; END IF;
  IF TG_OP <> 'DELETE' THEN v_new_header_id := NEW.journal_header_id; END IF;

  -- 라인 변경과 분개 POSTED 전환을 직렬화한다.
  FOR v_header_id, v_status, v_run_id IN
    SELECT journal_header_id, status, validation_run_id
      FROM journal_header
     WHERE journal_header_id = ANY (
       array_remove(ARRAY[v_old_header_id, v_new_header_id]::bigint[], NULL)
     )
     ORDER BY journal_header_id
     FOR UPDATE
  LOOP
    IF v_status IN ('POSTED','REVERSED') THEN
      RAISE EXCEPTION 'Lines of posted/reversed journal % are immutable', v_header_id;
    END IF;
    IF v_run_id IS NOT NULL THEN
      SELECT status INTO v_run_status
        FROM validation_run
       WHERE validation_run_id = v_run_id
       FOR SHARE;
      IF v_run_status = 'FINALIZED' THEN
        RAISE EXCEPTION 'Journal lines of finalized validation run % are immutable', v_run_id;
      END IF;
    END IF;
  END LOOP;

  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_journal_line_guard
BEFORE INSERT OR UPDATE OR DELETE ON journal_line
FOR EACH ROW EXECUTE FUNCTION fgc.guard_journal_line_write();

-- ============================================================================
-- 7. 예상/실제 대사 (1차)
-- ============================================================================

CREATE TABLE reconciliation_run (
  reconciliation_run_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_run_id     bigint REFERENCES validation_run(validation_run_id),
  settlement_month      date         NOT NULL CHECK (fgc.is_first_day_of_month(settlement_month)),
  payment_stage         varchar(20)  NOT NULL CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC')),
  insurer_id            bigint REFERENCES insurer(insurer_id),
  status                varchar(20)  NOT NULL DEFAULT 'CREATED'
                                    CHECK (status IN ('CREATED','RUNNING','COMPLETED','FAILED','FINALIZED')),
  tolerance_policy_version_id bigint REFERENCES policy_version(policy_version_id),
  started_at            timestamptz,
  completed_at          timestamptz,
  finalized_at          timestamptz,
  finalized_by          bigint REFERENCES app_user(user_id),
  created_by            bigint REFERENCES app_user(user_id),
  created_at            timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_reconciliation_finalized CHECK (status <> 'FINALIZED' OR finalized_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_reconciliation_run
  ON reconciliation_run (settlement_month, payment_stage, COALESCE(insurer_id,0), COALESCE(validation_run_id,0));
COMMENT ON TABLE reconciliation_run IS '지급단계별 예상 스케줄과 실제 명세/확정 지급건 대사 실행';

CREATE TABLE reconciliation_result (
  reconciliation_result_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  reconciliation_run_id    bigint       NOT NULL REFERENCES reconciliation_run(reconciliation_run_id),
  match_group_key          varchar(300) NOT NULL,
  contract_id              bigint REFERENCES insurance_contract(contract_id),
  expected_agent_id        bigint REFERENCES agent(agent_id),
  actual_agent_id          bigint REFERENCES agent(agent_id),
  actual_source_agent_code varchar(80),
  commission_item_id       bigint REFERENCES commission_item(commission_item_id),
  installment_no           integer,
  result_type              varchar(30)  NOT NULL CHECK (result_type IN (
                              'MATCHED','AMOUNT_DIFFERENCE','EXPECTED_MISSING','ACTUAL_MISSING',
                              'DUPLICATE','AGENT_MISMATCH','INSTALLMENT_MISMATCH',
                              'INVALID_CONTRACT_PAYMENT','POLICY_VERSION_ERROR','JOURNAL_IMBALANCE','REVIEW_REQUIRED'
                            )),
  expected_total_amount    numeric(15,2) NOT NULL DEFAULT 0,
  actual_total_amount      numeric(15,2) NOT NULL DEFAULT 0,
  difference_amount        numeric(15,2) NOT NULL DEFAULT 0,
  primary_reason_code      varchar(50),
  secondary_reason_codes   text[]       NOT NULL DEFAULT '{}',
  detail_snapshot          jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at               timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_reconciliation_result UNIQUE (reconciliation_run_id, match_group_key)
);
COMMENT ON TABLE reconciliation_result IS '대사 매칭그룹별 일치·누락·중복·금액차·상대방/회차 불일치 결과';

CREATE TABLE reconciliation_match (
  reconciliation_match_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  reconciliation_result_id bigint       NOT NULL REFERENCES reconciliation_result(reconciliation_result_id),
  match_seq                integer      NOT NULL CHECK (match_seq > 0),
  schedule_line_id         bigint REFERENCES schedule_line(schedule_line_id),
  transaction_attribution_id bigint REFERENCES transaction_attribution(transaction_attribution_id),
  matched_amount           numeric(15,2) NOT NULL DEFAULT 0 CHECK (matched_amount >= 0),
  match_role               varchar(20)  NOT NULL CHECK (match_role IN ('EXPECTED','ACTUAL','BOTH')),
  created_at               timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_reconciliation_match UNIQUE (reconciliation_result_id, match_seq),
  CONSTRAINT ck_reconciliation_match_source CHECK (
    schedule_line_id IS NOT NULL OR transaction_attribution_id IS NOT NULL
  )
);
COMMENT ON TABLE reconciliation_match IS '대사결과와 예상 스케줄행·실제 귀속행의 상세 연결';

CREATE VIEW vw_reconciliation_summary AS
SELECT r.reconciliation_run_id,
       r.settlement_month,
       r.payment_stage,
       COUNT(x.reconciliation_result_id) AS result_count,
       COUNT(*) FILTER (WHERE x.result_type = 'MATCHED') AS matched_count,
       COUNT(*) FILTER (WHERE x.result_type <> 'MATCHED') AS exception_count,
       COALESCE(SUM(x.expected_total_amount),0) AS expected_total,
       COALESCE(SUM(x.actual_total_amount),0) AS actual_total,
       COALESCE(SUM(x.difference_amount),0) AS difference_total
  FROM reconciliation_run r
  LEFT JOIN reconciliation_result x ON x.reconciliation_run_id = r.reconciliation_run_id
 GROUP BY r.reconciliation_run_id, r.settlement_month, r.payment_stage;
COMMENT ON VIEW vw_reconciliation_summary IS '대사 실행 요약. 화면용 중복 저장 없이 상세에서 집계';

-- ============================================================================
-- 8. 공통 예외 워크플로 (1차)
-- ============================================================================

CREATE TABLE exception_case (
  exception_case_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  exception_key           varchar(300) NOT NULL,
  exception_type          varchar(50)  NOT NULL CHECK (exception_type IN (
                               'CAP_WARNING','CAP_VIOLATION','CAP_REVIEW_REQUIRED',
                               'RECONCILIATION_MISMATCH','JOURNAL_IMBALANCE','ARBITRAGE_CANDIDATE',
                               'REFUND_TABLE_MISSING','PRODUCT_CODE_MISMATCH','POLICY_MISSING','POLICY_DUPLICATE',
                               'ALLOCATION_EVIDENCE_MISSING','DATA_QUALITY','OTHER'
                             )),
  severity                varchar(15)  NOT NULL CHECK (severity IN ('INFO','WARNING','HIGH','CRITICAL')),
  status                  varchar(20)  NOT NULL DEFAULT 'NEW'
                                      CHECK (status IN ('NEW','IN_REVIEW','RESOLVED','REJECTED')),
  validation_run_id       bigint REFERENCES validation_run(validation_run_id),
  contract_id             bigint REFERENCES insurance_contract(contract_id),
  agent_id                bigint REFERENCES agent(agent_id),
  policy_version_id       bigint REFERENCES policy_version(policy_version_id),
  source_entity_type      varchar(60)  NOT NULL,
  source_entity_id        varchar(100) NOT NULL,
  title                   varchar(300) NOT NULL,
  description             varchar(2000),
  assigned_to             bigint REFERENCES app_user(user_id),
  due_at                  timestamptz,
  resolved_at             timestamptz,
  created_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_exception_key UNIQUE (exception_key)
);
COMMENT ON TABLE exception_case IS '1,200%·대사·원장·차익거래·데이터품질의 공통 예외 큐';
CREATE TRIGGER trg_exception_case_updated_at BEFORE UPDATE ON exception_case
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();

CREATE TABLE exception_action (
  exception_action_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  exception_case_id     bigint       NOT NULL REFERENCES exception_case(exception_case_id),
  action_seq            integer      NOT NULL CHECK (action_seq > 0),
  from_status           varchar(20),
  to_status             varchar(20)  NOT NULL CHECK (to_status IN ('NEW','IN_REVIEW','RESOLVED','REJECTED')),
  action_type           varchar(40)  NOT NULL CHECK (action_type IN (
                             'ASSIGN','START_REVIEW','CORRECT','REDUCE','CANCEL','DEFER',
                             'RECONCILE_AGAIN','FALSE_POSITIVE','RESOLVE','REJECT','COMMENT'
                           )),
  reason                varchar(2000) NOT NULL,
  evidence_ref          varchar(500),
  action_by             bigint       NOT NULL REFERENCES app_user(user_id),
  action_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_exception_action_seq UNIQUE (exception_case_id, action_seq)
);
COMMENT ON TABLE exception_action IS '예외 담당·상태·처리사유의 변경 이력';
CREATE TRIGGER trg_exception_action_append_only
BEFORE UPDATE OR DELETE ON exception_action
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

-- FINALIZED 실행의 부모·근거·상세를 모두 잠근다.
CREATE OR REPLACE FUNCTION fgc.validation_run_is_finalized(p_run_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT COALESCE((SELECT status = 'FINALIZED' FROM validation_run WHERE validation_run_id = p_run_id), false);
$$;

CREATE OR REPLACE FUNCTION fgc.guard_finalized_validation_result()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_old_run_id bigint;
  v_new_run_id bigint;
  v_parent_id bigint;
  v_run_id bigint;
  v_status varchar(20);
BEGIN
  IF TG_TABLE_NAME IN ('validation_target','cap_check','arbitrage_check','acquisition_cost_check','maintenance_check') THEN
    IF TG_OP <> 'INSERT' THEN v_old_run_id := OLD.validation_run_id; END IF;
    IF TG_OP <> 'DELETE' THEN v_new_run_id := NEW.validation_run_id; END IF;
  ELSIF TG_TABLE_NAME = 'cap_check_detail' THEN
    IF TG_OP <> 'INSERT' THEN
      v_parent_id := OLD.cap_check_id;
      SELECT validation_run_id INTO v_old_run_id
        FROM cap_check WHERE cap_check_id = v_parent_id
        FOR SHARE;
    END IF;
    IF TG_OP <> 'DELETE' THEN
      v_parent_id := NEW.cap_check_id;
      SELECT validation_run_id INTO v_new_run_id
        FROM cap_check WHERE cap_check_id = v_parent_id
        FOR SHARE;
    END IF;
  ELSE
    RAISE EXCEPTION 'Unsupported validation result table: %', TG_TABLE_NAME;
  END IF;

  -- 결과행 변경과 validation_run FINALIZED 전환을 직렬화한다.
  FOR v_run_id, v_status IN
    SELECT validation_run_id, status
      FROM validation_run
     WHERE validation_run_id = ANY (
       array_remove(ARRAY[v_old_run_id, v_new_run_id]::bigint[], NULL)
     )
     ORDER BY validation_run_id
     FOR SHARE
  LOOP
    IF v_status = 'FINALIZED' THEN
      RAISE EXCEPTION 'Results of finalized validation run % are immutable', v_run_id;
    END IF;
  END LOOP;

  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_validation_target_immutable
BEFORE INSERT OR UPDATE OR DELETE ON validation_target
FOR EACH ROW EXECUTE FUNCTION fgc.guard_finalized_validation_result();
CREATE TRIGGER trg_cap_check_immutable
BEFORE INSERT OR UPDATE OR DELETE ON cap_check
FOR EACH ROW EXECUTE FUNCTION fgc.guard_finalized_validation_result();
CREATE TRIGGER trg_cap_check_detail_immutable
BEFORE INSERT OR UPDATE OR DELETE ON cap_check_detail
FOR EACH ROW EXECUTE FUNCTION fgc.guard_finalized_validation_result();
CREATE TRIGGER trg_arbitrage_check_immutable
BEFORE INSERT OR UPDATE OR DELETE ON arbitrage_check
FOR EACH ROW EXECUTE FUNCTION fgc.guard_finalized_validation_result();

CREATE OR REPLACE FUNCTION fgc.guard_validation_run_finalized()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP='DELETE' AND OLD.status='FINALIZED' THEN
    RAISE EXCEPTION 'Finalized validation run % cannot be deleted', OLD.validation_run_id;
  ELSIF TG_OP='UPDATE' AND OLD.status='FINALIZED' THEN
    RAISE EXCEPTION 'Finalized validation run % is immutable', OLD.validation_run_id;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_validation_run_finalized
BEFORE UPDATE OR DELETE ON validation_run
FOR EACH ROW EXECUTE FUNCTION fgc.guard_validation_run_finalized();

CREATE OR REPLACE FUNCTION fgc.reconciliation_run_is_finalized(p_run_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT COALESCE((SELECT status = 'FINALIZED' FROM reconciliation_run WHERE reconciliation_run_id = p_run_id), false);
$$;

CREATE OR REPLACE FUNCTION fgc.guard_finalized_reconciliation_result()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_old_run_id bigint;
  v_new_run_id bigint;
  v_run_id bigint;
  v_status varchar(20);
BEGIN
  IF TG_TABLE_NAME = 'reconciliation_result' THEN
    IF TG_OP <> 'INSERT' THEN v_old_run_id := OLD.reconciliation_run_id; END IF;
    IF TG_OP <> 'DELETE' THEN v_new_run_id := NEW.reconciliation_run_id; END IF;
  ELSIF TG_TABLE_NAME = 'reconciliation_match' THEN
    IF TG_OP <> 'INSERT' THEN
      SELECT reconciliation_run_id INTO v_old_run_id
        FROM reconciliation_result
       WHERE reconciliation_result_id = OLD.reconciliation_result_id
       FOR SHARE;
    END IF;
    IF TG_OP <> 'DELETE' THEN
      SELECT reconciliation_run_id INTO v_new_run_id
        FROM reconciliation_result
       WHERE reconciliation_result_id = NEW.reconciliation_result_id
       FOR SHARE;
    END IF;
  ELSE
    RAISE EXCEPTION 'Unsupported reconciliation result table: %', TG_TABLE_NAME;
  END IF;

  -- 상세행 변경과 reconciliation_run FINALIZED 전환을 직렬화한다.
  FOR v_run_id, v_status IN
    SELECT reconciliation_run_id, status
      FROM reconciliation_run
     WHERE reconciliation_run_id = ANY (
       array_remove(ARRAY[v_old_run_id, v_new_run_id]::bigint[], NULL)
     )
     ORDER BY reconciliation_run_id
     FOR SHARE
  LOOP
    IF v_status = 'FINALIZED' THEN
      RAISE EXCEPTION 'Results of finalized reconciliation run % are immutable', v_run_id;
    END IF;
  END LOOP;

  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_reconciliation_result_immutable
BEFORE INSERT OR UPDATE OR DELETE ON reconciliation_result
FOR EACH ROW EXECUTE FUNCTION fgc.guard_finalized_reconciliation_result();
CREATE TRIGGER trg_reconciliation_match_immutable
BEFORE INSERT OR UPDATE OR DELETE ON reconciliation_match
FOR EACH ROW EXECUTE FUNCTION fgc.guard_finalized_reconciliation_result();

CREATE OR REPLACE FUNCTION fgc.guard_reconciliation_run_finalized()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP='DELETE' AND OLD.status='FINALIZED' THEN
    RAISE EXCEPTION 'Finalized reconciliation run % cannot be deleted', OLD.reconciliation_run_id;
  ELSIF TG_OP='UPDATE' AND OLD.status='FINALIZED' THEN
    RAISE EXCEPTION 'Finalized reconciliation run % is immutable', OLD.reconciliation_run_id;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_reconciliation_run_finalized
BEFORE UPDATE OR DELETE ON reconciliation_run
FOR EACH ROW EXECUTE FUNCTION fgc.guard_reconciliation_run_finalized();

-- ============================================================================
-- 9. 2차 확장: 인사·담당자·환수·2027/2029 검증
-- ============================================================================

CREATE TABLE agent_history (
  agent_history_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  agent_id              bigint       NOT NULL REFERENCES agent(agent_id),
  effective_from        date         NOT NULL,
  effective_to          date,
  change_type           varchar(20)  NOT NULL CHECK (change_type IN ('RANK','ORG','STATUS','APPOINT','TERMINATE','EXPERIENCE')),
  organization_id       bigint REFERENCES organization(organization_id),
  rank_code             varchar(30),
  agent_status          varchar(20) CHECK (agent_status IS NULL OR agent_status IN ('ACTIVE','INACTIVE','TERMINATED')),
  newcomer_snapshot     jsonb        NOT NULL DEFAULT '{}'::jsonb,
  reason                varchar(1000),
  created_by            bigint REFERENCES app_user(user_id),
  created_at            timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_agent_history_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CONSTRAINT uq_agent_history UNIQUE (agent_id, effective_from, change_type)
);
COMMENT ON TABLE agent_history IS '설계사 직급·소속·상태·위촉·해촉·경력판정 이력(2차)';

CREATE TABLE contract_manager_assignment (
  contract_manager_assignment_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  contract_id                    bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  agent_id                       bigint       NOT NULL REFERENCES agent(agent_id),
  assignment_type                varchar(20)  NOT NULL CHECK (assignment_type IN ('ORIGINAL_SOLICITOR','MAINTENANCE_MANAGER')),
  effective_from                 date         NOT NULL,
  effective_to                   date,
  reason                         varchar(1000),
  created_by                     bigint REFERENCES app_user(user_id),
  created_at                     timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_contract_manager_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
  CONSTRAINT uq_contract_manager UNIQUE (contract_id, assignment_type, effective_from)
);
COMMENT ON TABLE contract_manager_assignment IS '계약 모집설계사·유지관리 담당자 유효기간 이력(2차)';

CREATE TABLE policy_approval_action (
  policy_approval_action_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  policy_version_id         bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  action_seq                integer      NOT NULL CHECK (action_seq > 0),
  action_type               varchar(20)  NOT NULL CHECK (action_type IN ('SUBMIT','REVIEW','APPROVE','REJECT','ACTIVATE','RETIRE')),
  action_by                 bigint       NOT NULL REFERENCES app_user(user_id),
  action_at                 timestamptz  NOT NULL DEFAULT clock_timestamp(),
  comment                   varchar(2000),
  evidence_ref              varchar(500),
  CONSTRAINT uq_policy_approval_action UNIQUE (policy_version_id, action_seq)
);
COMMENT ON TABLE policy_approval_action IS '작성자·승인자 분리와 정책 상태전이 이력(2차)';
CREATE TRIGGER trg_policy_approval_action_append_only
BEFORE UPDATE OR DELETE ON policy_approval_action
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

CREATE TABLE clawback_rule (
  clawback_rule_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  policy_version_id      bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  insurer_id             bigint REFERENCES insurer(insurer_id),
  product_offering_id    bigint REFERENCES product_offering(product_offering_id),
  commission_item_id     bigint REFERENCES commission_item(commission_item_id),
  elapsed_month_from     integer      NOT NULL CHECK (elapsed_month_from >= 0),
  elapsed_month_to       integer      NOT NULL CHECK (elapsed_month_to >= elapsed_month_from),
  clawback_basis_code    varchar(40)  NOT NULL,
  clawback_rate_pct      numeric(9,6) NOT NULL CHECK (clawback_rate_pct BETWEEN 0 AND 100),
  condition_expression   jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at             timestamptz  NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX uq_clawback_rule
  ON clawback_rule (policy_version_id, elapsed_month_from, elapsed_month_to, COALESCE(commission_item_id,0));
COMMENT ON TABLE clawback_rule IS '보험회사·상품·경과월별 환수율과 조건(2차, 회사정책 영역)';

CREATE TABLE clawback_case (
  clawback_case_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  contract_id             bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  trigger_status_event_id bigint REFERENCES contract_status_event(contract_status_event_id),
  policy_version_id       bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  case_no                 varchar(80)  NOT NULL,
  status                  varchar(25)  NOT NULL DEFAULT 'DETECTED'
                                      CHECK (status IN ('DETECTED','CALCULATED','NOTIFIED','OBJECTED','CONFIRMED','RECOVERING','CLOSED','CANCELLED')),
  calculated_amount       numeric(15,2) NOT NULL DEFAULT 0 CHECK (calculated_amount >= 0),
  confirmed_amount        numeric(15,2) CHECK (confirmed_amount IS NULL OR confirmed_amount >= 0),
  notice_date             date,
  objection_deadline      date,
  objection_received_at   timestamptz,
  description             varchar(2000),
  created_by              bigint REFERENCES app_user(user_id),
  created_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_clawback_case_no UNIQUE (case_no)
);
COMMENT ON TABLE clawback_case IS '환수 탐지→계산→통지→이의→확정→회수 케이스(2차)';
CREATE TRIGGER trg_clawback_case_updated_at BEFORE UPDATE ON clawback_case
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();

CREATE TABLE clawback_line (
  clawback_line_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  clawback_case_id          bigint       NOT NULL REFERENCES clawback_case(clawback_case_id),
  line_no                   integer      NOT NULL CHECK (line_no > 0),
  transaction_attribution_id bigint      NOT NULL REFERENCES transaction_attribution(transaction_attribution_id),
  clawback_rule_id          bigint       NOT NULL REFERENCES clawback_rule(clawback_rule_id),
  originally_paid_amount    numeric(15,2) NOT NULL CHECK (originally_paid_amount >= 0),
  applied_rate_pct          numeric(9,6) NOT NULL CHECK (applied_rate_pct BETWEEN 0 AND 100),
  clawback_amount           numeric(15,2) NOT NULL CHECK (clawback_amount >= 0),
  calculation_snapshot      jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at                timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_clawback_line UNIQUE (clawback_case_id, line_no)
);
COMMENT ON TABLE clawback_line IS '기지급 귀속행별 환수 계산근거(2차)';

CREATE TABLE recovery_transaction (
  recovery_transaction_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  clawback_case_id        bigint       NOT NULL REFERENCES clawback_case(clawback_case_id),
  recovery_seq            integer      NOT NULL CHECK (recovery_seq > 0),
  recovery_type           varchar(25)  NOT NULL CHECK (recovery_type IN ('OFFSET','DIRECT_PAYMENT','GUARANTEE_INSURANCE','COLLECTION','ADJUSTMENT')),
  recovery_date           date         NOT NULL,
  recovered_amount        numeric(15,2) NOT NULL CHECK (recovered_amount > 0),
  offset_transaction_id   bigint REFERENCES commission_transaction(commission_transaction_id),
  evidence_ref            varchar(500),
  created_by              bigint REFERENCES app_user(user_id),
  created_at              timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_recovery_transaction UNIQUE (clawback_case_id, recovery_seq)
);
COMMENT ON TABLE recovery_transaction IS '환수 확정액의 상계·입금·보증보험·추심 회수내역(2차)';
CREATE TRIGGER trg_recovery_transaction_append_only
BEFORE UPDATE OR DELETE ON recovery_transaction
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

CREATE TABLE acquisition_cost_check (
  acquisition_cost_check_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_run_id         bigint       NOT NULL REFERENCES validation_run(validation_run_id),
  contract_id               bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  policy_version_id         bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  upfront_limit_amount      numeric(15,2) NOT NULL CHECK (upfront_limit_amount >= 0),
  upfront_total_amount      numeric(15,2) NOT NULL CHECK (upfront_total_amount >= 0),
  maintenance_limit_amount  numeric(15,2) NOT NULL CHECK (maintenance_limit_amount >= 0),
  maintenance_total_amount  numeric(15,2) NOT NULL CHECK (maintenance_total_amount >= 0),
  indirect_support_amount   numeric(15,2) NOT NULL DEFAULT 0 CHECK (indirect_support_amount >= 0),
  result_status             varchar(20)  NOT NULL CHECK (result_status IN ('NORMAL','VIOLATION','REVIEW_REQUIRED')),
  calculation_snapshot      jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at                timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_acquisition_cost_check UNIQUE (validation_run_id, contract_id)
);
COMMENT ON TABLE acquisition_cost_check IS '2027년 이후 선지급·유지관리수수료 계약체결비용 한도 분리검증(2차)';

CREATE TABLE maintenance_check (
  maintenance_check_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_run_id      bigint       NOT NULL REFERENCES validation_run(validation_run_id),
  contract_id            bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  schedule_line_id       bigint       NOT NULL REFERENCES schedule_line(schedule_line_id),
  policy_version_id      bigint       NOT NULL REFERENCES policy_version(policy_version_id),
  contract_active_yn     boolean      NOT NULL,
  manager_valid_yn       boolean      NOT NULL,
  installment_valid_yn   boolean      NOT NULL,
  equal_monthly_valid_yn boolean      NOT NULL,
  limit_valid_yn         boolean      NOT NULL,
  result_status          varchar(20)  NOT NULL CHECK (result_status IN ('NORMAL','HOLD','VIOLATION','REVIEW_REQUIRED')),
  decision_reason        varchar(1000) NOT NULL,
  created_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_maintenance_check UNIQUE (validation_run_id, schedule_line_id)
);
COMMENT ON TABLE maintenance_check IS '2027/2029 유지관리·장기유지수수료의 계약상태·담당자·회차·동일월금액·한도 검증(2차)';
CREATE TRIGGER trg_acquisition_cost_check_immutable
BEFORE INSERT OR UPDATE OR DELETE ON acquisition_cost_check
FOR EACH ROW EXECUTE FUNCTION fgc.guard_finalized_validation_result();
CREATE TRIGGER trg_maintenance_check_immutable
BEFORE INSERT OR UPDATE OR DELETE ON maintenance_check
FOR EACH ROW EXECUTE FUNCTION fgc.guard_finalized_validation_result();

-- ============================================================================
-- 10. 인덱스
-- ============================================================================

CREATE INDEX ix_agent_insurer_code_resolve
  ON agent_insurer_code(insurer_id, insurer_agent_code, effective_from, effective_to, code_status);
CREATE INDEX ix_agent_insurer_code_agent
  ON agent_insurer_code(agent_id, insurer_id, effective_from);
CREATE INDEX ix_product_offering_resolve
  ON product_offering(product_id, sales_start_date, sales_end_date, channel_code, fee_regime_code);
CREATE INDEX ix_policy_version_resolve
  ON policy_version(policy_type, source_class, status, effective_from, effective_to, fee_regime_code);
CREATE INDEX ix_commission_rule_resolve
  ON commission_rule(policy_version_id, payment_stage, product_offering_id, organization_id, agent_rank_code, installment_from, installment_to);
CREATE INDEX ix_refund_rate_table_resolve
  ON refund_rate_table(product_id, payment_term_months, channel_code, effective_from, effective_to);
CREATE INDEX ix_contract_date_offering
  ON insurance_contract(contract_date, product_offering_id, current_status);
CREATE INDEX ix_financial_snapshot_contract_date
  ON contract_financial_snapshot(contract_id, as_of_date);
CREATE INDEX ix_transaction_month_stage
  ON commission_transaction(settlement_month, payment_stage, status);
CREATE INDEX ix_transaction_agent
  ON commission_transaction(recipient_agent_id, settlement_month);
CREATE INDEX ix_attribution_contract_month
  ON transaction_attribution(contract_id, attribution_month, inclusion_status_snapshot)
  WHERE contract_id IS NOT NULL;
CREATE INDEX ix_attribution_contract_date
  ON transaction_attribution(contract_id, attribution_date, inclusion_status_snapshot)
  WHERE contract_id IS NOT NULL;
CREATE INDEX ix_attribution_agent_scope
  ON transaction_attribution(agent_id, attribution_date, inclusion_status_snapshot)
  WHERE attribution_scope = 'AGENT';
CREATE INDEX ix_attribution_source_agent
  ON transaction_attribution(source_agent_code, attribution_month) WHERE source_agent_code IS NOT NULL;
CREATE INDEX ix_schedule_line_due
  ON schedule_line(due_month, line_status, schedule_header_id);
CREATE INDEX ix_schedule_line_contract_month
  ON schedule_line(schedule_header_id, contract_month_no, installment_no);
CREATE INDEX ix_validation_target_status
  ON validation_target(validation_run_id, selection_status);
CREATE INDEX ix_cap_check_contract
  ON cap_check(contract_id, payment_stage, as_of_date DESC);
CREATE INDEX ix_arbitrage_contract
  ON arbitrage_check(contract_id, payment_stage, as_of_date);
CREATE INDEX ix_journal_contract_date
  ON journal_header(contract_id, journal_date, status);
CREATE INDEX ix_reconciliation_result_type
  ON reconciliation_result(reconciliation_run_id, result_type);
CREATE INDEX ix_exception_work_queue
  ON exception_case(status, severity, assigned_to, created_at);
CREATE INDEX ix_contract_status_event_date
  ON contract_status_event(contract_id, effective_at);
CREATE INDEX ix_clawback_case_status
  ON clawback_case(status, contract_id);

-- ============================================================================
-- 11. 유용한 조회 뷰
-- ============================================================================

CREATE VIEW vw_transaction_attribution_balance AS
SELECT t.commission_transaction_id,
       t.source_type,
       t.source_business_key,
       t.amount AS transaction_amount,
       COALESCE(SUM(a.attributed_amount),0) AS attributed_total,
       t.amount - COALESCE(SUM(a.attributed_amount),0) AS difference_amount,
       t.status
  FROM commission_transaction t
  LEFT JOIN transaction_attribution a
    ON a.commission_transaction_id = t.commission_transaction_id
 GROUP BY t.commission_transaction_id, t.source_type, t.source_business_key, t.amount, t.status
HAVING t.amount <> COALESCE(SUM(a.attributed_amount),0);
COMMENT ON VIEW vw_transaction_attribution_balance IS '지급건 금액과 계약별 귀속합계가 다른 건';

CREATE VIEW vw_latest_cap_check AS
SELECT DISTINCT ON (contract_id, payment_stage)
       cap_check_id, contract_id, payment_stage, as_of_date,
       limit_amount, included_amount, remaining_amount, usage_pct, result_status,
       validation_run_id
  FROM cap_check
 ORDER BY contract_id, payment_stage, checked_at DESC, cap_check_id DESC;
COMMENT ON VIEW vw_latest_cap_check IS '계약·지급단계별 최신 1,200% 판정';

-- ============================================================================
-- 12. 데이터 품질 사전점검 예시
-- ============================================================================
-- 1) 활성 정책 중복
-- SELECT policy_code, COUNT(*)
--   FROM policy_version
--  WHERE status = 'ACTIVE'
--  GROUP BY policy_code
-- HAVING COUNT(*) > 1;
--
-- 2) 상품별 12차월 예상 해약환급률 누락
-- SELECT h.refund_rate_table_id
--   FROM refund_rate_table h
--   LEFT JOIN refund_rate_line l
--     ON l.refund_rate_table_id = h.refund_rate_table_id
--    AND l.contract_month_no = 12
--  WHERE l.refund_rate_line_id IS NULL;
--
-- 3) 확정 지급건 귀속 불균형
-- SELECT * FROM vw_transaction_attribution_balance WHERE status = 'CONFIRMED';
--
-- 4) 원장 불균형
-- SELECT * FROM vw_journal_imbalance;
--
-- 5) 활성 예상 스케줄 중복
-- SELECT contract_id, payment_stage, COUNT(*)
--   FROM schedule_header
--  WHERE active_yn AND schedule_purpose = 'OPERATIONAL'
--  GROUP BY contract_id, payment_stage
-- HAVING COUNT(*) > 1;

COMMIT;
