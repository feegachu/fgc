import { test, expect, login, completeMonthly } from './fixtures'

test.use({ video: 'off' })

test('FUN-088 실제 서버: 전체 업무 가이드는 계약 생성 화면에서 시작하고 저장을 대신하지 않는다', async ({ page }) => {
  await login(page, 'settle01', '/?month=2026-07')
  const writes: string[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/')) writes.push(path)
  })
  await page.getByRole('button', { name: '전체 업무 가이드', exact: true }).click()
  const overview = page.getByRole('dialog', { name: '전체 업무 가이드', exact: true })
  await expect(overview.getByRole('list', { name: '전체 업무 9단계' }).getByRole('listitem')).toHaveCount(9)
  await overview.getByRole('button', { name: '계약 생성부터 시작' }).click()
  await expect(page).toHaveURL(/\/contracts\/new/)
  const guide = page.getByRole('complementary', { name: '전체 업무 가이드' })
  await expect(guide).toContainText('계약 등록 · 1/9')
  await expect(guide.getByRole('heading', { name: '계약일', exact: true })).toBeVisible()
  await guide.getByRole('button', { name: '접기', exact: true }).click()
  await page.locator('input[name=contractNo]').fill('E2E-GUIDE-UNSAVED')
  await guide.getByRole('button', { name: '펼치기', exact: true }).click()
  await expect(page.locator('input[name=contractNo]')).toHaveValue('E2E-GUIDE-UNSAVED')
  await guide.getByRole('button', { name: '종료', exact: true }).click()
  await expect(guide).toHaveCount(0)
  await expect(page.locator('input[name=contractNo]')).toHaveValue('E2E-GUIDE-UNSAVED')
  expect(writes).toEqual([])
})

test('FUN-088 실제 서버: 대사 범위 시연 → 왼쪽 메뉴로 검증원장 → 예외함 안내', async ({ page }, info) => {
  await page.goto('login')
  await completeMonthly(page)
  await login(page, 'settle01', '/reconciliations?month=2026-07')
  const writes: string[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/')) writes.push(path)
  })
  await page.getByRole('button', { name: '도움말', exact: true }).click()
  const card = page.locator('.screen-step-guide')
  await card.getByRole('button', { name: '다음', exact: true }).click()
  const history = page.locator('[data-guide-target="reconciliation.history"]')
  await expect(history.locator('[aria-expanded="true"]')).toHaveCount(0)
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(page.locator('.payment-guide-spotlight')).toHaveCount(1)
  await expect(history.locator('[aria-expanded="true"]').first()).toBeVisible({ timeout: 15000 })
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await page.locator('[data-guide-feature="navigation.menu.ledg-w01"]').click()
  await expect(page).toHaveURL(/\/journals/)
  await expect(card).toBeVisible()
  for (let index = 0; index < 4; index++) await card.getByRole('button', { name: '다음', exact: true }).click()
  await page.locator('[data-guide-feature="navigation.menu.excp-w01"]').click()
  await expect(page).toHaveURL(/\/exceptions/)
  await expect(card.getByRole('heading', { name: '유형별 미처리 현황' })).toBeVisible()
  expect(writes).toEqual([])
  await info.attach('reconciliation-ledger-exceptions', { body: await page.screenshot(), contentType: 'image/png' })
})
