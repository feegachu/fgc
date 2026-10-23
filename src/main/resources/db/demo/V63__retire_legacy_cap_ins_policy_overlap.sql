-- #635 / SRC-065 / 시드명세 §40: CAP 유산 정책 폐기·활성 범위 중복 정리.
--
-- 문제: V3의 REG-CAP-INS-2026-V1(원수사→GA, 2021-01-01~ 무기한, 준법경영비 3% 공제)을 V6_1이 REG-CAP-INS-2021-V2(~2026-12-31, 0%)와
--       REG-CAP-INS-2027-V1(2027-01-01~, 3%)로 대체했지만, 유산 정책을 폐기하지 않았다. V46·V52는 ACTIVE CAP 정책을 새 버전으로
--       복제하면서 유산 정책도 계속 ACTIVE로 이어갔다. 그 결과 같은 범위·유효기간의 활성 룰셋이 겹치고, CapRuleQueryRepository는
--       contract_date_from·cap_rule_set_id 정렬로 이를 우회했을 뿐이다.
-- 처리: 유산 정책(REG-CAP-INS-2026-V1)의 ACTIVE 버전만 RETIRED로 전환한다. ACTIVE→RETIRED는 정책 수명주기 가드가 허용하는 전이이고
--       자식 행(cap_rule_set·cap_rule_item)·과거 cap_check 참조는 건드리지 않아 보존된다. 적용된 마이그레이션이나 잠긴 자식 행은 고치지 않는다.
--       V6_1의 두 룰셋이 2021-01-01~무기한을 빈틈·겹침 없이 채우므로 새 룰셋은 발행하지 않는다.
-- 안전장치: 대체 룰셋이 범위를 채우지 못하거나 정리 뒤에도 활성 중복이 남으면 Flyway 트랜잭션 전체를 중단한다.
SET search_path TO fgc, public;

DO $$
DECLARE
    v_legacy_stage_gap integer;
BEGIN
    -- 대체 룰셋(2021-V2: ~2026-12-31, 2027-V1: 2027-01-01~)이 ACTIVE이고 유산 범위를 이어서 채우는지 확인한다.
    IF NOT EXISTS (
        SELECT 1
          FROM cap_rule_set a
          JOIN policy_version pa ON pa.policy_version_id = a.policy_version_id
          JOIN cap_rule_set b ON b.payment_stage = a.payment_stage
          JOIN policy_version pb ON pb.policy_version_id = b.policy_version_id
         WHERE pa.policy_code = 'REG-CAP-INS-2021-V2' AND pa.status = 'ACTIVE'
           AND pb.policy_code = 'REG-CAP-INS-2027-V1' AND pb.status = 'ACTIVE'
           AND a.payment_stage = 'INSURER_TO_GA'
           AND a.contract_date_from = DATE '2021-01-01'
           AND a.contract_date_to + 1 = b.contract_date_from
           AND b.contract_date_to IS NULL
           AND a.insurer_id IS NULL AND a.product_group_code IS NULL AND a.channel_code IS NULL
           AND b.insurer_id IS NULL AND b.product_group_code IS NULL AND b.channel_code IS NULL
    ) THEN
        RAISE EXCEPTION '#635: 유산 CAP 정책을 대체할 REG-CAP-INS-2021-V2/2027-V1 활성 룰셋이 범위를 채우지 못한다';
    END IF;

    UPDATE policy_version
       SET status = 'RETIRED'
     WHERE policy_code = 'REG-CAP-INS-2026-V1'
       AND policy_type = 'CAP_1200'
       AND status = 'ACTIVE';

    -- 정리 후: 같은 단계·범위에서 계약일 구간이 겹치는 활성 룰셋 쌍이 없어야 한다.
    SELECT COUNT(*) INTO v_legacy_stage_gap
      FROM cap_rule_set a
      JOIN policy_version pa ON pa.policy_version_id = a.policy_version_id AND pa.status = 'ACTIVE'
      JOIN cap_rule_set b ON b.cap_rule_set_id > a.cap_rule_set_id
                         AND b.payment_stage = a.payment_stage
                         AND b.insurer_id IS NOT DISTINCT FROM a.insurer_id
                         AND b.product_group_code IS NOT DISTINCT FROM a.product_group_code
                         AND b.channel_code IS NOT DISTINCT FROM a.channel_code
                         AND a.contract_date_from <= COALESCE(b.contract_date_to, DATE 'infinity')
                         AND b.contract_date_from <= COALESCE(a.contract_date_to, DATE 'infinity')
      JOIN policy_version pb ON pb.policy_version_id = b.policy_version_id AND pb.status = 'ACTIVE';
    IF v_legacy_stage_gap > 0 THEN
        RAISE EXCEPTION '#635: 정리 후에도 동일 범위·유효기간의 활성 CAP 룰셋 중복 %건이 남아 있다', v_legacy_stage_gap;
    END IF;
END;
$$;
