import { chromium, expect } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import assert from 'node:assert/strict'
const directory = resolve(process.argv[2])
const staticRoot = resolve('../src/main/resources/static')
const server = process.env.FGC_REFERENCE_SERVER
const access = process.env.FGC_REFERENCE_ACCESS
await mkdir(directory, { recursive: true })
const browser = await chromium.launch({ executablePath: process.env.FGC_BROWSER_EXECUTABLE || undefined })
const evidence = { base: [], policies: [], errors: [], apiStatuses: [] }
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const api = async (path) => {
    const response = await context.request.get(server + path, { headers: { Authorization: `Bearer ${access}` } })
    assert.equal(response.status(), 200, path)
    return (await response.json()).data
  }
  const forward = async (route) => {
    const path = new URL(route.request().url()).pathname + new URL(route.request().url()).search
    const response = await route.fetch({
      url: server + path,
      headers: { ...route.request().headers(), Authorization: `Bearer ${access}` },
    })
    evidence.apiStatuses.push({ path, status: response.status() })
    return route.fulfill({ response })
  }
  const legacy = await context.newPage()
  await legacy.route('http://legacy.fixture/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/base' || path === '/policies')
      return route.fulfill({
        contentType: 'text/html; charset=utf-8',
        body: await readFile(resolve(directory, `${path.slice(1)}.html`)),
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
  const react = await context.newPage()
  react.on('pageerror', (error) => evidence.errors.push(error.message))
  legacy.on('pageerror', (error) => evidence.errors.push(error.message))
  await react.route('**/api/v1/**', (route) => {
    if (new URL(route.request().url()).pathname === '/api/v1/auth/refresh')
      return route.fulfill({
        json: {
          data: { accessToken: access, tokenType: 'Bearer', expiresIn: 1800 },
          error: null,
          requestId: 'reference-session',
        },
      })
    return forward(route)
  })
  const screenshot = async (page, name) => {
    await page.evaluate(() => document.fonts.ready)
    await page.screenshot({ path: resolve(directory, `${name}.png`), fullPage: true })
  }
  const firstInsurer = (await api('/api/v1/base/insurers?page=1&size=20')).content[0]
  const bases = [
    ['organization', 'organizations', 'organizationCode'],
    ['insurer', 'insurers', 'insurerCode'],
    ['product', 'products', 'insurerProductCode'],
    ['agent', 'agents', 'agentCode'],
    ['commission-item', 'commission-items', 'itemCode'],
  ]
  for (const [tab, endpoint, key] of bases) {
    const search = `month=2026-07&tab=${tab}&asOf=2026-07-01${tab === 'product' ? `&insurerId=${firstInsurer.insurerId}` : ''}`
    const data = await api(
      `/api/v1/base/${endpoint}?asOf=2026-07-01&page=1&size=20${tab === 'product' ? `&insurerId=${firstInsurer.insurerId}` : ''}`,
    )
    const rows = Array.isArray(data) ? data : data.content
    assert.ok(rows.length > 0, `${tab} real seeded rows`)
    await legacy.goto(`http://legacy.fixture/base?${search}`)
    await react.goto(`http://localhost:5173/app/base?${search}`)
    await expect(legacy.locator(`[data-base-body="${tab}"]`)).toContainText(rows[0][key])
    await expect(react.getByRole('tabpanel')).toContainText(rows[0][key])
    for (const row of rows) await expect(react.getByRole('tabpanel')).toContainText(row[key])
    await screenshot(legacy, `legacy-base-${tab}`)
    await screenshot(react, `react-base-${tab}`)
    evidence.base.push({ tab, count: rows.length, firstCode: rows[0][key] })
  }
  const policies = await api('/api/v1/policies?asOf=2026-07-01')
  await legacy.goto('http://legacy.fixture/policies?month=2026-07&asOf=2026-07-01')
  await react.goto('http://localhost:5173/app/policies?month=2026-07&asOf=2026-07-01')
  for (const policy of policies)
    await expect(react.getByRole('table', { name: '정책 버전' })).toContainText(policy.policyCode)
  await screenshot(legacy, 'legacy-policies-versions')
  await screenshot(react, 'react-policies-versions')
  const nonEmpty = { commission: null, cap: null, refund: null }
  for (const policy of policies) {
    const detail = await api(`/api/v1/policies/${policy.policyVersionId}`)
    for (const [tab, property] of [
      ['commission', 'commissionRules'],
      ['cap', 'capRuleSets'],
      ['refund', 'refundRateTables'],
    ])
      if (!nonEmpty[tab] && detail[property].length) nonEmpty[tab] = { policy, detail }
  }
  for (const [tab, label, legacyTab, property, key] of [
    ['commission', '수수료 규칙', 'rule', 'commissionRules', 'itemName'],
    ['cap', '1,200% 룰셋', 'cap', 'capRuleSets', 'paymentStageLabel'],
    ['refund', '예상 해약환급률표', 'refund', 'refundRateTables', 'productName'],
  ]) {
    const fixture = nonEmpty[tab]
    assert.ok(fixture, `${tab} real policy detail`)
    await legacy.getByRole('tab', { name: '정책 버전' }).click()
    await legacy.locator(`[data-policy-version-id="${fixture.policy.policyVersionId}"]`).click()
    await legacy.getByRole('tab', { name: label, exact: true }).click()
    await react.goto(
      `http://localhost:5173/app/policies?month=2026-07&asOf=2026-07-01&tab=${tab}&policyVersionId=${fixture.policy.policyVersionId}`,
    )
    await expect(react.getByRole('tabpanel')).toContainText(fixture.detail[property][0][key])
    await expect(legacy.locator(`#tab-${legacyTab}`)).toContainText(fixture.detail[property][0][key])
    await screenshot(legacy, `legacy-policies-${tab}`)
    await screenshot(react, `react-policies-${tab}`)
    evidence.policies.push({ tab, policy: fixture.policy.policyCode, count: fixture.detail[property].length })
  }
  await react.reload()
  await expect(react.getByRole('tab', { name: '예상 해약환급률표' })).toHaveAttribute('aria-selected', 'true')
  await expect(react.getByRole('tabpanel')).toContainText(nonEmpty.refund.detail.refundRateTables[0].productName)
  await react.goto('http://localhost:5173/app/policies?month=2026-07&tab=commission&policyVersionId=999999999')
  await expect(react.getByRole('alert')).toBeVisible()
  await react.getByRole('tab', { name: '정책 버전' }).click()
  await expect(react.getByRole('table', { name: '정책 버전' })).toContainText(policies[0].policyCode)
  await react.goto('http://localhost:5173/app/policies?month=2026-07&asOf=broken')
  await expect(react.getByRole('alert')).toBeVisible()
  assert.deepEqual(evidence.errors, [])
  await writeFile(resolve(directory, 'evidence.json'), JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify({ directory, base: evidence.base, policies: evidence.policies, errors: evidence.errors }))
} finally {
  await browser.close()
}
