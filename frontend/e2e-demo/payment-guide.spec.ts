import { selectEntity } from '../e2e/selectEntity'
import { test, expect, login, api, responseFor, successful, sql } from './fixtures'

// Screenshots and HTTP/DB evidence are retained without requiring video encoding.
test.use({ video: 'off' })

for (const multi of [false, true]) {
  test(`FUN-087 실제 서버 ${multi ? '다중 계약·증빙 누락' : '단일 계약'}: 기존 도움말 → 초안 저장 → 사전검증 → 확정 → 목록`, async ({
    page,
  }, info) => {
    const errors: string[] = []
    const requests: { path: string; method: string; status: number }[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('response', (response) => {
      const path = new URL(response.url()).pathname
      if (path.startsWith('/api/v1/transactions'))
        requests.push({ path, method: response.request().method(), status: response.status() })
    })
    await login(page, 'settle01', '/transactions/new?amount=0&month=2026-07')
    const contracts = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0002&page=1&size=20')
    expect(contracts.content).toHaveLength(1)
    const contract = await api(page, `/contracts/${contracts.content[0].contractId}`)
    const items = await api(page, '/base/commission-items?asOf=2026-07-01')
    const item = items.find((entry: { itemCode: string }) => entry.itemCode === 'BASE_COMMISSION')
    expect(item).toBeTruthy()
    await selectEntity(page, page.locator('#recipient'), String(contract.agentId))
    await selectEntity(page, page.locator('#item'), String(item.commissionItemId))
    await page.locator('#bizKey').fill('E2E-FUN087-GUIDE')
    await page.locator('#amount').fill('20')
    if (!(await page.locator('[data-contract-id]').count())) await page.locator('#btn-add-attr').click()
    await selectEntity(page, page.locator('[data-contract-id]'), String(contract.contractId))
    await page.locator('[data-attr-date]').fill('2026-07-31')
    await page.locator('[data-attr-amount]').fill('20')
    if (multi) {
      const second = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0005&page=1&size=20')
      expect(second.content).toHaveLength(1)
      const other = await api(page, `/contracts/${second.content[0].contractId}`)
      expect(other.agentId).toBe(contract.agentId)
      await page.locator('#amount').fill('40')
      await page.locator('#btn-add-attr').click()
      await selectEntity(page, page.locator('[data-contract-id]').nth(1), String(other.contractId))
      await page.locator('[data-attr-date]').nth(1).fill('2026-07-31')
      await page.locator('[data-attr-amount]').nth(1).fill('20')
      await page.locator('[data-inclusion]').first().selectOption('EXCLUDED:NEW_AGENT_SUPPORT')
      await expect(page.locator('#btn-save')).toBeDisabled()
      await page.locator('[data-attr-evidence]').first().fill('E2E 다중 계약 증빙')
      await expect(page.locator('#btn-save')).toBeEnabled()
      // Restore the actual included allocation after checking the evidence gate.
      await page.locator('[data-inclusion]').first().selectOption('INCLUDED')
    }
    await page.getByRole('button', { name: '업무 비서 AI 열기, 피코', exact: true }).click()
    const assistant = page.getByRole('dialog', { name: '피코 업무 비서' })
    await assistant.getByRole('tab', { name: '업무 안내', exact: true }).click()
    await assistant.getByRole('button').filter({ has: page.getByText('지급 초안 저장', { exact: true }) }).click()
    const card = page.locator('.screen-step-guide')
    await expect(card).toBeVisible()
    await expect(page.locator('.pico-guide-card')).toHaveCount(0)
    expect(requests.filter((entry) => entry.method === 'POST')).toHaveLength(0)
    await info.attach('existing-payment-help', { body: await page.screenshot(), contentType: 'image/png' })
    await card.getByRole('button', { name: '종료', exact: true }).click()
    const saving = responseFor(page, '/transactions')
    await page.locator('#btn-save').click()
    const payment = await successful(saving)
    const id = payment.commissionTransactionId as number
    expect(Number.isSafeInteger(id)).toBe(true)
    expect(payment.status).toBe('DRAFT')
    const checking = responseFor(page, `/transactions/${id}/precheck`)
    await page.locator('#btn-precheck').click()
    const checkingResponse = await checking
    expect(checkingResponse.headers()['x-fgc-precheck-token']).toBeTruthy()
    const check = await successful(Promise.resolve(checkingResponse))
    expect(check.paymentId).toBe(id)
    expect(check.confirmable).toBe(true)
    expect(check.blockers).toEqual([])
    await page.locator('#btn-confirm').click()
    const confirming = responseFor(page, `/transactions/${id}/confirm`)
    await page
      .getByRole('dialog', { name: '지급 건 확정' })
      .getByRole('button', { name: '확정 실행', exact: true })
      .click()
    const confirmed = await successful(confirming)
    expect(confirmed.status).toBe('CONFIRMED')
    await expect(page).toHaveURL(/\/transactions(?:\?.*)?$/)
    const detail = await api(page, `/transactions/${id}`)
    expect(detail.status).toBe('CONFIRMED')
    expect(detail.attributions).toHaveLength(multi ? 2 : 1)
    expect(new Set(detail.attributions.map((row: { contractId: number }) => row.contractId)).size).toBe(multi ? 2 : 1)
    const persisted = JSON.parse(
      sql(
        `SELECT json_build_object('id',commission_transaction_id,'status',status,'amount',amount) FROM fgc.commission_transaction WHERE commission_transaction_id=${id}`,
      ),
    )
    expect(persisted).toMatchObject({ id, status: 'CONFIRMED', amount: multi ? 40 : 20 })
    await expect(page.locator('#list-body')).toContainText('E2E-FUN087-GUIDE')
    await info.attach('confirmed-payment-list', { body: await page.screenshot(), contentType: 'image/png' })
    expect(requests.filter((entry) => entry.method === 'POST' && entry.path.endsWith('/confirm'))).toHaveLength(1)
    expect(errors).toEqual([])
    await info.attach('real-server-evidence', {
      body: JSON.stringify({ persisted, requests, errors }, null, 2),
      contentType: 'application/json',
    })
  })
}
