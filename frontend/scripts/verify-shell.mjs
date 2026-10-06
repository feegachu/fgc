import { chromium } from 'playwright'
import { readFile, mkdir } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import assert from 'node:assert/strict'

// Called by AppShellScreenshotIntegrationTest after rendering real Thymeleaf HTML.
const directory = resolve(process.argv[2])
const staticRoot = resolve('../src/main/resources/static')
await mkdir(directory, { recursive: true })
const browser = await chromium.launch({ executablePath: process.env.FGC_BROWSER_EXECUTABLE || undefined })
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const legacy = await context.newPage()
  await legacy.route('http://legacy.fixture/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/contracts') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: await readFile(resolve(directory, 'legacy.html')) })
    if (pathname.startsWith('/api/')) return route.fulfill({ json: { data: { content: [], totalPages: 0, totalElements: 0 }, error: null, requestId: 'visual-fixture' } })
    const file = resolve(staticRoot, `.${pathname}`)
    if (!file.startsWith(staticRoot + sep)) return route.abort()
    try {
      const contentType = pathname.endsWith('.css') ? 'text/css' : pathname.endsWith('.js') ? 'application/javascript' : pathname.endsWith('.woff2') ? 'font/woff2' : pathname.endsWith('.png') ? 'image/png' : 'application/octet-stream'
      return route.fulfill({ contentType, body: await readFile(file) })
    } catch { return route.abort() }
  })
  await legacy.goto('http://legacy.fixture/contracts?month=2026-07')
  await legacy.locator('.workspace-tab.is-active').waitFor()
  await legacy.evaluate(() => document.fonts.ready)
  await legacy.screenshot({ path: resolve(directory, 'legacy.png'), fullPage: true })
  const react = await context.newPage()
  await react.route('**/api/v1/auth/refresh', (route) => route.fulfill({ json: { data: { accessToken: 'visual-fixture', tokenType: 'Bearer', expiresIn: 1800 }, error: null, requestId: 'visual-fixture' } }))
  await react.route('**/api/v1/auth/me', (route) => route.fulfill({ json: { data: { loginId: 'settle01', userName: '정산담당자', roleCode: 'SETTLEMENT', canProcess: true, canViewAuditLog: false, demoMonth: '2026-07' }, error: null, requestId: 'visual-fixture' } }))
  await react.goto(process.env.FGC_REACT_URL || 'http://localhost:5173/app/contracts?month=2026-07')
  await react.getByRole('heading', { name: '보험계약' }).waitFor()
  await react.evaluate(() => document.fonts.ready)
  await react.screenshot({ path: resolve(directory, 'react.png'), fullPage: true })
  const dimensions = async (page) => page.evaluate(() => Object.fromEntries(['.app-sidebar', '.app-header', '.workspace-tab'].map((selector) => {
    const box = document.querySelector(selector).getBoundingClientRect()
    return [selector, { width: box.width, height: box.height }]
  })))
  const old = await dimensions(legacy), next = await dimensions(react)
  assert.equal(next['.app-sidebar'].width, old['.app-sidebar'].width)
  assert.equal(next['.app-header'].height, old['.app-header'].height)
  assert.equal(next['.workspace-tab'].width, old['.workspace-tab'].width)
  await react.getByRole('button', { name: '기준 정산월 2026-07' }).click()
  await react.screenshot({ path: resolve(directory, 'react-month.png'), fullPage: true })
  console.log(JSON.stringify({ directory, legacy: old, react: next }))
} finally { await browser.close() }
