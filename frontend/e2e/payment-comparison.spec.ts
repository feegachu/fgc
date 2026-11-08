import { test, expect } from '@playwright/test'

test('지급 등록 비교 안내는 앞 3개 행을 강조하고 안내 카드를 점검표 위에 둔다', async ({ page }, info) => {
  const contract = { contractId: 3, contractNo: 'C-003', productName: '가상 상품', contractDate: '2026-07-01' }
  const item = { commissionItemId: 1, itemCode: 'BASE_COMMISSION', itemName: '초회 수수료', cashflowType: 'PAYMENT' }
  const header = { scheduleHeaderId: 5, activeYn: true, paymentStage: 'GA_TO_FC', scheduleRegime: 'CURRENT', schedulePurpose: 'OPERATIONAL', status: 'CONFIRMED', scheduleVersionNo: 1 }
  const lines = Array.from({ length: 12 }, (_, index) => ({
    scheduleLineId: index + 1, contractId: 3, installmentNo: 1,
    beneficiaryAgentId: index + 1, recipientName: `설계사 ${index + 1}`,
    commissionItemId: 1, commissionItemName: '초회 수수료', cashflowType: 'PAYMENT',
    attributionDate: '2026-07-01', dueDate: '2026-07-01', expectedAmount: 100000, lineStatus: 'PLANNED',
  }))
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/auth/refresh') ? { accessToken: 'payment-layout-fixture', expiresIn: 1800 }
      : path.endsWith('/auth/me') ? { loginId: 'layout', userName: '화면 검증', roleCode: 'SETTLEMENT', canProcess: true, demoMonth: '2026-07' }
        : path.endsWith('/contracts/3/schedules') ? { headers: [header] }
          : path.endsWith('/schedules/5') ? { header, lines }
            : path.endsWith('/contracts/3') ? contract
              : path.endsWith('/contracts/3/transactions') ? { transactions: [], reconciliations: [] }
              : path.endsWith('/contracts') ? { content: [contract], totalPages: 1 }
                : path.includes('/commission-items') ? { content: [item], totalPages: 1 } : { content: [], totalPages: 1 }
    await route.fulfill({ json: { data, error: null, requestId: 'payment-layout' } })
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/transactions/new?month=2026-07&contractId=3')
  await expect(page.locator('.contract-payment-preview-list > li')).toHaveCount(12)
  await page.getByRole('button', { name: '도움말', exact: true }).click()
  const card = page.getByRole('region', { name: '계약별 지급 등록 도움말' })
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(page.locator('.contract-payment-comparison-overlay')).toBeVisible()
  await expect.poll(async () => {
    const coach = await card.boundingBox()
    const gates = await page.locator('.contract-payment-gates').boundingBox()
    const expected = await page.locator('[data-guide-target="payment.batch.expected"]').boundingBox()
    return !!coach && !!gates && !!expected && coach.x >= gates.x && coach.y >= gates.y && coach.y + coach.height <= expected.y
  }).toBe(true)
  const expected = page.locator('.contract-payment-comparison-overlay > rect.contract-comparison-panel-reveal').nth(1)
  const thirdRow = await page.locator('.contract-payment-preview-list > li').nth(2).boundingBox()
  const fourthRow = await page.locator('.contract-payment-preview-list > li').nth(3).boundingBox()
  const spotlight = await expected.boundingBox()
  expect(thirdRow && fourthRow && spotlight).toBeTruthy()
  // SVG boundingBox includes the one-pixel outline around its geometric rectangle.
  expect(Math.abs(spotlight!.y + spotlight!.height - Math.min(900, thirdRow!.y + thirdRow!.height + 6))).toBeLessThanOrEqual(1.1)
  expect(spotlight!.y + spotlight!.height).toBeLessThan(fourthRow!.y + fourthRow!.height)
  await page.screenshot({ path: info.outputPath('payment-comparison.png') })
})

for (const sourceBusinessKey of ['SCHEDULE-LINE-GA_TO_FC-7', 'GA-202607-L7']) {
test(`계약별 지급 초안 복원·수정은 기존 식별키를 유지한다: ${sourceBusinessKey}`, async ({ page }) => {
  const contract = { contractId: 3, contractNo: 'C-003', productName: '가상 상품', contractDate: '2026-07-01' }
  const item = { commissionItemId: 1, itemCode: 'BASE_COMMISSION', itemName: '기본수수료', cashflowType: 'PAYMENT' }
  const header = { scheduleHeaderId: 5, activeYn: true, paymentStage: 'GA_TO_FC', scheduleRegime: 'CURRENT', schedulePurpose: 'OPERATIONAL', status: 'CONFIRMED' }
  const line = { scheduleLineId: 7, contractId: 3, installmentNo: 1, beneficiaryAgentId: 2,
    commissionItemId: 1, cashflowType: 'PAYMENT', expectedAmount: 1000,
    attributionDate: '2026-07-01', dueDate: '2026-07-01', lineStatus: 'PLANNED' }
  let payment = { commissionTransactionId: 12, contractId: 3, agentId: 2, commissionItemId: 1,
    paymentStage: 'GA_TO_FC', settlementMonth: '2026-07-01', sourceType: 'GA_MANUAL_PAYMENT',
    sourceBusinessKey, status: 'DRAFT', updatedAt: '2026-07-01T12:00:00',
    amount: 800, cashflowType: 'PAYMENT', scheduledPaymentDate: '2026-07-15', evidenceRef: 'DOC-12',
    attributions: [{ contractId: 3, amount: 800, attributionDate: '2026-07-10' }] }
  const writes: string[] = []
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (request.method() !== 'GET' && !path.includes('/auth/')) writes.push(`${request.method()} ${path}`)
    if (path === '/api/v1/transactions/12' && request.method() === 'PUT') {
      expect(request.postDataJSON().expectedUpdatedAt).toBe(payment.updatedAt)
      expect(request.postDataJSON().sourceBusinessKey).toBe(sourceBusinessKey)
      payment = { ...payment, ...request.postDataJSON(), updatedAt: '2026-07-01T12:01:00' }
    }
    const data = path.endsWith('/auth/refresh') ? { accessToken: 'resume' }
      : path.endsWith('/auth/me') ? { loginId: 'resume', userName: '복원 검증', roleCode: 'SETTLEMENT', canProcess: true, demoMonth: '2026-07' }
      : path.endsWith('/contracts/3/schedules') ? { headers: [header] }
      : path.endsWith('/schedules/5') ? { header, lines: [line] }
      : path.endsWith('/contracts/3/transactions') ? { transactions: [payment], reconciliations: [] }
      : path.endsWith('/transactions/12') ? payment
      : path.endsWith('/contracts/3') ? contract
      : path.endsWith('/contracts') ? { content: [contract], totalPages: 1 }
      : path.includes('/commission-items') ? { content: [item], totalPages: 1 } : { content: [], totalPages: 1 }
    await route.fulfill({ json: { data, error: null, requestId: 'resume' } })
  })
  await page.goto('/transactions/new?month=2026-07&contractId=3')
  await expect(page.getByRole('button', { name: '사전검증', exact: true })).toBeEnabled()
  await expect(page.getByRole('spinbutton', { name: '귀속금액 7' })).toHaveValue('800')
  await page.getByRole('spinbutton', { name: '귀속금액 7' }).fill('900')
  await page.locator('[data-guide-target="payment.batch.save"]').click()
  await expect(page.getByRole('button', { name: '사전검증', exact: true })).toBeEnabled()
  expect(writes).toEqual(['PUT /api/v1/transactions/12'])
  expect(payment.amount).toBe(900)
  await page.reload()
  await expect(page.getByRole('button', { name: '사전검증', exact: true })).toBeEnabled()
  await expect(page.getByRole('spinbutton', { name: '귀속금액 7' })).toHaveValue('900')
  expect(writes).toEqual(['PUT /api/v1/transactions/12'])
})
}
