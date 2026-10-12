import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

// #404 UI 계약 검증. 실제 JWT 서명·DB 세션 무효화 시연은 #418 실서버 시나리오로 인계한다.
const envelope = (data: unknown) => ({ data, error: null, requestId: 'auth-ui-fixture' })
async function authFixture(page: Page, roleCode = 'SETTLEMENT', session = { version: 0 }, initial = false) {
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
    return route.fulfill({ json: envelope({
      loginId: 'tester', userName: '테스트 사용자', roleCode, demoMonth: '2026-07',
      canProcess: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
      canViewAuditLog: ['COMPLIANCE', 'SYSTEM_ADMIN'].includes(roleCode),
      canHandleException: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
      canReverseJournal: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
      canFinalizeValidation: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
    }) })
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
    await expect(page.locator('.shell-user-name')).toContainText('테스트 사용자')
    await expect(page.locator('.sidebar-user-role')).toHaveText({
      SETTLEMENT: '정산담당자', COMPLIANCE: '준법·감사', GA_ADMIN: 'GA관리자', SYSTEM_ADMIN: '시스템관리자',
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
  await expect(page.getByRole('heading', { name: '수수료 지급 등록' })).toBeVisible()
  await expect(page).toHaveURL(/\/app\/transactions\/new\?month=2026-08&status=ACTIVE#form$/)
  await page.getByRole('link', { name: 'FGC 업무 대시보드' }).click()
  await expect(page.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/app\/login\?logout$/)
  await expect(page.getByRole('status')).toHaveText('로그아웃되었습니다.')
  await page.goBack()
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
  await expect(page.locator('.app-shell')).toHaveCount(0)
})

test('유효한 쿠키로 로그인 주소를 새로 열면 대시보드로 이동한다', async ({ page }) => {
  await authFixture(page, 'SETTLEMENT', { version: 0 }, true)
  await page.goto('login')
  await expect(page.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
})

test('COMPLIANCE의 지급 등록 딥링크는 403으로 차단한다', async ({ page }) => {
  await authFixture(page, 'COMPLIANCE')
  await page.goto('login?redirect=%2Ftransactions%2Fnew')
  await login(page)
  await expect(page.getByRole('heading', { name: '403 · 권한 없음' })).toBeVisible()
  await expect(page.getByRole('link', { name: '기존 화면으로 이동' })).toHaveCount(0)
})

test('쿠키 만료 후 재진입 시 로그인으로 이동하고 경로를 보존한다', async ({ page }) => {
  const fixture = await authFixture(page)
  await page.goto('login?redirect=%2Fcontracts%3Fmonth%3D2026-07')
  await login(page)
  await expect(page.getByRole('heading', { name: '보험계약' })).toBeVisible()
  fixture.expire()
  await page.reload()
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('redirect')).toBe('/contracts?month=2026-07')
})

test('두 브라우저의 중복 로그인: A 안내·경로 보존, B 정상, A 재로그인 복귀', async ({ browser }) => {
  const a = await browser.newContext()
  const b = await browser.newContext()
  try {
    const pageA = await a.newPage(), pageB = await b.newPage()
    const session = { version: 0 }
    await authFixture(pageA, 'SETTLEMENT', session)
    await authFixture(pageB, 'SETTLEMENT', session)
    await pageA.goto('http://localhost:5173/app/login?redirect=%2Fcontracts%3Fmonth%3D2026-07')
    await login(pageA)
    await expect(pageA.getByRole('heading', { name: '보험계약' })).toBeVisible()
    await pageB.goto('http://localhost:5173/app/login')
    await login(pageB)
    await expect(pageB.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
    await pageA.reload()
    await expect(pageA.getByRole('status')).toHaveText('다른 곳에서 같은 계정으로 로그인해 로그아웃되었습니다. 다시 로그인하세요.')
    expect(new URL(pageA.url()).searchParams.get('redirect')).toBe('/contracts?month=2026-07')
    await expect(pageB.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
    await login(pageA)
    await expect(pageA.getByRole('heading', { name: '보험계약' })).toBeVisible()
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
