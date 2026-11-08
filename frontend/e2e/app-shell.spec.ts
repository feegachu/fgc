import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function session(page: Page, roleCode = 'SETTLEMENT') {
  const envelope = (data: unknown) => ({ data, error: null, requestId: 'shell-fixture' })
  // Shell scenarios must not depend on an unmocked domain API or a developer's backend.
  // Later, specific fixtures take precedence over this unavailable-service response.
  await page.route('**/api/v1/**', (route) =>
    route.fulfill({
      status: 503,
      json: { data: null, error: { code: 'E2E-DOMAIN-UNAVAILABLE' }, requestId: 'shell-fixture' },
    }),
  )
  await page.route('**/api/v1/base/**', (route) =>
    route.fulfill({ json: envelope({ content: [], totalPages: 0, totalElements: 0 }) }),
  )
  await page.route('**/api/v1/policies**', (route) => route.fulfill({ json: envelope([]) }))
  await page.route('**/api/v1/contracts?**', (route) =>
    route.fulfill({ json: envelope({ content: [], totalPages: 0, totalElements: 0 }) }),
  )
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
    await expect(page.getByRole('heading', { name: '보험계약 목록' })).toBeVisible()
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

test('read-only role finishes screen help where the write action is not available', async ({ page }) => {
  await session(page, 'COMPLIANCE')
  const empty = { data: { content: [], totalPages: 0, totalElements: 0 }, error: null, requestId: 'shell-fixture' }
  for (const path of ['validation-runs', 'reconciliation', 'reconciliations', 'exceptions'])
    await page.route(`**/api/v1/${path}**`, (route) => route.fulfill({ json: empty }))
  for (const [path, steps] of [['contracts', 1], ['validation-runs', 1], ['reconciliations', 3], ['exceptions', 2]] as const) {
    // 기준월 없이 열면 셸이 주소에 month를 붙이면서 도움말을 새로 시작해, 그보다 먼저 연 안내가 닫힌다.
    await page.goto(`${path}?month=2026-07`)
    await page.getByRole('button', { name: '도움말', exact: true }).first().click()
    for (let step = 0; step < steps; step++) await page.getByRole('button', { name: '설명 확인 · 다음' }).click()
    await expect(page.getByRole('heading', { name: '화면 사용법 안내 완료' })).toBeVisible()
    await page.getByRole('button', { name: '안내 마치기' }).click()
  }
})

test('eleventh tab is bounded and restored; month updates every tab and removes page', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 850 })
  await session(page)
  await page.goto('contracts?month=2026-07&page=3&status=ACTIVE')
  await expect(page.locator('.workspace-tab.is-active .workspace-tab-link')).toHaveAttribute(
    'href',
    /^\/contracts\?/,
  )
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
      window.history.pushState({}, '', `${href}?month=2026-07&page=3`)
      window.dispatchEvent(new PopStateEvent('popstate'))
    }, path)
    await expect(page.locator('.workspace-tab.is-active .workspace-tab-link')).toHaveAttribute(
      'href',
      new RegExp('^' + path + '[?]'),
    )
    await expect(page.locator('.workspace-tab.is-active')).toHaveCount(1)
    await expect(page).toHaveURL(new RegExp(path + '[?]'))
  }
  await expect(page.locator('.workspace-tab')).toHaveCount(10)
  await page.reload()
  await expect(page.locator('.workspace-tab')).toHaveCount(10)
  const firstTab = page.getByRole('button', { name: '첫 번째 업무 탭으로 이동' })
  const lastTab = page.getByRole('button', { name: '마지막 업무 탭으로 이동' })
  await firstTab.click()
  await expect(firstTab).toBeDisabled()
  await expect(lastTab).toBeEnabled()
  await lastTab.click()
  await expect(lastTab).toBeDisabled()
  await expect(firstTab).toBeEnabled()

  const tab = await page.locator('.workspace-tab.is-active').boundingBox()
  const month = await page.getByRole('button', { name: '기준 정산월 2026-07' }).boundingBox()
  expect(tab?.height).toBe(month?.height)
  expect(tab?.y).toBe(month?.y)

  await page.getByRole('button', { name: '기준 정산월 2026-07' }).click()
  await page.getByRole('gridcell', { name: '8월', exact: true }).click()
  await page.getByRole('button', { name: '적용', exact: true }).click()
  await expect(page).toHaveURL(/month=2026-08/)
  const links = page.locator('.workspace-tab-link')
  await expect(links).toHaveCount(10)
  // URL 변경 후 셸의 effect가 모든 탭에 기준월을 반영할 때까지 기다린다.
  for (let index = 0; index < 10; index += 1) {
    await expect(links.nth(index)).toHaveAttribute('href', /[?&]month=2026-08(?:&|$)/)
    await expect(links.nth(index)).not.toHaveAttribute('href', /[?&]page=/)
  }
})

test('unknown route and keyboard month cancellation', async ({ page }) => {
  await session(page)
  await page.goto('unknown')
  await expect(page.getByRole('heading', { name: '찾을 수 없음' })).toBeVisible()
  const trigger = page.getByRole('button', { name: '기준 정산월 2026-07' })
  await trigger.click()
  await page.getByRole('gridcell', { name: '7월', exact: true }).focus()
  await page.keyboard.press('ArrowRight')
  await expect(page.getByRole('gridcell', { name: '8월', exact: true })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(trigger).toBeFocused()
  await expect(page).toHaveURL(/month=2026-07/)
})
