import { expect, test } from '@playwright/test'
for (const roleCode of ['SETTLEMENT', 'COMPLIANCE', 'GA_ADMIN', 'SYSTEM_ADMIN']) {
  test(`${roleCode}: reference queries preserve URL state and gate policy authoring`, async ({ page }) => {
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
            policyName: longPolicyCode,
            versionNo: 1,
            regulationRefs: [],
          },
        ]
      else if (path === '/api/v1/policies/1')
        data = {
          commissionRules: [{ commissionRuleId: 1, ratePct: '650.123456', fixedAmount: -1234 }],
          capRuleSets: [],
          refundRateTables: [],
          references: [{ ref: 'REG-08', registryTitle: '테스트 규정', detailTitle: '근거 설명 확인', explanation: '이 정책 버전에 저장된 설명' }],
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
    await expect(page.locator('.pagination-controls [aria-current="page"]')).toHaveText('2')
    await page.getByRole('button', { name: '조회', exact: true }).click()
    await expect(page).not.toHaveURL(/page=/)
    expect(requests.some((p) => p.includes('asOf=2026-07-11') && p.includes('page=2'))).toBe(true)
    await page.goto('policies?month=2026-08&tab=commission&policyVersionId=1')
    await expect(page.getByRole('tabpanel').getByRole('cell', { name: '650.1', exact: true })).toBeVisible()
    await expect(page.locator('.is-negative-amount')).toHaveText('-1,234')
    await expect(page.locator('.is-negative-amount')).toHaveCSS('color', 'rgb(185, 28, 28)')
    await page.reload()
    await expect(page.getByRole('tab', { name: '수수료 규칙' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tabpanel').getByRole('cell', { name: '650.1', exact: true })).toBeVisible()
    await page.getByRole('tab', { name: '정책 버전' }).click()
    const policyTable = page.getByRole('table', { name: '정책 버전', exact: true })
    const longCode = policyTable.getByText(longPolicyCode, { exact: true })
    await expect(longCode).toBeVisible()
    await expect(longCode).toHaveText(longPolicyCode)
    await expect(policyTable.locator('details, summary')).toHaveCount(0)
    await expect(page.getByRole('radio', { name: '정책 테스트 v1 선택', exact: true })).toBeChecked()
    await expect(policyTable.getByRole('columnheader', { name: '근거', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /근거 표시/ })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '정책 등록', exact: false })).toHaveCount(roleCode === 'COMPLIANCE' ? 0 : 1)
    await expect(page.getByRole('button', { name: /수정|저장|삭제/, exact: true })).toHaveCount(0)
    await page.getByRole('tab', { name: '관리 상세', exact: true }).click()
    await page.getByRole('tab', { name: '근거 자료', exact: true }).click()
    await page.getByRole('button', { name: '초년도 1,200%룰 근거 정보', exact: true }).click()
    await expect(page.getByRole('tooltip')).toContainText('근거 설명 확인')
  })
}
