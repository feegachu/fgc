-- 기존 보험료 환산 규칙 코드를 월납환산보험료 기준의 신규 코드로 변경한다.
UPDATE fgc.insurance_contract
SET premium_conversion_rule_code = CASE premium_conversion_rule_code
    WHEN 'QUARTERLY_DIV_3' THEN 'MONTHLY_TO_QUARTERLY_X3'
    WHEN 'SEMI_ANNUAL_DIV_6' THEN 'MONTHLY_TO_SEMI_ANNUAL_X6'
    WHEN 'ANNUAL_DIV_12' THEN 'MONTHLY_TO_ANNUAL_X12'
    ELSE premium_conversion_rule_code
END
WHERE premium_conversion_rule_code IN (
    'QUARTERLY_DIV_3',
    'SEMI_ANNUAL_DIV_6',
    'ANNUAL_DIV_12'
);
