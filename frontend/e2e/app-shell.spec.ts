import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function session(page: Page, roleCode = 'SETTLEMENT') {
  const envelope = (data: unknown) => ({ data, error: null, requestId: 'shell-fixture' })
  await page.route('**/api/v1/base/**', (route) =>
    route.fulfill({ json: envelope({ content: [], totalPages: 0, totalElements: 0 }) }),
  )
  await page.route('**/api/v1/policies**', (route) => route.fulfill({ json: envelope([]) }))
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ json: envelope({ accessToken: 'shell-fixture', tokenType: 'Bearer', expiresIn: 1800 }) }),
  )
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: envelope({
        loginId: roleCode.toLowerCase(),
        userName: '테스트 사용자',
        roleCode,
        canProcess: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
        canViewAuditLog: ['COMPLIANCE', 'SYSTEM_ADMIN'].includes(roleCode),
        demoMonth: '2026-07',
      }),
    }),
  )
}

for (const role of ['SETTLEMENT', 'COMPLIANCE', 'GA_ADMIN', 'SYSTEM_ADMIN']) {
  test(`${role}: menu flags, collapse and restore`, async ({ page }) => {
    await session(page, role)
    await page.goto('contracts')
    await expect(page.getByRole('heading', { name: '보험계약' })).toBeVisible()
    await page.locator('summary').filter({ hasText: '관리' }).click()
    await expect(page.locator('.sidebar-subnav-link').filter({ hasText: '감사로그' })).toHaveCount(
      ['COMPLIANCE', 'SYSTEM_ADMIN'].includes(role) ? 1 : 0,
    )
    await page.getByRole('button', { name: '사이드바 접기' }).click()
    await page.reload()
    await expect(page.locator('.app-shell')).toHaveClass(/is-sidebar-collapsed/)
    await page.getByRole('button', { name: '사이드바 펼치기' }).click()
    await expect(page.locator('.sidebar-nav-details[open]')).toHaveCount(2)
  })
}

test('eleventh tab is bounded and restored; month updates every tab and removes page', async ({ page }) => {
  await session(page)
  await page.goto('contracts?month=2026-07&page=3&status=ACTIVE')
  for (const path of [
    '/transactions',
    '/schedules',
    '/cap-checks',
    '/arbitrage-checks',
    '/validation-runs',
    '/journals',
    '/reconciliations',
    '/exceptions',
    '/base',
    '/policies',
  ]) {
    await page.evaluate((href) => {
      window.history.pushState({}, '', `/app${href}?month=2026-07&page=3`)
      window.dispatchEvent(new PopStateEvent('popstate'))
    }, path)
    await expect(page.locator('.workspace-tab.is-active')).toHaveCount(1)
    await expect(page).toHaveURL(new RegExp(`${path}\\?`))
  }
  await expect(page.locator('.workspace-tab')).toHaveCount(10)
  await page.reload()
  await expect(page.locator('.workspace-tab')).toHaveCount(10)
  await page.getByRole('button', { name: '기준 정산월 2026-07' }).click()
  await page.getByRole('gridcell', { name: '8월', exact: true }).click()
  await page.getByRole('button', { name: '적용', exact: true }).click()
  await expect(page).toHaveURL(/month=2026-08/)
  const hrefs = await page
    .locator('.workspace-tab-link')
    .evaluateAll((links) => links.map((link) => link.getAttribute('href')))
  expect(hrefs).toHaveLength(10)
  for (const href of hrefs) {
    expect(href).toContain('month=2026-08')
    expect(href).not.toContain('page=')
  }
})

test('unknown route and keyboard month cancellation', async ({ page }) => {
  await session(page)
  await page.goto('unknown')
  await expect(page.getByRole('heading', { name: '404 · 찾을 수 없음' })).toBeVisible()
  const trigger = page.getByRole('button', { name: '기준 정산월 2026-07' })
  await trigger.click()
  await page.getByRole('gridcell', { name: '7월', exact: true }).focus()
  await page.keyboard.press('ArrowRight')
  await expect(page.getByRole('gridcell', { name: '8월', exact: true })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(trigger).toBeFocused()
  await expect(page).toHaveURL(/month=2026-07/)
})
