-- FGC-FUN-044-03 FINALIZED 결과 불변 잠금
-- V1의 결과 불변 함수는 reconciliation_run 자체의 FINALIZED만 확인했다.
-- 상위 validation_run 확정도 동일한 결과 잠금 경계이므로 부모 상태 확인을 추가한다.

CREATE OR REPLACE FUNCTION fgc.guard_finalized_reconciliation_result()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  v_old_run_id bigint;
  v_new_run_id bigint;
  v_run_id bigint;
  v_status varchar(20);
  v_validation_run_id bigint;
  v_validation_status varchar(20);
BEGIN
  -- UPDATE가 부모를 바꾸는 경우 OLD/NEW 양쪽 실행을 모두 검사해야 우회 이동을 막을 수 있다.
  IF TG_TABLE_NAME = 'reconciliation_result' THEN
    IF TG_OP <> 'INSERT' THEN v_old_run_id := OLD.reconciliation_run_id; END IF;
    IF TG_OP <> 'DELETE' THEN v_new_run_id := NEW.reconciliation_run_id; END IF;
  ELSIF TG_TABLE_NAME = 'reconciliation_match' THEN
    IF TG_OP <> 'INSERT' THEN
      SELECT reconciliation_run_id INTO v_old_run_id
        FROM fgc.reconciliation_result
       WHERE reconciliation_result_id = OLD.reconciliation_result_id
       FOR SHARE;
    END IF;
    IF TG_OP <> 'DELETE' THEN
      SELECT reconciliation_run_id INTO v_new_run_id
        FROM fgc.reconciliation_result
       WHERE reconciliation_result_id = NEW.reconciliation_result_id
       FOR SHARE;
    END IF;
  ELSE
    RAISE EXCEPTION 'Unsupported reconciliation result table: %', TG_TABLE_NAME;
  END IF;

  -- ID 오름차순 잠금은 서로 다른 결과를 교차 이동하는 동시 UPDATE의 교착 가능성을 낮춘다.
  FOR v_run_id, v_status, v_validation_run_id IN
    SELECT reconciliation_run_id, status, validation_run_id
      FROM fgc.reconciliation_run
     WHERE reconciliation_run_id = ANY (
       array_remove(ARRAY[v_old_run_id, v_new_run_id]::bigint[], NULL)
     )
     ORDER BY reconciliation_run_id
     FOR SHARE
  LOOP
    IF v_status = 'FINALIZED' THEN
      RAISE EXCEPTION 'Results of finalized reconciliation run % are immutable', v_run_id;
    END IF;
    IF v_validation_run_id IS NOT NULL THEN
      SELECT status INTO v_validation_status
        FROM fgc.validation_run
       WHERE validation_run_id = v_validation_run_id
       FOR SHARE;
      IF v_validation_status = 'FINALIZED' THEN
        RAISE EXCEPTION 'Reconciliation results of finalized validation run % are immutable',
          v_validation_run_id;
      END IF;
    END IF;
  END LOOP;

  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

-- 대사 실행 메타데이터도 검증 결과 스냅샷의 일부다. 기존 자체 FINALIZED 잠금을 유지하면서
-- INSERT/이동/수정/삭제가 가리키는 OLD/NEW validation_run을 모두 확인한다.
CREATE OR REPLACE FUNCTION fgc.guard_reconciliation_run_finalized()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  v_old_validation_run_id bigint;
  v_new_validation_run_id bigint;
  v_validation_run_id bigint;
  v_validation_status varchar(20);
BEGIN
  IF TG_OP = 'DELETE' AND OLD.status = 'FINALIZED' THEN
    RAISE EXCEPTION 'Finalized reconciliation run % cannot be deleted', OLD.reconciliation_run_id;
  ELSIF TG_OP = 'UPDATE' AND OLD.status = 'FINALIZED' THEN
    RAISE EXCEPTION 'Finalized reconciliation run % is immutable', OLD.reconciliation_run_id;
  END IF;

  IF TG_OP <> 'INSERT' THEN v_old_validation_run_id := OLD.validation_run_id; END IF;
  IF TG_OP <> 'DELETE' THEN v_new_validation_run_id := NEW.validation_run_id; END IF;

  FOR v_validation_run_id, v_validation_status IN
    SELECT validation_run_id, status
      FROM fgc.validation_run
     WHERE validation_run_id = ANY (
       array_remove(ARRAY[v_old_validation_run_id, v_new_validation_run_id]::bigint[], NULL)
     )
     ORDER BY validation_run_id
     FOR SHARE
  LOOP
    IF v_validation_status = 'FINALIZED' THEN
      RAISE EXCEPTION 'Reconciliation run of finalized validation run % is immutable',
        v_validation_run_id;
    END IF;
  END LOOP;

  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_reconciliation_run_finalized ON fgc.reconciliation_run;
CREATE TRIGGER trg_reconciliation_run_finalized
BEFORE INSERT OR UPDATE OR DELETE ON fgc.reconciliation_run
FOR EACH ROW EXECUTE FUNCTION fgc.guard_reconciliation_run_finalized();
