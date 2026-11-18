-- FUN-089 마감 알림 문자(IF-EXT-05). 본인이 등록한 수신 설정과, 발송 대기열을 겸하는 발송 이력.

-- 수신 동의한 사용자만 행이 있다. 동의를 철회하면 행을 지워 번호를 남기지 않는다.
CREATE TABLE fgc.user_notification_setting (
  user_id       bigint       PRIMARY KEY REFERENCES fgc.app_user(user_id),
  mobile_phone  varchar(11)  NOT NULL CHECK (mobile_phone ~ '^01[016789][0-9]{7,8}$'),
  consented_at  timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at    timestamptz  NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE fgc.user_notification_setting IS '마감 알림 문자 수신 설정 — 본인이 등록한 휴대폰 번호와 동의 시각. 철회하면 행을 삭제한다(FUN-089)';
CREATE TRIGGER trg_user_notification_setting_updated_at BEFORE UPDATE ON fgc.user_notification_setting
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();

-- 수신번호는 담지 않는다. 보낼 때 수신 설정에서 읽으므로 철회 뒤에는 이력에도 번호가 남지 않는다.
CREATE TABLE fgc.closing_notification (
  closing_notification_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_type          varchar(20)  NOT NULL CHECK (event_type IN ('FINALIZE_READY','RUN_FINALIZED')),
  validation_run_id   bigint       NOT NULL REFERENCES fgc.validation_run(validation_run_id),
  recipient_user_id   bigint       REFERENCES fgc.app_user(user_id),
  message_text        varchar(200) NOT NULL,
  status              varchar(20)  NOT NULL DEFAULT 'PENDING'
                                   CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED','NO_RECIPIENT')),
  attempt_count       integer      NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  next_attempt_at     timestamptz  NOT NULL DEFAULT clock_timestamp(),
  provider_message_id varchar(64),
  error_code          varchar(60),
  created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  sent_at             timestamptz,
  updated_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_closing_notification_recipient CHECK ((status = 'NO_RECIPIENT') = (recipient_user_id IS NULL)),
  CONSTRAINT ck_closing_notification_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);
COMMENT ON TABLE fgc.closing_notification IS '마감 알림 발송 대기열 겸 이력 — 사건(확정 조건 충족·확정)과 수신자마다 1행. 수신 대상이 없으면 NO_RECIPIENT 1행(FUN-089)';
-- 같은 사건을 같은 사람에게 두 번 만들지 않는다. 수신 대상 없음 기록도 사건마다 1행이다.
CREATE UNIQUE INDEX uq_closing_notification_recipient
  ON fgc.closing_notification (event_type, validation_run_id, COALESCE(recipient_user_id, 0));
CREATE INDEX ix_closing_notification_pending ON fgc.closing_notification (next_attempt_at) WHERE status = 'PENDING';
CREATE TRIGGER trg_closing_notification_updated_at BEFORE UPDATE ON fgc.closing_notification
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
