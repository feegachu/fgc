import type { Page } from '@playwright/test'
import { test, expect, login, api, completeMonthly, sql } from './fixtures'
import { selectEntity } from '../e2e/selectEntity'
import catalog from '../src/features/assistant/workflow-guides.v1.json' with { type: 'json' }

// SRC-028: real catalog + real HTTP/DOM; verify each entry opens its existing page help.
// This suite never clicks business save/execute/confirm actions; only test setup may write.
const month = '2026-07'
const guides = catalog.filter((guide) => guide.enabled)
type Guide = (typeof guides)[number]
async function seedContract(page: Page) {
  await page.goto('login')
  const result = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0001&page=1&size=20')
  expect(result.content.length, 'A real seeded contract is required; missing data must fail, not skip.').toBeGreaterThan(0)
  return api(page, `/contracts/${result.content[0].contractId}`)
}

interface Preparation { cap?: { capCheckId: number; contractNo: string }; fixtureNotes?: string[] }
async function prepare(page: Page, guide: Guide, account: string): Promise<Preparation | undefined> {
  if (account === 'agent01') {
    // V64 intentionally disables this account. Enable only this isolated, reset-after-test DB fixture.
    sql("UPDATE fgc.app_user SET account_status='ACTIVE', password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'")
  }
  if (guide.workflowId === 'contract.register' || guide.workflowId === 'contract.edit' || guide.workflowId === 'contract.detail') {
    const original = await seedContract(page)
    const route = guide.route.replace(':id', String(original.contractId))
    await login(page, account, `${route}?month=${month}`)
    if (guide.workflowId === 'contract.register') {
      await page.locator('input[name=contractDate]').fill(original.contractDate)
      await page.locator('select[name=insurerId]').selectOption(String(original.insurerId))
      await selectEntity(page, page.locator('input[name=productOfferingId]'), String(original.productOfferingId))
      await selectEntity(page, page.locator('input[name=agentId]'), String(original.agentId))
    }
    return
  }
  if (guide.workflowId === 'schedule.detail') {
    const original = await seedContract(page)
    // The baseline contract has no operational GA_TO_FC schedule. Create the required real fixture explicitly.
    const headers = await api(page, `/contracts/${original.contractId}/schedules/regenerate`, { reason: 'E2E-catalog-guide-fixture' })
    expect(headers.length, 'Schedule preparation must create a real header before UI verification.').toBeGreaterThan(0)
    await login(page, account, `/schedules?month=${month}&contractNo=FGC-FGL01-202607-0001&stage=GA_TO_FC`)
    await page.getByRole('table').locator('tbody a[href^="/schedules/"]').first().click()
    await expect(page).toHaveURL(/\/schedules\/\d+(?:\?|$)/)
    return
  }
  if (guide.workflowId === 'clawback.review') {
    await page.goto('login')
    const contractNo = 'FGC-FGN01-202603-0001'
    const query = new URLSearchParams({ dateBasis: 'EVENT_DATE', dateFrom: `${month}-01`, dateTo: `${month}-31`, contractNo, page: '1', size: '20' })
    const rows = await api(page, `/clawbacks/candidates?${query}`)
    const candidate = rows.content.find((entry: { contractNo: string }) => entry.contractNo === contractNo)
    expect(candidate, 'Clawback tutorial requires the persisted July candidate with real source/payment evidence.').toBeTruthy()
    const detail = await api(page, `/clawbacks/candidates/${candidate.candidateId}`)
    expect(detail.sourceRefs.length).toBeGreaterThan(0)
    expect(detail.paymentAttributions.length).toBeGreaterThan(0)
    await login(page, account, `/clawbacks?month=${month}&${query}`)
    return { fixtureNotes: [`UI targets only: ${detail.scheduleLines.length} schedule rows; no clawback calculation or confirmation is executed.`] }
  }
  if (guide.workflowId === 'cap.review') {
    await page.goto('login')
    await completeMonthly(page, month)
    const rows = await api(page, `/cap-checks?month=${month}&stage=INSURER_TO_GA&page=1&size=50`)
    const cap = rows.content.find((entry: { resultStatus: string; capCheckId: number; contractNo: string }) => entry.resultStatus !== 'NOT_APPLICABLE' && entry.capCheckId != null && entry.contractNo)
    expect(cap, 'Full cap tutorial requires a real calculation result, not only the NOT_APPLICABLE branch.').toBeTruthy()
    await login(page, account, `/cap-checks?month=${month}&stage=INSURER_TO_GA&contractNo=${encodeURIComponent(cap.contractNo)}`)
    return { cap: { capCheckId: cap.capCheckId, contractNo: cap.contractNo } }
  }
  if (guide.workflowId === 'exception.resolve') {
    await page.goto('login')
    await completeMonthly(page, month)
  }
  if (guide.workflowId === 'validation.detail') {
    await page.goto('login')
    const id = await completeMonthly(page, month)
    await login(page, account, `/validation-runs/${id}?month=${month}`)
    return
  }
  if (guide.workflowId === 'policy.lifecycle') {
    await page.goto('login')
    const policies = await api(page, `/policies?asOf=${month}-01`)
    const policy = policies.find((entry: { policyType: string; status: string }) => entry.policyType === 'CURRENT_COMMISSION' && entry.status === 'ACTIVE')
    expect(policy, 'The positive lifecycle scenario requires a supported active commission policy.').toBeTruthy()
    await login(page, account, `/policies?month=${month}&asOf=${month}-01&policyVersionId=${policy.policyVersionId}`)
    return
  }
  await login(page, account, `${account === 'agent01' ? '/agent-home' : guide.route}?month=${month}`)
}

// Pico delegates to each page's existing Help button; the removed workflow runtime has no steps to execute.
for (const guide of guides) {
  const role = guide.roles.includes('COMPLIANCE') ? 'COMPLIANCE' : 'SETTLEMENT'
  if (guide.roles.includes('AGENT')) continue
  const account = role === 'COMPLIANCE' ? 'audit01' : 'settle01'
  test(`기존 도움말 연결 ${guide.workflowId}: ${role}`, async ({ page }, info) => {
    const errors: string[] = [], writes: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await prepare(page, guide, account)
    page.on('request', request => {
      const path = new URL(request.url()).pathname
      if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/')) writes.push(path)
    })
    const help = page.locator('#main-content [data-screen-help]').filter({ hasText: '도움말' }).first()
    await expect(help).toBeEnabled()
    await help.click()
    const card = page.locator('.screen-step-guide')
    await expect(card).toBeVisible()
    const directHeading = await card.getByRole('heading').innerText()
    const directText = await card.innerText()
    await card.getByRole('button', { name: '종료', exact: true }).click()
    await page.getByRole('button', { name: '업무 비서 AI 열기, 피코', exact: true }).click()
    const pico = page.getByRole('dialog', { name: '피코 업무 비서' })
    await pico.getByRole('tab', { name: '업무 안내', exact: true }).click()
    await pico.getByRole('button').filter({ has: page.getByText(guide.title, { exact: true }) }).click()
    await expect(card.getByRole('heading')).toHaveText(directHeading)
    await expect.poll(() => card.innerText()).toBe(directText)
    await expect(page.locator('.pico-guide-card')).toHaveCount(0)
    await expect(pico).toHaveCount(0)
    await page.screenshot({ path: info.outputPath('existing-page-help.png'), fullPage: true })
    await card.getByRole('button', { name: '종료', exact: true }).click()
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })
}
