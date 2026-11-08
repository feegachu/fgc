import { test, expect } from '@playwright/test'

test('지급 등록 비교 안내는 앞 3개 행을 강조하고 안내 카드를 아래에 둔다', async ({ page }, info) => {
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
    const row = await page.locator('.contract-payment-table tbody > tr').nth(2).boundingBox()
    return !!coach && !!row && coach.y >= row.y + row.height && coach.y + coach.height <= 900
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
