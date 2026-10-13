-- #475: 데모 전용. V4의 시행 전 A1/A2 및 저장된 결과는 그대로 보존한다.
-- 운영에는 정책 담당자의 별도 등록·승인이 필요하다.
SET search_path TO fgc, public;
INSERT INTO policy_version(policy_code, policy_name, policy_type, source_class, version_no,
                           effective_from, status, regulation_refs, source_refs, created_by)
SELECT 'ARB-APPLICABILITY-2026', 'GA→FC 차익거래 적용 시작일', 'ARBITRAGE', 'REGULATORY', 1,
       DATE '2026-01-01', 'DRAFT', ARRAY['REG-12','REG-19'], ARRAY['SRC-022','SRC-025 Q11'],
       (SELECT user_id FROM app_user WHERE login_id='settle01');
INSERT INTO policy_parameter(policy_version_id, parameter_key, parameter_value, description)
SELECT policy_version_id, 'ARBITRAGE_EFFECTIVE_CONTRACT_DATE', '"2026-03-01"'::jsonb,
       'GA→FC 판정은 이 날짜 이상 체결된 신계약에 적용한다.'
FROM policy_version WHERE policy_code='ARB-APPLICABILITY-2026' AND status='DRAFT';
UPDATE policy_version SET status='APPROVED', approved_at=TIMESTAMPTZ '2026-10-13 09:00+09',
       approved_by=(SELECT user_id FROM app_user WHERE login_id='gaadmin'),
       approval_evidence_ref='데모 승인 — #475, 운영 정책 승인을 대체하지 않음'
WHERE policy_code='ARB-APPLICABILITY-2026' AND status='DRAFT';
UPDATE policy_version SET status='ACTIVE' WHERE policy_code='ARB-APPLICABILITY-2026' AND status='APPROVED';

CREATE TEMP TABLE arb475_map(old_no text, new_no text) ON COMMIT DROP;
INSERT INTO arb475_map VALUES
('FGC-FGL02-202601-0001','FGC-FGL02-202603-0475'),
('FGC-FGL01-202601-0001','FGC-FGL01-202603-0475');
INSERT INTO insurance_contract(insurer_id, product_offering_id, contract_no, contract_date,
 agent_id, organization_id, premium_per_cycle_amount, first_premium_amount,
 monthly_equivalent_first_premium, premium_conversion_rule_code, payment_cycle_code,
 payment_term_months, standard_surrender_deduction_amount, current_status, data_origin)
SELECT c.insurer_id,c.product_offering_id,m.new_no,DATE '2026-03-01',c.agent_id,c.organization_id,
 c.premium_per_cycle_amount,c.first_premium_amount,c.monthly_equivalent_first_premium,
 c.premium_conversion_rule_code,c.payment_cycle_code,c.payment_term_months,
 c.standard_surrender_deduction_amount,c.current_status,'SEED'
FROM insurance_contract c JOIN arb475_map m ON m.old_no=c.contract_no;
INSERT INTO contract_status_event(contract_id,event_seq,previous_status,new_status,effective_at,
 received_at,source_system,source_event_key,reason_code,data_origin)
SELECT c.contract_id,e.event_seq,e.previous_status,e.new_status,
 CASE WHEN e.event_seq=1 THEN TIMESTAMPTZ '2026-03-01 00:00+09' ELSE e.effective_at END,
 CASE WHEN e.event_seq=1 THEN TIMESTAMPTZ '2026-03-02 06:00+09' ELSE e.received_at END,
 e.source_system,'ARB475-'||e.source_event_key,e.reason_code,'SEED'
FROM arb475_map m JOIN insurance_contract old ON old.contract_no=m.old_no
JOIN contract_status_event e ON e.contract_id=old.contract_id
JOIN insurance_contract c ON c.contract_no=m.new_no;
INSERT INTO contract_financial_snapshot(contract_id,as_of_date,contract_month_no,
 cumulative_paid_premium,surrender_value,surrender_value_type)
SELECT c.contract_id,fs.as_of_date,6,600000,fs.surrender_value,fs.surrender_value_type
FROM arb475_map m JOIN insurance_contract old ON old.contract_no=m.old_no
JOIN contract_financial_snapshot fs ON fs.contract_id=old.contract_id
JOIN insurance_contract c ON c.contract_no=m.new_no
WHERE fs.as_of_date >= DATE '2026-08-01';
INSERT INTO commission_transaction(payment_stage,source_type,source_business_key,insurer_id,
 recipient_agent_id,commission_item_id,policy_version_id,settlement_month,due_date,
 amount,cashflow_type,note,installment_no)
SELECT ct.payment_stage,ct.source_type,'ARB475-'||ct.source_business_key,ct.insurer_id,
 ct.recipient_agent_id,ct.commission_item_id,ct.policy_version_id,DATE '2026-03-01',DATE '2026-03-25',
 ct.amount,ct.cashflow_type,'#475 시행 후 차익거래 시연',ct.installment_no
FROM arb475_map m JOIN insurance_contract old ON old.contract_no=m.old_no
JOIN transaction_attribution ta ON ta.contract_id=old.contract_id
JOIN commission_transaction ct ON ct.commission_transaction_id=ta.commission_transaction_id;
INSERT INTO transaction_attribution(commission_transaction_id,attribution_scope,contract_id,agent_id,
 source_agent_code,attribution_date,attribution_month,attributed_amount,cap_rule_item_id,
 inclusion_status_snapshot,attribution_method,allocation_policy_id,evidence_ref)
SELECT ct.commission_transaction_id,ta.attribution_scope,c.contract_id,ta.agent_id,ta.source_agent_code,
 DATE '2026-03-25',DATE '2026-03-01',ta.attributed_amount,ta.cap_rule_item_id,
 ta.inclusion_status_snapshot,ta.attribution_method,ta.allocation_policy_id,'#475 합성 시연 데이터'
FROM arb475_map m JOIN insurance_contract old ON old.contract_no=m.old_no
JOIN transaction_attribution ta ON ta.contract_id=old.contract_id
JOIN commission_transaction oldct ON oldct.commission_transaction_id=ta.commission_transaction_id
JOIN commission_transaction ct ON ct.source_type=oldct.source_type
 AND ct.source_business_key='ARB475-'||oldct.source_business_key
JOIN insurance_contract c ON c.contract_no=m.new_no;
UPDATE commission_transaction SET status='CONFIRMED',paid_on=due_date
WHERE source_business_key LIKE 'ARB475-%' AND status='DRAFT';
