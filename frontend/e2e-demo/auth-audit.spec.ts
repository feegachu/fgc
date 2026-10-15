import { test, expect, login, legacyLogin, backend, completeMonthly, password } from './fixtures'

test('로그인 4개 역할·보호 딥링크·로그아웃', async ({ browser, baseURL }) => {
  for (const account of ['admin', 'gaadmin', 'settle01', 'audit01']) {
    const context = await browser.newContext({ baseURL })
    const page = await context.newPage()
    await login(page, account)
    await expect(page.getByRole('heading', { name: '보험계약 목록', exact: true })).toBeVisible()
    await page.goto('schedules?month=2026-07')
    await page.getByRole('button', { name: '로그아웃' }).click()
    await expect(page).toHaveURL(/\/login/)
    await page.goBack()
    await expect(page).toHaveURL(/\/login/)
    await context.close()
  }
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

test('AUDT A1–A5 목록·필터·페이지·키보드 선택·새로고침·1차 비교', async ({ page }, info) => {
  await login(page)
  await completeMonthly(page)
  await legacyLogin(page, 'audit01')
  await page.goto(`${backend}/audit-logs?action=JOURNAL_DRAFT_SAVED&page=2`)
  const legacyRows = page.locator('tr[data-audit-detail-href]')
  await expect(legacyRows.first()).toBeVisible()
  const oldCells = await legacyRows.evaluateAll((rows) =>
    rows.map((row) =>
      [...row.querySelectorAll('td')].map((cell) =>
        (cell.querySelector('.table-cell-preview')?.textContent ?? cell.textContent ?? '').replace(/\s+/g, ' ').trim(),
      ),
    ),
  )
  await info.attach('legacy-audit', { body: await page.screenshot(), contentType: 'image/png' })
  await login(page, 'audit01')
  const adminSection = page.locator('aside details').filter({ has: page.locator('summary', { hasText: '관리' }) })
  if ((await adminSection.getAttribute('open')) === null) await adminSection.locator('summary').click()
  await adminSection.getByRole('link', { name: '감사로그', exact: true }).click()
  await expect(page).toHaveURL(/\/audit-logs\?/)
  await expect(page.getByRole('heading', { name: '감사로그 조회' })).toBeVisible()
  const table = page.getByRole('table', { name: '검색조건에 해당하는 감사 기록 목록' })
  await expect(table.locator('tbody tr')).toHaveCount(20)
  await page.getByLabel('행위 종류').selectOption('JOURNAL_DRAFT_SAVED')
  await page.getByRole('button', { name: '조회', exact: true }).click()
  await expect(page).toHaveURL(/action=JOURNAL_DRAFT_SAVED/)
  await page.getByRole('button', { name: '2', exact: true }).click()
  await expect(page).toHaveURL(/page=2/)
  await expect(page.locator('.audit-pagination-status')).toContainText('2 /')
  const rows = table.locator('tbody tr')
  await expect(rows).toHaveCount(oldCells.length)
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
  expect(newCells).toEqual(oldCells)
  const row = rows.first()
  await row.focus()
  await page.keyboard.press('Enter')
  await expect(row).toHaveAttribute('aria-selected', 'true')
  await expect(page).toHaveURL(/selected=\d+/)
  await expect(page.locator('#diff-body')).toContainText('JOURNAL_DRAFT_SAVED')
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
