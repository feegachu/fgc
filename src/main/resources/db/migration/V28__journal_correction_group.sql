-- FUN-047 원장 정정그룹 및 역분개/재기표 무결성
-- 기존 V1 journal_header 스키마는 수정하지 않고 정정 메타데이터와 관계 제약만 확장한다.

SET search_path TO fgc, public;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM fgc.journal_header WHERE correction_group_key IS NOT NULL) THEN
    RAISE EXCEPTION
      'V28 cannot infer correction metadata for pre-existing correction_group_key values';
  END IF;
END;
$$;

CREATE TABLE journal_correction_group (
  correction_group_key       varchar(80) PRIMARY KEY,
  original_journal_header_id bigint       NOT NULL
      REFERENCES journal_header(journal_header_id),
  reason                     varchar(1000) NOT NULL,
  evidence_ref               varchar(500),
  created_by                 bigint REFERENCES app_user(user_id),
  created_at                 timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_journal_correction_original UNIQUE (original_journal_header_id),
  CONSTRAINT ck_journal_correction_group_key_not_blank
      CHECK (btrim(correction_group_key) <> ''),
  CONSTRAINT ck_journal_correction_reason_not_blank
      CHECK (btrim(reason) <> '')
);
COMMENT ON TABLE journal_correction_group IS
  '원분개를 변경하지 않고 역분개와 선택적 재기표를 연결하는 불변 정정그룹';

ALTER TABLE journal_header
  ADD CONSTRAINT fk_journal_header_correction_group
  FOREIGN KEY (correction_group_key)
  REFERENCES journal_correction_group(correction_group_key);

ALTER TABLE journal_header
  ADD CONSTRAINT ck_journal_reversal_group
  CHECK (journal_type <> 'REVERSAL' OR correction_group_key IS NOT NULL);

CREATE UNIQUE INDEX uq_journal_correction_group_reversal
  ON journal_header(correction_group_key)
  WHERE journal_type = 'REVERSAL';

CREATE UNIQUE INDEX uq_journal_correction_group_repost
  ON journal_header(correction_group_key)
  WHERE journal_type <> 'REVERSAL' AND correction_group_key IS NOT NULL;

CREATE OR REPLACE FUNCTION fgc.guard_journal_correction_group_write()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_status varchar(15);
  v_type varchar(35);
BEGIN
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'Journal correction group % is immutable', OLD.correction_group_key;
  END IF;

  SELECT status, journal_type
    INTO v_status, v_type
    FROM journal_header
   WHERE journal_header_id = NEW.original_journal_header_id
   FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Original journal % does not exist', NEW.original_journal_header_id;
  END IF;
  IF v_status <> 'POSTED' THEN
    RAISE EXCEPTION 'Only a POSTED journal may be corrected: % is %',
      NEW.original_journal_header_id, v_status;
  END IF;
  IF v_type = 'REVERSAL' THEN
    RAISE EXCEPTION 'A reversal journal cannot be corrected again: %',
      NEW.original_journal_header_id;
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_journal_correction_group_guard
BEFORE INSERT OR UPDATE OR DELETE ON journal_correction_group
FOR EACH ROW EXECUTE FUNCTION fgc.guard_journal_correction_group_write();

CREATE OR REPLACE FUNCTION fgc.guard_journal_correction_membership()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_original_id bigint;
  v_original_type varchar(35);
  v_original_source_type varchar(60);
  v_original_source_id varchar(100);
  v_original_revision integer;
  v_original_status varchar(15);
BEGIN
  IF NEW.correction_group_key IS NULL THEN
    RETURN NEW;
  END IF;

  SELECT g.original_journal_header_id,
         o.journal_type,
         o.source_entity_type,
         o.source_entity_id,
         o.revision_no,
         o.status
    INTO v_original_id,
         v_original_type,
         v_original_source_type,
         v_original_source_id,
         v_original_revision,
         v_original_status
    FROM journal_correction_group g
    JOIN journal_header o
      ON o.journal_header_id = g.original_journal_header_id
   WHERE g.correction_group_key = NEW.correction_group_key
   FOR SHARE OF g, o;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Unknown journal correction group %', NEW.correction_group_key;
  END IF;

  IF NEW.journal_type = 'REVERSAL' THEN
    IF NEW.reversal_of_id IS DISTINCT FROM v_original_id
       OR NEW.source_entity_type <> 'JOURNAL_HEADER'
       OR NEW.source_entity_id <> v_original_id::text
       OR NEW.revision_no <> 1 THEN
      RAISE EXCEPTION 'Reversal journal does not match correction group %',
        NEW.correction_group_key;
    END IF;
    IF v_original_status <> 'POSTED' THEN
      RAISE EXCEPTION 'Original journal % is no longer POSTED', v_original_id;
    END IF;
  ELSE
    IF NEW.reversal_of_id IS NOT NULL
       OR NEW.journal_type <> v_original_type
       OR NEW.source_entity_type <> v_original_source_type
       OR NEW.source_entity_id <> v_original_source_id
       OR NEW.revision_no <> v_original_revision + 1 THEN
      RAISE EXCEPTION 'Reposted journal does not match correction group %',
        NEW.correction_group_key;
    END IF;
    IF NOT EXISTS (
      SELECT 1
        FROM journal_header r
       WHERE r.correction_group_key = NEW.correction_group_key
         AND r.journal_type = 'REVERSAL'
         AND r.reversal_of_id = v_original_id
         AND r.status = 'POSTED'
    ) THEN
      RAISE EXCEPTION 'Reposted journal requires a POSTED reversal in correction group %',
        NEW.correction_group_key;
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_journal_correction_membership
BEFORE INSERT OR UPDATE ON journal_header
FOR EACH ROW EXECUTE FUNCTION fgc.guard_journal_correction_membership();
