import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { dashboardWalkthrough } from '../src/features/dashboard/dashboard-walkthrough'

const envelope = (data: unknown) => ({ data, error: null, requestId: 'dashboard-fixture' })
const unavailable = {
  status: 500,
  json: { data: null, error: { code: 'FGC-COMMON-500', message: '처리 중 오류가 발생했습니다.' }, requestId: 'dashboard-fixture' },
}
const branches = {
  month: '2026-07-01', asOf: '2026-07-31',
  branches: Array.from({ length: 12 }, (_, index) => ({
    organizationId: index + 1, name: `가상지점 ${String(index + 1).padStart(2, '0')}`,
    registeredAgents: 46, activeAgents: 42, newContracts: 20, validContracts: 1000,
    gaAmount: 9000000 - index * 100000, agentAmount: 6000000, openExceptions: index === 0 ? 10 : 0,
  })),
}
const commissions = { months: [
  { month: '2026-06-01', gaAmount: 12000000, agentAmount: 8000000 },
  { month: '2026-07-01', gaAmount: 15000000, agentAmount: 10000000 },
] }

async function session(page: Page, overrides: { branches?: boolean; counts?: boolean; emptyCommissions?: boolean } = {}) {
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path.endsWith('/auth/refresh'))
      return route.fulfill({ json: envelope({ accessToken: 'dashboard-fixture', tokenType: 'Bearer', expiresIn: 1800 }) })
    if (path.endsWith('/auth/me'))
      return route.fulfill({ json: envelope({ loginId: 'settle01', userName: '정산 담당자', roleCode: 'SETTLEMENT', canProcess: true, demoMonth: '2026-07' }) })
    if (path.endsWith('/dashboard/summary'))
      return route.fulfill({ json: envelope({
        month: '2026-07-01', capViolation: 3, capWarning: 2, arbitrageCandidate: 6,
        reconMismatch: 29, journalImbalance: 0, openException: 41,
        recentExceptions: [1, 2, 3].map(id => ({
          exceptionCaseId: id, severity: 'HIGH', severityLabel: '높음', exceptionType: 'CAP_VIOLATION',
          contractNo: `C-${id}`, title: `예외-${id}`, status: 'NEW', statusLabel: '신규', createdAt: '2026-07-10T15:30:00Z',
        })), recentRuns: [],
      }) })
    if (path.endsWith('/exceptions/counts'))
      return route.fulfill(overrides.counts === false ? unavailable : { json: envelope({ total: 41, bySeverity: [], byType: [] }) })
    if (path.endsWith('/dashboard/branches'))
      return route.fulfill(overrides.branches === false ? unavailable : { json: envelope(branches) })
    if (path.endsWith('/dashboard/monthly-commissions'))
      return route.fulfill({ json: envelope(overrides.emptyCommissions ? { months: [] } : commissions) })
    if (path.endsWith('/dashboard/branch-details')) {
      const current = Number(url.searchParams.get('page'))
      return route.fulfill({ json: envelope({
        content: [{ id: current, label: `설계사-${current}`, detail: `A-00${current}`, status: 'ACTIVE' }],
        page: current, size: 10, totalPages: 2, totalElements: 11,
      }) })
    }
    return route.fulfill({ json: envelope({ content: [], page: 1, size: 20, totalElements: 0, totalPages: 0 }) })
  })
}

test('관리자 최종 화면은 이전 설정과 관계없이 두 막대 차트와 KPI를 표시한다', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('fgc.ui-revision.v1', 'before'))
  await session(page)
  await page.goto('?month=2026-07')
  await expect(page.getByRole('region', { name: '주요 검증 지표' }).getByRole('link')).toHaveCount(6)
  await expect(page.getByRole('button', { name: /변경 전|변경 후/ })).toHaveCount(0)
  const monthly = page.getByRole('region', { name: '월별 수수료' })
  await expect(monthly.getByText('15,000,000원', { exact: true })).toBeVisible()
  await expect(monthly.getByText('전월 대비 25.0% 증가')).toHaveCount(2)
  const region = page.getByRole('region', { name: '지점별 현황' })
  await expect(region.getByRole('button', { name: /수수료 상세 보기/ })).toHaveCount(12)
  await expect(page.getByRole('heading', { name: '검증 항목별 건수 비중' })).toBeVisible()
  await expect(page.getByRole('link', { name: '보험GA협회 홈페이지 열기 (새 탭)' })).toHaveAttribute('href', 'https://www.igaa.or.kr/')
  const kpiBox = await page.getByRole('region', { name: '주요 검증 지표' }).boundingBox()
  const branchBox = await region.boundingBox()
  expect(kpiBox!.y).toBeLessThan(branchBox!.y)
  const table = page.getByRole('table', { name: '최근 예외 목록' })
  await expect(table.getByRole('row')).toHaveCount(3)
  await expect(table.getByRole('link')).toHaveText(['예외-1', '예외-2'])
  await expect(table.getByRole('link')).toHaveText(['예외-2', '예외-3'], { timeout: 7000 })
  expect(await table.locator('..').evaluate(el => el.scrollHeight - el.clientHeight)).toBe(0)
})

test('사이드바의 세 도움말 버전을 전환하고 v2에서 번호별 화면 설명을 확인한다', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 })
  await session(page)
  await page.goto('?month=2026-07')

  const sidebar = page.getByRole('complementary').filter({ has: page.getByRole('navigation', { name: '주요 업무 메뉴' }) })
  const comparison = sidebar.getByRole('radiogroup', { name: '도움말 비교' })
  const before = comparison.getByRole('radio', { name: '수정 전' })
  const after = comparison.getByRole('radio', { name: '수정 후', exact: true })
  const afterV2 = comparison.getByRole('radio', { name: '수정 후 v2', exact: true })
  const help = page.getByRole('button', { name: '도움말', exact: true })
  const dialog = page.getByRole('dialog', { name: '업무 대시보드 도움말' })
  const image = dialog.getByRole('img', { name: '업무 대시보드 화면 예시' })
  const legend = dialog.getByRole('list', { name: '번호별 화면 설명' })
  const walkthrough = page.getByRole('region', { name: '업무 대시보드 도움말' })

  await expect(comparison.getByRole('radio')).toHaveCount(3)
  await expect(afterV2).toBeChecked()
  await page.screenshot({ path: 'test-results/help-three-modes-desktop.png' })
  await help.click()
  await expect(dialog.getByRole('radiogroup')).toHaveCount(0)
  await expect(image).toBeVisible()
  await expect(legend.getByRole('listitem')).toHaveCount(8)
  const imageBox = await image.boundingBox()
  const legendBox = await legend.boundingBox()
  expect(legendBox!.x).toBeGreaterThan(imageBox!.x + imageBox!.width)
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(help).toBeFocused()

  await afterV2.focus()
  await page.keyboard.press('ArrowLeft')
  await expect(after).toBeChecked()
  await help.click()
  await expect(dialog).toHaveCount(0)
  await expect(walkthrough.getByRole('button', { name: '다음', exact: true })).toBeEnabled()
  await expect(walkthrough.getByRole('heading', { name: '기준 정산월', exact: true })).toBeVisible()
  await after.focus()
  await page.keyboard.press('ArrowLeft')
  await expect(before).toBeChecked()
  await expect(walkthrough).toHaveCount(0)
  await help.click()
  await expect(dialog).toHaveCount(0)
  await expect(walkthrough.getByRole('button', { name: '설명 확인 · 다음' })).toBeEnabled()
  await walkthrough.getByRole('button', { name: '종료' }).click()

  await before.focus()
  await page.keyboard.press('ArrowRight')
  await expect(after).toBeChecked()
  await page.keyboard.press('ArrowRight')
  await expect(afterV2).toBeChecked()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'test-results/help-three-modes-mobile.png' })
  await help.click()
  await expect(dialog).toBeVisible()
  const mobileImage = await image.boundingBox()
  const mobileLegend = await legend.boundingBox()
  expect(mobileLegend!.y).toBeGreaterThan(mobileImage!.y + mobileImage!.height)
})

for (const width of [1440, 390]) {
  test(`수정 후 대시보드 도움말은 11단계와 두 줄 설명으로 영역과 아이콘을 짚는다 (${width})`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 })
    await session(page)
    await page.goto('?month=2026-07')
    const writes: string[] = []
    page.on('request', request => {
      if (/POST|PUT|PATCH|DELETE/.test(request.method()) && !request.url().includes('/auth/')) writes.push(request.url())
    })
    await page.getByRole('radio', { name: '수정 후 v2', exact: true }).focus()
    await page.keyboard.press('ArrowLeft')
    const trigger = page.getByRole('button', { name: '도움말', exact: true })
    await trigger.click()
    const guide = page.getByRole('region', { name: '업무 대시보드 도움말' })
    const spotlight = page.locator('.screen-step-spotlight')
    expect(dashboardWalkthrough).toHaveLength(11)
    await expect(spotlight).toHaveCSS('transition-duration', '0.7s, 0.7s, 0.7s, 0.7s')
    for (const [index, step] of dashboardWalkthrough.entries()) {
      await expect(guide.getByRole('heading', { name: step.title, exact: true })).toBeVisible()
      await expect(guide).toHaveAttribute('data-help-target', step.targetId)
      await expect(guide.locator('.screen-step-summary > span')).toHaveCount(2)
      await expect(guide.locator('.screen-step-details, .screen-step-interaction, .screen-step-context')).toHaveCount(0)
      for (const line of await guide.locator('.screen-step-summary > span').all()) {
        expect(await line.evaluate(element => element.getBoundingClientRect().height <= parseFloat(getComputedStyle(element).lineHeight) + 1)).toBe(true)
      }
      const next = guide.getByRole('button', { name: index === dashboardWalkthrough.length - 1 ? '안내 마치기' : '다음', exact: true })
      await expect(next).toBeEnabled()
      await expect(page.getByRole('dialog')).toHaveCount(0)
      const target = page.locator(`[${step.attribute ?? 'data-guide-target'}="${step.targetId}"]`)
      await expect(target).toHaveCount(1)
      const highlightMatches = async () => {
        const box = await spotlight.boundingBox(), element = await target.boundingBox()
        const main = await page.locator('#main-content').boundingBox()
        const header = await page.locator('.app-header').boundingBox()
        const topBoundary = step.scope === 'shell' ? 0 : (header!.y + header!.height + 8)
        return Boolean(box && element && box.width > 0 && box.height > 0
          && Math.abs(box.x - Math.max(main!.x, element.x - 10)) < 2
          && Math.abs(box.y - Math.max(topBoundary, element.y - 10)) < 2)
      }
      await expect.poll(highlightMatches).toBe(true)
      if (index === 1) {
        await guide.getByRole('button', { name: '이전', exact: true }).click()
        await expect(guide.getByRole('heading', { name: '기준 정산월', exact: true })).toBeVisible()
        await guide.getByRole('button', { name: '다음', exact: true }).click()
        await expect(guide.getByRole('heading', { name: step.title, exact: true })).toBeVisible()
        await expect.poll(highlightMatches).toBe(true)
        await page.screenshot({ path: `test-results/dashboard-walkthrough-${width}.png` })
      }
      if (step.targetId.startsWith('dashboard.chart-view.')) {
        const label = await target.getAttribute('aria-label')
        await target.click()
        await expect(target).toHaveAttribute('aria-pressed', 'true')
        await expect(page.getByRole('list', { name: `지점별 ${label}`, exact: true })).toHaveCount(1)
        await expect(guide.getByRole('heading', { name: step.title, exact: true })).toBeVisible()
        await expect.poll(highlightMatches).toBe(true)
        await page.screenshot({ path: `test-results/dashboard-${step.targetId.split('.').at(-1)}-${width}.png` })
      }
      if (step.targetId === 'dashboard.chart-view.branch-agents' && width === 1440) {
        await page.getByRole('button', { name: '가상지점 01 설계사 상세 보기' }).click()
        const detail = page.getByRole('dialog', { name: '가상지점 01 · 설계사 상세' })
        await expect(detail).toBeVisible()
        await expect(guide).toHaveCount(0)
        await page.keyboard.press('Escape')
        await expect(guide.getByRole('heading', { name: step.title, exact: true })).toBeVisible()
      }
      await next.click()
    }
    await expect(guide).toHaveCount(0)
    await expect(trigger).toBeFocused()
    expect(writes).toEqual([])
    expect(new URL(page.url()).pathname).toBe('/')
  })
}

test('월별 자료가 없어도 설명을 계속 읽고 기준월 변경 시 도움말을 닫는다', async ({ page }) => {
  await session(page, { emptyCommissions: true })
  await page.goto('?month=2026-07')
  await page.getByRole('radio', { name: '수정 후 v2', exact: true }).focus()
  await page.keyboard.press('ArrowLeft')
  const trigger = page.getByRole('button', { name: '도움말', exact: true })
  await trigger.click()
  const guide = page.getByRole('region', { name: '업무 대시보드 도움말' })
  for (let index = 0; index < 2; index++) {
    await expect(guide.getByRole('button', { name: '다음', exact: true })).toBeEnabled()
    await guide.getByRole('button', { name: '다음', exact: true }).click()
  }
  await expect(guide.getByRole('heading', { name: '월별 수수료', exact: true })).toBeVisible()
  await expect(page.locator('[data-guide-target="dashboard.commissions.chart"]')).toHaveText('표시할 월별 수수료가 없습니다.')
  await expect(guide.getByRole('button', { name: '다음', exact: true })).toBeEnabled()
  await page.keyboard.press('Escape')
  await expect(trigger).toBeFocused()
  await trigger.click()
  await page.getByRole('button', { name: '기준 정산월 2026-07', exact: true }).click()
  await page.getByRole('gridcell', { name: '8월', exact: true }).click()
  await page.getByRole('button', { name: '적용', exact: true }).click()
  await expect(guide).toHaveCount(0)
  await expect(page.getByRole('button', { name: '기준 정산월 2026-08', exact: true })).toBeVisible()
})

test('지점 조회 오류는 해당 영역에서 재시도하고 다른 지표를 유지한다', async ({ page }) => {
  const state = { branches: false, counts: false }
  await session(page, state)
  await page.goto('?month=2026-07')
  const region = page.getByRole('region', { name: '지점별 현황' })
  await expect(region.getByRole('alert')).toContainText('처리 중 오류가 발생했습니다.')
  await expect(region.getByRole('button', { name: /상세 보기/ })).toHaveCount(0)
  const kpis = page.getByRole('region', { name: '주요 검증 지표' })
  await expect(kpis.getByRole('link', { name: '1,200% 위반 3건' })).toBeVisible()
  await expect(kpis.getByRole('button', { name: '미처리 예외 다시 불러오기' })).toBeVisible()
  await expect(kpis.getByRole('link', { name: /미처리 예외/ })).toHaveCount(0)
  state.branches = true
  await region.getByRole('button', { name: '다시 시도' }).click()
  await expect(region.getByRole('button', { name: /수수료 상세 보기/ })).toHaveCount(12)
})

test('지표와 막대는 키보드로 선택하고 지점 상세 페이지를 넘길 수 있다', async ({ page }) => {
  await session(page)
  await page.goto('?month=2026-07')
  const region = page.getByRole('region', { name: '지점별 현황' })
  await region.getByRole('button', { name: '설계사', exact: true }).focus()
  await page.keyboard.press('Enter')
  const branch = region.getByRole('button', { name: '가상지점 01 설계사 상세 보기' })
  await branch.focus()
  const request = page.waitForRequest('**/dashboard/branch-details?month=2026-07&organizationId=1&metric=AGENTS&page=1')
  await page.keyboard.press('Enter')
  await request
  const dialog = page.getByRole('dialog', { name: '가상지점 01 · 설계사 상세' })
  await expect(dialog.getByText('설계사-1')).toBeVisible()
  await dialog.getByRole('button', { name: '2', exact: true }).click()
  await expect(dialog.getByText('설계사-2')).toBeVisible()
  await expect(dialog.getByRole('group', { name: '지점 상세 페이지 이동' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(branch).toBeFocused()
})

test('지점이 많은 모바일 화면도 페이지가 가로로 넘치지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await session(page)
  await page.goto('?month=2026-07')
  const region = page.getByRole('region', { name: '지점별 현황' })
  await expect(region.getByRole('button', { name: /수수료 상세 보기/ })).toHaveCount(12)
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBe(0)
  await region.getByRole('button', { name: '설계사', exact: true }).click()
  await region.getByRole('button', { name: '가상지점 01 설계사 상세 보기' }).click()
  const dialog = page.getByRole('dialog', { name: '가상지점 01 · 설계사 상세' })
  await expect(dialog.getByText('설계사-1')).toBeVisible()
  const bounds = await dialog.boundingBox()
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390)
})
