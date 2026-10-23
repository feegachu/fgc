-- #637: 파기 의도 커밋 후 원본 접근을 차단하고 파일 삭제 실패는 재시도한다.
SET search_path = fgc, pg_temp;
ALTER TABLE intake_document
  ADD COLUMN source_purged_at timestamptz,
  ADD COLUMN source_deleted_at timestamptz,
  ADD CONSTRAINT ck_intake_source_deleted CHECK (source_deleted_at IS NULL OR source_purged_at IS NOT NULL),
  ADD CONSTRAINT ck_intake_source_not_extracting CHECK (source_purged_at IS NULL OR status <> 'EXTRACTING');
CREATE INDEX ix_intake_source_purge_pending ON intake_document (purge_after, intake_document_id)
  WHERE source_deleted_at IS NULL AND purge_after IS NOT NULL;
COMMENT ON COLUMN intake_document.source_purged_at IS '원본 접근 차단·파기 의도 커밋. 파일 삭제 실패 시 재시도하며 문서/행 이력은 보존';
COMMENT ON COLUMN intake_document.source_deleted_at IS '물리 원본 삭제(또는 이미 없음)를 확인한 시각';
