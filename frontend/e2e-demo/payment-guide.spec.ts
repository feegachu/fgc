import { test, expect, login, api, responseFor, successful, sql } from './fixtures'

// Screenshots and HTTP/DB evidence are retained without requiring video encoding.
test.use({ video: 'off' })

test('FUN-087 실제 서버: 초안 저장 → 사전검증 → 확정 → 가이드 완료 → 목록', async ({ page }, info) => {
  const errors: string[] = []
  const requests: { path: string; method: string; status: number }[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (path.startsWith('/api/v1/transactions'))
      requests.push({ path, method: response.request().method(), status: response.status() })
  })
  await login(page, 'settle01', '/transactions/new?month=2026-07')
  const contracts = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0002&page=1&size=20')
  expect(contracts.content).toHaveLength(1)
  const contract = await api(page, `/contracts/${contracts.content[0].contractId}`)
  const items = await api(page, '/base/commission-items?asOf=2026-07-01')
  const item = items.find((entry: { itemCode: string }) => entry.itemCode === 'BASE_COMMISSION')
  expect(item).toBeTruthy()
  await page.locator('#recipient').selectOption(String(contract.agentId))
  await page.locator('#item').selectOption(String(item.commissionItemId))
  await page.locator('#bizKey').fill('E2E-FUN087-GUIDE')
  await page.locator('#amount').fill('20')
  await page.locator('#btn-add-attr').click()
  await page.locator('[data-contract-id]').selectOption(String(contract.contractId))
  await page.locator('[data-attr-date]').fill('2026-07-31')
  await page.locator('[data-attr-amount]').fill('20')
  await page.getByRole('button', { name: '도움말', exact: true }).click()
  const card = page.getByRole('region', { name: '지급 업무 가이드' })
  await card.getByRole('button', { name: '지급 초안 저장', exact: true }).click()
  for (let index = 0; index < 3; index++) await card.getByRole('button', { name: '확인하고 다음', exact: true }).click()
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '초안을 저장하세요' })).toBeVisible()
  await expect(card.getByRole('button', { name: '다음', exact: true })).toBeDisabled()
  expect(requests.filter((entry) => entry.method === 'POST')).toHaveLength(0)
  const saving = responseFor(page, '/transactions')
  await page.locator('#btn-save').click()
  const payment = await successful(saving)
  const id = payment.commissionTransactionId as number
  expect(Number.isSafeInteger(id)).toBe(true)
  expect(payment.status).toBe('DRAFT')
  await card.getByRole('button', { name: '현재 내용으로 다시 찾기' }).click()
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '안내 완료' })).toBeVisible()
  await info.attach('draft-guide-completed', { body: await page.screenshot(), contentType: 'image/png' })
  await card.getByRole('button', { name: '사전검증·확정 안내' }).click()
  await card.getByRole('button', { name: '확인하고 다음', exact: true }).click()
  await card.getByRole('button', { name: '다음', exact: true }).click()
  const checking = responseFor(page, `/transactions/${id}/precheck`)
  await page.locator('#btn-precheck').click()
  const check = await successful(checking)
  expect(check.paymentId).toBe(id)
  expect(check.confirmable).toBe(true)
  expect(check.blockers).toEqual([])
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await card.getByRole('button', { name: '확인하고 다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '지급을 확정하세요' })).toBeVisible()
  await page.locator('#btn-confirm').click()
  await expect(card).toBeHidden()
  const confirming = responseFor(page, `/transactions/${id}/confirm`)
  await page
    .getByRole('dialog', { name: '지급 건 확정' })
    .getByRole('button', { name: '확정 실행', exact: true })
    .click()
  const confirmed = await successful(confirming)
  expect(confirmed.status).toBe('CONFIRMED')
  await expect(card.getByRole('heading', { name: '지급 확정 완료' })).toBeVisible()
  await expect(card).toHaveAttribute('data-guide-status', 'COMPLETED')
  await expect(page).toHaveURL(/transactions\/new/)
  const detail = await api(page, `/transactions/${id}`)
  expect(detail.status).toBe('CONFIRMED')
  const persisted = JSON.parse(
    sql(
      `SELECT json_build_object('id',commission_transaction_id,'status',status,'amount',amount) FROM fgc.commission_transaction WHERE commission_transaction_id=${id}`,
    ),
  )
  expect(persisted).toMatchObject({ id, status: 'CONFIRMED', amount: 20 })
  await info.attach('confirmed-guide-before-navigation', { body: await page.screenshot(), contentType: 'image/png' })
  await card.getByRole('button', { name: '목록으로 이동' }).click()
  await expect(page).toHaveURL(/\/transactions(?:\?.*)?$/)
  await expect(page.locator('#list-body')).toContainText('E2E-FUN087-GUIDE')
  await info.attach('confirmed-payment-list', { body: await page.screenshot(), contentType: 'image/png' })
  expect(requests.filter((entry) => entry.method === 'POST' && entry.path.endsWith('/confirm'))).toHaveLength(1)
  expect(errors).toEqual([])
  await info.attach('real-server-evidence', {
    body: JSON.stringify({ persisted, requests, errors }, null, 2),
    contentType: 'application/json',
  })
})
