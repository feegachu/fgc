import { expect, test } from '@playwright/test'

test('활성 계정 API에 없는 기존 계정으로 과거 원장을 조회할 수 있다', async ({ page }) => {
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/auth/refresh')
      ? { accessToken: 'ledger-fixture' }
      : path.endsWith('/auth/me')
        ? {
            loginId: 'fixture',
            userName: '담당자',
            roleCode: 'SETTLEMENT',
            canReverseJournal: true,
            demoMonth: '2026-07',
          }
        : path.endsWith('/journals/accounts')
          ? [{ accountCode: 'EXPECTED_INCOME', accountName: '예상 수수료수익 대응', activeYn: true }]
          : { content: [], page: 1, totalPages: 0 }
    await route.fulfill({ json: { data, error: null, requestId: 'ledger-history-fixture' } })
  })
  await page.goto('journals?month=2026-07')
  await expect(page.getByRole('option', { name: '예상 수수료미수금' })).toBeEnabled()
  await page.getByLabel('계정과목').selectOption('EXPECTED_RECEIVABLE')
  const response = page.waitForResponse(
    (res) => new URL(res.url()).searchParams.get('account') === 'EXPECTED_RECEIVABLE',
  )
  await page.getByRole('button', { name: '조회', exact: true }).click()
  expect((await response).ok()).toBe(true)
  await expect(page).toHaveURL(/account=EXPECTED_RECEIVABLE/)
})

const envelope = (data: unknown) => ({ data, error: null, requestId: 'ledger-fixture' })
const original = {
  journalHeaderId: 11,
  journalNo: 'JRN-001',
  journalDate: '2026-07-01',
  journalType: 'EXPECTED_FC_PAYOUT',
  journalTypeLabel: '예상 지급(GA→설계사)',
  status: 'POSTED',
  statusLabel: '기표됨',
  debitTotal: 1200000,
  creditTotal: 1200000,
  balanced: true,
  validationRunId: 3,
  lines: [{ lineNo: 1, accountCode: 'EXPENSE', accountName: '비용', debitAmount: 1200000, creditAmount: 0 }],
}

test('LEDG-W01 조건 조회 → 행 선택 → 역분개 생성 → 상태 갱신', async ({ page }) => {
  let reversed = false
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    let data: unknown
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'ledger-fixture' }
    else if (path.endsWith('/auth/me'))
      data = {
        loginId: 'fixture',
        userName: '담당자',
        roleCode: 'SETTLEMENT',
        demoMonth: '2026-07',
        canReverseJournal: true,
      }
    else if (path.endsWith('/journals/accounts')) data = []
    else if (path === '/api/v1/journals')
      data = { content: [original], page: 1, size: 20, totalElements: 1, totalPages: 1, sort: 'journalDate,desc' }
    else if (path.endsWith('/journals/11'))
      data = reversed
        ? { ...original, status: 'REVERSED', statusLabel: '역분개됨', reversedByJournalHeaderId: 12 }
        : original
    else if (path.endsWith('/imbalances')) data = { totalCount: 0, content: [] }
    else if (path.endsWith('/reverse')) {
      expect(route.request().postDataJSON()).toEqual({ reason: '금액 정정', evidenceRef: 'DOC-1' })
      expect(route.request().headers().authorization).toBe('Bearer ledger-fixture')
      reversed = true
      data = { journalHeaderId: 12, reversalOfId: 11 }
    } else throw new Error(`unexpected ${path}`)
    await route.fulfill({ json: envelope(data) })
  })
  await page.goto('journals')
  await expect(page.getByText('조회 조건을 설정하고 조회 버튼을 눌러 주세요.')).toBeVisible()
  await page.getByRole('button', { name: '조회', exact: true }).click()
  await page.getByRole('button', { name: 'JRN-001', exact: true }).click()
  await page.getByRole('button', { name: '역분개', exact: true }).click()
  const modal = page.getByRole('dialog')
  await modal.getByRole('textbox', { name: '역분개 사유', exact: true }).fill('금액 정정')
  await modal.getByLabel('증빙 참조').fill('DOC-1')
  await modal.getByRole('button', { name: '역분개 생성' }).click()
  await expect(modal).not.toBeVisible()
  await expect(page.getByRole('region', { name: '분개 상세' }).getByText('역분개됨')).toBeVisible()
})

/** #418 재현용. 격리된 QA DB와 인증 환경변수를 지정할 때만 실제 쓰기를 실행한다. */
test('실서버 LEDG-W01 전후 목록·상세 대조 및 역분개', async ({ page }) => {
  const loginId = process.env.FGC_LEDGER_QA_LOGIN_ID
  const password = process.env.FGC_LEDGER_QA_PASSWORD
  test.skip(!loginId || !password || process.env.FGC_LEDGER_QA_WRITE !== 'true', '격리 QA DB와 인증 환경변수 필요')
  const query = 'month=2026-07&from=2026-07-01&to=2026-07-31&searched=true'
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('http://localhost:8081/login')
  await page.locator('input[name="username"]').fill(loginId!)
  await page.locator('input[name="password"]').fill(password!)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForURL((url) => !url.pathname.endsWith('/login'))
  await page.goto(`http://localhost:8081/journals?${query}`)
  const legacyRows = await page.locator('#list-body tr[data-journal-id]').allTextContents()
  expect(legacyRows.length).toBeGreaterThan(0)
  const eligibleIndex = legacyRows.findIndex((row) => !row.includes('역분개') && row.includes('기표됨'))
  expect(eligibleIndex).toBeGreaterThanOrEqual(0)
  const selected = await page
    .locator('#list-body tr[data-journal-id]')
    .nth(eligibleIndex)
    .getAttribute('data-journal-id')
  const oldDetailResponse = page.waitForResponse((response) => response.url().endsWith(`/api/v1/journals/${selected}`))
  await page.goto(`http://localhost:8081/journals?${query}&selected=${selected}`)
  const oldDetail = (await (await oldDetailResponse).json()).data
  await expect(page.locator('#detail-body')).toContainText(oldDetail.journalNo)
  await expect(page.locator('#balance-banner')).toBeVisible()
  await page.goto(`login?redirect=${encodeURIComponent(`/journals?${query}`)}`)
  await page.getByRole('textbox', { name: /아이디/ }).fill(loginId!)
  await page.getByRole('textbox', { name: /비밀번호/ }).fill(password!)
  const searchResponse = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/journals')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  const data = (await (await searchResponse).json()).data
  const list = page.getByRole('table', { name: '조회 조건에 해당하는 검증원장 분개 목록' })
  await expect(list.getByRole('row')).toHaveCount(data.content.length + 1)
  expect(data.content.length).toBe(legacyRows.length)
  for (let i = 0; i < data.content.length; i++) expect(legacyRows[i]).toContain(data.content[i].journalNo)
  const newDetailResponse = page.waitForResponse((response) => response.url().endsWith(`/api/v1/journals/${selected}`))
  await list.getByRole('button', { name: oldDetail.journalNo, exact: true }).click()
  expect((await (await newDetailResponse).json()).data).toEqual(oldDetail)
  await expect(page.getByRole('button', { name: '역분개', exact: true })).toBeEnabled()
  await expect(page.getByText('선택한 분개의 검증 실행은 원장 불균형이 0건입니다.')).toBeVisible()
  await page.getByRole('button', { name: '역분개', exact: true }).click()
  await page.getByRole('textbox', { name: '역분개 사유', exact: true }).fill('413 격리 QA 역분개 검증')
  await page.getByLabel('증빙 참조').fill('QA-413')
  await page.getByRole('button', { name: '역분개 생성' }).click()
  await expect(page.getByRole('region', { name: '분개 상세' }).getByText('역분개됨')).toBeVisible()
})
