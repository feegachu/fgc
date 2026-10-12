import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

// #410 browser interaction fixtures; #418 can run the same demo paths against the real server
// with TransactionScreensIntegrationTest / scripts/verify-transaction.mjs.
const envelope = (data: unknown) => ({ data, error: null, requestId: 'transaction-e2e' })
const contracts = [
  { contractId: 1, contractNo: 'DEMO-CONTRACT-1', productName: '종신보험', contractDate: '2026-07-10' },
  { contractId: 2, contractNo: 'DEMO-CONTRACT-2', productName: '종신보험', contractDate: '2026-07-12' },
]

async function installApi(page: Page, options: { violation?: boolean; reader?: boolean; retryConfirmation?: boolean; lostConfirmationResponse?: boolean } = {}) {
  const requests: { path: string; method: string; key?: string }[] = []
  let payment: Record<string, unknown> = {}
  let confirmationAttempts = 0
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    requests.push({ path: path + url.search, method: request.method(), key: request.headers()['idempotency-key'] })
    let data: unknown
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'transaction-fixture', tokenType: 'Bearer', expiresIn: 1800 }
    else if (path.endsWith('/auth/me')) data = {
      loginId: options.reader ? 'audit01' : 'settle01', userName: '검증 사용자',
      roleCode: options.reader ? 'COMPLIANCE' : 'SETTLEMENT', canProcess: !options.reader,
      canViewAuditLog: Boolean(options.reader), canHandleException: !options.reader, demoMonth: '2026-07',
    }
    else if (path === '/api/v1/base/agents') data = { content: [
      { agentId: 6, agentCode: 'A-BM-001', agentName: '한지사', rankCode: 'BRANCH_MANAGER', agentStatus: 'ACTIVE', activeYn: true },
    ] }
    else if (path === '/api/v1/base/commission-items') data = [
      { commissionItemId: 1, itemCode: 'MANAGEMENT_COMMISSION', itemName: '관리자수수료', cashflowType: 'PAYMENT' },
    ]
    else if (path === '/api/v1/base/insurers') data = { content: [{ insurerId: 1, insurerName: '시연보험', insurerCode: 'DEMO' }] }
    else if (path === '/api/v1/contracts') data = { content: contracts }
    else if (path === '/api/v1/transactions' && request.method() === 'POST') {
      payment = { ...request.postDataJSON(), commissionTransactionId: 101, status: 'DRAFT' }
      return route.fulfill({ status: 201, json: envelope(payment) })
    } else if (path === '/api/v1/transactions/101/precheck') data = {
      paymentId: 101, confirmable: !options.violation,
      blockers: options.violation ? [{ code: 'FGC-CAP-001', message: '1,200% 한도를 초과했습니다.', contractId: 1, transactionAttributionId: 11 }] : [],
      capPreview: [{ contractId: 1, contractNo: contracts[0].contractNo, paymentStage: 'GA_TO_FC', paymentStageLabel: 'GA→설계사',
        asOfDate: '2026-07-31', basePremiumAmount: 100000, refund12mAmount: 0, complianceDeductionAmount: 0,
        limitAmount: 1200000, existingIncludedAmount: 890000, candidateAmount: options.violation ? 1300000 : 20,
        includedAmount: options.violation ? 2190000 : 890020, remainingAmount: options.violation ? -990000 : 309980,
        usagePct: options.violation ? '182.500000' : '74.168333', resultStatus: options.violation ? 'VIOLATION' : 'NORMAL',
        resultStatusLabel: options.violation ? '위반' : '정상', capRuleSetId: 1, capCheckId: null }],
    }
    else if (path === '/api/v1/transactions/101/confirm') {
      confirmationAttempts++
      if (options.retryConfirmation && confirmationAttempts === 1)
        return route.fulfill({ status: 401, json: { data: null, error: { code: 'FGC-AUTH-002', message: '인증이 필요합니다.' }, requestId: 'retry-auth' } })
      if (options.violation)
        return route.fulfill({ status: 422, json: { data: null, error: { code: 'FGC-CAP-001', message: '1,200% 한도를 초과했습니다.' }, requestId: 'recorded-violation' } })
      payment = { ...payment, status: 'CONFIRMED', capCheckIds: [201, 202] }
      // Model a committed operation whose HTTP response is lost. Retrying must use the
      // original key and return the existing result, rather than creating a second payment.
      if (options.lostConfirmationResponse && confirmationAttempts === 1) return route.abort('failed')
      data = payment
    } else if (path === '/api/v1/transactions/101') data = payment
    else if (path === '/api/v1/transactions') data = {
      content: payment.commissionTransactionId ? [{ ...payment, recipientAgentId: 6, recipientAgentName: '한지사', commissionItemName: '관리자수수료',
        paymentStageLabel: 'GA→설계사', sourceTypeLabel: 'GA 수기지급', cashflowTypeLabel: '지급', statusLabel: '확정', attributionCount: 2, differenceAmount: 0 }] : [],
      page: Number(url.searchParams.get('page') || 1), size: 20, totalElements: payment.commissionTransactionId ? 1 : 0, totalPages: 1,
    }
    else return route.fulfill({ status: 404, json: { data: null, error: { code: 'FGC-COMMON-002', message: path }, requestId: 'unexpected-fixture-request' } })
    return route.fulfill({ json: envelope(data) })
  })
  return { requests, payment: () => payment }
}

async function enterDraft(page: Page, amount: number, count: number) {
  await page.goto('transactions/new?month=2026-07')
  await page.getByLabel('수령 설계사', { exact: false }).selectOption('6')
  await page.getByLabel('수수료 항목', { exact: false }).selectOption('1')
  await page.locator('#bizKey').fill('E2E-MULTI-CONTRACT')
  await page.locator('#amount').fill(String(amount))
  for (let index = 0; index < count; index++) {
    await page.getByRole('button', { name: '귀속행 추가' }).click()
    const row = page.locator('#attr-body tr').filter({ has: page.locator('[data-contract-id]') }).last()
    await row.locator('[data-contract-id]').selectOption(String(index + 1))
    await row.locator('[data-attr-date]').fill('2026-07-31')
    await row.locator('[data-attr-amount]').fill(String(amount / count))
  }
  await expect(page.locator('#gate-list .transaction-gate-step')).toHaveCount(6)
  await page.locator('#btn-save').click()
  await expect(page.locator('#amount')).toBeDisabled()
  await expect(page.locator('#btn-confirm')).toBeDisabled()
  await page.locator('#btn-precheck').click()
  await expect(page.locator('#btn-confirm')).toBeEnabled()
  await page.locator('#btn-confirm').click()
  await expect(page.getByRole('dialog', { name: '지급 건 확정' })).toBeVisible()
}

test('복수 계약 등록 → 사전검증 → 같은 멱등키로 인증 재시도 → 확정', async ({ page }) => {
  const fixture = await installApi(page, { retryConfirmation: true })
  await enterDraft(page, 20, 2)
  expect(fixture.payment().attributions).toHaveLength(2)
  await expect(page.locator('#gate-list .transaction-gate-pass')).toHaveCount(6)
  await page.getByRole('button', { name: '확정 실행', exact: true }).click()
  await expect(page).toHaveURL(/\/transactions(?:\?.*)?$/)
  await expect(page.locator('#list-body')).toContainText('E2E-MULTI-CONTRACT')
  const confirms = fixture.requests.filter((request) => request.path.endsWith('/confirm'))
  expect(confirms).toHaveLength(2)
  expect(confirms.map((request) => request.key)).toEqual(['TRAN-CONFIRM-101', 'TRAN-CONFIRM-101'])
})

test('한도 초과는 확정 시도 후 DRAFT를 유지하고 예외함 링크를 보여 준다', async ({ page }) => {
  const fixture = await installApi(page, { violation: true })
  await enterDraft(page, 1300000, 1)
  await expect(page.getByRole('link', { name: '예외함에서 처리하기' })).toHaveCount(0)
  await page.getByRole('button', { name: '확정 시도 · 예외 등록' }).click()
  await expect(page.getByRole('link', { name: '예외함에서 처리하기' })).toHaveAttribute('href', /\/app\/exceptions\?type=CAP_VIOLATION&contractNo=DEMO-CONTRACT-1(?:&month=2026-07)?$/)
  await expect(page.locator('#btn-confirm-submit')).toBeHidden()
  await expect(page.getByRole('dialog')).toContainText('1,200% 한도를 초과했습니다.')
  expect(fixture.payment().status).toBe('DRAFT')
  expect(fixture.requests.filter((request) => request.path.endsWith('/confirm'))).toHaveLength(1)
})

test('확정 응답이 유실되어도 명시적 재시도는 같은 멱등키를 사용한다', async ({ page }) => {
  const fixture = await installApi(page, { lostConfirmationResponse: true })
  await enterDraft(page, 20, 2)
  const confirm = page.getByRole('button', { name: '확정 실행', exact: true })
  await confirm.click()
  await expect(page.locator('#transaction-confirm-blockers')).toBeVisible()
  await expect(confirm).toBeEnabled()
  await confirm.click()
  await expect(page).toHaveURL(/\/transactions(?:\?.*)?$/)
  const confirms = fixture.requests.filter((request) => request.path.endsWith('/confirm'))
  expect(confirms.map((request) => request.key)).toEqual(['TRAN-CONFIRM-101', 'TRAN-CONFIRM-101'])
  expect(fixture.payment().capCheckIds).toEqual([201, 202])
})

test('COMPLIANCE는 지급 등록 경로에 접근할 수 없다', async ({ page }) => {
  const fixture = await installApi(page, { reader: true })
  await page.goto('transactions/new')
  await expect(page.getByRole('heading', { name: '403 · 권한 없음' })).toBeVisible()
  await expect(page.locator('#btn-save')).toHaveCount(0)
  expect(fixture.requests.some((request) => request.path.startsWith('/api/v1/transactions'))).toBe(false)
})

test('귀속 표의 가로 스크롤은 페이지 전체 너비를 늘리지 않는다', async ({ page }) => {
  await installApi(page)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('transactions/new?month=2026-07')
  await expect(page.locator('#recipient')).toBeEnabled()
  const layout = await page.evaluate(() => ({
    viewport: window.innerWidth,
    document: document.documentElement.scrollWidth,
    overflowing: Array.from(document.querySelectorAll('body *')).filter((element) =>
      element.getBoundingClientRect().right > window.innerWidth + 1,
    ).slice(0, 8).map((element) => ({ tag: element.tagName, className: element.className, width: element.getBoundingClientRect().width })),
  }))
  expect(layout.document, JSON.stringify(layout)).toBeLessThanOrEqual(layout.viewport)
})

test('기준월 없는 목록 딥링크에 기본월을 넣어도 2페이지와 숨은 필터를 보존한다', async ({ page }) => {
  const fixture = await installApi(page)
  await page.goto('transactions?settlementMonth=2026-07&page=2&attributionImbalanceOnly=true')
  await expect(page.getByRole('heading', { name: '수수료 지급 건 목록' })).toBeVisible()
  await expect(page).toHaveURL(/month=2026-07/)
  expect(new URL(page.url()).searchParams.get('page')).toBe('2')
  expect(new URL(page.url()).searchParams.get('attributionImbalanceOnly')).toBe('true')
  await expect.poll(() => fixture.requests.some((request) => {
    const url = new URL(request.path, 'http://fixture.local')
    return url.pathname === '/api/v1/transactions' && url.searchParams.get('page') === '2'
      && url.searchParams.get('attributionImbalanceOnly') === 'true'
  })).toBe(true)
  await page.reload()
  await expect(page.getByRole('heading', { name: '수수료 지급 건 목록' })).toBeVisible()
  expect(new URL(page.url()).searchParams.get('page')).toBe('2')
})
