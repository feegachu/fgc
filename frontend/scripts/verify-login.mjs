import { chromium } from 'playwright'
import { readFile, mkdir } from 'node:fs/promises'
import { resolve, sep } from 'node:path'
import assert from 'node:assert/strict'

// LoginViewRenderingTest에서 만든 실제 Thymeleaf HTML과 React 화면을 비교한다.
const directory = resolve(process.argv[2])
const staticRoot = resolve('../src/main/resources/static')
await mkdir(directory, { recursive: true })
const browser = await chromium.launch({ executablePath: process.env.FGC_BROWSER_EXECUTABLE || undefined })
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const legacy = await context.newPage()
  await legacy.route('http://legacy.fixture/**', async (route) => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/login') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: await readFile(resolve(directory, 'legacy.html')) })
    const file = resolve(staticRoot, `.${pathname}`)
    if (!file.startsWith(staticRoot + sep)) return route.abort()
    const contentType = pathname.endsWith('.css') ? 'text/css' : pathname.endsWith('.js') ? 'application/javascript'
      : pathname.endsWith('.woff2') ? 'font/woff2' : pathname.endsWith('.png') ? 'image/png'
      : pathname.endsWith('.jpg') ? 'image/jpeg' : 'application/octet-stream'
    try { return await route.fulfill({ contentType, body: await readFile(file) }) }
    catch { return route.abort() }
  })
  await legacy.goto('http://legacy.fixture/login')
  const react = await context.newPage()
  await react.route('**/api/v1/auth/refresh', (route) => route.fulfill({ status: 401, json: {
    data: null, error: { code: 'FGC-AUTH-002', message: '로그인이 만료되었습니다. 다시 로그인하세요.' }, requestId: 'visual-fixture',
  } }))
  await react.goto(process.env.FGC_REACT_LOGIN_URL || 'http://localhost:5173/app/login')
  await react.getByRole('heading', { name: '로그인', exact: true }).waitFor()
  for (const [name, page] of [['legacy', legacy], ['react', react]]) {
    await page.evaluate(() => document.fonts.ready)
    await page.screenshot({ path: resolve(directory, `${name}-desktop.png`), fullPage: true })
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    assert.match(await page.getByRole('alert').innerText(), /아이디와 비밀번호를 모두 입력해 주세요/)
    await page.getByRole('button', { name: '비밀번호 표시' }).click()
    assert.equal(await page.locator('input[name=password]').getAttribute('type'), 'text')
    await page.getByRole('button', { name: '비밀번호 숨기기' }).click()
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: resolve(directory, `${name}-mobile-validation.png`), fullPage: true })
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
  }
  console.log(JSON.stringify({ directory, checked: ['legacy-rendering', 'desktop', 'mobile', 'required-fields', 'password-toggle'] }))
} finally {
  await browser.close()
}
