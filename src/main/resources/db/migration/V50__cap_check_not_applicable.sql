-- #482 V50: 2026-10-13 사용자 확인으로 팀 예약 완료. 기존 공유 migration 수정 금지.
-- 이전 버전의 상태 제약 이름에 관계없이 이번 상태 확장을 적용한다.
ALTER TABLE fgc.cap_check DROP CONSTRAINT IF EXISTS cap_check_result_status_check;
ALTER TABLE fgc.cap_check DROP CONSTRAINT IF EXISTS ck_cap_check_result_status;
ALTER TABLE fgc.cap_check ADD CONSTRAINT cap_check_result_status_check
 CHECK (result_status IN ('NORMAL','WARNING','VIOLATION','REVIEW_REQUIRED','NOT_APPLICABLE'));
ALTER TABLE fgc.cap_check
 ALTER COLUMN cap_rule_set_id DROP NOT NULL,
 ALTER COLUMN refund_12m_amount DROP NOT NULL,
 ALTER COLUMN compliance_deduction_amount DROP NOT NULL,
 ALTER COLUMN limit_amount DROP NOT NULL,
 ALTER COLUMN included_amount DROP NOT NULL,
 ALTER COLUMN remaining_amount DROP NOT NULL;
ALTER TABLE fgc.cap_check ADD CONSTRAINT ck_cap_check_applicability_shape CHECK (
 (result_status = 'NOT_APPLICABLE'
  AND cap_rule_set_id IS NULL AND refund_rate_table_id IS NULL
  AND refund_12m_amount IS NULL AND compliance_deduction_amount IS NULL
  AND limit_amount IS NULL AND included_amount IS NULL AND remaining_amount IS NULL AND usage_pct IS NULL
  AND calculation_snapshot #>> '{applicability,status}' IS NOT NULL
  AND calculation_snapshot #>> '{applicability,status}' = 'NOT_APPLICABLE'
  AND NULLIF(calculation_snapshot #>> '{applicability,reasonCode}', '') IS NOT NULL
  AND NULLIF(calculation_snapshot #>> '{applicability,reason}', '') IS NOT NULL)
 OR
 -- 기존에 다른 제외 사유로 기록한 0원 스냅샷은 보존한다. #482 신규 결과는 위 null 형태만 사용한다.
 (result_status = 'NOT_APPLICABLE'
  AND cap_rule_set_id IS NULL AND refund_rate_table_id IS NULL
  AND refund_12m_amount IS NOT NULL AND compliance_deduction_amount IS NOT NULL
  AND limit_amount IS NOT NULL AND included_amount IS NOT NULL AND remaining_amount IS NOT NULL
  AND refund_12m_amount = 0 AND compliance_deduction_amount = 0
  AND limit_amount = 0 AND included_amount = 0 AND remaining_amount = 0 AND usage_pct IS NULL
  AND COALESCE(calculation_snapshot ->> 'notApplicable', '') = 'true'
  AND NULLIF(calculation_snapshot ->> 'notApplicableReason', '') IS NOT NULL)
 OR
 (result_status <> 'NOT_APPLICABLE'
  AND cap_rule_set_id IS NOT NULL AND refund_12m_amount IS NOT NULL
  AND compliance_deduction_amount IS NOT NULL AND limit_amount IS NOT NULL
  AND included_amount IS NOT NULL AND remaining_amount IS NOT NULL)
);
