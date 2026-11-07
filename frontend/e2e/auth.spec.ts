import { expect, test } from '@playwright/test'
import type { BrowserContext, Page } from '@playwright/test'

// #404 UI 계약 검증. 실제 JWT 서명·DB 세션 무효화 시연은 #418 실서버 시나리오로 인계한다.
const envelope = (data: unknown) => ({ data, error: null, requestId: 'auth-ui-fixture' })
// 인증 fixture 토큰이 실제 업무 서버로 전달되지 않도록 조회를 격리한다.
async function isolate(target: Page | BrowserContext) {
  await target.route('**/api/v1/contracts?**', (route) => route.fulfill({ json: envelope({ content: [], totalPages: 0, totalElements: 0 }) }))
  await target.route('**/api/v1/base/**', (route) => route.fulfill({ json: envelope({ content: [], totalPages: 0, totalElements: 0 }) }))
  await target.route('**/api/v1/base/commission-items?**', (route) => route.fulfill({ json: envelope([]) }))
  await target.route('**/api/v1/dashboard/**', (route) => route.fulfill({ json: envelope({ recentExceptions: [], recentRuns: [] }) }))
  await target.route('**/api/v1/home/**', (route) => route.fulfill({ json: envelope({ branches: [], attentionAgents: [] }) }))
  await target.route('**/api/v1/exceptions/counts**', (route) => route.fulfill({ json: envelope({ total: 0, bySeverity: [], byType: [] }) }))
}
const meData = (roleCode: string) => ({
  loginId: 'tester', userName: '테스트 사용자', roleCode, demoMonth: '2026-07',
  canProcess: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
  canViewAuditLog: ['COMPLIANCE', 'SYSTEM_ADMIN'].includes(roleCode),
  canHandleException: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
  canReverseJournal: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
  canFinalizeValidation: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
})
async function authFixture(page: Page, roleCode = 'SETTLEMENT', session = { version: 0 }, initial = false) {
  await isolate(page)
  let issued = initial ? ++session.version : 0
  let expired = false
  const token = () => envelope({ accessToken: `fixture-${issued}`, tokenType: 'Bearer', expiresIn: 1800 })
  await page.route('**/api/v1/auth/**', async (route) => {
    const operation = new URL(route.request().url()).pathname.split('/').pop()
    if (operation === 'login') {
      expect(route.request().postDataJSON()).toEqual({ loginId: 'tester', password: 'fixture-password' })
      expect(route.request().headers()['x-fgc-client']).toBe('web')
      issued = ++session.version
      expired = false
      return route.fulfill({ json: token() })
    }
    if (operation === 'logout') {
      issued = 0
      return route.fulfill({ json: envelope(null) })
    }
    if (!issued || expired || issued !== session.version) {
      return route.fulfill({ status: 401, json: {
        data: null, error: { code: issued && issued !== session.version ? 'FGC-AUTH-004' : 'FGC-AUTH-002', message: '로그인이 만료되었습니다. 다시 로그인하세요.' }, requestId: 'auth-ui-fixture',
      } })
    }
    if (operation === 'refresh') return route.fulfill({ json: token() })
    expect(route.request().headers().authorization).toBe(`Bearer fixture-${issued}`)
    return route.fulfill({ json: envelope(meData(roleCode)) })
  })
  return { expire: () => { expired = true } }
}
async function login(page: Page) {
  await page.getByLabel('아이디', { exact: false }).fill('tester')
  await page.getByLabel(/^비밀번호\s*\*?$/).fill('fixture-password')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
}

for (const role of ['SETTLEMENT', 'COMPLIANCE', 'GA_ADMIN', 'SYSTEM_ADMIN']) {
  test(`${role}: 로그인 후 사용자·역할·메뉴를 표시한다`, async ({ page }) => {
    await authFixture(page, role)
    await page.goto('login')
    await login(page)
    await expect(page.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
    await expect(page.locator('.app-header .shell-user-name')).toHaveCount(0)
    await expect(page.locator('.sidebar-user-name')).toContainText('테스트 사용자')
    await expect(page.locator('.sidebar-user-role')).toHaveText({
      SETTLEMENT: '정산담당자', COMPLIANCE: '준법/감사', GA_ADMIN: 'GA관리자', SYSTEM_ADMIN: '시스템관리자',
    }[role]!)
    await expect(page.locator('.sidebar-subnav-link').filter({ hasText: '감사로그' })).toHaveCount(['COMPLIANCE', 'SYSTEM_ADMIN'].includes(role) ? 1 : 0)
  })
}

test('보호 딥링크로 복귀하고 로그아웃 뒤 뒤로 가기로 접근할 수 없다', async ({ page }) => {
  await authFixture(page)
  await page.goto('transactions/new?month=2026-08&status=ACTIVE#form')
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('redirect')).toBe('/transactions/new?month=2026-08&status=ACTIVE#form')
  await login(page)
  await expect(page.getByRole('heading', { name: '지급 건 등록, 귀속, 확정' })).toBeVisible()
  await expect(page).toHaveURL(/\/transactions\/new\?month=2026-08&status=ACTIVE#form$/)
  await page.getByRole('link', { name: 'FGC 업무 대시보드' }).click()
  await expect(page.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/login\?logout$/)
  await expect(page.getByRole('status')).toHaveText('로그아웃되었습니다.')
  await page.goBack()
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
  await expect(page.locator('.app-shell')).toHaveCount(0)
})

test('쓰던 브라우저에서 유효한 쿠키로 로그인 주소를 새로 열면 대시보드로 이동한다', async ({ page }) => {
  await authFixture(page, 'SETTLEMENT', { version: 0 }, true)
  await page.addInitScript(() => localStorage.setItem('fgc.lastActivityAt', String(Date.now())))
  await page.goto('login')
  await expect(page.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
})

test('처음 쓰는 브라우저는 복원 요청 없이 로그인 화면을 바로 보여 준다', async ({ page }) => {
  await authFixture(page, 'SETTLEMENT', { version: 0 }, true)
  const refreshes: string[] = []
  page.on('request', (request) => {
    if (request.url().endsWith('/api/v1/auth/refresh')) refreshes.push(request.url())
  })
  for (const path of ['login', './']) {
    await page.goto(path)
    await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
    expect(new URL(page.url()).pathname.endsWith('/login')).toBe(true)
  }
  expect(refreshes).toEqual([])
})

test('COMPLIANCE의 지급 등록 딥링크는 403으로 차단한다', async ({ page }) => {
  await authFixture(page, 'COMPLIANCE')
  await page.goto('login?redirect=%2Ftransactions%2Fnew')
  await login(page)
  await expect(page.getByRole('heading', { name: '권한 없음' })).toBeVisible()
  await expect(page.getByRole('link', { name: '기존 화면으로 이동' })).toHaveCount(0)
})

test('쿠키 만료 후 재진입 시 로그인으로 이동하고 경로를 보존한다', async ({ page }) => {
  const fixture = await authFixture(page)
  await page.goto('login?redirect=%2Fcontracts%3Fmonth%3D2026-07')
  await login(page)
  await expect(page.getByRole('heading', { name: '보험계약 목록', level: 1, exact: true })).toBeVisible()
  fixture.expire()
  await page.reload()
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('redirect')).toBe('/contracts?month=2026-07')
})

test('오래 자리를 비운 뒤 다시 열면 쿠키가 있어도 자동 로그아웃한다', async ({ page }) => {
  await authFixture(page)
  await page.goto('login?redirect=%2Fcontracts%3Fmonth%3D2026-07')
  await login(page)
  await expect(page.getByRole('heading', { name: '보험계약 목록', level: 1, exact: true })).toBeVisible()
  const refreshes: string[] = []
  const loggedOut = page.waitForRequest('**/api/v1/auth/logout')
  page.on('request', (request) => {
    if (request.url().endsWith('/api/v1/auth/refresh')) refreshes.push(request.url())
  })
  await page.evaluate(() => localStorage.setItem('fgc.lastActivityAt', String(Date.now() - 31 * 60_000)))
  await page.reload()
  await loggedOut
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
  await expect(page.getByText('오랫동안 사용하지 않아 자동으로 로그아웃되었습니다.')).toBeVisible()
  expect(refreshes).toEqual([])
})

test('화면을 모두 닫고 3분이 지나 다시 열면 쿠키가 있어도 자동 로그아웃한다', async ({ page, context }) => {
  await authFixture(page)
  await page.goto('login?redirect=%2Fcontracts%3Fmonth%3D2026-07')
  await login(page)
  await expect(page.getByRole('heading', { name: '보험계약 목록', level: 1, exact: true })).toBeVisible()
  const refreshes: string[] = []
  const loggedOut = page.waitForRequest('**/api/v1/auth/logout')
  page.on('request', (request) => {
    if (request.url().endsWith('/api/v1/auth/refresh')) refreshes.push(request.url())
  })
  // 새 문서가 앱보다 먼저 읽도록 넣는다. 열린 화면이 30초마다 남기는 표시와 겹치지 않는다.
  await context.addInitScript(() => localStorage.setItem('fgc.openAt', String(Date.now() - 4 * 60_000)))
  await page.reload()
  await loggedOut
  await expect(page.getByText('오랫동안 사용하지 않아 자동으로 로그아웃되었습니다.')).toBeVisible()
  expect(refreshes).toEqual([])
})

test('두 탭이 동시에 열려도 refresh를 한 번에 하나씩 보내 둘 다 로그인을 유지한다', async ({ context }) => {
  await isolate(context)
  let inFlight = 0
  let most = 0
  let issued = 0
  await context.route('**/api/v1/auth/**', async (route) => {
    const operation = new URL(route.request().url()).pathname.split('/').pop()
    if (operation === 'refresh') {
      inFlight++
      most = Math.max(most, inFlight)
      // 응답이 늦은 동안 다른 탭이 같은 쿠키로 회전하면 서버가 재사용으로 보고 둘 다 끊는다.
      await new Promise((resolve) => setTimeout(resolve, 300))
      inFlight--
      return route.fulfill({ json: envelope({ accessToken: `fixture-${++issued}`, tokenType: 'Bearer', expiresIn: 1800 }) })
    }
    return route.fulfill({ json: envelope(meData('SETTLEMENT')) })
  })
  await context.addInitScript(() => {
    localStorage.setItem('fgc.lastActivityAt', String(Date.now()))
    localStorage.setItem('fgc.openAt', String(Date.now()))
  })
  const tabs = [await context.newPage(), await context.newPage()]
  await Promise.all(tabs.map((tab) => tab.goto('contracts?month=2026-07')))
  for (const tab of tabs) await expect(tab.getByRole('heading', { name: '보험계약 목록', level: 1, exact: true })).toBeVisible()
  expect(issued).toBe(2)
  expect(most).toBe(1)
})

test('두 브라우저의 중복 로그인: A 안내·경로 보존, B 정상, A 재로그인 복귀', async ({ browser, baseURL }) => {
  const a = await browser.newContext({ baseURL })
  const b = await browser.newContext({ baseURL })
  try {
    const pageA = await a.newPage(), pageB = await b.newPage()
    const session = { version: 0 }
    await authFixture(pageA, 'SETTLEMENT', session)
    await authFixture(pageB, 'SETTLEMENT', session)
    await pageA.goto('login?redirect=%2Fcontracts%3Fmonth%3D2026-07')
    await login(pageA)
    await expect(pageA.getByRole('heading', { name: '보험계약 목록', level: 1, exact: true })).toBeVisible()
    await pageB.goto('login')
    await login(pageB)
    await expect(pageB.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
    await pageA.reload()
    await expect(pageA.getByRole('status')).toHaveText('다른 곳에서 같은 계정으로 로그인해 로그아웃되었습니다. 다시 로그인하세요.')
    expect(new URL(pageA.url()).searchParams.get('redirect')).toBe('/contracts?month=2026-07')
    await expect(pageB.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
    await login(pageA)
    await expect(pageA.getByRole('heading', { name: '보험계약 목록', level: 1, exact: true })).toBeVisible()
  } finally {
    await a.close()
    await b.close()
  }
})

test('로그인 PC·모바일 화면과 비밀번호 토글', async ({ page }, testInfo) => {
  await authFixture(page)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('login')
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
  await page.evaluate(() => document.fonts.ready)
  await page.screenshot({ path: testInfo.outputPath('login-desktop.png'), fullPage: true })
  await testInfo.attach('로그인 PC', { path: testInfo.outputPath('login-desktop.png'), contentType: 'image/png' })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '비밀번호 표시' }).click()
  await expect(page.getByLabel(/^비밀번호\s*\*?$/)).toHaveAttribute('type', 'text')
  await page.getByRole('button', { name: '비밀번호 숨기기' }).click()
  await page.screenshot({ path: testInfo.outputPath('login-mobile.png'), fullPage: true })
  await testInfo.attach('로그인 모바일', { path: testInfo.outputPath('login-mobile.png'), contentType: 'image/png' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('신규 지급의 실제 입력은 이동 취소 시 유지하고 명시적 버리기 후에만 이동한다', async ({ page }) => {
  await authFixture(page)
  await page.goto('transactions/new?month=2026-08')
  await login(page)
  await expect(page.getByRole('heading', { name: '지급 건 등록, 귀속, 확정' })).toBeVisible()
  await page.locator('#bizKey').fill('UNSAVED-UI-ONLY')
  await page.getByRole('link', { name: 'FGC 업무 대시보드' }).click()
  await expect(page.getByRole('dialog', { name: '저장하지 않은 입력이 있습니다' })).toBeVisible()
  await page.getByRole('button', { name: '현재 입력 유지하고 돌아가기' }).click()
  await expect(page.locator('#bizKey')).toHaveValue('UNSAVED-UI-ONLY')
  await page.getByRole('link', { name: 'FGC 업무 대시보드' }).click()
  await page.getByRole('button', { name: '입력을 버리고 이동' }).click()
  await expect(page.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
})
