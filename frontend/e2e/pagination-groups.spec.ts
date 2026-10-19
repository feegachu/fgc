import { expect, test } from '@playwright/test'

const screens = [
  { id: 'base-organization', path: 'base', filter: 'tab=organization&keyword=QA&asOf=2026-07-01' },
  { id: 'base-insurer', path: 'base', filter: 'tab=insurer&keyword=QA&asOf=2026-07-01' },
  { id: 'base-product', path: 'base', filter: 'tab=product&keyword=QA&asOf=2026-07-01&insurerId=1' },
  { id: 'base-agent', path: 'base', filter: 'tab=agent&keyword=QA&asOf=2026-07-01&organizationId=1' },
  { id: 'schedule', path: 'schedules', filter: 'stage=GA_TO_FC&status=PLANNED' },
  { id: 'cap', path: 'cap-checks', filter: 'stage=GA_TO_FC&status=WARNING&contractNo=QA' },
  { id: 'ledger', path: 'journals', filter: 'searched=true&from=2026-07-01&to=2026-07-31&contract=1' },
  { id: 'audit', path: 'audit-logs', filter: 'action=PAYMENT_CONFIRMED&userId=12' },
  { id: 'validation', path: 'validation-runs', filter: 'status=COMPLETED' },
  { id: 'arbitrage', path: 'arbitrage-checks', filter: 'stage=GA_TO_FC&status=CANDIDATE&contractNo=QA' },
]

for (const screen of screens) {
  test(`${screen.id}: five-page groups preserve filters and boundaries`, async ({ page }, testInfo) => {
    const requested: URL[] = []
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.route('**/api/v1/**', async (route) => {
      const url = new URL(route.request().url())
      requested.push(url)
      const paging = {
        content: [], page: Number(url.searchParams.get('page') || 1),
        size: Number(url.searchParams.get('size') || 20),
        totalPages: url.searchParams.get('size') === '100' ? 1 : 12,
        totalElements: url.searchParams.get('size') === '100' ? 1 : 240,
      }
      let data: unknown = paging
      if (url.pathname.endsWith('/auth/refresh')) data = { accessToken: 'pagination-fixture', expiresIn: 1800 }
      else if (url.pathname.endsWith('/auth/me')) data = {
        loginId: 'pagination-qa', userName: '검증 담당자', roleCode: 'SYSTEM_ADMIN', demoMonth: '2026-07',
        canProcess: true, canViewAuditLog: true, canHandleException: true,
      }
      else if (url.pathname.endsWith('/audit-logs/options')) data = {
        users: [{ userId: 12, loginId: 'pagination-qa' }], actionCodes: ['PAYMENT_CONFIRMED'], entityTypes: [],
      }
      else if (url.pathname.endsWith('/base/organizations')) data = {
        ...paging, content: [{ organizationId: 1, organizationCode: 'QA', organizationName: '시연 조직', activeYn: true }],
      }
      else if (url.pathname.endsWith('/base/insurers')) data = {
        ...paging, content: [{ insurerId: 1, insurerCode: 'QA', insurerName: '시연 보험사', activeYn: true }],
      }
      else if (url.pathname.endsWith('/base/products')) data = {
        ...paging, content: [{ productOfferingId: 1, productName: '시연 상품', offeringVersion: 'v1', activeYn: true }],
      }
      else if (url.pathname.endsWith('/base/agents')) data = {
        ...paging, content: [{ agentId: 1, agentCode: 'QA', agentName: '시연 설계사', activeYn: true }],
      }
      else if (url.pathname.endsWith('/journals/accounts')) data = []
      else if (url.pathname.endsWith('/base/commission-items')) data = []
      else if (url.pathname.includes('active-monthly')) data = { exists: false }
      else if (url.pathname.endsWith('/arbitrage-checks')) data = {
        items: paging, summary: { clearCount: 0, candidateCount: 0, reviewRequiredCount: 0 },
      }
      else if (url.pathname.endsWith('/cap-checks')) data = {
        ...paging, summary: { normal: 0, warning: 0, violation: 0, reviewRequired: 0 },
        stageSummary: [], agentSummary: [],
      }
      await route.fulfill({ json: { data, error: null, requestId: 'pagination-557' } })
    })
    const initial = new URLSearchParams(`month=2026-07&${screen.filter}&page=4`)
    await page.goto(`${screen.path}?${initial}`)
    const pagination = page.locator('.pagination-controls')
    await expect(pagination).toBeVisible()
    await page.evaluate(() => document.fonts.ready)
    await pagination.screenshot({ path: testInfo.outputPath(`${screen.id}-desktop.png`) })
    // Assert group behavior.
    await expect(pagination.locator('.pagination-button')).toHaveText([
      'chevron_left', '1', '2', '3', '4', '5', 'chevron_right',
    ])
    await expect(pagination.locator('[aria-current="page"]')).toHaveText('4')
    await expect(pagination.getByRole('button', { name: '이전 5페이지' })).toBeDisabled()
    await expect(pagination.getByRole('button', { name: '처음', exact: true })).toHaveCount(0)
    await expect(pagination.getByRole('button', { name: '마지막', exact: true })).toHaveCount(0)
    const move = async (label: string, target: number) => {
      await pagination.getByRole('button', { name: label, exact: true }).click()
      await expect(pagination.locator('[aria-current="page"]')).toHaveText(String(target))
      const params = new URL(page.url()).searchParams
      expect(params.get('page')).toBe(String(target))
      for (const [key, value] of initial) if (key !== 'page') expect(params.get(key)).toBe(value)
      await expect.poll(() => requested.some((url) => url.searchParams.get('page') === String(target))).toBe(true)
    }
    await move('다음 5페이지', 6)
    await expect(pagination.locator('.pagination-button')).toHaveText([
      'chevron_left', '6', '7', '8', '9', '10', 'chevron_right',
    ])
    await move('이전 5페이지', 5)
    await move('3', 3)
    await move('다음 5페이지', 6)
    await move('다음 5페이지', 11)
    await expect(pagination.locator('.pagination-button')).toHaveText(['chevron_left', '11', '12', 'chevron_right'])
    await expect(pagination.getByRole('button', { name: '다음 5페이지' })).toBeDisabled()
    await move('이전 5페이지', 10)
    await page.setViewportSize({ width: 375, height: 900 })
    await pagination.scrollIntoViewIfNeeded()
    const bounds = await pagination.boundingBox()
    expect(bounds).not.toBeNull()
    expect(bounds!.x).toBeGreaterThanOrEqual(0)
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(375)
    await pagination.screenshot({ path: testInfo.outputPath(`${screen.id}-mobile.png`) })
  })
}
