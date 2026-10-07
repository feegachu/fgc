import { expect, test } from '@playwright/test'
for (const roleCode of ['SETTLEMENT', 'COMPLIANCE', 'GA_ADMIN', 'SYSTEM_ADMIN']) {
  test(`${roleCode}: reference screens are read-only and restore URL state`, async ({ page }) => {
    const requests: string[] = []
    const longPolicyCode = 'POLICY-' + 'LONG-CODE-'.repeat(12)
    await page.route('**/api/v1/**', (route) => {
      const url = new URL(route.request().url())
      const path = url.pathname
      requests.push(url.pathname + url.search)
      let data: unknown
      if (path.endsWith('/refresh')) data = { accessToken: 'reference-fixture', tokenType: 'Bearer', expiresIn: 1800 }
      else if (path.endsWith('/me'))
        data = {
          loginId: roleCode,
          userName: '테스트 사용자',
          roleCode,
          canProcess: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
          canViewAuditLog: ['COMPLIANCE', 'SYSTEM_ADMIN'].includes(roleCode),
          demoMonth: '2026-07',
        }
      else if (path === '/api/v1/policies')
        data = [
          {
            policyVersionId: 1,
            policyCode: 'POL-1',
            policyName: '정책 테스트',
            versionNo: 1,
            regulationRefs: ['REG-08'],
          },
          {
            policyVersionId: 2,
            policyCode: longPolicyCode,
            policyName: '긴 코드 정책',
            versionNo: 1,
            regulationRefs: [],
          },
        ]
      else if (path === '/api/v1/policies/1')
        data = {
          commissionRules: [{ commissionRuleId: 1, ratePct: '650.123456', fixedAmount: -1234 }],
          capRuleSets: [],
          refundRateTables: [],
        }
      else
        data = {
          content: [{ organizationId: 1, organizationCode: 'ORG-1', organizationName: '서울지사', activeYn: true }],
          page: Number(url.searchParams.get('page') ?? 1),
          totalPages: 3,
          totalElements: 42,
        }
      return route.fulfill({ json: { data, error: null, requestId: 'reference-e2e' } })
    })
    await page.goto('base?month=2026-08&tab=organization&keyword=서울&asOf=2026-07-11&page=2')
    await expect(page.getByRole('table', { name: '조직', exact: true })).toContainText('서울지사')
    await expect(page.getByLabel('조직 검색')).toHaveValue('서울')
    await expect(page.getByLabel('기준일')).toHaveValue('2026-07-11')
    await page.reload()
    await expect(page.getByRole('button', { name: '2', exact: true })).toHaveAttribute('aria-current', 'page')
    await page.getByRole('button', { name: '조회', exact: true }).click()
    await expect(page).not.toHaveURL(/page=/)
    expect(requests.some((p) => p.includes('asOf=2026-07-11') && p.includes('page=2'))).toBe(true)
    await page.goto('policies?month=2026-08&tab=commission&policyVersionId=1')
    await expect(page.getByRole('tabpanel')).toContainText('650.1234')
    await expect(page.locator('.is-negative-amount')).toHaveText('(1,234)')
    await page.reload()
    await expect(page.getByRole('tab', { name: '수수료 규칙' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tabpanel')).toContainText('650.1234')
    await page.getByRole('tab', { name: '정책 버전' }).click()
    const disclosure = page.locator('.policy-version-table details:not([hidden])').first()
    await expect(disclosure).toBeVisible()
    await disclosure.locator('summary').click()
    await expect(disclosure.locator('.table-cell-full')).toHaveText(longPolicyCode)
    await expect(page.getByRole('radio', { name: 'POL-1 v1 선택', exact: true })).toBeChecked()
    await disclosure.locator('summary').click()
    await page.getByRole('button', { name: 'REG-08' }).click()
    await expect(page.getByRole('tooltip')).toContainText('REG-08')
    await expect(page.getByRole('button', { name: /등록|수정|저장|삭제/, exact: true })).toHaveCount(0)
  })
}
