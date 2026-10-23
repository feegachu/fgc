-- 2차 신규 테이블 16개 + statement_batch 판 관리 (이슈 #632, 요구사항명세서 v2.2.2-r19)
-- 근거: FUN-066·068·069·078·079·084·SER-013 외 (FUN-072·081~083·086), 화면정의서 §6-A, 인터페이스정의서 §4-2.
-- 원칙: 계약자 개인정보 컬럼을 두지 않는다(SRC-027 D-05). AI 출력은 초안 테이블에만 쓴다(COR-007).
-- 엔티티·서비스·API는 각 기능 이슈가 만든다. pgvector는 2차에서 쓰지 않는다(OI-023).
SET search_path = fgc, pg_temp;

-- FUN-068: AI 문서 입력으로 적재한 원천 명세 묶음을 정규화 적재분과 구분한다.
ALTER TABLE statement_batch DROP CONSTRAINT statement_batch_data_origin_check;
ALTER TABLE statement_batch ADD CONSTRAINT statement_batch_data_origin_check
  CHECK (data_origin IN ('NORMALIZED_DB','SEED','AI_INTAKE'));

-- FUN-068(r18): 보험사 정정 명세는 전체 대체본만. 같은 보험사·월·종류·외부명세번호의 다음 판으로 넣고 이전 판은 SUPERSEDED.
-- 대사 쿼리는 status IN (AVAILABLE, VALIDATED, RECONCILED)만 읽으므로 SUPERSEDED 판은 새 실행에서 빠진다.
ALTER TABLE statement_batch
  ADD COLUMN version_no integer NOT NULL DEFAULT 1 CHECK (version_no > 0),
  ADD COLUMN supersedes_statement_batch_id bigint REFERENCES statement_batch(statement_batch_id);
ALTER TABLE statement_batch DROP CONSTRAINT statement_batch_status_check;
ALTER TABLE statement_batch ADD CONSTRAINT statement_batch_status_check
  CHECK (status IN ('AVAILABLE','VALIDATED','RECONCILED','REJECTED','SUPERSEDED'));
ALTER TABLE statement_batch ADD CONSTRAINT ck_statement_batch_supersedes
  CHECK ((version_no = 1) = (supersedes_statement_batch_id IS NULL));
DROP INDEX uq_statement_batch;
CREATE UNIQUE INDEX uq_statement_batch
  ON statement_batch (insurer_id, settlement_month, statement_type, COALESCE(external_statement_no, ''), version_no);
CREATE UNIQUE INDEX uq_statement_batch_successor
  ON statement_batch (supersedes_statement_batch_id) WHERE supersedes_statement_batch_id IS NOT NULL;

-- ---------------------------------------------------------------- AI 문서 입력
CREATE TABLE intake_document (
  intake_document_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_type       varchar(30)  NOT NULL DEFAULT 'UNCLASSIFIED'
                                   CHECK (document_type IN ('UNCLASSIFIED','REFUND_RATE_TABLE','INSURER_STATEMENT',
                                          'COMMISSION_RATE_TABLE','POLICY_CERTIFICATE','ASSOCIATION_GRADE','UNSUPPORTED')),
  status              varchar(20)  NOT NULL DEFAULT 'UPLOADED'
                                   CHECK (status IN ('UPLOADED','EXTRACTING','REVIEW','CONFIRMED','REJECTED','FAILED')),
  original_file_name  varchar(255) NOT NULL,
  storage_key         varchar(300) NOT NULL,
  media_type          varchar(100) NOT NULL,
  file_size_bytes     bigint       NOT NULL CHECK (file_size_bytes > 0),
  content_hash        varchar(64)  NOT NULL,
  insurer_id          bigint REFERENCES insurer(insurer_id),
  base_month          date CHECK (base_month IS NULL OR fgc.is_first_day_of_month(base_month)),
  statement_batch_id  bigint REFERENCES statement_batch(statement_batch_id),
  policy_version_id   bigint REFERENCES policy_version(policy_version_id),
  extraction_model    varchar(100),
  extracted_at        timestamptz,
  failure_reason      varchar(1000),
  reject_reason       varchar(1000),
  purge_after         date,
  correction_mode     varchar(20)  NOT NULL DEFAULT 'NONE' CHECK (correction_mode IN ('NONE','FULL_REPLACEMENT')),
  supersedes_statement_batch_id bigint REFERENCES statement_batch(statement_batch_id),
  uploaded_by         bigint       NOT NULL REFERENCES app_user(user_id),
  uploaded_at         timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_intake_document_hash UNIQUE (content_hash),
  CONSTRAINT ck_intake_document_reject_reason CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL),
  CONSTRAINT ck_intake_document_failure_reason CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL),
  CONSTRAINT ck_intake_document_correction CHECK ((correction_mode = 'FULL_REPLACEMENT') = (supersedes_statement_batch_id IS NOT NULL))
);
COMMENT ON TABLE intake_document IS 'AI 문서함 업로드 문서와 문서 단위 처리상태(2차 제안, FUN-066, OI-021 예외 범위)';

CREATE TABLE intake_draft_row (
  intake_draft_row_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  intake_document_id  bigint       NOT NULL REFERENCES intake_document(intake_document_id),
  row_no              integer      NOT NULL CHECK (row_no > 0),
  source_location     jsonb        NOT NULL,
  original_fields     jsonb        NOT NULL,
  normalized_fields   jsonb,
  edited_fields       jsonb,
  match_result        jsonb,
  row_status          varchar(20)  NOT NULL DEFAULT 'REVIEW_REQUIRED'
                                   CHECK (row_status IN ('READY','REVIEW_REQUIRED','ERROR','CONFIRMED','EXCLUDED')),
  result_entity_type  varchar(40),
  result_entity_id    bigint,
  edited_by           bigint REFERENCES app_user(user_id),
  edited_at           timestamptz,
  confirmed_by        bigint REFERENCES app_user(user_id),
  confirmed_at        timestamptz,
  exclude_reason      varchar(500),
  failure_stage       varchar(20) CHECK (failure_stage IS NULL OR failure_stage IN ('MATCH','ATTRIBUTION','LOAD','SUPERSEDE')),
  failure_reason      varchar(1000),
  CONSTRAINT uq_intake_draft_row UNIQUE (intake_document_id, row_no),
  CONSTRAINT ck_intake_draft_row_excluded CHECK ((row_status = 'EXCLUDED') = (exclude_reason IS NOT NULL)),
  CONSTRAINT ck_intake_draft_row_failure CHECK ((failure_stage IS NULL) = (failure_reason IS NULL)),
  CONSTRAINT ck_intake_draft_row_confirmed CHECK (
    (row_status = 'CONFIRMED') = (confirmed_at IS NOT NULL AND result_entity_type IS NOT NULL AND result_entity_id IS NOT NULL))
);
COMMENT ON TABLE intake_draft_row IS '추출 초안 행 — 원본 위치·원본값·정규화값·수정값·행 상태. 확정 행은 불변(2차 제안, FUN-067~069)';

CREATE TABLE intake_confirm_request (
  idempotency_key     varchar(100) PRIMARY KEY,
  intake_document_id  bigint       NOT NULL REFERENCES intake_document(intake_document_id),
  request_hash        varchar(64)  NOT NULL,
  response_body       jsonb        NOT NULL,
  requested_by        bigint       NOT NULL REFERENCES app_user(user_id),
  requested_at        timestamptz  NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE intake_confirm_request IS '일괄 확정 멱등키 — 같은 키·같은 본문은 기존 결과, 다른 본문은 거절(2차 제안, FUN-069)';

-- ---------------------------------------------------------------- 생성 문서(PDF)
CREATE TABLE generated_document (
  generated_document_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_no         varchar(60)  NOT NULL,
  version_no          integer      NOT NULL DEFAULT 1 CHECK (version_no > 0),
  document_kind       varchar(30)  NOT NULL CHECK (document_kind IN ('COMPARE_CONFIRMATION','CLAWBACK_NOTICE')),
  source_entity_type  varchar(40)  NOT NULL,
  source_entity_id    bigint       NOT NULL,
  storage_key         varchar(300) NOT NULL,
  content_hash        varchar(64)  NOT NULL,
  source_version_no   integer CHECK (source_version_no IS NULL OR source_version_no > 0),
  created_by          bigint       NOT NULL REFERENCES app_user(user_id),
  created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_generated_document_version UNIQUE (document_no, version_no)
);
COMMENT ON TABLE generated_document IS '생성 PDF의 판별 이력(문서번호·판·해시). 생성은 통지·수령 완료가 아니다(2차 제안, FUN-079·084)';

CREATE TABLE clawback_notice_delivery (
  clawback_notice_delivery_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  clawback_case_id    bigint       NOT NULL REFERENCES clawback_case(clawback_case_id),
  generated_document_id bigint     NOT NULL REFERENCES generated_document(generated_document_id),
  recipient_agent_id  bigint       NOT NULL REFERENCES agent(agent_id),
  notice_date         date         NOT NULL,
  notice_kind         varchar(20)  NOT NULL CHECK (notice_kind IN ('FIRST','ADDITIONAL')),
  delivery_method     varchar(30)  NOT NULL CHECK (delivery_method IN ('IN_PERSON','POST','EMAIL','MESSENGER','OTHER')),
  evidence_ref        varchar(500) NOT NULL,
  registered_by       bigint       NOT NULL REFERENCES app_user(user_id),
  registered_at       timestamptz  NOT NULL DEFAULT clock_timestamp()
);
CREATE UNIQUE INDEX uq_clawback_notice_first ON clawback_notice_delivery (clawback_case_id) WHERE notice_kind = 'FIRST';
COMMENT ON TABLE clawback_notice_delivery IS '환수 통지 등록 이력 — 첫 통지(CALCULATED→NOTIFIED, 최초 통지일)와 추가 통지(이력만) 구분. 재발행이 기존 이의 상태를 되돌리지 않음(2차 제안, FUN-084)';

-- ---------------------------------------------------------------- 대형 GA 비교설명
CREATE TABLE compare_product_group (
  compare_product_group_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  group_code          varchar(40)  NOT NULL,
  insurance_sector    varchar(10)  NOT NULL CHECK (insurance_sector IN ('LIFE','NONLIFE')),
  major_class         varchar(20)  NOT NULL CHECK (major_class IN ('PROTECTION','SAVINGS')),
  minor_class_name    varchar(100) NOT NULL,
  CONSTRAINT uq_compare_product_group_code UNIQUE (group_code)
);
COMMENT ON TABLE compare_product_group IS '동종·유사 상품군 분류(2차 FAQ Q17 분류 기준표, 2차 제안, FUN-078)';

CREATE TABLE commission_grade (
  commission_grade_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  product_offering_id bigint       NOT NULL REFERENCES product_offering(product_offering_id),
  compare_product_group_id bigint  NOT NULL REFERENCES compare_product_group(compare_product_group_id),
  underwriting_type   varchar(20)  NOT NULL CHECK (underwriting_type IN ('SIMPLIFIED','STANDARD')),
  refund_type         varchar(20)  NOT NULL CHECK (refund_type IN ('STANDARD','PURE_PROTECTION','MATURITY_REFUND','NO_REFUND','LOW_REFUND')),
  grade_code          varchar(20)  NOT NULL CHECK (grade_code IN ('VERY_HIGH','HIGH','NORMAL','LOW','VERY_LOW')),
  rank_no             integer CHECK (rank_no IS NULL OR rank_no > 0),
  rank_set_key        varchar(100),
  data_as_of          date         NOT NULL,
  self_calc_grade_code varchar(20) CHECK (self_calc_grade_code IS NULL OR self_calc_grade_code IN ('VERY_HIGH','HIGH','NORMAL','LOW','VERY_LOW')),
  source_intake_document_id bigint REFERENCES intake_document(intake_document_id),
  created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_commission_grade_rank_set CHECK (rank_no IS NULL OR rank_set_key IS NOT NULL)
);
-- r19(R18-04): 같은 상품·기준일이라도 비교 집합(ABC·ABCD)마다 순위 행이 따로 있다. 순위 없는 등급 행(rank_set_key NULL)은 상품·기준일당 1행.
CREATE UNIQUE INDEX uq_commission_grade ON commission_grade (product_offering_id, data_as_of, COALESCE(rank_set_key, ''));
COMMENT ON TABLE commission_grade IS '협회 제공 수수료 등급·순위(정본, OI-025). 순위는 비교 집합·기준일과 함께 보존 — 같은 상품·기준일에 여러 비교 집합 공존(2차 제안, FUN-078)';

CREATE TABLE compare_explanation (
  compare_explanation_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  version_no          integer      NOT NULL DEFAULT 1 CHECK (version_no > 0),
  grade_data_as_of    date         NOT NULL,
  agent_id            bigint       NOT NULL REFERENCES agent(agent_id),
  compare_product_group_id bigint  NOT NULL REFERENCES compare_product_group(compare_product_group_id),
  recommended_offering_id bigint   NOT NULL REFERENCES product_offering(product_offering_id),
  compared_offering_ids jsonb      NOT NULL,
  customer_selected_offering_ids jsonb,
  rank_set_key        varchar(100),
  rank_as_of          date,
  selection_criteria  varchar(2000) NOT NULL,
  recommendation_reason varchar(2000) NOT NULL,
  fee_level_note      varchar(1000) NOT NULL,
  industry_exception_ref varchar(500),
  industry_exception_confirmed_by bigint REFERENCES app_user(user_id),
  generated_document_id bigint REFERENCES generated_document(generated_document_id),
  created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_compare_explanation_rank CHECK ((rank_set_key IS NULL) = (rank_as_of IS NULL)),
  CONSTRAINT ck_compare_explanation_exception CHECK ((industry_exception_ref IS NULL) = (industry_exception_confirmed_by IS NULL))
);
COMMENT ON COLUMN compare_explanation.rank_set_key IS '초안 저장 시 서버가 정한 순위 비교 집합(commission_grade.rank_set_key). 맞는 자료가 없으면 rank_as_of와 함께 NULL (IF-API-66A)';
COMMENT ON COLUMN compare_explanation.rank_as_of IS '순위 기준일(commission_grade.data_as_of). 확인서 생성 시 비교 상품 집합과 이 값을 저장된 판으로 다시 검사한다 (IF-API-67)';
COMMENT ON TABLE compare_explanation IS '설계사 비교설명 초안·기록 — 판 번호로 저장하고 확인서는 같은 판으로만 생성. 비교 상품·계약자 선택 상품·선정기준·추천사유·수수료 수준. generated_document_id 는 최근 생성 PDF, 수령은 compare_confirmation_receipt(PDF 판마다). 고객 개인정보 없음(2차 제안, FUN-079, D-05)';

CREATE TABLE compare_confirmation_receipt (
  generated_document_id bigint     PRIMARY KEY REFERENCES generated_document(generated_document_id),
  received_on         date         NOT NULL,
  receipt_confirmed_by bigint      NOT NULL REFERENCES app_user(user_id),
  paper_storage_ref   varchar(500) NOT NULL,
  registered_at       timestamptz  NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE compare_confirmation_receipt IS '비교·설명 확인서 수령 기록 — 생성 PDF 판마다 1건. 새 판을 만들어도 이전 판 수령은 남는다. 고객 이름·서명 이미지 없음(2차 제안, FUN-079 r19, D-05)';

-- ---------------------------------------------------------------- 공지·문의·자료마당
CREATE TABLE notice (
  notice_id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title               varchar(200) NOT NULL,
  body                varchar(4000) NOT NULL,
  target_role_codes   jsonb        NOT NULL,
  post_from           date         NOT NULL,
  post_to             date,
  pinned_yn           boolean      NOT NULL DEFAULT false,
  closed_yn           boolean      NOT NULL DEFAULT false,
  created_by          bigint       NOT NULL REFERENCES app_user(user_id),
  created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  updated_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_notice_period CHECK (post_to IS NULL OR post_to >= post_from)
);
COMMENT ON TABLE notice IS '공지사항 — 삭제 대신 게시 종료(2차 제안, FUN-082)';

CREATE TABLE inquiry (
  inquiry_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  inquiry_kind        varchar(20)  NOT NULL CHECK (inquiry_kind IN ('INQUIRY','OBJECTION')),
  target_type         varchar(20)  NOT NULL CHECK (target_type IN ('CONTRACT','TRANSACTION','CLAWBACK')),
  target_id           bigint       NOT NULL,
  agent_id            bigint       NOT NULL REFERENCES agent(agent_id),
  content             varchar(4000) NOT NULL,
  status              varchar(20)  NOT NULL DEFAULT 'RECEIVED'
                                   CHECK (status IN ('RECEIVED','IN_REVIEW','ANSWERED','CORRECTED','CLOSED')),
  assignee_user_id    bigint REFERENCES app_user(user_id),
  due_date            date         NOT NULL,
  created_by          bigint       NOT NULL REFERENCES app_user(user_id),
  created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_inquiry_objection_target CHECK (inquiry_kind <> 'OBJECTION' OR target_type = 'CLAWBACK')
);
COMMENT ON TABLE inquiry IS '설계사 수수료 문의·환수 이의신청. 금액 정정은 기존 경로로만(2차 제안, FUN-083)';

CREATE TABLE inquiry_action (
  inquiry_action_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  inquiry_id          bigint       NOT NULL REFERENCES inquiry(inquiry_id),
  action_seq          integer      NOT NULL CHECK (action_seq > 0),
  action_type         varchar(20)  NOT NULL CHECK (action_type IN ('START_REVIEW','ANSWER','LINK_CORRECTION','CLOSE')),
  message             varchar(4000),
  correction_entity_type varchar(40),
  correction_entity_id bigint,
  acted_by            bigint       NOT NULL REFERENCES app_user(user_id),
  acted_at            timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_inquiry_action_seq UNIQUE (inquiry_id, action_seq),
  CONSTRAINT ck_inquiry_action_correction CHECK (
    (action_type = 'LINK_CORRECTION') = (correction_entity_type IS NOT NULL AND correction_entity_id IS NOT NULL))
);
COMMENT ON TABLE inquiry_action IS '문의 처리 이력(append-only, 2차 제안, FUN-083)';

CREATE TABLE library_item (
  library_item_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  item_kind           varchar(20)  NOT NULL CHECK (item_kind IN ('OFFICIAL','NEWS','INTERNAL')),
  source_name         varchar(100) NOT NULL,
  title               varchar(500) NOT NULL,
  url                 varchar(1000),
  src_id              varchar(20),
  published_at        timestamptz,
  collected_at        timestamptz  NOT NULL DEFAULT clock_timestamp(),
  topic               varchar(100),
  relevance_note      varchar(500),
  CONSTRAINT uq_library_item_url UNIQUE (url),
  CONSTRAINT ck_library_item_ref CHECK ((item_kind = 'INTERNAL' AND src_id IS NOT NULL) OR (item_kind <> 'INTERNAL' AND url IS NOT NULL))
);
COMMENT ON TABLE library_item IS '자료마당 — 공식 RSS·업계뉴스는 제목·출처·게시일·링크만, 본문 미저장(2차 제안, FUN-081, SRC-055)';

CREATE TABLE knowledge_chunk (
  knowledge_chunk_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  src_id              varchar(20)  NOT NULL,
  document_version    varchar(60)  NOT NULL,
  chunk_key           varchar(100) NOT NULL,
  source_grade        varchar(30)  NOT NULL CHECK (source_grade IN ('STATUTE','OFFICIAL_FAQ','ASSOCIATION','COMPANY_AGREEMENT','ARTICLE','PROJECT_ASSUMPTION')),
  effective_from      date,
  effective_to        date,
  source_location     varchar(300) NOT NULL,
  content             varchar(8000) NOT NULL,
  created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_knowledge_chunk UNIQUE (src_id, document_version, chunk_key),
  CONSTRAINT ck_knowledge_chunk_period CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from)
);
COMMENT ON TABLE knowledge_chunk IS '규정·FAQ 근거 청크 — 청크마다 출처(SRC)·문서 버전 1개. 임베딩 컬럼은 pgvector 도입 시 추가(2차 제안, FUN-072, ADR-04)';

-- ---------------------------------------------------------------- 설계사·AI 운영
CREATE TABLE agent_contract_check (
  agent_contract_check_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  agent_id            bigint       NOT NULL REFERENCES agent(agent_id),
  contract_id         bigint       NOT NULL REFERENCES insurance_contract(contract_id),
  check_result        varchar(20)  NOT NULL CHECK (check_result IN ('CLOSED','FOLLOW_UP')),
  next_check_date     date,
  memo                varchar(1000),
  checked_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT ck_agent_contract_check_follow_up CHECK ((check_result = 'FOLLOW_UP') = (next_check_date IS NOT NULL))
);
COMMENT ON TABLE agent_contract_check IS '설계사 홈 계약 확인 기록 — 종결과 후속 확인(다음 확인일) 구분(2차 제안, FUN-086)';

CREATE TABLE ai_call_log (
  ai_call_log_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  purpose             varchar(30)  NOT NULL CHECK (purpose IN ('INTAKE_CLASSIFY','INTAKE_EXTRACT','PICO_EXPLAIN','RAG_ANSWER','REASON_DRAFT','RECEIPT_SUMMARY','LIBRARY_RELEVANCE')),
  model_name          varchar(100) NOT NULL,
  input_tokens        integer CHECK (input_tokens IS NULL OR input_tokens >= 0),
  output_tokens       integer CHECK (output_tokens IS NULL OR output_tokens >= 0),
  latency_ms          integer CHECK (latency_ms IS NULL OR latency_ms >= 0),
  call_status         varchar(20)  NOT NULL CHECK (call_status IN ('SUCCESS','ERROR','TIMEOUT','FALLBACK_RULE')),
  error_code          varchar(40),
  related_entity_type varchar(40),
  related_entity_id   bigint,
  requested_by        bigint REFERENCES app_user(user_id),
  requested_at        timestamptz  NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE ai_call_log IS '외부 LLM 호출 기록 — 원문·개인정보 미저장(2차 제안, SER-013·PER-008)';

-- ---------------------------------------------------------------- DB 가드(이력 불변·updated_at)
CREATE OR REPLACE FUNCTION fgc.reject_confirmed_draft_row_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.row_status = 'CONFIRMED' THEN
    RAISE EXCEPTION 'intake_draft_row % is confirmed; UPDATE/DELETE is not allowed', OLD.intake_draft_row_id;
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_intake_draft_row_confirmed_immutable
BEFORE UPDATE OR DELETE ON intake_draft_row
FOR EACH ROW EXECUTE FUNCTION fgc.reject_confirmed_draft_row_change();
CREATE TRIGGER trg_inquiry_action_append_only
BEFORE UPDATE OR DELETE ON inquiry_action
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();
CREATE TRIGGER trg_clawback_notice_delivery_append_only
BEFORE UPDATE OR DELETE ON clawback_notice_delivery
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();
CREATE TRIGGER trg_compare_confirmation_receipt_append_only
BEFORE UPDATE OR DELETE ON compare_confirmation_receipt
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

CREATE TRIGGER trg_intake_document_updated_at BEFORE UPDATE ON intake_document
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
CREATE TRIGGER trg_compare_explanation_updated_at BEFORE UPDATE ON compare_explanation
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();
CREATE TRIGGER trg_notice_updated_at BEFORE UPDATE ON notice
FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();

-- ---------------------------------------------------------------- 판 승계 키·버전 검사, 생성 문서 불변
CREATE OR REPLACE FUNCTION fgc.check_statement_batch_successor()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  prev fgc.statement_batch%ROWTYPE;
BEGIN
  IF NEW.supersedes_statement_batch_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT * INTO prev FROM fgc.statement_batch WHERE statement_batch_id = NEW.supersedes_statement_batch_id;
  IF NOT FOUND
     OR prev.insurer_id <> NEW.insurer_id
     OR prev.settlement_month <> NEW.settlement_month
     OR prev.statement_type <> NEW.statement_type
     OR COALESCE(prev.external_statement_no, '') <> COALESCE(NEW.external_statement_no, '')
     OR NEW.version_no <> prev.version_no + 1 THEN
    RAISE EXCEPTION 'statement_batch successor must share the business key and be version_no + 1 of the superseded batch';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_statement_batch_successor
BEFORE INSERT ON statement_batch
FOR EACH ROW EXECUTE FUNCTION fgc.check_statement_batch_successor();

CREATE TRIGGER trg_generated_document_append_only
BEFORE UPDATE OR DELETE ON generated_document
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();
