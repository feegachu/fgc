-- V58 보완 (이슈 #632 후속, PR #684 리뷰·병합 후 검토 반영)
-- 근거: FUN-068·운영정책서 제55조 5(전체 대체본 한 트랜잭션 전환, 가상 GA 정책 — OI-021), IF-API-66A(초안 저장마다 판 +1),
--       FUN-079·084(확인서·통지서 PDF 증적), SRC-050(DB 가드 함수 search_path 고정), SRC-027 D-04(V58은 고치지 않고 덧붙임).
-- 이미 적용된 V58은 고치지 않는다. 바꾸는 함수는 CREATE OR REPLACE 로 교체한다.
SET search_path = fgc, pg_temp;

-- ---------------------------------------------------------------- 1. 명세 묶음 식별값 불변
-- V58은 후속본이 "있을 때만" 이전 판의 업무키 변경을 막았다. 후속본 INSERT와 이전 판 UPDATE가 동시에 오면
-- 서로의 미커밋 행을 못 봐 둘 다 커밋됐다(READ COMMITTED 재현). 식별값을 아예 바꾸지 못하게 해 격리수준과 무관하게 막는다.
-- 정정은 다음 판(전체 대체본)으로만 한다(FUN-068). 판 사슬에 들지 않은 묶음을 잘못 적재했으면 REJECTED 로 두고 올바른 업무키로 새로 넣는다.
-- 후속본이 잘못됐으면 REJECTED 가 아니라 그다음 판으로 바로잡는다(3번 (a) 참고).
CREATE OR REPLACE FUNCTION fgc.check_statement_batch_successor()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  prev fgc.statement_batch%ROWTYPE;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF (OLD.insurer_id, OLD.settlement_month, OLD.statement_type, COALESCE(OLD.external_statement_no, ''),
        OLD.version_no, OLD.supersedes_statement_batch_id)
       IS DISTINCT FROM
       (NEW.insurer_id, NEW.settlement_month, NEW.statement_type, COALESCE(NEW.external_statement_no, ''),
        NEW.version_no, NEW.supersedes_statement_batch_id) THEN
      RAISE EXCEPTION 'statement_batch %: business key, version_no and supersedes_statement_batch_id are immutable; load a full replacement as the next version',
        OLD.statement_batch_id;
    END IF;
    RETURN NEW;
  END IF;
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

-- ---------------------------------------------------------------- 2. 대체된 판 보존
-- SUPERSEDED 복귀 금지(V58)에 삭제 금지를 더한다. 후속본을 지우면 이전 판만 SUPERSEDED 로 남아 명세가 대사에서 사라진다.
CREATE OR REPLACE FUNCTION fgc.reject_superseded_statement_batch_revival()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status = 'SUPERSEDED' OR OLD.supersedes_statement_batch_id IS NOT NULL THEN
      RAISE EXCEPTION 'statement_batch % is part of a version chain; DELETE is not allowed', OLD.statement_batch_id;
    END IF;
    RETURN OLD;
  END IF;
  IF OLD.status = 'SUPERSEDED' AND NEW.status <> 'SUPERSEDED' THEN
    RAISE EXCEPTION 'statement_batch % is SUPERSEDED; status cannot be changed back', OLD.statement_batch_id;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_statement_batch_version_chain_no_delete
BEFORE DELETE ON statement_batch
FOR EACH ROW EXECUTE FUNCTION fgc.reject_superseded_statement_batch_revival();

-- ---------------------------------------------------------------- 3. 전체 대체 전환은 커밋 시점에 완결돼야 한다
-- 제55조 5: 새 판 적재 → 이전 판 SUPERSEDED → 이전 원천 CANCELLED 를 한 트랜잭션으로 한다.
-- 커밋 직전에 다음을 확인한다. 하나라도 어기면 트랜잭션 전체가 롤백된다.
--   (a) 후속본의 이전 판은 SUPERSEDED 다 — 두 판이 함께 유효하면 대사가 실제액을 두 번 센다.
--       후속본은 REJECTED 가 될 수 없다 — 이전 판은 되살릴 수 없으므로 후속본을 거절하면 명세가 대사에서 사라진다.
--       전환을 커밋한 뒤 후속본을 거절하는 규칙은 문서에 없다 — 업무 규칙 아님, 가상 GA 정책으로 정함(다음 판으로 정정).
--   (b) SUPERSEDED 판에는 후속본이 있다 — 없으면 명세가 대사에서 사라진다. (a)로 후속본은 늘 유효한 상태다.
--   (c) SUPERSEDED 판 아래 CONFIRMED 원천이 없다 — 분개 원천 조회(JournalPostingSourceQueryRepository)는
--       묶음 상태를 보지 않고 원천의 CONFIRMED 만 보므로, 남아 있으면 이전 판이 다시 기표된다.
-- 이전 POSTED 실제 분개의 역분개(제40조)는 분개 테이블 쪽 일이라 여기서 검사하지 않는다(#643).
CREATE OR REPLACE FUNCTION fgc.check_statement_batch_supersession_complete()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  cur fgc.statement_batch%ROWTYPE;
BEGIN
  -- 지연 트리거는 이벤트 당시 행을 받으므로 커밋 시점의 현재 행을 다시 읽는다.
  SELECT * INTO cur FROM fgc.statement_batch WHERE statement_batch_id = NEW.statement_batch_id;
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;
  IF cur.supersedes_statement_batch_id IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM fgc.statement_batch
        WHERE statement_batch_id = cur.supersedes_statement_batch_id AND status = 'SUPERSEDED') THEN
    RAISE EXCEPTION 'statement_batch % supersedes %, which must be SUPERSEDED in the same transaction',
      cur.statement_batch_id, cur.supersedes_statement_batch_id;
  END IF;
  IF cur.supersedes_statement_batch_id IS NOT NULL AND cur.status = 'REJECTED' THEN
    RAISE EXCEPTION 'statement_batch % replaces % and cannot be REJECTED; correct it with the next version',
      cur.statement_batch_id, cur.supersedes_statement_batch_id;
  END IF;
  IF cur.status = 'SUPERSEDED' THEN
    IF NOT EXISTS (SELECT 1 FROM fgc.statement_batch WHERE supersedes_statement_batch_id = cur.statement_batch_id) THEN
      RAISE EXCEPTION 'statement_batch % is SUPERSEDED without a successor', cur.statement_batch_id;
    END IF;
    IF EXISTS (SELECT 1 FROM fgc.commission_transaction
                WHERE statement_batch_id = cur.statement_batch_id AND status = 'CONFIRMED') THEN
      RAISE EXCEPTION 'statement_batch % is SUPERSEDED but still has CONFIRMED source rows; cancel them in the same transaction',
        cur.statement_batch_id;
    END IF;
  END IF;
  RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_statement_batch_supersession_complete
AFTER INSERT OR UPDATE OF status ON statement_batch
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION fgc.check_statement_batch_supersession_complete();

-- (c)를 전환 뒤에도 지킨다: SUPERSEDED 판 아래 원천은 새로 확정할 수 없다.
-- FOR SHARE 로 묶음 행을 잠가, 확정과 전환(묶음 상태 UPDATE)이 동시에 오면 한쪽이 상대 커밋을 기다리게 한다.
-- 그래야 전환 쪽 커밋 검사 (c)가 동시에 확정된 원천을 놓치지 않는다. 경합하면 교착 감지로 한쪽만 실패할 수 있다(정합성은 유지).
CREATE OR REPLACE FUNCTION fgc.reject_source_confirm_on_superseded_batch()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  v_status varchar(20);
BEGIN
  IF NEW.statement_batch_id IS NULL OR NEW.status <> 'CONFIRMED'
     OR (TG_OP = 'UPDATE' AND OLD.status = 'CONFIRMED'
         AND OLD.statement_batch_id IS NOT DISTINCT FROM NEW.statement_batch_id) THEN
    RETURN NEW;
  END IF;
  SELECT status INTO v_status FROM fgc.statement_batch WHERE statement_batch_id = NEW.statement_batch_id FOR SHARE;
  IF v_status = 'SUPERSEDED' THEN
    RAISE EXCEPTION 'statement_batch % is SUPERSEDED; its source rows cannot be confirmed', NEW.statement_batch_id;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_commission_transaction_superseded_batch
BEFORE INSERT OR UPDATE OF status, statement_batch_id ON commission_transaction
FOR EACH ROW EXECUTE FUNCTION fgc.reject_source_confirm_on_superseded_batch();

-- ---------------------------------------------------------------- 4. 이력 행이 가리키는 PDF 종류
-- 통지·수령 이력은 append-only 라 잘못 넣으면 고칠 수 없다. 넣을 때 PDF 종류를 확인한다(FUN-084 통지서, FUN-079 확인서).
-- 같은 환수 건·같은 비교설명의 PDF인지는 source_entity_type 이름이 정해지면 서비스에서 검사한다(#671·#657).
CREATE OR REPLACE FUNCTION fgc.check_generated_document_kind()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
BEGIN
  IF NEW.generated_document_id IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM fgc.generated_document
        WHERE generated_document_id = NEW.generated_document_id AND document_kind = TG_ARGV[0]) THEN
    RAISE EXCEPTION '%.generated_document_id % must reference a % document',
      TG_TABLE_NAME, NEW.generated_document_id, TG_ARGV[0];
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_clawback_notice_delivery_document_kind
BEFORE INSERT ON clawback_notice_delivery
FOR EACH ROW EXECUTE FUNCTION fgc.check_generated_document_kind('CLAWBACK_NOTICE');
CREATE TRIGGER trg_compare_confirmation_receipt_document_kind
BEFORE INSERT ON compare_confirmation_receipt
FOR EACH ROW EXECUTE FUNCTION fgc.check_generated_document_kind('COMPARE_CONFIRMATION');
CREATE TRIGGER trg_compare_explanation_document_kind
BEFORE INSERT OR UPDATE OF generated_document_id ON compare_explanation
FOR EACH ROW EXECUTE FUNCTION fgc.check_generated_document_kind('COMPARE_CONFIRMATION');

-- ---------------------------------------------------------------- 5. 비교설명 판 번호
-- IF-API-66A: 저장마다 판 +1. 내용이 바뀌었는데 판이 그대로면, 그 판으로 만든 확인서 PDF(source_version_no)와
-- 저장된 초안이 달라져도 구별할 수 없다(FUN-079 "PDF 값이 저장된 초안과 다르면 허용하지 않는다").
-- 판은 그대로이거나 +1 만 허용하고, 내용이 바뀌면 반드시 +1 이어야 한다. 최근 PDF 연결·수정시각만 바뀌면 판을 올리지 않아도 된다.
CREATE OR REPLACE FUNCTION fgc.check_compare_explanation_version()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
BEGIN
  IF NEW.version_no NOT IN (OLD.version_no, OLD.version_no + 1) THEN
    RAISE EXCEPTION 'compare_explanation % version_no must stay % or become %',
      OLD.compare_explanation_id, OLD.version_no, OLD.version_no + 1;
  END IF;
  IF NEW.version_no = OLD.version_no
     AND ROW(NEW.grade_data_as_of, NEW.agent_id, NEW.compare_product_group_id, NEW.recommended_offering_id,
             NEW.compared_offering_ids, NEW.customer_selected_offering_ids, NEW.rank_set_key, NEW.rank_as_of,
             NEW.selection_criteria, NEW.recommendation_reason, NEW.fee_level_note,
             NEW.industry_exception_ref, NEW.industry_exception_confirmed_by)
         IS DISTINCT FROM
         ROW(OLD.grade_data_as_of, OLD.agent_id, OLD.compare_product_group_id, OLD.recommended_offering_id,
             OLD.compared_offering_ids, OLD.customer_selected_offering_ids, OLD.rank_set_key, OLD.rank_as_of,
             OLD.selection_criteria, OLD.recommendation_reason, OLD.fee_level_note,
             OLD.industry_exception_ref, OLD.industry_exception_confirmed_by) THEN
    RAISE EXCEPTION 'compare_explanation % content changed without incrementing version_no', OLD.compare_explanation_id;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_compare_explanation_version
BEFORE UPDATE ON compare_explanation
FOR EACH ROW EXECUTE FUNCTION fgc.check_compare_explanation_version();

-- ---------------------------------------------------------------- 6. search_path 고정 (SRC-050, V6·V55 규칙)
-- V6 루프 이후 추가된 비IMMUTABLE 함수 중 고정이 빠진 것. 위에서 교체·신설한 함수는 정의에 SET 을 넣었다.
ALTER FUNCTION fgc.reject_confirmed_draft_row_change() SET search_path = fgc, pg_temp;
ALTER FUNCTION fgc.guard_commission_transaction_installment_write() SET search_path = fgc, pg_temp;
ALTER FUNCTION fgc.validate_financial_snapshot_predecessor() SET search_path = fgc, pg_temp;
