-- FUN-090 지급 이체 대사(IF-EXT-06). 은행 계좌 연결, 대사 실행과 결과, 모의 은행의 계좌 거래.

-- 연결한 수수료 지급 계좌. 한 건만 둔다. 계좌번호 원문과 토큰은 저장하지 않는다(은행은 가린 번호만 준다).
CREATE TABLE fgc.bank_account_link (
  bank_account_link_id smallint     PRIMARY KEY DEFAULT 1 CHECK (bank_account_link_id = 1),
  fintech_use_num      varchar(24)  NOT NULL,
  bank_name            varchar(40)  NOT NULL,
  account_num_masked   varchar(40)  NOT NULL,
  account_holder_name  varchar(60)  NOT NULL,
  linked_by            bigint       NOT NULL REFERENCES fgc.app_user(user_id),
  linked_at            timestamptz  NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE fgc.bank_account_link IS '오픈뱅킹으로 연결한 수수료 지급 계좌 1건 — 핀테크이용번호와 가린 계좌번호만 둔다. 토큰은 서버 메모리에만 있다(FUN-090)';

-- 대사를 돌릴 때마다 1행. 그 달의 최신 행이 화면에 보이는 결과다.
CREATE TABLE fgc.bank_reconciliation_run (
  bank_reconciliation_run_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  settlement_month     date         NOT NULL CHECK (fgc.is_first_day_of_month(settlement_month)),
  validation_run_id    bigint       NOT NULL REFERENCES fgc.validation_run(validation_run_id),
  inquiry_from         date         NOT NULL,
  inquiry_to           date         NOT NULL,
  bank_name            varchar(40)  NOT NULL,
  account_num_masked   varchar(40)  NOT NULL,
  withdrawal_count     integer      NOT NULL CHECK (withdrawal_count >= 0),
  created_by           bigint       NOT NULL REFERENCES fgc.app_user(user_id),
  created_at           timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_bank_reconciliation_run_period CHECK (inquiry_to >= inquiry_from)
);
COMMENT ON TABLE fgc.bank_reconciliation_run IS '지급 이체 대사 실행 — 확정된 정산월의 확정 지급 순액과 은행 출금을 맞춘 1회(FUN-090)';
CREATE INDEX ix_bank_reconciliation_run_month
  ON fgc.bank_reconciliation_run (settlement_month, bank_reconciliation_run_id DESC);

CREATE TABLE fgc.bank_reconciliation_item (
  bank_reconciliation_item_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  bank_reconciliation_run_id  bigint NOT NULL REFERENCES fgc.bank_reconciliation_run(bank_reconciliation_run_id),
  result             varchar(20)   NOT NULL
                                   CHECK (result IN ('MATCHED','AMOUNT_MISMATCH','NOT_TRANSFERRED','UNKNOWN_WITHDRAWAL')),
  agent_id           bigint        REFERENCES fgc.agent(agent_id),
  reference_code     varchar(20),
  expected_amount    numeric(15,2) NOT NULL DEFAULT 0 CHECK (expected_amount >= 0),
  bank_amount        numeric(15,2) NOT NULL DEFAULT 0 CHECK (bank_amount >= 0),
  bank_tran_date     date,
  print_content      varchar(40),
  -- 확인 안 된 출금은 누구 앞인지 모르는 출금이다. 나머지는 설계사가 있다.
  CONSTRAINT ck_bank_reconciliation_item_agent CHECK ((result = 'UNKNOWN_WITHDRAWAL') = (agent_id IS NULL))
);
COMMENT ON TABLE fgc.bank_reconciliation_item IS '지급 이체 대사 결과 — 설계사별 확정 지급 순액과 은행 출금 합계의 비교, 또는 참조코드 없는 출금 1건(FUN-090)';
CREATE INDEX ix_bank_reconciliation_item_run ON fgc.bank_reconciliation_item (bank_reconciliation_run_id);

-- 모의 은행(저장소에 들어 있는 대역)의 계좌 출금. 실제 은행에 연결하면 쓰지 않는다.
CREATE TABLE fgc.mock_bank_transaction (
  mock_bank_transaction_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  settlement_month   date          NOT NULL CHECK (fgc.is_first_day_of_month(settlement_month)),
  tran_date          date          NOT NULL,
  tran_time          varchar(6)    NOT NULL CHECK (tran_time ~ '^[0-9]{6}$'),
  print_content      varchar(20)   NOT NULL,
  tran_amt           numeric(15,0) NOT NULL CHECK (tran_amt > 0),
  -- 같은 달의 이체를 두 번 만들지 않는다.
  CONSTRAINT uq_mock_bank_transaction UNIQUE (settlement_month, print_content)
);
COMMENT ON TABLE fgc.mock_bank_transaction IS '모의 은행의 수수료 지급 계좌 출금 — 확정된 달의 확정 지급대로 이체한 것처럼 만든 시연용 자료(FUN-090)';
CREATE INDEX ix_mock_bank_transaction_date
  ON fgc.mock_bank_transaction (tran_date, tran_time, mock_bank_transaction_id);
