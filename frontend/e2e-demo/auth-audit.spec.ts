import { test, expect, login, completeMonthly, password, responseFor, successful } from './fixtures'

test('로그인 4개 역할·대시보드·사용자·메뉴·로그아웃', async ({ browser, baseURL }) => {
  for (const [account, userName, roleName, auditMenu] of [
    ['admin', '시스템관리자', '시스템관리자', true],
    ['gaadmin', 'GA관리자', 'GA관리자', false],
    ['settle01', '정산담당자', '정산담당자', false],
    ['audit01', '준법감사자', '준법·감사', true],
  ] as const) {
    const context = await browser.newContext({ baseURL })
    const page = await context.newPage()
    await page.goto('login')
    await page.getByLabel(/^아이디/).fill(account)
    await page.getByLabel(/^비밀번호\s*\*?$/).fill(password)
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page.getByRole('heading', { name: '업무 대시보드' })).toBeVisible()
    await expect(page.locator('.sidebar-user-name')).toHaveText(userName)
    await expect(page.locator('.sidebar-user-role')).toHaveText(roleName)
    await expect(
      page.locator('aside').getByRole('link', { name: '감사로그', exact: true, includeHidden: true }),
    ).toHaveCount(auditMenu ? 1 : 0)
    if (auditMenu) {
      const management = page.locator('aside details').filter({ has: page.locator('summary', { hasText: '관리' }) })
      await management.locator('summary').click()
      await expect(management.getByRole('link', { name: '감사로그', exact: true })).toBeVisible()
    }
    await page.goto('contracts?month=2026-07')
    await expect(page.getByRole('heading', { name: '보험계약 목록', exact: true })).toBeVisible()
    await page.goto('schedules?month=2026-07')
    await page.getByRole('button', { name: '로그아웃' }).click()
    await expect(page).toHaveURL(/\/login/)
    await page.goBack()
    await expect(page).toHaveURL(/\/login/)
    await context.close()
  }
})

test('비로그인 지급 등록 딥링크 → 로그인 → 쿼리·해시 복원', async ({ page }) => {
  const route = '/transactions/new?month=2026-07&status=ACTIVE#form'
  await page.goto(route.slice(1))
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('redirect')).toBe(route)
  await page.getByLabel(/^아이디/).fill('settle01')
  await page.getByLabel(/^비밀번호\s*\*?$/).fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: '지급 건 등록 · 귀속 · 확정' })).toBeVisible()
  await expect(page).toHaveURL(new URL(route, page.url()).href)
})

test('중복 로그인은 이전 브라우저를 차단하고 재로그인 후 경로 복원', async ({ page, browser, baseURL }) => {
  await login(page)
  const otherContext = await browser.newContext({ baseURL })
  try {
    const other = await otherContext.newPage()
    await login(other)
    await page.reload()
    await expect(page).toHaveURL(/reason=duplicate/)
    await expect(
      page.getByText('다른 곳에서 같은 계정으로 로그인해 로그아웃되었습니다. 다시 로그인하세요.'),
    ).toBeVisible()
    await expect(other.getByRole('heading', { name: '보험계약 목록', exact: true })).toBeVisible()
    await page.getByLabel(/^아이디/).fill('settle01')
    await page.getByLabel(/^비밀번호\s*\*?$/).fill(password)
    await page.getByRole('button', { name: '로그인', exact: true }).click()
    await expect(page).toHaveURL(/\/contracts\?/)
  } finally {
    await otherContext.close()
  }
})

test('AUDT A1–A5 목록·필터·페이지·키보드 선택·새로고침·API 대조', async ({ page }, info) => {
  await login(page)
  await completeMonthly(page)
  await login(page, 'audit01')
  const adminSection = page.locator('aside details').filter({ has: page.locator('summary', { hasText: '관리' }) })
  if ((await adminSection.getAttribute('open')) === null) await adminSection.locator('summary').click()
  await adminSection.getByRole('link', { name: '감사로그', exact: true }).click()
  await expect(page).toHaveURL(/\/audit-logs\?/)
  await expect(page.getByRole('heading', { name: '감사로그 조회' })).toBeVisible()
  const table = page.getByRole('table', { name: '검색조건에 해당하는 감사 기록 목록' })
  await expect(table.locator('tbody tr')).toHaveCount(20)
  await page.getByLabel('행위 종류').selectOption('JOURNAL_DRAFT_SAVED')
  const filtered = responseFor(page, '/audit-logs', 'GET')
  await page.getByRole('button', { name: '조회', exact: true }).click()
  const firstPage = await successful(filtered)
  await expect(page).toHaveURL(/action=JOURNAL_DRAFT_SAVED/)
  await expect(page.locator('.audit-panel-caption .tabular-nums')).toHaveText(firstPage.totalElements.toLocaleString('en-US'))
  const paged = responseFor(page, '/audit-logs', 'GET')
  await page.getByRole('button', { name: '2', exact: true }).click()
  const secondPage = await successful(paged)
  await expect(page).toHaveURL(/page=2/)
  await expect(page.locator('.audit-pagination .list-pagination-status')).toHaveText(/^2 \/ \d+ 페이지$/)
  await expect(
    page.getByRole('navigation', { name: '감사로그 목록 페이지 선택' }).locator('[aria-current="page"]'),
  ).toHaveText('2')
  const rows = table.locator('tbody tr')
  await expect(rows).toHaveCount(secondPage.content.length)
  const newCells = await rows.evaluateAll((rows) =>
    rows.map((row) =>
      [...row.querySelectorAll('td')].map((cell) =>
        (cell.querySelector('.table-cell-preview')?.textContent ?? cell.textContent ?? '')
          .replace(/전체 보기|접기/g, '')
          .replace(/\s+/g, ' ')
          .trim(),
      ),
    ),
  )
  // 1차 화면 대조 대신(#419에서 1차 제거) 화면이 받은 API 응답과 행 순서·대상 ID 를 대조한다.
  expect(newCells.map((cells) => cells[4])).toEqual(
    secondPage.content.map((item: { entityId: string }) => item.entityId),
  )
  const row = rows.first()
  await row.focus()
  await page.keyboard.press('Enter')
  await expect(row).toHaveAttribute('aria-selected', 'true')
  await expect(page).toHaveURL(/selected=\d+/)
  await expect(page.locator('#diff-body')).toContainText('JOURNAL_DRAFT_SAVED')
  // A4 includes any non-button cell, in addition to the keyboard path above.
  const mouseRow = rows.nth(1)
  await mouseRow.locator('td').nth(3).click()
  await expect(mouseRow).toHaveAttribute('aria-selected', 'true')
  await expect(row).toHaveAttribute('aria-selected', 'false')
  await expect(page.locator('.audit-diff-context')).toHaveText(
    `${newCells[1][2]} (JOURNAL_DRAFT_SAVED) · ${newCells[1][3]} (JOURNAL_HEADER) #${newCells[1][4]}`,
  )
  const selected = new URL(page.url()).searchParams.get('selected')
  await page.reload()
  await expect(table.locator('tr.is-selected')).toHaveCount(1)
  expect(new URL(page.url()).searchParams.get('selected')).toBe(selected)
  await expect(page.locator('#diff-body')).toContainText('JOURNAL_DRAFT_SAVED')
  await info.attach('react-audit', { body: await page.screenshot(), contentType: 'image/png' })
})

test('AUDT A6 SETTLEMENT 메뉴 숨김·화면 403·API 403', async ({ page }) => {
  await login(page, 'settle01', '/audit-logs?month=2026-07')
  await expect(page.getByRole('heading', { name: '403 · 권한 없음' })).toBeVisible()
  await expect(page.getByRole('main')).toContainText('FGC-AUTH-003')
  await expect(page.getByText('이 작업을 할 권한이 없습니다.', { exact: true })).toBeVisible()
  await expect(page.locator('aside').getByRole('link', { name: /감사로그/ })).toHaveCount(0)
  const auth = await page.request.post('/api/v1/auth/login', {
    data: { loginId: 'settle01', password },
    headers: { Origin: new URL(page.url()).origin, 'X-FGC-Client': 'web' },
  })
  expect(auth.ok()).toBe(true)
  const token = (await auth.json()).data.accessToken
  const denied = await page.request.get('/api/v1/audit-logs', { headers: { Authorization: `Bearer ${token}` } })
  expect(denied.status()).toBe(403)
  expect((await denied.json()).error.code).toBe('FGC-AUTH-003')
})

test('COMPLIANCE 스케줄 확정 버튼 비활성·지급 등록 403', async ({ page }) => {
  await login(page)
  await completeMonthly(page)
  await page.context().clearCookies()
  await login(page, 'audit01', '/schedules?month=2026-07')
  await page.getByRole('table').locator('tbody tr').first().getByRole('link').first().click()
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '새 버전 만들기', exact: true })).toBeDisabled()
  await page.goto('transactions/new?month=2026-07')
  await expect(page.getByRole('heading', { name: '403 · 권한 없음' })).toBeVisible()
})

test('패키징 서버 Swagger가 JWT 인증 후 정상이고 재시작 추적 필드를 노출한다', async ({ request, baseURL }, testInfo) => {
  const backend = 'http://127.0.0.1:18081'
  const auth = await request.post(`${backend}/api/v1/auth/login`, {
    headers: { Origin: new URL(baseURL!).origin, 'X-FGC-Client': 'web' },
    data: { loginId: 'admin', password },
  })
  expect(auth.status()).toBe(200)
  const token = (await auth.json()).data.accessToken
  const response = await request.get(`${backend}/v3/api-docs`, { headers: { Authorization: `Bearer ${token}` } })
  expect(response.status()).toBe(200)
  const document = await response.json()
  expect(document.openapi).toMatch(/^3\./)
  expect(document.paths['/api/v1/validation-runs/{id}/execute']?.post).toBeDefined()
  expect(document.paths['/api/v1/validation-runs/{id}/progress']?.get).toBeDefined()
  const schemas = Object.values(document.components.schemas) as Array<{ properties?: Record<string, unknown> }>
  const progress = schemas.find((schema) => schema.properties?.attemptId && schema.properties?.currentStep)
  expect(progress?.properties).toHaveProperty('requestId')
  await testInfo.attach('packaged-swagger-restart-summary', {
    contentType: 'application/json',
    body: JSON.stringify({ openapi: document.openapi, execute: true, progress: true, fields: Object.keys(progress!.properties!) }),
  })
})
