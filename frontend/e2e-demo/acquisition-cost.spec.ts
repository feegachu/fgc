import { test, expect, login, sql } from './fixtures'
import type { Page } from '@playwright/test'

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

async function requestCheck(page: Page, contractNo: string, capture?: string) {
  await page.getByRole('button', { name: '검증 요청' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('combobox', { name: /계약/ }).click()
  await dialog.getByRole('combobox', { name: /계약/ }).fill(contractNo)
  await page.getByRole('option', { name: new RegExp(contractNo) }).click()
  if (capture) await page.screenshot({ path: capture, fullPage: true })
  const response = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/v1/acquisition-cost-checks' && r.request().method() === 'POST',
  )
  await dialog.getByRole('button', { name: '검증 요청' }).click()
  const result = await response
  expect(result.status(), await result.text()).toBe(201)
  await expect(dialog).toBeHidden()
  return (await result.json()).data
}

test('실제 서버에서 두 한도를 따로 판정하고 자료 부족과 적용 대상 아님을 구분한다', async ({ page }) => {
  await login(page, 'settle01', '/acquisition-cost-checks')
  await expect(page.getByRole('heading', { name: '계약체결비용 한도 검증' })).toBeVisible()
  await expect(page.getByText('조건에 맞는 검증 결과가 없습니다.')).toBeVisible()
  await page.screenshot({ path: 'e2e-artifacts/729-empty.png', fullPage: true })

  // 현행 판매버전은 적용 대상 아님이다. 금액을 0으로 채우지 않는다.
  const current = await requestCheck(page, 'FGC-FGL01-202607-0001', 'e2e-artifacts/729-request-modal.png')
  expect(current.resultStatus).toBe('NOT_APPLICABLE')
  expect(current.upfront).toBeNull()
  await expect(page.getByRole('list', { name: '판정 사유' })).toContainText('적용 대상이 아닙니다')
  await page.screenshot({ path: 'e2e-artifacts/729-not-applicable.png', fullPage: true })

  // 지급 정책과 기준 값이 없으면 금액 없이 검토 필요다. 데모 시드의 상태와 같다.
  const contractId = fourYearFixture(false)
  scheduleLine(1, 'BASE_COMMISSION', 600000)
  const missing = await requestCheck(page, CONTRACT_NO)
  expect(missing.resultStatus).toBe('REVIEW_REQUIRED')
  expect(missing.acquisitionCost.amount).toBeNull()
  expect(missing.upfront.limitAmount).toBeNull()
  expect(missing.upfront.calculatedAmount).toBe(600000)
  await expect(page.getByRole('article', { name: '선지급 총액 한도 결과' })).toContainText('자료 없음')
  await page.screenshot({ path: 'e2e-artifacts/729-review-required.png', fullPage: true })

  // 같은 판매버전에 기준 값이 있는 새 지급 정책으로 바꾸면 금액이 나온다.
  fourYearFixture(true)
  scheduleLine(1, 'BASE_COMMISSION', 600000)
  for (let installment = 1; installment <= 48; installment++)
    scheduleLine(installment, 'MAINTENANCE_COMMISSION', 45000)
  scheduleLine(1, 'COMMON_COST', 500000)

  // 한도 이내. 간접지원경비는 유지관리 총액에 들어가고 고정 비율로 막지 않는다(기준액의 16.7%).
  const normal = await requestCheck(page, CONTRACT_NO)
  expect(normal.resultStatus).toBe('NORMAL')
  expect(normal.upfront.calculatedAmount).toBe(600000)
  expect(normal.maintenance.calculatedAmount).toBe(2660000)
  expect(normal.indirectSupportAmount).toBe(500000)
  expect(normal.limitPolicy.policyCode).toBe('REG-FOUR-YEAR-2027-V1')
  const maintenance = page.getByRole('article', { name: '유지관리 총액 한도 결과' })
  await expect(maintenance).toContainText('2,660,000원')
  await expect(maintenance).toContainText('500,000원')
  await expect(page.getByRole('article', { name: '선지급 총액 한도 결과' })).toContainText('600,000원')
  // 화면의 판과 정책 근거는 API가 준 값이다.
  const basis = page.getByRole('heading', { name: '계산 근거' }).locator('..')
  await expect(basis).toContainText(`#${normal.scheduleHeaderId} v${normal.scheduleVersionNo}`)
  await expect(basis).toContainText(`${normal.paymentPolicy.policyName} v${normal.paymentPolicy.versionNo}`)
  await expect(basis).toContainText(`${normal.limitPolicy.policyName} v${normal.limitPolicy.versionNo}`)
  expect(normal.limitPolicy.regulationRefs).toContain('REG-02')
  await expect(basis).toContainText('선지급수수료')
  await page.getByRole('region', { name: '알림' }).getByRole('button').first().click()
  await page.screenshot({ path: 'e2e-artifacts/729-normal.png', fullPage: true })

  // 1원 초과는 그 한도만 초과이고 유지관리 판정은 그대로다.
  scheduleLine(2, 'BASE_COMMISSION', 2400001)
  const violation = await requestCheck(page, CONTRACT_NO)
  expect(violation.resultStatus).toBe('VIOLATION')
  expect(violation.upfront.status).toBe('VIOLATION')
  expect(violation.upfront.exceededFromInstallmentNo).toBe(2)
  expect(violation.maintenance.status).toBe('NORMAL')
  await expect(page.getByRole('article', { name: '선지급 총액 한도 결과' })).toContainText('2회차부터 초과')
  await page.screenshot({ path: 'e2e-artifacts/729-violation.png', fullPage: true })

  // 시드의 판매기간 밖 계약은 경계라 금액 없이 검토 필요다.
  const boundary = await requestCheck(page, BOUNDARY_CONTRACT_NO)
  expect(boundary.resultStatus).toBe('REVIEW_REQUIRED')
  expect(boundary.upfront).toBeNull()
  await expect(page.getByRole('list', { name: '판정 사유' })).toContainText('계약일이 판매기간 밖입니다.')
  await page.screenshot({ path: 'e2e-artifacts/729-boundary.png', fullPage: true })

  // 계약 상세의 작은 영역은 같은 저장 결과를 보인다. 탭을 열어도 새 결과를 만들지 않는다.
  const before = sql('SELECT count(*) FROM fgc.acquisition_cost_check')
  await page.goto(`/contracts/${contractId}?tab=acquisition&month=2026-07`)
  const panel = page.getByRole('tabpanel', { name: '계약체결비용' })
  await expect(panel.getByRole('table')).toContainText('한도 초과')
  await expect(panel.getByRole('columnheader', { name: '선지급 판정' })).toBeVisible()
  await page.screenshot({ path: 'e2e-artifacts/729-contract-tab.png', fullPage: true })
  expect(sql('SELECT count(*) FROM fgc.acquisition_cost_check')).toBe(before)

  // 탭의 링크는 이 계약으로 좁힌 전체 결과로 이동한다.
  await panel.getByRole('link', { name: '전체 결과 보기' }).click()
  await expect(page.getByRole('heading', { name: '계약체결비용 한도 검증' })).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`contractNo=${CONTRACT_NO}`))
  await expect(page.getByRole('row', { name: new RegExp(BOUNDARY_CONTRACT_NO) })).toHaveCount(0)
  await expect(page.getByRole('row', { name: new RegExp(`계약 ${CONTRACT_NO} 결과 보기`) }).first()).toBeVisible()

  // 준법 담당은 결과만 본다.
  await login(page, 'audit01', '/acquisition-cost-checks')
  await expect(page.getByRole('row', { name: new RegExp(`계약 ${CONTRACT_NO} 결과 보기`) }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '검증 요청' })).toBeHidden()
  await expect(page.getByText('검증 요청은 처리 권한이 있는 계정만 할 수 있습니다.')).toBeVisible()
  await page.screenshot({ path: 'e2e-artifacts/729-read-only.png', fullPage: true })

  // 설계사는 직접 URL로 들어와도 화면이 열리지 않고 이 API를 부르지 않는다. 서버 거절은 통합 시험에서 본다.
  // V64가 막아 둔 계정이라 격리 DB에서만 연다.
  sql("UPDATE fgc.app_user SET account_status='ACTIVE', password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'")
  await page.goto('about:blank')
  const agentCalls: string[] = []
  page.on('request', (r) => {
    if (r.url().includes('/api/v1/acquisition-cost-checks')) agentCalls.push(r.url())
  })
  await login(page, 'agent01', '/acquisition-cost-checks')
  await expect(page.getByRole('heading', { name: '권한 없음' })).toBeVisible()
  expect(agentCalls).toEqual([])

  // 세션이 끝나면 기존 인증 복구 흐름으로 로그인 화면에 간다. 저장한 결과를 비인증으로 보이지 않는다.
  await page.context().clearCookies()
  await page.reload()
  await expect(page).toHaveURL(/\/login/)
})
