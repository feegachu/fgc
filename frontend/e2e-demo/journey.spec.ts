import type { Page } from '@playwright/test'
import {
  test,
  expect,
  login,
  legacyLogin,
  backend,
  api,
  completeMonthly,
  responseFor,
  successful,
  sql,
} from './fixtures'

const month = '2026-07'

test('계약 등록·수정 → 스케줄 재생성·확정', async ({ page }, info) => {
  await legacyLogin(page)
  await page.goto(`${backend}/contracts?month=${month}`)
  await expect(page.locator('.contract-table tbody tr').first()).toBeVisible()
  await info.attach('legacy-contracts', { body: await page.screenshot(), contentType: 'image/png' })
  const seed = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0001&page=1&size=20')
  const original = await api(page, `/contracts/${seed.content[0].contractId}`)
  await login(page, 'settle01', '/contracts/new?month=2026-07')
  await page.locator('input[name=contractDate]').fill(original.contractDate)
  await page.locator('select[name=insurerId]').selectOption(String(original.insurerId))
  await page.locator('select[name=productOfferingId]').selectOption(String(original.productOfferingId))
  await page.locator('input[name=contractNo]').fill('E2E-418-CONTRACT')
  await page.locator('select[name=agentId]').selectOption(String(original.agentId))
  for (const field of ['premiumPerCycleAmount', 'firstPremiumAmount', 'monthlyEquivalentFirstPremium'])
    await page.locator(`input[name=${field}]`).fill(String(original[field]))
  const term = page.locator('input[name=paymentTermMonths]')
  if (await term.isEditable()) await term.fill(String(original.paymentTermMonths))
  const created = responseFor(page, '/contracts')
  await page.locator('button[type=submit]').click()
  const contract = await successful(created)
  await expect(page).toHaveURL(new RegExp(`/contracts/${contract.contractId}(?:\\?|$)`))
  await page.getByRole('button', { name: '수정', exact: true }).click()
  await page.locator('input[name=contractNo]').fill('E2E-418-CONTRACT-EDIT')
  const edited = responseFor(page, `/contracts/${contract.contractId}`, 'PUT')
  await page.locator('button[type=submit]').click()
  await successful(edited)
  await expect(page.getByRole('main')).toContainText('E2E-418-CONTRACT-EDIT')
  await page.getByRole('tab', { name: '예상 스케줄', exact: true }).click()
  await page.goto('schedules?month=2026-07&contractNo=E2E-418-CONTRACT-EDIT&stage=GA_TO_FC')
  await page.getByRole('table').locator('tbody tr').first().getByRole('link').first().click()
  await page.getByRole('button', { name: '새 버전 만들기', exact: true }).click()
  await page.getByLabel('생성 사유').fill('418 시연 재생성')
  const regenerated = page.waitForResponse((r) => r.url().endsWith('/regenerate') && r.request().method() === 'POST')
  await page.getByRole('dialog').getByRole('button', { name: '새 버전 만들기', exact: true }).click()
  await successful(regenerated)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('button', { name: '확정', exact: true }).click()
  const confirmed = page.waitForResponse((r) => r.url().endsWith('/confirm') && r.request().method() === 'POST')
  await page.getByRole('dialog').getByRole('button', { name: '확정', exact: true }).click()
  await successful(confirmed)
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeDisabled()
  await info.attach('react-schedule-confirmed', { body: await page.screenshot(), contentType: 'image/png' })
})

async function payment(page: Page, amount: number, contractNumbers: string[]) {
  const contracts = []
  for (const contractNo of contractNumbers) {
    const data = await api(page, `/contracts?contractNo=${contractNo}&page=1&size=20`)
    expect(data.content).toHaveLength(1)
    contracts.push(await api(page, `/contracts/${data.content[0].contractId}`))
  }
  const items = await api(page, '/base/commission-items?asOf=2026-07-01')
  const item = items.find((item: { itemCode: string }) => item.itemCode === 'BASE_COMMISSION')
  expect(item, 'Demo BASE_COMMISSION item must exist').toBeTruthy()
  await page.goto(`transactions/new?month=${month}`)
  await page.getByLabel('수령 설계사', { exact: false }).selectOption(String(contracts[0].agentId))
  await page.getByLabel('수수료 항목', { exact: false }).selectOption(String(item.commissionItemId))
  await page.locator('#bizKey').fill(`E2E-418-PAYMENT-${amount}`)
  await page.locator('#amount').fill(String(amount))
  for (let index = 0; index < contracts.length; index++) {
    await page.getByRole('button', { name: '귀속행 추가' }).click()
    const row = page
      .locator('#attr-body tr')
      .filter({ has: page.locator('[data-contract-id]') })
      .nth(index)
    await row.locator('[data-contract-id]').selectOption(String(contracts[index].contractId))
    await row.locator('[data-attr-date]').fill('2026-07-31')
    await row.locator('[data-attr-amount]').fill(String(amount / contracts.length))
  }
  const saved = responseFor(page, '/transactions')
  await page.locator('#btn-save').click()
  const result = await successful(saved)
  await expect(page.locator('#amount')).toBeDisabled()
  await page.locator('#btn-precheck').click()
  await expect(page.locator('#btn-confirm')).toBeEnabled()
  await page.locator('#btn-confirm').click()
  return result.commissionTransactionId as number
}

test('복수 계약 지급 등록 → 사전검증 → 확정 성공', async ({ page }) => {
  await login(page)
  const id = await payment(page, 20000, ['FGC-FGL01-202607-0002', 'FGC-FGL01-202607-0005'])
  const confirmed = responseFor(page, `/transactions/${id}/confirm`)
  await page.getByRole('button', { name: '확정 실행', exact: true }).click()
  await successful(confirmed)
  await expect(page).toHaveURL(/\/transactions\?/)
  await expect(page.locator('#list-body')).toContainText('E2E-418-PAYMENT-20000')
  const actual = await api(page, `/transactions/${id}`)
  expect(actual.status).toBe('CONFIRMED')
  expect(actual.attributions).toHaveLength(2)
  expect(actual.attributions.reduce((sum: number, row: { amount: number }) => sum + row.amount, 0)).toBe(20000)
})

test('한도 초과 거절 → 예외함 조치 → CAP 계산근거', async ({ page }) => {
  await login(page)
  const id = await payment(page, 250000, ['FGC-FGL01-202607-0005'])
  const rejected = responseFor(page, `/transactions/${id}/confirm`)
  await page.getByRole('button', { name: '확정 시도 · 예외 등록' }).click()
  const failure = await rejected
  expect(failure.ok()).toBe(false)
  expect((await failure.json()).error.code).toBe('FGC-CAP-001')
  expect((await api(page, `/transactions/${id}`)).status).toBe('DRAFT')
  await page.getByRole('link', { name: '예외함에서 처리하기' }).click()
  await expect(page.getByRole('heading', { name: '예외함', exact: true })).toBeVisible()
  await page.getByRole('table', { name: '예외 목록' }).locator('tbody tr').first().getByRole('button').first().click()
  await page.getByRole('combobox', { name: '조치', exact: true }).selectOption('START_REVIEW')
  await page.getByLabel('처리 사유').fill('418 한도 초과 검토 시작')
  await page.getByRole('button', { name: '처리 저장' }).click()
  await expect(page.getByText('1. 검토 시작')).toBeVisible()
  await expect(page.getByRole('table', { name: '예외 목록' }).getByText('검토중')).toBeVisible()
  const capLink = page
    .getByRole('link', { name: /계산근거|CAP|한도/ })
    .filter({ hasText: /근거|CAP/ })
    .first()
  await capLink.click()
  await expect(page.getByRole('dialog')).toBeVisible()
  const capUrl = page.url()
  expect(new URL(capUrl).searchParams.get('capCheckId')).toBeTruthy()
  await page.reload()
  await expect(page.getByRole('dialog')).toBeVisible()
})

test('월 검증 생성·실행·8개 조건·GA_ADMIN 확정', async ({ page }) => {
  await login(page, 'settle01', '/validation-runs?month=2026-07')
  const created = responseFor(page, '/validation-runs')
  await page.getByRole('button', { name: '실행 생성' }).click()
  const run = await successful(created)
  await page.getByRole('button', { name: '실행', exact: true }).click()
  await expect
    .poll(async () => (await api(page, `/validation-runs/${run.validationRunId}/progress`)).status, {
      timeout: 120_000,
    })
    .toBe('COMPLETED')
  const checklist = await api(page, `/validation-runs/${run.validationRunId}/finalize-checklist`)
  expect(checklist.conditions).toHaveLength(8)
  // The checked-in seed deliberately produces critical exceptions. Exercise the real gate and the
  // existing review workflow; never change the checklist response or force FINALIZED in SQL.
  expect(checklist.conditions[2].passed).toBe(false)
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeDisabled()
  const pending = await api(
    page,
    '/exceptions?validationMonth=2026-07-01&severity=CRITICAL&status=OPEN&page=1&size=100',
  )
  expect(pending.content.length).toBeGreaterThan(0)
  await page.goto('exceptions?month=2026-07&severity=CRITICAL&status=OPEN')
  for (const item of pending.content) {
    await page
      .getByRole('table', { name: '예외 목록' })
      .getByRole('button', { name: String(item.exceptionCaseId), exact: true })
      .click()
    for (const action of ['START_REVIEW', 'RESOLVE']) {
      await page.getByRole('combobox', { name: '조치', exact: true }).selectOption(action)
      await page.getByLabel('처리 사유').fill('418 격리 데모의 확정 게이트 검증을 위한 조치')
      const saved = responseFor(page, `/exceptions/${item.exceptionCaseId}/actions`)
      await page.getByRole('button', { name: '처리 저장' }).click()
      await successful(saved)
    }
  }
  const ready = await api(page, `/validation-runs/${run.validationRunId}/finalize-checklist`)
  expect(ready.passed, JSON.stringify(ready.conditions)).toBe(true)
  await login(page, 'gaadmin', `/validation-runs/${run.validationRunId}?month=2026-07`)
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '확정', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '확정', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '확정했습니다' })).toBeVisible()
  expect((await api(page, `/validation-runs/${run.validationRunId}`)).header.status).toBe('FINALIZED')
})

test('대사 실행 → 결과 자동 선택 → 비교 모달', async ({ page }, info) => {
  await login(page)
  await completeMonthly(page)
  // The monthly batch already reconciles every seeded insurer. In the isolated fixture only,
  // return one non-finalized reconciliation to its pre-execution state, preserving all real inputs.
  const candidate = JSON.parse(
    sql(`SELECT json_build_object('id',r.reconciliation_run_id,'insurerId',r.insurer_id,'stage',r.payment_stage)
    FROM fgc.reconciliation_run r WHERE r.status='COMPLETED' AND EXISTS
    (SELECT 1 FROM fgc.reconciliation_result x WHERE x.reconciliation_run_id=r.reconciliation_run_id)
    ORDER BY r.reconciliation_run_id LIMIT 1`),
  )
  const id = Number(candidate.id)
  expect(Number.isSafeInteger(id)).toBe(true)
  const previous = await api(page, `/reconciliations/${id}/results?page=1&size=100`)
  sql(`BEGIN;
    DELETE FROM fgc.reconciliation_match WHERE reconciliation_result_id IN
      (SELECT reconciliation_result_id FROM fgc.reconciliation_result WHERE reconciliation_run_id=${id});
    DELETE FROM fgc.reconciliation_result WHERE reconciliation_run_id=${id};
    DELETE FROM fgc.reconciliation_run WHERE reconciliation_run_id=${id};
    COMMIT;`)
  await page.goto(`reconciliations?month=2026-07&stage=${candidate.stage}&insurer=${candidate.insurerId}`)
  const created = responseFor(page, '/reconciliations')
  await page.getByRole('button', { name: '대사 실행', exact: true }).click()
  const run = await successful(created)
  await expect(page).toHaveURL(new RegExp(`runId=${run.reconciliationRunId}`))
  const compare = page.getByRole('button', { name: /대사 결과 .* 비교 보기/ }).first()
  await expect(compare).toBeVisible({ timeout: 60_000 })
  await compare.click()
  await expect(page.getByRole('dialog')).toContainText('허용오차 0원')
  const current = await api(page, `/reconciliations/${run.reconciliationRunId}/results?page=1&size=100`)
  expect(current.summary).toEqual(previous.summary)
  await info.attach('reconciliation-comparison', { body: await page.screenshot(), contentType: 'image/png' })
})

test('원장 역분개 → 원본 상태와 반대 분개 확인', async ({ page }) => {
  await login(page)
  await completeMonthly(page)
  await page.goto('journals?month=2026-07&from=2026-07-01&to=2026-07-31&searched=true')
  const table = page.getByRole('table', { name: '조회 조건에 해당하는 검증원장 분개 목록' })
  const row = table.locator('tbody tr').filter({ hasText: '기표됨' }).first()
  await row.getByRole('button').first().click()
  await page.getByRole('button', { name: '역분개', exact: true }).click()
  await page.getByRole('textbox', { name: '역분개 사유', exact: true }).fill('418 시연 역분개')
  await page.getByLabel('증빙 참조').fill('E2E-418')
  const reversed = page.waitForResponse(
    (r) => r.request().method() === 'POST' && /\/journals\/.*\/revers/.test(r.url()),
  )
  await page.getByRole('button', { name: '역분개 생성' }).click()
  await successful(reversed)
  await expect(page.getByRole('region', { name: '분개 상세' }).getByText('역분개됨')).toBeVisible()
  expect(
    sql("SELECT count(*) FROM fgc.journal_header WHERE journal_type = 'REVERSAL' AND reversal_of_id IS NOT NULL"),
  ).toBe('1')
})
