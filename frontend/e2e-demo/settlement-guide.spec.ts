import { selectEntity } from '../e2e/selectEntity'
import { test, expect, login, api, responseFor, successful } from './fixtures'
test.use({ video: 'off' })
for (const startMode of ['existing', 'new'] as const) {
  test(`FUN-088 실제 서버 ${startMode}: 계약 → 지급 → 월 검증 → 대사 불일치 분기`, async ({ page }, info) => {
    const errors: string[] = []
    const writes: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('request', (request) => {
      if (
        request.method() === 'POST' &&
        /\/api\/v1\/(contracts|transactions|validation-runs|reconciliations|exceptions)(\/|$|\?)/.test(request.url())
      )
        writes.push(new URL(request.url()).pathname)
    })
    await login(page, 'settle01', '/?month=2026-07')
    await page.getByRole('button', { name: '전체 업무 가이드', exact: true }).click()
    const guide = page.getByRole('complementary', { name: '전체 업무 가이드' })
    const contractNo = startMode === 'new' ? 'E2E-FUN088-CONTRACT' : 'FGC-FGL01-202607-0002'
    if (startMode === 'existing') await guide.getByLabel(/기존 계약번호/).fill(contractNo)
    await guide.getByRole('button', { name: '안내 시작', exact: true }).click()
    expect(writes).toHaveLength(0)
    if (startMode === 'new') {
      await expect(page).toHaveURL(/\/contracts\/new/)
      const seed = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0001&page=1&size=20')
      const original = await api(page, `/contracts/${seed.content[0].contractId}`)
      await page.locator('input[name=contractDate]').fill(original.contractDate)
      await selectEntity(page, page.locator('[role=combobox][name=insurerId]'), String(original.insurerId))
      await selectEntity(page, page.locator('[role=combobox][name=productOfferingId]'), String(original.productOfferingId))
      await page.locator('input[name=contractNo]').fill(contractNo)
      await selectEntity(page, page.locator('[role=combobox][name=agentId]'), String(original.agentId))
      for (const field of ['premiumPerCycleAmount', 'firstPremiumAmount', 'monthlyEquivalentFirstPremium'])
        await page.locator(`input[name=${field}]`).fill(String(original[field]))
      const term = page.locator('input[name=paymentTermMonths]')
      if (await term.isEditable()) await term.fill(String(original.paymentTermMonths))
      const created = responseFor(page, '/contracts')
      await page.locator('button[type=submit]').click()
      const savedContract = await successful(created)
      await expect(page).toHaveURL(new RegExp(`/contracts/${savedContract.contractId}(?:\\?|$)`))
    } else {
      await expect(page).toHaveURL(/\/contracts\/\d+/)
      const regenerated = page.waitForResponse(
        (response) => response.url().includes('/schedules/regenerate') && response.request().method() === 'POST',
      )
      await page.getByRole('button', { name: '스케줄 재생성', exact: true }).click()
      await successful(regenerated)
    }
    await guide.getByRole('button', { name: '현재 결과로 이어서 안내' }).click()
    await expect(page).toHaveURL(/\/schedules\/\d+/)
    await expect(guide).toContainText('2/9')
    await guide.getByRole('button', { name: '스케줄 확인 완료' }).click()
    await expect(page).toHaveURL(/\/transactions\/new/)
    expect(writes).toHaveLength(1)
    const contracts = await api(page, `/contracts?contractNo=${contractNo}&page=1&size=20`)
    const contract = await api(page, `/contracts/${contracts.content[0].contractId}`)
    const items = await api(page, '/base/commission-items?asOf=2026-07-01')
    const item = items.find((entry: { itemCode: string }) => entry.itemCode === 'BASE_COMMISSION')
    await selectEntity(page, page.locator('#recipient'), String(contract.agentId))
    await selectEntity(page, page.locator('#item'), String(item.commissionItemId))
    await page.locator('#bizKey').fill('E2E-FUN088-GUIDE')
    await page.locator('#amount').fill('20')
    if (!(await page.locator('[data-contract-id]').count())) await page.locator('#btn-add-attr').click()
    await selectEntity(page, page.locator('[data-contract-id]'), String(contract.contractId))
    await page.locator('[data-attr-date]').fill('2026-07-31')
    await page.locator('[data-attr-amount]').fill('20')
    const saving = responseFor(page, '/transactions')
    await page.locator('#btn-save').click()
    const payment = await successful(saving)
    const next = guide.getByRole('button', { name: '현재 결과로 이어서 안내' })
    await next.click()
    await expect(guide).toContainText('4/9')
    const checking = responseFor(page, `/transactions/${payment.commissionTransactionId}/precheck`)
    await page.locator('#btn-precheck').click()
    expect((await successful(checking)).confirmable).toBe(true)
    await next.click()
    await expect(guide).toContainText('5/9')
    await page.locator('#btn-confirm').click()
    const confirming = responseFor(page, `/transactions/${payment.commissionTransactionId}/confirm`)
    await page.getByRole('dialog').getByRole('button', { name: '확정 실행' }).click()
    expect((await successful(confirming)).status).toBe('CONFIRMED')
    await expect(page).toHaveURL(/\/transactions\?/)
    await next.click()
    await expect(guide).toContainText('6/9')
    const creating = responseFor(page, '/validation-runs')
    await page.getByRole('button', { name: '실행 생성' }).click()
    const run = await successful(creating)
    await next.click()
    await expect(guide).toContainText('202 수락은 완료가 아닙니다')
    await page.getByRole('button', { name: '실행', exact: true }).click()
    await expect
      .poll(async () => (await api(page, `/validation-runs/${run.validationRunId}/progress`)).status, {
        timeout: 120000,
      })
      .toBe('COMPLETED')
    await next.click()
    await expect(guide).toContainText('7/9')
    await expect(page).toHaveURL(/\/reconciliations/)
    const history = page.getByRole('table', { name: '검색조건에 해당하는 대사 실행 이력' })
    const runs = await api(page, '/reconciliations?month=2026-07&stage=GA_TO_FC&page=1&size=100')
    const selectedRun = runs.content.find(
      (entry: { validationRunId: number; insurerId: number }) =>
        entry.validationRunId === run.validationRunId && entry.insurerId === contract.insurerId,
    )
    expect(selectedRun).toBeTruthy()
    await history
      .getByRole('row')
      .filter({ has: page.getByRole('cell', { name: String(selectedRun.reconciliationRunId), exact: true }) })
      .click()
    await next.click()
    await expect(guide).toContainText('8/9')
    await expect(page).toHaveURL(/\/exceptions/)
    expect(writes.filter((path) => path.endsWith('/confirm'))).toHaveLength(1)
    expect(writes.filter((path) => path === '/api/v1/reconciliations')).toHaveLength(0)
    expect(errors).toEqual([])
    await info.attach('exception-branch', { body: await page.screenshot(), contentType: 'image/png' })
    await info.attach('real-guide-evidence', {
      body: JSON.stringify({
        paymentId: payment.commissionTransactionId,
        validationRunId: run.validationRunId,
        writes,
        errors,
      }),
      contentType: 'application/json',
    })
  })
}
