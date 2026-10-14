import { chromium, expect } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import assert from 'node:assert/strict'
const directory = resolve(process.argv[2])
const staticRoot = resolve('../src/main/resources/static')
const server = process.env.FGC_ARB_SERVER
const access = process.env.FGC_ARB_ACCESS
const baseline = process.env.FGC_ARB_BASELINE === 'true'
await mkdir(directory, { recursive: true })
const browser = await chromium.launch({ executablePath: process.env.FGC_BROWSER_EXECUTABLE || undefined })
const evidence = { apiStatuses: [], errors: [], consoleErrors: [], layout: [] }
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const api = async (path) => {
    const response = await context.request.get(server + path, { headers: { Authorization: `Bearer ${access}` } })
    assert.equal(response.status(), 200, path)
    return (await response.json()).data
  }
  const forward = async (route) => {
    const url = new URL(route.request().url())
    const response = await route.fetch({
      url: server + url.pathname + url.search,
      headers: { ...route.request().headers(), Authorization: `Bearer ${access}` },
    })
    evidence.apiStatuses.push({
      path: url.pathname + url.search,
      method: route.request().method(),
      status: response.status(),
    })
    return route.fulfill({ response })
  }
  const legacy = await context.newPage()
  await legacy.route('http://legacy.fixture/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/arbitrage-checks')
      return route.fulfill({
        contentType: 'text/html; charset=utf-8',
        body: await readFile(resolve(directory, 'arbitrage.html')),
      })
    if (path.startsWith('/api/')) return forward(route)
    const file = resolve(staticRoot, `.${path}`)
    if (!file.startsWith(staticRoot + sep)) return route.abort()
    try {
      return route.fulfill({
        contentType: path.endsWith('.css')
          ? 'text/css'
          : path.endsWith('.js')
            ? 'application/javascript'
            : path.endsWith('.woff2')
              ? 'font/woff2'
              : 'application/octet-stream',
        body: await readFile(file),
      })
    } catch {
      return route.abort()
    }
  })
  // Demo seeds contain contracts/snapshots, but no calculated arbitrage rows. Build real persisted results via the existing API.
  for (const asOfDate of ['2026-07-30', '2026-07-31']) {
    const response = await context.request.post(
      `${server}/api/v1/contracts/${process.env.FGC_ARB_CONTRACT}/arbitrage-check`,
      { headers: { Authorization: `Bearer ${access}` }, data: { asOfDate, reason: '412 격리 브라우저 검증 데이터' } },
    )
    assert.equal(response.status(), 200, await response.text())
  }
  const data = await api('/api/v1/arbitrage-checks?month=2026-07&stage=GA_TO_FC&page=1&size=20')
  evidence.rows = data.items.content.map((row) => ({
    id: row.arbitrageCheckId,
    contractNo: row.contractNo,
    status: row.resultStatus,
  }))
  assert.ok(data.items.content.length, 'Real seeded arbitrage rows')
  await legacy.goto('http://legacy.fixture/arbitrage-checks?month=2026-07')
  await expect(legacy.locator('#list-body')).toContainText(data.items.content[0].contractNo)
  await expect(legacy.locator('.arb-timeline-data-table')).toBeVisible()
  await legacy.evaluate(() => document.fonts.ready)
  await legacy.screenshot({ path: resolve(directory, 'legacy-arbitrage.png'), fullPage: true })
  await legacy.getByRole('button', { name: '이 계약만 다시 검증' }).click()
  await legacy.getByRole('dialog').screenshot({ path: resolve(directory, 'legacy-recheck.png') })
  await legacy.getByRole('button', { name: '취소', exact: true }).click()
  if (baseline) {
    await writeFile(resolve(directory, 'baseline.json'), JSON.stringify(evidence, null, 2))
    console.log(JSON.stringify({ baseline: true, rows: evidence.rows }))
  } else {
    const react = await context.newPage()
    react.on('pageerror', (error) => evidence.errors.push(error.message))
    react.on('console', (message) => {
      if (message.type() === 'error' && !message.text().startsWith('Failed to load resource:'))
        evidence.consoleErrors.push(message.text())
    })
    await react.route('**/api/v1/**', (route) => {
      if (new URL(route.request().url()).pathname === '/api/v1/auth/refresh')
        return route.fulfill({
          json: {
            data: { accessToken: access, tokenType: 'Bearer', expiresIn: 1800 },
            error: null,
            requestId: '412-session',
          },
        })
      return forward(route)
    })
    await react.goto('http://localhost:5173/app/arbitrage-checks?month=2026-07')
    await expect(react.locator('.arb-list-table')).toContainText(data.items.content[0].contractNo)
    await expect(react.locator('.arb-timeline-data-table')).toBeVisible()
    for (const row of data.items.content) await expect(react.locator('.arb-list-table')).toContainText(row.asOfDate)
    await Promise.all([legacy.evaluate(() => document.fonts.ready), react.evaluate(() => document.fonts.ready)])
    const measure = (page, selector) =>
      page
        .locator(selector)
        .first()
        .evaluate((element) => {
          const anchor = document.querySelector('.arb-page .page-header').getBoundingClientRect()
          const rect = element.getBoundingClientRect()
          return { x: rect.x - anchor.x, y: rect.y - anchor.y, width: rect.width, height: rect.height }
        })
    for (const selector of [
      '.arb-filter-bar',
      '.arb-filter-month',
      '.arb-filter-actions',
      '.arb-summary-grid',
      '.arb-content-grid',
      '.arb-panel-header',
      '.arb-list-table thead',
      '.arb-list-table tbody tr:first-child',
      '.arb-list-table th:first-child',
      '.arb-detail-list',
      '.arb-calculation',
      '.arb-timeline-panel',
    ]) {
      const before = await measure(legacy, selector),
        after = await measure(react, selector)
      evidence.layout.push({ selector, before, after })
      for (const key of ['x', 'y', 'width', 'height'])
        assert.ok(
          Math.abs(before[key] - after[key]) <= 2,
          `${selector} ${key}: legacy=${before[key]}, react=${after[key]}`,
        )
    }
    const headers = (page) => page.locator('.arb-list-table th').allTextContents()
    assert.deepEqual(await headers(react), await headers(legacy))
    await react.screenshot({ path: resolve(directory, 'react-arbitrage.png'), fullPage: true })
    await react.getByRole('button', { name: '이 계약만 다시 검증' }).click()
    await expect(react.getByLabel('검증 기준일')).toBeFocused()
    await react.getByRole('dialog').screenshot({ path: resolve(directory, 'react-recheck.png') })
    await react.getByRole('button', { name: '재검증 실행', exact: true }).click()
    await expect(react.getByText('재검증 사유를 입력하세요.')).toBeVisible()
    await react.getByLabel('재검증 사유').fill('412 실제 화면 재검증')
    const responsePromise = react.waitForResponse(
      (response) => response.url().endsWith('/arbitrage-check') && response.request().method() === 'POST',
    )
    await react.getByRole('button', { name: '재검증 실행', exact: true }).click()
    const response = await responsePromise
    assert.equal(response.status(), 200)
    const saved = (await response.json()).data
    await expect(react.getByRole('dialog')).toHaveCount(0)
    await expect(react.getByText('차익거래 재검증을 완료했습니다.')).toBeVisible()
    const after = await api('/api/v1/arbitrage-checks?month=2026-07&stage=GA_TO_FC&page=1&size=20')
    assert.ok(
      after.items.content.some((row) => row.arbitrageCheckId === saved.arbitrageCheckId),
      'Real recheck persisted and reread via API',
    )
    evidence.recheck = {
      contractId: data.items.content[0].contractId,
      resultStatus: saved.resultStatus,
      validationRunId: saved.validationRunId,
    }
    await react.reload()
    await expect(react.locator('.arb-timeline-data-table')).toBeVisible()
    await react.setViewportSize({ width: 390, height: 844 })
    await expect
      .poll(() => react.locator('.arb-page').evaluate((element) => element.scrollWidth <= element.clientWidth))
      .toBe(true)
    await react.screenshot({ path: resolve(directory, 'react-mobile.png'), fullPage: true })
    assert.deepEqual(evidence.errors, [])
    assert.deepEqual(evidence.consoleErrors, [])
    await writeFile(resolve(directory, 'evidence.json'), JSON.stringify(evidence, null, 2))
    console.log(
      JSON.stringify({
        rows: evidence.rows,
        layout: evidence.layout.length,
        recheck: evidence.recheck,
        errors: evidence.errors,
        consoleErrors: evidence.consoleErrors,
      }),
    )
  }
} finally {
  await browser.close()
}
