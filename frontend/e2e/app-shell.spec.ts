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
    const card = page.locator('.screen-step-guide')
    let visited = 0
    for (; visited < 20; visited++) {
      await expect(card.getByRole('heading')).toBeVisible()
      const finish = card.getByRole('button', { name: '안내 마치기', exact: true })
      if (await finish.count()) { await finish.click(); break }
      const next = card.getByRole('button', { name: '다음', exact: true })
      if (!await next.count()) {
        await expect(card.getByRole('button', { name: '강조된 메뉴를 눌러주세요' })).toBeDisabled()
        await card.getByRole('button', { name: '종료', exact: true }).click()
        break
      }
      await next.click()
    }
    expect(visited + 1).toBeGreaterThanOrEqual(steps)
    await expect(card).toHaveCount(0)
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


test.describe('탭 경계 정렬', () => {
  test.use({ deviceScaleFactor: 1.25 })
  test('125% 배율에서도 탭을 통째로 맞추고 이동 및 리사이즈 후 닫기 버튼이 잘리지 않는다', async ({ page }) => {
    test.setTimeout(60000)
    await session(page, 'SYSTEM_ADMIN')
    await page.setViewportSize({ width: 1536, height: 864 })
    await page.goto('contracts?month=2026-07')
    const aligned = async () => page.locator('.workspace-tabs').evaluate(rail => {
      const box = rail.getBoundingClientRect()
      const tabs = [...rail.querySelectorAll<HTMLElement>('.workspace-tab')].map(tab => tab.getBoundingClientRect())
      const width = tabs[0]?.width ?? 0
      const visible = tabs.filter(tab => Math.min(tab.right, box.right) - Math.max(tab.left, box.left) > 1)
      return width > 0 && Math.abs(box.width / width - Math.round(box.width / width)) < 0.01
        && tabs.every(tab => Math.abs(tab.width - width) < 0.1)
        && visible.length > 0 && visible.every(tab => tab.left >= box.left - 1 && tab.right <= box.right + 1)
    })
    for (const path of ['/transactions', '/schedules', '/cap-checks', '/journals', '/reconciliations', '/exceptions', '/base', '/policies']) {
      await page.evaluate(href => {
        window.history.pushState({}, '', `${href}?month=2026-07`)
        window.dispatchEvent(new PopStateEvent('popstate'))
      }, path)
      await expect(page.locator('.workspace-tab.is-active a')).toHaveAttribute('href', new RegExp(`^${path}[?]`))
      await expect.poll(aligned).toBe(true)
    }
    for (const width of [1536, 1280, 1097, 1440]) {
      await page.setViewportSize({ width, height: 864 })
      await expect.poll(aligned).toBe(true)
      await page.getByRole('button', { name: '첫 번째 업무 탭으로 이동' }).click()
      await expect(page.getByRole('button', { name: '첫 번째 업무 탭으로 이동' })).toBeDisabled()
      await expect.poll(aligned).toBe(true)
      await page.getByRole('button', { name: '마지막 업무 탭으로 이동' }).click()
      await expect(page.getByRole('button', { name: '마지막 업무 탭으로 이동' })).toBeDisabled()
      await expect.poll(aligned).toBe(true)
    }
    await page.locator('.workspace-tabs').evaluate(rail => { rail.scrollLeft = rail.querySelector('.workspace-tab')!.getBoundingClientRect().width * 1.6 })
    await expect.poll(aligned).toBe(true)
    await page.getByRole('button', { name: '마지막 업무 탭으로 이동' }).click()
    await page.locator('.workspace-tab.is-active .workspace-tab-close').click()
    await expect.poll(aligned).toBe(true)
    await page.screenshot({ path: 'test-results/workspace-fitted-tabs.png' })
  })
})
