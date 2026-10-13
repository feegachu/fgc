import { chromium, expect } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import assert from 'node:assert/strict'

// Run via TransactionScreensIntegrationTest: HTML comes from the real Thymeleaf engine;
// every domain request goes to the real HTTP/JWT server and disposable PostgreSQL.
// FGC_TRANSACTION_BASELINE=true captures the existing screen before React implementation.
const directory = resolve(process.argv[2])
const staticRoot = resolve('../src/main/resources/static')
const server = process.env.FGC_TRANSACTION_SERVER
const access = process.env.FGC_TRANSACTION_ACCESS
const readerAccess = process.env.FGC_TRANSACTION_READER_ACCESS
const baseline = process.env.FGC_TRANSACTION_BASELINE === 'true'
const reactOrigin = process.env.FGC_TRANSACTION_REACT_ORIGIN || 'http://localhost:5173'
assert.ok(server && access && readerAccess, 'Use the opt-in Spring integration test to supply real JWTs')
await mkdir(directory, { recursive: true })
const browser = await chromium.launch({ executablePath: process.env.FGC_BROWSER_EXECUTABLE || undefined })
const evidence = { baseline, lists: [], flows: [], apiStatuses: [], errors: [], consoleErrors: [], layout: [] }
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Seoul' })
  const api = async (path, options = {}) => {
    const response = await context.request.fetch(server + path, {
      ...options,
      headers: { Authorization: `Bearer ${access}`, ...options.headers },
    })
    assert.ok(response.ok(), `${options.method || 'GET'} ${path}: ${response.status()} ${await response.text()}`)
    return (await response.json()).data
  }
  const forward = async (route, token = access, label = 'legacy') => {
    const url = new URL(route.request().url())
    const path = url.pathname + url.search
    const response = await route.fetch({
      url: server + path,
      headers: { ...route.request().headers(), Authorization: `Bearer ${token}` },
    })
    evidence.apiStatuses.push({ label, path, method: route.request().method(), status: response.status() })
    return route.fulfill({ response })
  }
  const instrument = (page) => {
    page.on('pageerror', (error) => evidence.errors.push(error.message))
    page.on('console', (message) => {
      if (message.type() === 'error' && !message.text().startsWith('Failed to load resource:'))
        evidence.consoleErrors.push(message.text())
    })
  }
  const legacy = await context.newPage()
  instrument(legacy)
  await legacy.route('http://legacy.fixture/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/transactions' || path === '/transactions/new')
      return route.fulfill({ contentType: 'text/html; charset=utf-8', body: await readFile(resolve(directory, `${path.slice(1).replaceAll('/', '-')}.html`)) })
    if (path.startsWith('/api/')) return forward(route)
    const file = resolve(staticRoot, `.${path}`)
    if (!file.startsWith(staticRoot + sep)) return route.abort()
    try {
      return route.fulfill({
        contentType: path.endsWith('.css') ? 'text/css' : path.endsWith('.js') ? 'application/javascript' : path.endsWith('.woff2') ? 'font/woff2' : 'application/octet-stream',
        body: await readFile(file),
      })
    } catch { return route.abort() }
  })
  const createReactPage = async (token = access) => {
    const page = await context.newPage()
    instrument(page)
    await page.route('**/api/v1/**', (route) => {
      if (new URL(route.request().url()).pathname === '/api/v1/auth/refresh')
        return route.fulfill({ json: { data: { accessToken: token, tokenType: 'Bearer', expiresIn: 1800 }, error: null, requestId: 'transaction-session' } })
      return forward(route, token, 'react')
    })
    return page
  }
  const screenshot = async (page, name) => {
    // Capture settled content without transient toast stacks obscuring the confirmation.
    await page.locator('.toast-close').evaluateAll((buttons) => buttons.forEach((button) => button.click()))
    await expect(page.locator('.toast-close')).toHaveCount(0)
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.evaluate(() => document.fonts.ready)
    await page.screenshot({ path: resolve(directory, `${name}.png`), fullPage: await page.getByRole('dialog').count() === 0 })
  }
  const react = baseline ? null : await createReactPage()
  const variants = [{ page: legacy, name: 'legacy', origin: 'http://legacy.fixture' }]
  if (react) variants.push({ page: react, name: 'react', origin: reactOrigin + '/app' })
  const contracts = (await api('/api/v1/contracts?page=1&size=100')).content
  const targets = ['FGC-FGL01-202607-0001', 'FGC-FGL01-202607-0002'].map((number) => contracts.find((row) => row.contractNo === number))
  assert.ok(targets.every(Boolean), 'Seeded normal/warning contracts exist')
  const agents = (await api('/api/v1/base/agents?asOf=2026-07-01&page=1&size=100')).content
  const manager = agents.find((row) => row.agentCode === 'A-BM-001')
  const items = await api('/api/v1/base/commission-items?asOf=2026-07-01')
  const item = items.find((row) => row.itemCode === 'MANAGEMENT_COMMISSION')
  assert.ok(manager && item, 'Seeded manager and management commission exist')
  for (const query of ['settlementMonth=2026-07', 'settlementMonth=2026-07&attributionImbalanceOnly=true', 'settlementMonth=2026-07&page=2']) {
    const data = await api('/api/v1/transactions?' + query + '&size=20')
    for (const { page, name, origin } of variants) {
      await page.goto(`${origin}/transactions?month=2026-07&${query}`)
      if (name === 'legacy') await expect(page.locator('#list-body')).toHaveAttribute('aria-busy', 'false')
      else await expect(page.locator('.transaction-list-panel')).toHaveAttribute('aria-busy', 'false')
      await expect(page.locator('#row-count')).toHaveText(new Intl.NumberFormat('ko-KR').format(data.totalElements))
      for (const row of data.content) await expect(page.locator('#list-body')).toContainText(row.sourceBusinessKey)
      await screenshot(page, `${name}-list-${query.includes('Imbalance') ? 'imbalance' : query.includes('page=2') ? 'page-2' : 'default'}`)
    }
    evidence.lists.push({ query, totalElements: data.totalElements, rows: data.content.map((row) => row.sourceBusinessKey) })
  }
  for (const { page, name, origin } of variants) {
    await page.goto(`${origin}/transactions/new?month=2026-07`)
    await expect(page.locator('#recipient option', { hasText: manager.agentCode })).toHaveCount(1)
    await expect(page.locator('#gate-list .transaction-gate-step')).toHaveCount(6)
    await screenshot(page, `${name}-form-initial`)
  }
  if (react) {
    for (const selector of ['.transaction-form-layout', '.transaction-form-panel', '.transaction-attribution-panel', '.transaction-gate-panel']) {
      const measure = (page) => page.locator(selector).evaluate((element) => {
        const rect = element.getBoundingClientRect()
        const parent = element.closest('.transaction-page').querySelector('.page-header').getBoundingClientRect()
        return { x: rect.x - parent.x, y: rect.y - parent.y, width: rect.width, height: rect.height }
      })
      evidence.layout.push({ selector, before: await measure(legacy), after: await measure(react) })
    }
  }
  const fillAndSave = async ({ page, name, origin }, scenario, selected, amount) => {
    await page.goto(`${origin}/transactions/new?month=2026-07`)
    await expect(page.locator('#recipient option', { hasText: manager.agentCode })).toHaveCount(1)
    await page.locator('#recipient').selectOption(String(manager.agentId))
    await page.locator('#item').selectOption(String(item.commissionItemId))
    const key = `VERIFY-TRAN-${name}-${scenario}-${Date.now()}`
    await page.locator('#bizKey').fill(key)
    await page.locator('#amount').fill(String(amount))
    for (const contract of selected) {
      await page.locator('#btn-add-attr').click()
      const row = page.locator('#attr-body tr').filter({ has: page.locator('[data-contract-id]') }).last()
      await row.locator('[data-contract-id]').selectOption(String(contract.contractId))
      await row.locator('[data-attr-date]').fill('2026-07-31')
      await row.locator('[data-attr-amount]').fill(String(amount / selected.length))
    }
    const saving = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/transactions' && response.request().method() === 'POST')
    await page.locator('#btn-save').click()
    const saved = await saving
    assert.equal(saved.status(), 201, await saved.text())
    const payment = (await saved.json()).data
    await expect(page.locator('#btn-save')).toBeDisabled()
    await expect(page.locator('#amount')).toBeDisabled()
    await expect(page.locator('#btn-confirm')).toBeDisabled()
    const checking = page.waitForResponse((response) => response.url().endsWith(`/transactions/${payment.commissionTransactionId}/precheck`))
    await page.locator('#btn-precheck').click()
    const previewResponse = await checking
    assert.equal(previewResponse.status(), 200)
    const preview = (await previewResponse.json()).data
    assert.equal((await api(`/api/v1/transactions/${payment.commissionTransactionId}`)).status, 'DRAFT')
    await screenshot(page, `${name}-${scenario}-precheck`)
    await page.locator('#btn-confirm').click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await screenshot(page, `${name}-${scenario}-confirmation`)
    return { payment, preview, key }
  }
  // Complete both successful paths before recording a violation, which intentionally blocks
  // later confirmations on that contract. The two rejection paths use different seeded contracts.
  for (const variant of variants) {
    const { page, name } = variant
    const { payment, preview, key } = await fillAndSave(variant, 'success', targets, 20)
    assert.equal(preview.confirmable, true, JSON.stringify(preview.blockers))
    const confirming = page.waitForResponse((response) => response.url().endsWith(`/transactions/${payment.commissionTransactionId}/confirm`))
    await page.locator('#btn-confirm-submit').click()
    const confirmed = await confirming
    assert.equal(confirmed.status(), 200, await confirmed.text())
    const expectedKey = `TRAN-CONFIRM-${payment.commissionTransactionId}`
    assert.equal(confirmed.request().headers()['idempotency-key'], expectedKey)
    const result = (await confirmed.json()).data
    const retried = await api(`/api/v1/transactions/${payment.commissionTransactionId}/confirm`, { method: 'POST', headers: { 'Idempotency-Key': expectedKey } })
    assert.deepEqual(retried.capCheckIds, result.capCheckIds, 'Same key returns the same committed checks')
    await expect(page).toHaveURL(/\/transactions(?:\?.*)?$/)
    evidence.flows.push({ name, scenario: 'success', key, id: payment.commissionTransactionId, status: result.status, attributions: result.attributions.length, idempotencyKey: expectedKey })
  }
  for (let index = 0; index < variants.length; index++) {
    const variant = variants[index]
    const { page, name } = variant
    const { payment, preview } = await fillAndSave(variant, 'violation', [targets[index]], 1300000)
    assert.equal(preview.confirmable, false)
    assert.equal(preview.blockers[0].code, 'FGC-CAP-001', JSON.stringify(preview.blockers))
    await expect(page.locator('#btn-confirm-submit')).toHaveText('확정 시도 · 예외 등록')
    const confirming = page.waitForResponse((response) => response.url().endsWith(`/transactions/${payment.commissionTransactionId}/confirm`))
    await page.locator('#btn-confirm-submit').click()
    const rejected = await confirming
    assert.equal(rejected.status(), 422, await rejected.text())
    assert.equal((await rejected.json()).error.code, 'FGC-CAP-001')
    await expect(page.getByRole('link', { name: '예외함에서 처리하기' })).toHaveAttribute('href', new RegExp(`exceptions\\?type=CAP_VIOLATION&contractNo=${targets[index].contractNo}`))
    await expect(page.locator('#btn-confirm-submit')).toBeHidden()
    assert.equal((await api(`/api/v1/transactions/${payment.commissionTransactionId}`)).status, 'DRAFT')
    const exceptions = await api(`/api/v1/exceptions?type=CAP_VIOLATION&contractNo=${targets[index].contractNo}`)
    assert.ok(exceptions.content.length > 0, 'Rejected confirmation persisted the exception')
    await screenshot(page, `${name}-violation-rejected`)
    evidence.flows.push({ name, scenario: 'violation', id: payment.commissionTransactionId, status: 'DRAFT', code: 'FGC-CAP-001', contractNo: targets[index].contractNo })

    // The next precheck sees an already recorded violation (CAP-003), not a new recordable failure.
    await page.getByRole('button', { name: '취소', exact: true }).click()
    await page.locator('#btn-precheck').click()
    await expect(page.locator('#btn-confirm')).toBeEnabled()
    await page.locator('#btn-confirm').click()
    await expect(page.getByRole('dialog')).toContainText('FGC-CAP-003')
    const existingLink = page.getByRole('link', { name: '예외함에서 처리하기' })
    await expect(existingLink).toHaveAttribute('href', /type=CAP_VIOLATION/)
    await expect(existingLink).not.toHaveAttribute('href', /contractNo=/)
    assert.equal((await api('/api/v1/transactions/' + payment.commissionTransactionId)).status, 'DRAFT')
    evidence.flows.push({ name, scenario: 'existing-violation', id: payment.commissionTransactionId,
      status: 'DRAFT', code: 'FGC-CAP-003' })
  }
  if (react) {
    const reader = await createReactPage(readerAccess)
    await reader.goto(reactOrigin + '/app/transactions/new')
    await expect(reader.getByRole('heading', { name: '403 · 권한 없음' })).toBeVisible()
    await screenshot(reader, 'react-compliance-forbidden')
  }
  assert.deepEqual(evidence.errors, [])
  assert.deepEqual(evidence.consoleErrors, [])
} finally {
  await writeFile(resolve(directory, 'evidence.json'), JSON.stringify(evidence, null, 2))
  await browser.close()
}
console.log(JSON.stringify({ directory, baseline, lists: evidence.lists.length, flows: evidence.flows }))
