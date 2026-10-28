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
  // 진입 시 기준 정산월을 자동 조회한다. 조건 재조회 후에도 행 선택을 유지한다.
  await expect(page.getByRole('button', { name: 'JRN-001', exact: true })).toBeVisible()
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
