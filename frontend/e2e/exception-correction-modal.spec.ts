import { expect, test, type Locator, type Page } from '@playwright/test'

const envelope = (data: unknown) => ({ data, error: null, requestId: 'correction-modal-fixture' })
const readTextLines = (locator: Locator) => locator.evaluate(element => {
  const range = document.createRange()
  range.selectNodeContents(element)
  return range.getClientRects().length
})
for (const viewport of [{ width: 1365, height: 768 }, { width: 390, height: 844 }]) {
  test(`지급 원장 정정 버튼이 화면 안에 보인다 ${viewport.width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport)
    await page.route('**/api/v1/**', route => {
      const path = new URL(route.request().url()).pathname
      let data: unknown = {}
      if (path.endsWith('/auth/refresh')) data = { accessToken: 'fixture', tokenType: 'Bearer', expiresIn: 1800 }
      else if (path.endsWith('/auth/me')) data = { loginId: 'settle01', userName: '정산 담당자', roleCode: 'SETTLEMENT',
        canProcess: true, canHandleException: true, canReverseJournal: true, demoMonth: '2026-10' }
      else if (path.endsWith('/exceptions/options')) data = { types: [], reasons: [], severities: [], assignableUsers: [], assignees: [], validationMonths: [] }
      else if (path.endsWith('/exceptions')) data = { summary: [], content: [{ exceptionCaseId: 11,
        type: 'RECONCILIATION_MISMATCH', typeLabel: '대사 불일치', reconciliationResultType: 'AMOUNT_DIFFERENCE',
        status: 'IN_REVIEW', statusLabel: '검토중', severity: 'HIGH', severityLabel: '높음', assignedTo: 7, assigneeLoginId: 'settle01',
        contractId: 3, contractNo: 'FGC-FGL01-202610-0109', title: '금액 차이 20,000원 — 예상 845,000 / 실제 825,000',
        validationMonth: '2026-10-01', occurrences: [], actions: [] }], page: 1, size: 20, totalElements: 1, totalPages: 1 }
      else if (path.endsWith('/payment-correction')) data = { payments: [{ commissionTransactionId: 77, amount: 825000, status: 'CONFIRMED' }],
        paymentJournalIds: { '77': 70 }, reconciliationResultType: 'AMOUNT_DIFFERENCE' }
      else if (path.endsWith('/repost-number-preview')) data = { reversalJournalNo: 'JV-2026-10-0096', repostedJournalNo: 'JV-2026-10-0097' }
      else if (path.endsWith('/journals/accounts')) data = [{ accountCode: '5000', accountName: '확정 설계사 지급비용' }, { accountCode: '2000', accountName: '확정 지급채무' }]
      else if (path.endsWith('/journals/70')) data = { journalHeaderId: 70, journalNo: 'JV-2026-10-0095', journalDate: '2026-10-19',
        journalType: 'CONFIRMED_FC_PAYOUT', journalTypeLabel: '확정 지급(GA→설계사)', status: 'POSTED', statusLabel: '기표됨', balanced: true,
        contractNo: 'FGC-FGL01-202610-0109', debitTotal: 825000, creditTotal: 825000, description: '확정 지급 건 GA-202610-L713',
        lines: [{ lineNo: 1, accountCode: '5000', accountName: '확정 설계사 지급비용', debitAmount: 825000, creditAmount: 0 },
          { lineNo: 2, accountCode: '2000', accountName: '확정 지급채무', debitAmount: 0, creditAmount: 825000 }] }
      return route.fulfill({ json: envelope(data) })
    })
    await page.goto('/exceptions?selected=11')
    await page.getByRole('button', { name: '예외 건 처리', exact: true }).click()
    const modal = page.getByRole('dialog', { name: '검증원장 지급 기록 정정' })
    const correction = modal.locator('.modal-footer').getByRole('button', { name: '역분개 + 재기표', exact: true })
    await expect(correction).toBeEnabled()
    await expect(correction).toBeInViewport({ ratio: 1 })
    await expect(modal.getByRole('button', { name: '닫기', exact: true })).toBeInViewport({ ratio: 1 })
    await modal.locator('.modal-body').evaluate(element => { element.scrollTop = element.scrollHeight })
    await expect(correction).toBeInViewport({ ratio: 1 })
    await page.screenshot({ path: testInfo.outputPath('journal-correction-footer.png') })
    await correction.click()
    const editor = page.getByRole('dialog', { name: '역분개 + 재기표', exact: true })
    const journalTable = editor.getByRole('table', { name: '원분개, 역분개 및 신규 재기표' })
    await expect(journalTable.locator('tbody tr')).toHaveCount(6)
    await expect(journalTable.locator('tbody').first().getByText('JV-2026-10-0095')).toBeVisible()
    await expect(editor.getByText('JV-2026-10-0097')).toBeVisible()
    const summary = editor.getByText(/예상 845,000 \/ 실제 825,000/)
    await expect(summary).toBeVisible()
    expect(await readTextLines(summary)).toBeLessThanOrEqual(viewport.width > 400 ? 1 : 2)
    await expect(editor.getByRole('button', { name: '등록', exact: true })).toBeInViewport({ ratio: 1 })
  })
}

async function openPaymentCorrection(page: Page, scenario: { type: string; reconciliationResultType?: string; description?: string },
  respond: (path: string, body: unknown) => unknown) {
  await page.route('**/api/v1/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    let data: unknown
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'fixture', tokenType: 'Bearer', expiresIn: 1800 }
    else if (path.endsWith('/auth/me')) data = { loginId: 'settle01', userName: '정산 담당자', roleCode: 'SETTLEMENT',
      canProcess: true, canHandleException: true, canReverseJournal: true, demoMonth: '2026-11' }
    else if (path.endsWith('/exceptions/options')) data = { types: [], reasons: [], severities: [], assignableUsers: [], assignees: [], validationMonths: [] }
    else if (path.endsWith('/exceptions')) data = { summary: [], content: [{ exceptionCaseId: 11, ...scenario,
      status: 'IN_REVIEW', assignedTo: 7, assigneeLoginId: 'settle01', contractId: 3, contractNo: 'FGC-TEST-0001',
      title: '검증 예외', validationMonth: '2026-11-01', occurrences: [], actions: [] }], page: 1, size: 20, totalElements: 1, totalPages: 1 }
    else data = respond(path, request.postDataJSON()) ?? {}
    return route.fulfill({ json: envelope(data) })
  })
  await page.goto('/exceptions?selected=11')
  await page.getByRole('button', { name: '예외 건 처리', exact: true }).click()
}

test('두 지급 분개를 같은 모달에서 비교하고 고른 건만 역분개 및 재대사한다', async ({ page }) => {
  const updatedAt = '2026-11-09T09:00:00+09:00'
  const payments = [225, 226].map((id, index) => ({ commissionTransactionId: id, amount: 80000, status: 'CONFIRMED',
    sourceBusinessKey: `GA-202611-L837${index ? '-2' : ''}`, updatedAt }))
  const submissions: unknown[] = []
  await openPaymentCorrection(page, { type: 'RECONCILIATION_MISMATCH', reconciliationResultType: 'DUPLICATE' }, (path, body) => {
    if (path.endsWith('/payment-correction')) return { payments, paymentJournalIds: { '225': 90, '226': 91 }, reconciliationResultType: 'DUPLICATE' }
    if (path.endsWith('/cancel-duplicate')) {
      submissions.push(body)
      return { resolved: true, message: '중복 지급 기록을 역분개 및 취소하고 재대사 일치를 확인했습니다.' }
    }
    if (/\/journals\/(90|91)$/.test(path)) {
      const id = Number(path.split('/').at(-1))
      return { journalHeaderId: id, journalNo: `JV-2026-11-00${id}`, journalDate: '2026-11-09', journalType: 'CONFIRMED_FC_PAYOUT',
        status: 'POSTED', debitTotal: 80000, creditTotal: 80000, description: payments[id - 90].sourceBusinessKey,
        lines: [{ lineNo: 1, accountName: '지급비용', debitAmount: 80000 }, { lineNo: 2, accountName: '지급채무', creditAmount: 80000 }] }
    }
  })
  const modal = page.getByRole('dialog', { name: '검증원장 중복 지급' })
  const table = modal.getByRole('table', { name: '분개별 차변 및 대변 내역' })
  await expect(table.locator('tbody tr')).toHaveCount(4)
  const reverse = modal.getByRole('button', { name: '역분개 후 재대사' })
  await expect(reverse).toBeDisabled()
  await table.getByRole('button', { name: 'JV-2026-11-0091' }).click()
  await expect(table.getByRole('button', { name: 'JV-2026-11-0090' })).toHaveAttribute('aria-pressed', 'false')
  await reverse.click()
  const confirmation = page.getByRole('dialog', { name: '선택한 지급 분개를 역분개할까요?' })
  await expect(confirmation.getByText(/JV-2026-11-0091.*GA-202611-L837-2/)).toBeVisible()
  expect(submissions).toHaveLength(0)
  await confirmation.getByRole('button', { name: '역분개하고 재대사' }).click()
  await expect(modal).toBeHidden()
  expect(submissions).toEqual([{ paymentId: 226, expectedUpdatedAt: updatedAt, evidenceRef: null }])
})

test('한도 초과 안내가 전체 너비를 쓰고 실제 초과액을 표시한다', async ({ page }) => {
  await openPaymentCorrection(page, { type: 'CAP_VIOLATION',
    description: '지급단계 GA_TO_FC 기준일 2026-11-05 한도액 960,000원 산입액 1,012,000원 잔여 -52,000원 사용률 105.416667% 초과액 52,000원' },
  path => path.endsWith('/payment-correction') ? { payments: [], capBasis: { capCheck: { includedAmount: 1012000, limitAmount: 960000 } } } : undefined)
  const modal = page.getByRole('dialog', { name: '수수료 지급 건 등록 및 수정' })
  const heading = modal.getByText('한도 초과 최소 52,000원 줄여야 합니다.', { exact: true })
  await expect(heading).toBeVisible()
  expect(await readTextLines(heading)).toBe(1)
  await expect(modal.getByText(/지급단계 GA→설계사.*사용률 105\.4%/)).toBeVisible()
})

test('지급 누락 모달은 안내와 별도 재대사 버튼 없이 대사 차이를 흰 표에 표시한다', async ({ page }) => {
  await openPaymentCorrection(page, { type: 'RECONCILIATION_MISMATCH', reconciliationResultType: 'ACTUAL_MISSING' },
    path => path.endsWith('/payment-correction') ? { payments: [], reconciliationResultType: 'ACTUAL_MISSING', canRegisterPayment: false,
      remainingIssues: [{ resultId: 42, expectedAmount: 22000, actualAmount: 0, differenceAmount: -22000, resultLabel: '실제 없음(누락)' }] } : undefined)
  const modal = page.getByRole('dialog', { name: '수수료 지급 건 등록 및 수정' })
  const table = modal.getByRole('table', { name: '현재 대사 금액 비교' })
  await expect(table.locator('tbody tr')).toHaveCSS('background-color', 'rgb(255, 255, 255)')
  await expect(modal.getByText('누락 지급 등록 재대사')).toHaveCount(0)
  await expect(modal.getByRole('button', { name: '재대사', exact: true })).toHaveCount(0)
})
