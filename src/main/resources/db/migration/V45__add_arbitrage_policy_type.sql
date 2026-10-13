-- #475: 적용일을 코드 상수가 아닌 승인된 policy_parameter로 관리한다.
-- 기존 정책·판정 데이터는 수정하지 않는다. 운영 정책은 별도 승인·등록이 필요하다.
ALTER TABLE fgc.policy_version DROP CONSTRAINT ck_policy_version_type;
ALTER TABLE fgc.policy_version ADD CONSTRAINT ck_policy_version_type CHECK (policy_type IN (
    'CURRENT_COMMISSION', 'FOUR_YEAR_COMMISSION', 'SEVEN_YEAR_COMMISSION',
    'CAP_1200', 'ALLOCATION', 'REFUND_RATE', 'CLAWBACK',
    'RECONCILIATION_TOLERANCE', 'SCHEDULE_ELIGIBILITY', 'ARBITRAGE'
));
