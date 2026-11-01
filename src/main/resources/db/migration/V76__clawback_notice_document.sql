-- #671 FUN-084 환수 통지서 PDF 발행 이력(IF-API-73·86·67A).
-- PDF 판은 V58 generated_document를 그대로 쓴다. 파일 원본은 #707 FileStorage(비공개 볼륨)에 두고 storage_key만 남긴다.
-- 발행·다운로드는 clawback_case의 상태·notice_date·confirmed_amount를 바꾸지 않는다(통지 등록은 이번 범위가 아니다).
SET search_path TO fgc, public;

-- 같은 멱등키 재요청은 최초 발행판을 돌려준다(V70 clawback_calculation_request와 같은 구조).
CREATE TABLE clawback_notice_document_request (
    idempotency_key       varchar(160) PRIMARY KEY,
    requested_by          bigint       NOT NULL REFERENCES app_user(user_id),
    clawback_case_id      bigint       NOT NULL REFERENCES clawback_case(clawback_case_id),
    request_hash          varchar(64)  NOT NULL,
    generated_document_id bigint       NOT NULL REFERENCES generated_document(generated_document_id),
    requested_at          timestamptz  NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_clawback_notice_document_request_doc ON clawback_notice_document_request(generated_document_id);
CREATE TRIGGER trg_clawback_notice_document_request_append_only
BEFORE UPDATE OR DELETE ON clawback_notice_document_request
FOR EACH ROW EXECUTE FUNCTION reject_update_delete();
CREATE TRIGGER trg_clawback_notice_document_request_kind
BEFORE INSERT ON clawback_notice_document_request
FOR EACH ROW EXECUTE FUNCTION fgc.check_generated_document_kind('CLAWBACK_NOTICE');

-- IF-API-86 발행 이력은 case별 최신 판부터 읽는다.
CREATE INDEX ix_generated_document_source
    ON generated_document(source_entity_type, source_entity_id, document_kind, version_no DESC);

-- 통지서 판은 실제 계산판에서만 만든다. 문서번호는 case마다 하나이며 판은 1부터 빈틈없이 증가한다.
CREATE OR REPLACE FUNCTION fgc.check_clawback_notice_document()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
BEGIN
  IF NEW.document_kind <> 'CLAWBACK_NOTICE' THEN
    RETURN NEW;
  END IF;
  IF NEW.source_entity_type <> 'CLAWBACK_CASE' OR NEW.source_version_no IS NULL THEN
    RAISE EXCEPTION 'CLAWBACK_NOTICE % must reference a CLAWBACK_CASE calculation version', NEW.document_no;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM fgc.clawback_calculation c
                  WHERE c.clawback_case_id = NEW.source_entity_id AND c.version_no = NEW.source_version_no) THEN
    RAISE EXCEPTION 'CLAWBACK_NOTICE % references missing calculation % of case %',
      NEW.document_no, NEW.source_version_no, NEW.source_entity_id;
  END IF;
  IF EXISTS (SELECT 1 FROM fgc.generated_document d
              WHERE d.document_kind = 'CLAWBACK_NOTICE'
                AND ((d.source_entity_id = NEW.source_entity_id AND d.document_no <> NEW.document_no)
                  OR (d.document_no = NEW.document_no AND d.source_entity_id <> NEW.source_entity_id))) THEN
    RAISE EXCEPTION 'CLAWBACK_NOTICE document_no % must stay fixed to case %', NEW.document_no, NEW.source_entity_id;
  END IF;
  IF NEW.version_no <> 1 + COALESCE((SELECT max(d.version_no) FROM fgc.generated_document d
                                      WHERE d.document_no = NEW.document_no), 0) THEN
    RAISE EXCEPTION 'CLAWBACK_NOTICE % version_no % must be the next version', NEW.document_no, NEW.version_no;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_generated_document_clawback_notice
BEFORE INSERT ON generated_document
FOR EACH ROW EXECUTE FUNCTION fgc.check_clawback_notice_document();

COMMENT ON TABLE clawback_notice_document_request IS
    'IF-API-73 멱등키·사용자·요청 해시와 발행판. 발행과 같은 트랜잭션에 저장(FUN-084)';
