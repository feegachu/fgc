-- #722: 환수 규칙도 기존 정책 자식과 동일하게 승인·활성·종료 버전의 변경을 DB에서 막는다.
-- 기존 데이터·적용된 Flyway 파일은 수정하지 않는다. 양쪽 부모를 ID 순으로 잠가 재귀속도 보호한다.

CREATE OR REPLACE FUNCTION fgc.guard_policy_child_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
  c_insert CONSTANT text := 'INSERT';
  c_delete CONSTANT text := 'DELETE';
  v_old_policy_id bigint;
  v_new_policy_id bigint;
  v_policy_id bigint;
  v_status varchar(20);
BEGIN
  CASE
    WHEN TG_TABLE_NAME IN ('commission_rule','cap_rule_set','allocation_policy',
                           'refund_rate_table','policy_parameter','fee_regime_ruleset','clawback_rule') THEN
      IF TG_OP <> c_insert THEN v_old_policy_id := OLD.policy_version_id; END IF;
      IF TG_OP <> c_delete THEN v_new_policy_id := NEW.policy_version_id; END IF;
    WHEN TG_TABLE_NAME = 'cap_rule_item' THEN
      IF TG_OP <> c_insert THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM fgc.cap_rule_set WHERE cap_rule_set_id = OLD.cap_rule_set_id
          FOR SHARE;
      END IF;
      IF TG_OP <> c_delete THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM fgc.cap_rule_set WHERE cap_rule_set_id = NEW.cap_rule_set_id
          FOR SHARE;
      END IF;
    WHEN TG_TABLE_NAME = 'refund_rate_line' THEN
      IF TG_OP <> c_insert THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM fgc.refund_rate_table WHERE refund_rate_table_id = OLD.refund_rate_table_id
          FOR SHARE;
      END IF;
      IF TG_OP <> c_delete THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM fgc.refund_rate_table WHERE refund_rate_table_id = NEW.refund_rate_table_id
          FOR SHARE;
      END IF;
    -- 분급 룰셋 자식: 한도는 룰셋 헤더를 거쳐 정책버전을 찾는다
    WHEN TG_TABLE_NAME IN ('fee_regime_ruleset_limit') THEN
      IF TG_OP <> c_insert THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = OLD.fee_regime_ruleset_id
          FOR SHARE;
      END IF;
      IF TG_OP <> c_delete THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM fgc.fee_regime_ruleset WHERE fee_regime_ruleset_id = NEW.fee_regime_ruleset_id
          FOR SHARE;
      END IF;
    ELSE
      RAISE EXCEPTION 'Unsupported policy child table: %', TG_TABLE_NAME;
  END CASE;

  FOR v_policy_id, v_status IN
    SELECT policy_version_id, status
      FROM fgc.policy_version
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

  IF TG_OP = c_delete THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_clawback_rule_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON fgc.clawback_rule
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();
