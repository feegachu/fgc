import { test, expect, login, sql, password } from './fixtures'
import { request, type Page } from '@playwright/test'

const CONTRACT_NO = 'FGC-FGL01-202703-0001'
const BOUNDARY_CONTRACT_NO = 'FGC-FGL02-202611-0001'

/**
 * 후순위062 인수조건. 데모 시드에는 4년 지급 정책과 계약체결비용 기준이 없다(운영정책서 제46조: 값을 시드하지 않음).
 * 이 시험에서만 G1 계약을 4년 판매버전으로 바꾸고 지급 정책과 예정 회차를 만든다. 기준율 3000은 시드명세 §34의 가정값이다.
 */
function fourYearFixture(withBasis: boolean) {
  const code = withBasis ? 'GA-E2E-651-B' : 'GA-E2E-651-A'
  const parameters = withBasis
    ? `INSERT INTO fgc.policy_parameter(policy_version_id, parameter_key, parameter_value, description)
       SELECT policy_version_id, v.k, v.val::jsonb, v.d
         FROM fgc.policy_version
        CROSS JOIN (VALUES ('ACQUISITION_COST_BASIS', '"MONTHLY_EQUIVALENT_FIRST_PREMIUM"', '기준금액'),
                           ('ACQUISITION_COST_RATE_PCT', '3000', '가상 GA 정책(PROJECT_ASSUMPTION), 시드명세 34절 예시')) AS v(k, val, d)
        WHERE policy_code = '${code}';`
    : ''
  const id = sql(`
    UPDATE fgc.insurance_contract SET monthly_equivalent_first_premium = 100000 WHERE contract_no = '${CONTRACT_NO}';
    UPDATE fgc.product_offering
       SET fee_regime_code = 'FOUR_YEAR_2027', sales_start_date = DATE '2027-01-01',
           basic_document_version = 'BD-2027-01', basic_document_date = DATE '2026-12-01'
     WHERE product_offering_id = (SELECT product_offering_id FROM fgc.insurance_contract WHERE contract_no = '${CONTRACT_NO}');
    UPDATE fgc.policy_version SET status = 'RETIRED' WHERE policy_code LIKE 'GA-E2E-651-%' AND status = 'ACTIVE';
    INSERT INTO fgc.policy_version(policy_code, policy_name, policy_type, source_class, version_no,
        effective_from, status, fee_regime_code)
    VALUES ('${code}', '4년 지급 시험 정책', 'FOUR_YEAR_COMMISSION', 'GA_POLICY', 1, DATE '2026-01-01', 'DRAFT', 'FOUR_YEAR_2027');
    INSERT INTO fgc.fee_regime_ruleset(policy_version_id, fee_regime_code, sales_start_date_from,
        sales_start_date_to, basic_document_version, max_installments)
    SELECT policy_version_id, 'FOUR_YEAR_2027', DATE '2027-01-01', DATE '2028-12-31', 'BD-2027-01', 48
      FROM fgc.policy_version WHERE policy_code = '${code}';
    INSERT INTO fgc.commission_rule(policy_version_id, payment_stage, commission_item_id, fee_component_type,
        installment_from, installment_to, calculation_type, basis_code, rate_pct)
    SELECT p.policy_version_id, 'GA_TO_FC', i.commission_item_id, v.component, 1, 48, 'RATE', 'MONTHLY_EQUIVALENT_FIRST_PREMIUM', 1
      FROM fgc.policy_version p
     CROSS JOIN (VALUES ('BASE_COMMISSION', 'UPFRONT'), ('MAINTENANCE_COMMISSION', 'MAINTENANCE'), ('COMMON_COST', 'COMMON_COST')) AS v(item, component)
      JOIN fgc.commission_item i ON i.item_code = v.item
     WHERE p.policy_code = '${code}';
    ${parameters}
    UPDATE fgc.policy_version SET status = 'APPROVED', approved_at = clock_timestamp(),
           created_by = (SELECT min(user_id) FROM fgc.app_user), approved_by = (SELECT max(user_id) FROM fgc.app_user)
     WHERE policy_code = '${code}';
    UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_code = '${code}';
    UPDATE fgc.schedule_header SET active_yn = false
     WHERE scenario_code LIKE 'PHASE2_FOUR_YEAR_2027_%'
       AND contract_id = (SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = '${CONTRACT_NO}');
    INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no,
        schedule_purpose, scenario_code, schedule_regime, status, active_yn)
    SELECT c.contract_id, 'GA_TO_FC', p.policy_version_id, 1, 'SIMULATION', 'PHASE2_FOUR_YEAR_2027_' || p.policy_version_id,
           'FOUR_YEAR_2027', 'PLANNED', true
      FROM fgc.insurance_contract c, fgc.policy_version p
     WHERE c.contract_no = '${CONTRACT_NO}' AND p.policy_code = '${code}';
    SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = '${CONTRACT_NO}';
  `)
  return id.split('\n').at(-1)!.trim()
}

function scheduleLine(installment: number, item: string, amount: number) {
  sql(`
    INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
        commission_item_id, beneficiary_agent_id, basis_code, basis_amount, calculation_type, fixed_amount,
        expected_amount, source_commission_rule_id)
    SELECT h.schedule_header_id,
           (SELECT COALESCE(max(line_no), 0) + 1 FROM fgc.schedule_line WHERE schedule_header_id = h.schedule_header_id),
           ${installment}, ${installment}, DATE '2027-03-02', i.commission_item_id, c.agent_id,
           'FIXED_AMOUNT', ${amount}, 'FIXED', ${amount}, ${amount}, r.commission_rule_id
      FROM fgc.schedule_header h
      JOIN fgc.insurance_contract c ON c.contract_id = h.contract_id
      JOIN fgc.commission_item i ON i.item_code = '${item}'
      JOIN fgc.commission_rule r ON r.policy_version_id = h.policy_version_id AND r.commission_item_id = i.commission_item_id
     WHERE c.contract_no = '${CONTRACT_NO}' AND h.scenario_code LIKE 'PHASE2_FOUR_YEAR_2027_%' AND h.active_yn;
  `)
}

async function requestCheck(page: Page, contractNo: string) {
  const contractId = Number(sql(`SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = '${contractNo}'`))
  const origin = new URL(page.url()).origin
  const client = await request.newContext({ baseURL: origin, extraHTTPHeaders: { Origin: origin, 'X-FGC-Client': 'web' } })
  try {
    const auth = await client.post('/api/v1/auth/login', { data: { loginId: 'admin', password } })
    expect(auth.ok(), await auth.text()).toBe(true)
    const token = (await auth.json()).data.accessToken
    const result = await client.post('/api/v1/acquisition-cost-checks', {
      data: { contractId, paymentStage: 'GA_TO_FC' },
      headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() },
    })
    expect(result.status(), await result.text()).toBe(201)
    return (await result.json()).data
  } finally {
    await client.dispose()
  }
}

test('숨긴 계약체결비용 화면은 접근을 막고 기존 서버 판정은 유지한다', async ({ page }) => {
  const browserCalls: string[] = []
  page.on('request', r => {
    if (r.url().includes('/api/v1/acquisition-cost-checks')) browserCalls.push(r.url())
  })
  await login(page, 'settle01', '/acquisition-cost-checks')
  await expect(page.getByRole('heading', { name: '권한 없음' })).toBeVisible()
  await expect(page.getByRole('link', { name: '계약체결비용 한도', exact: true })).toHaveCount(0)
  expect(browserCalls).toEqual([])

  // 화면만 숨긴다. 실제 HTTP/DB의 기존 정책 판정 회귀 검사는 유지한다.
  const current = await requestCheck(page, 'FGC-FGL01-202607-0001')
  expect(current.resultStatus).toBe('NOT_APPLICABLE')
  expect(current.upfront).toBeNull()
  const contractId = fourYearFixture(false)
  scheduleLine(1, 'BASE_COMMISSION', 600000)
  const missing = await requestCheck(page, CONTRACT_NO)
  expect(missing.resultStatus).toBe('REVIEW_REQUIRED')
  expect(missing.acquisitionCost.amount).toBeNull()
  expect(missing.upfront.limitAmount).toBeNull()
  expect(missing.upfront.calculatedAmount).toBe(600000)

  fourYearFixture(true)
  scheduleLine(1, 'BASE_COMMISSION', 600000)
  for (let installment = 1; installment <= 48; installment++)
    scheduleLine(installment, 'MAINTENANCE_COMMISSION', 45000)
  scheduleLine(1, 'COMMON_COST', 500000)
  const normal = await requestCheck(page, CONTRACT_NO)
  expect(normal.resultStatus).toBe('NORMAL')
  expect(normal.upfront.calculatedAmount).toBe(600000)
  expect(normal.maintenance.calculatedAmount).toBe(2660000)
  expect(normal.indirectSupportAmount).toBe(500000)
  expect(normal.limitPolicy.policyCode).toBe('REG-FOUR-YEAR-2027-V1')
  expect(normal.limitPolicy.regulationRefs).toContain('REG-02')

  scheduleLine(2, 'BASE_COMMISSION', 2400001)
  const violation = await requestCheck(page, CONTRACT_NO)
  expect(violation.resultStatus).toBe('VIOLATION')
  expect(violation.upfront.status).toBe('VIOLATION')
  expect(violation.upfront.exceededFromInstallmentNo).toBe(2)
  expect(violation.maintenance.status).toBe('NORMAL')
  const boundary = await requestCheck(page, BOUNDARY_CONTRACT_NO)
  expect(boundary.resultStatus).toBe('REVIEW_REQUIRED')
  expect(boundary.upfront).toBeNull()

  const before = sql('SELECT count(*) FROM fgc.acquisition_cost_check')
  await page.goto(`/contracts/${contractId}?tab=acquisition&month=2026-07`)
  await expect(page.getByRole('tab', { name: '기본정보', exact: true })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('tab', { name: '계약체결비용', exact: true })).toHaveCount(0)
  expect(sql('SELECT count(*) FROM fgc.acquisition_cost_check')).toBe(before)

  for (const account of ['audit01', 'admin']) {
    await login(page, account, '/acquisition-cost-checks')
    await expect(page.getByRole('heading', { name: '권한 없음' })).toBeVisible()
  }
  sql("UPDATE fgc.app_user SET account_status='ACTIVE', password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'")
  await login(page, 'agent01', '/acquisition-cost-checks')
  await expect(page.getByRole('heading', { name: '권한 없음' })).toBeVisible()
  expect(browserCalls).toEqual([])
  await page.context().clearCookies()
  await page.reload()
  await expect(page).toHaveURL(/\/login/)
})
