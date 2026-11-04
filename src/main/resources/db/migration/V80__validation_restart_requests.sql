CREATE TABLE fgc.validation_run_retry (
 retry_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 validation_run_id bigint NOT NULL REFERENCES fgc.validation_run,
 previous_batch_execution_id bigint NOT NULL,
 batch_execution_id bigint,
 requested_by bigint NOT NULL REFERENCES fgc.app_user,
 idempotency_key varchar(100) NOT NULL,
 request_hash varchar(64) NOT NULL,
 reason varchar(500) NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('ACCEPTED','RUNNING','SUCCEEDED','FAILED')),
 requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 started_at timestamptz, finished_at timestamptz, error_code varchar(100),
 UNIQUE(validation_run_id, requested_by, idempotency_key)
);
CREATE UNIQUE INDEX uq_validation_retry_active ON fgc.validation_run_retry(validation_run_id)
 WHERE state IN ('ACCEPTED','RUNNING');
