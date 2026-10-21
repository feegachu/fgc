import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

const envelope = (data: unknown) => ({ data, error: null, requestId: 'resize-fixture' })

test('판정 태그와 헤더를 최소 너비로 보호하고 과거 저장값과 새 판정도 보정한다', async ({ page }) => {
  let label = '적용대상 아님'
  await page.route('**/api/v1/base/**', (route) => route.fulfill({ json: envelope({ content: [] }) }))
  await page.route('**/api/v1/contracts?**', (route) =>
    route.fulfill({
      json: envelope({
        content: [
          { contractId: 1, contractNo: 'FGC-001', capResultStatus: 'NOT_APPLICABLE', capResultStatusLabel: label },
        ],
        page: 1,
        size: 20,
        totalPages: 1,
        totalElements: 1,
      }),
    }),
  )
  await page.goto('contracts')
  const header = page.getByRole('columnheader', { name: '1,200% 판정', exact: true })
  const handle = page.getByRole('separator', { name: '1,200% 판정 열 너비', exact: true })
  const badge = page.locator('.contract-table .status-badge').filter({ hasText: label })
  await expect(badge).toBeVisible()
  await handle.focus()
  for (let i = 0; i < 16; i++) await page.keyboard.press('Shift+ArrowLeft')
  const width = (await header.boundingBox())!.width
  expect(width).toBeGreaterThan(64)
  const assertFits = async () => {
    await expect
      .poll(() =>
        page
          .locator('.contract-table tbody tr')
          .first()
          .locator('td')
          .nth(7)
          .evaluate((cell) => {
            const tag = cell.querySelector('.status-badge')!
            const box = cell.getBoundingClientRect()
            const badgeBox = tag.getBoundingClientRect()
            const padding = getComputedStyle(cell)
            return (
              badgeBox.left >= box.left + parseFloat(padding.paddingLeft) &&
              badgeBox.right <= box.right - parseFloat(padding.paddingRight)
            )
          }),
      )
      .toBe(true)
    expect(await header.evaluate((cell) => cell.scrollWidth <= cell.clientWidth)).toBe(true)
  }
  await assertFits()
  await drag(page, handle, -300)
  expect((await header.boundingBox())!.width).toBeCloseTo(width, 0)
  // Reproduce a width saved by the old version before the tag minimum existed.
  await page.evaluate(() => {
    const key = Object.keys(localStorage).find(
      (key) => key.startsWith('fgc:layout:') && key.includes('contractNo|insurerName'),
    )!
    const saved = JSON.parse(localStorage.getItem(key)!)
    saved[7] = 64
    localStorage.setItem(key, JSON.stringify(saved))
  })
  await page.reload()
  await expect(badge).toBeVisible()
  await assertFits()
  label = '적용대상 아님 · 추가 확인 필요'
  await page.reload()
  await expect(page.getByText(label, { exact: true })).toBeVisible()
  await assertFits()
  expect((await header.boundingBox())!.width).toBeGreaterThan(width)
})

test('리사이즈 후에도 예외 목록의 지정된 컬럼 정렬을 유지한다', async ({ page }) => {
  const table = page.getByRole('table', { name: '예외 목록' })
  for (const name of [
    '번호',
    '유형',
    '상세 원인',
    '심각도',
    '계약',
    '내용',
    '상태',
    '담당자',
    '최근 검출',
    '검출',
    '참조',
  ]) {
    const alignment = ['유형', '상세 원인', '심각도', '상태', '참조'].includes(name) ? 'center' : 'left'
    const header = table.getByRole('columnheader', { name, exact: true })
    await expect(header).toHaveCSS('text-align', alignment)
    const index = await header.evaluate((element) => (element as HTMLTableCellElement).cellIndex)
    await expect(table.locator('tbody tr').first().locator('td').nth(index)).toHaveCSS('text-align', alignment)
  }
})

for (const [path, label] of [
  ['journals', '분개 목록과 분개 상세 카드 너비'],
  ['arbitrage-checks', '계약별 판정과 상세 카드 너비'],
  ['transactions/new', '지급 건·귀속행과 확정 게이트 카드 너비'],
  ['audit-logs', '감사 기록과 변경 내용 비교 카드 너비'],
]) {
  test(path + ': 카드 드래그·저장·모바일 배치를 지원한다', async ({ page }, testInfo) => {
    await page.route('**/api/v1/**', async (route) => {
      const url = new URL(route.request().url())
      const empty = { content: [], page: 1, size: 20, totalPages: 0, totalElements: 0 }
      let data: unknown = empty
      if (url.pathname.endsWith('/auth/refresh')) data = { accessToken: 'resize-fixture' }
      else if (url.pathname.endsWith('/auth/me'))
        data = {
          loginId: 'fixture',
          userName: '테스트',
          roleCode: 'SYSTEM_ADMIN',
          canProcess: true,
          canViewAuditLog: true,
          demoMonth: '2026-07',
        }
      else if (url.pathname.endsWith('/accounts') || url.pathname.endsWith('/commission-items')) data = []
      else if (url.pathname.endsWith('/audit-logs/options')) data = { entityTypes: [], actions: [], users: [] }
      else if (url.pathname.endsWith('/arbitrage-checks'))
        data = {
          summary: { clearCount: 0, candidateCount: 0, reviewRequiredCount: 0 },
          items: empty,
        }
      await route.fulfill({ json: envelope(data) })
    })
    await page.goto(path)
    const handle = page.getByRole('separator', { name: label, exact: true })
    await expect(handle).toBeVisible()
    const panels = page.locator('.resizable-split-panel')
    const before = (await panels.first().boundingBox())!.width
    await drag(page, handle, -180)
    const width = (await panels.first().boundingBox())!.width
    expect(width).toBeCloseTo(before - 180, 0)
    await page.reload()
    await expect(handle).toBeVisible()
    expect((await panels.first().boundingBox())!.width).toBeCloseTo(width, 0)
    await page.screenshot({ path: testInfo.outputPath('cards.png'), fullPage: true })
    await page.setViewportSize({ width: 1000, height: 900 })
    await expect(handle).toBeHidden()
    const first = (await panels.first().boundingBox())!
    expect((await panels.nth(1).boundingBox())!.y).toBeGreaterThanOrEqual(first.y + first.height)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1000)
  })
}

test('예상 스케줄의 마지막 열이 남는 너비를 채우고 앞쪽 열만 늘리면 그만큼 줄어든다', async ({ page }, testInfo) => {
  await page.route('**/api/v1/schedules?**', (route) =>
    route.fulfill({
      json: envelope({
        content: [
          {
            scheduleHeaderId: 1,
            contractNo: 'FGC-001',
            paymentStage: 'GA_TO_FC',
            scheduleRegime: 'CURRENT',
            schedulePurpose: 'OPERATIONAL',
            scheduleVersionNo: 1,
            status: 'PLANNED',
            activeYn: true,
            lineCount: 36,
            expectedTotal: 895000,
            policyVersionLabel: 'GA-CUR-2026-V1 v1',
          },
        ],
        page: 1,
        totalPages: 1,
        totalElements: 1,
      }),
    }),
  )
  await page.goto('schedules')
  const table = page.getByRole('table').first()
  await expect(table.getByRole('link', { name: 'FGC-001 회차 보기' })).toBeVisible()
  const alignments = ['left', 'left', 'center', 'center', 'left', 'center', 'center', 'left', 'right', 'left', 'left']
  for (const [index, alignment] of alignments.entries()) {
    await expect(table.getByRole('columnheader').nth(index)).toHaveCSS('text-align', alignment)
    await expect(table.locator('tbody tr').first().locator('td').nth(index)).toHaveCSS('text-align', alignment)
  }
  const viewport = table.locator('..')
  const handles = viewport.getByRole('separator')
  for (let i = 0; i < (await handles.count()); i++) {
    await handles.nth(i).focus()
    for (let step = 0; step < 16; step++) await page.keyboard.press('Shift+ArrowLeft')
  }
  const headers = table.getByRole('columnheader')
  const first = headers.first()
  const last = headers.last()
  const lastBefore = (await last.boundingBox())!.width
  const assertFilled = async () => {
    const right = await last.boundingBox()
    const box = await viewport.boundingBox()
    expect(right!.x + right!.width).toBeCloseTo(box!.x + box!.width, 0)
    expect((await first.boundingBox())!.width).toBeCloseTo(64, 0)
  }
  await assertFilled()
  expect(lastBefore).toBeGreaterThan(200)
  await drag(page, handles.first(), 80)
  expect((await first.boundingBox())!.width).toBeCloseTo(144, 0)
  expect((await last.boundingBox())!.width).toBeCloseTo(lastBefore - 80, 0)
  await handles.first().focus()
  await page.keyboard.press('Shift+ArrowLeft')
  await assertFilled()
  await page.reload()
  await expect(table).toBeVisible()
  await assertFilled()
  await page.setViewportSize({ width: 1600, height: 1000 })
  await assertFilled()
  await page.screenshot({ path: testInfo.outputPath('last-column-fill.png'), fullPage: true })
})

async function fixture(page: Page) {
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ json: envelope({ accessToken: 'resize-fixture', tokenType: 'Bearer', expiresIn: 1800 }) }),
  )
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: envelope({
        loginId: 'settle01',
        userName: '정산 담당자',
        roleCode: 'SETTLEMENT',
        canProcess: true,
        canHandleException: true,
        demoMonth: '2026-07',
      }),
    }),
  )
  await page.route('**/api/v1/exceptions/options', (route) =>
    route.fulfill({ json: envelope({ types: [], reasons: [], severities: [], assignees: [], validationMonths: [] }) }),
  )
  await page.route('**/api/v1/exceptions?**', (route) =>
    route.fulfill({
      json: envelope({
        summary: [{ type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', count: 1 }],
        content: [
          {
            exceptionCaseId: 11,
            type: 'CAP_VIOLATION',
            typeLabel: '1,200% 위반',
            severity: 'HIGH',
            severityLabel: '높음',
            status: 'NEW',
            statusLabel: '신규',
            title: '1,200% 한도 초과 계약의 긴 검증 사유와 상세 내용',
            reasonLabel: '한도 초과',
            contractNo: 'FGC-FGL02-202603-0475',
            detectionCount: 1,
            sourceEntityType: 'CAP_CHECK',
            sourceEntityId: 173,
            occurrences: [],
            actions: [],
          },
        ],
        page: 1,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      }),
    }),
  )
}

async function drag(page: Page, handle: Locator, delta: number) {
  await handle.scrollIntoViewIfNeeded()
  const box = (await handle.boundingBox())!
  const x = box.x + box.width / 2
  const y = box.y + Math.min(box.height / 2, 30)
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x + delta, y, { steps: 12 })
  await page.mouse.up()
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 })
  await fixture(page)
  await page.goto('exceptions')
  await expect(page.getByRole('table', { name: '예외 목록' })).toBeVisible()
})

test('카드 경계를 드래그하고 새로고침·키보드·복원을 지원한다', async ({ page }, testInfo) => {
  const handle = page.getByRole('separator', { name: '예외 목록과 상세 카드 너비' })
  const left = page.locator('.resizable-split-panel').first()
  const before = (await left.boundingBox())!.width
  await drag(page, handle, 180)
  const resized = (await left.boundingBox())!.width
  expect(resized).toBeGreaterThan(before + 150)
  await page.reload()
  await expect(handle).toBeVisible()
  expect((await left.boundingBox())!.width).toBeCloseTo(resized, 0)
  await handle.focus()
  await page.keyboard.press('ArrowLeft')
  expect((await left.boundingBox())!.width).toBeLessThan(resized)
  await page.keyboard.press('Home')
  expect((await left.boundingBox())!.width).toBeCloseTo(before, 0)
  await drag(page, handle, -1500)
  await expect(handle).toHaveAttribute('aria-valuenow', '25')
  expect((await left.boundingBox())!.width).toBeGreaterThanOrEqual(256)
  await handle.dblclick()
  await expect(handle).toHaveAttribute('aria-valuenow', '60')
  await page.screenshot({ path: testInfo.outputPath('resizable-cards.png'), fullPage: true })
})

test('컬럼 확대·축소·가로 스크롤·저장 후에도 행 선택이 동작하고 전체 보기 없이 내용을 표시한다', async ({
  page,
}, testInfo) => {
  const table = page.getByRole('table', { name: '예외 목록' })
  const header = table.getByRole('columnheader', { name: '내용', exact: true })
  const handle = table.locator('..').getByRole('separator', { name: '내용 열 너비', exact: true })
  const initial = (await header.boundingBox())!.width
  const contractWidth = (await table.getByRole('columnheader', { name: '계약', exact: true }).boundingBox())!.width
  await drag(page, handle, 160)
  expect((await header.boundingBox())!.width).toBeCloseTo(initial + 160, 0)
  expect((await table.getByRole('columnheader', { name: '계약', exact: true }).boundingBox())!.width).toBeCloseTo(
    contractWidth,
    0,
  )
  await expect(table.locator('..')).toHaveJSProperty(
    'scrollWidth',
    await table.evaluate((element) => Math.ceil(element.getBoundingClientRect().width)),
  )
  await page.reload()
  await expect(header).toBeVisible()
  expect((await header.boundingBox())!.width).toBeCloseTo(initial + 160, 0)
  await handle.focus()
  for (let i = 0; i < 10; i++) await page.keyboard.press('Shift+ArrowLeft')
  expect((await header.boundingBox())!.width).toBeCloseTo(64, 0)
  await page.keyboard.press('ArrowRight')
  expect((await header.boundingBox())!.width).toBeCloseTo(80, 0)
  await page.keyboard.press('Home')
  expect((await header.boundingBox())!.width).toBeCloseTo(initial, 0)
  await table.getByRole('button', { name: '11', exact: true }).click()
  await expect(page.getByLabel('조치')).toBeVisible()
  const titleCell = table.getByRole('cell').filter({ hasText: '1,200% 한도 초과 계약의 긴 검증 사유와 상세 내용' })
  await expect(table.locator('summary, details')).toHaveCount(0)
  await expect(table.getByText('전체 보기', { exact: true })).toHaveCount(0)
  await drag(page, handle, 220)
  const preview = titleCell.locator('.table-cell-preview')
  await expect(preview).toHaveText('1,200% 한도 초과 계약의 긴 검증 사유와 상세 내용')
  expect(await preview.evaluate((element) => element.scrollHeight <= element.clientHeight + 1)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('resizable-columns.png'), fullPage: true })
})

test('모바일은 카드 세로 배치와 표 스크롤을 유지하고 데스크톱 복귀 시 비율을 복원한다', async ({ page }, testInfo) => {
  const handle = page.getByRole('separator', { name: '예외 목록과 상세 카드 너비' })
  await drag(page, handle, 140)
  const ratio = await handle.getAttribute('aria-valuenow')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(handle).toBeHidden()
  const panels = page.locator('.resizable-split-panel')
  const left = (await panels.nth(0).boundingBox())!
  const right = (await panels.nth(1).boundingBox())!
  expect(right.y).toBeGreaterThanOrEqual(left.y + left.height)
  await page.screenshot({ path: testInfo.outputPath('mobile.png'), fullPage: true })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  const viewport = page.getByRole('table', { name: '예외 목록' }).locator('..')
  expect(await viewport.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await expect(handle).toBeVisible()
  await expect(handle).toHaveAttribute('aria-valuenow', ratio!)
})
