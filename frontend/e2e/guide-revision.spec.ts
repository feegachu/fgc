import { expect, test, type Page } from '@playwright/test'

const envelope = (data: unknown) => ({ data, error: null, requestId: 'guide-revision' })
const paged = (content: unknown[]) => ({ content, page: 1, totalPages: 1, totalElements: content.length })
async function fixture(page: Page) {
  let status = 'CREATED', posts = 0
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    let data: unknown = paged([])
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'guide-revision', expiresIn: 1800 }
    else if (path.endsWith('/auth/me')) data = { loginId: 'guide', userName: '담당자', roleCode: 'SYSTEM_ADMIN', canProcess: true, canHandleException: true, canFinalizeValidation: true, demoMonth: '2026-07' }
    else if (path.endsWith('/validation-runs/7/execute')) { status = 'COMPLETED'; posts++; data = { validationRunId: 7, status: 'RUNNING' } }
    else if (path.endsWith('/validation-runs/7/progress')) data = { status, currentStep: status === 'CREATED' ? 0 : 8, totalSteps: 10 }
    else if (path.endsWith('/validation-runs/7')) data = {
      header: { validationRunId: 7, validationMonth: '2026-07-01', runNo: 2, runType: 'MONTHLY', status, currentStep: status === 'CREATED' ? 0 : 8 },
      targets: [], targetSummary: { selectedCount: 0, excludedCount: 0, reviewRequiredCount: 0 },
      capSummary: { checkedCount: 1, violationCount: 0, warningCount: 1 }, arbitrageSummary: { candidateCount: 1 }, ledgerSummary: { journalCount: 1, imbalanceCount: 0 },
      reconciliationSummary: { resultCount: 2, mismatchCount: 1 }, exceptionSummary: { detectedCount: 1, newCount: 1, recurringCount: 0, reopenedCount: 0, notDetectedCount: 0, openWorkItemCount: 1 },
    }
    else if (path.endsWith('/retries')) data = []
    else if (path.endsWith('/restartability')) data = { allowed: false }
    else if (path.endsWith('/finalize-checklist')) data = { passed: false, conditions: Array.from({ length: 8 }, (_, index) => ({ no: index + 1, label: `조건 ${index + 1}`, passed: false, count: 1 })) }
    else if (path.endsWith('/reconciliations')) data = paged([1, 2].map(id => ({ reconciliationRunId: id, validationRunId: 7, validationRunNo: 2, settlementMonth: '2026-07-01', paymentStage: 'GA_TO_FC', insurerId: id, insurerName: `보험사 ${id}`, status: 'COMPLETED', targetCount: id, exceptionCount: id, differenceTotal: id * 1000 })))
    else if (/reconciliations\/\d+\/results/.test(path)) {
      const id = Number(path.split('/').at(-2))
      data = { summary: { resultCount: id, matchedCount: 0, exceptionCount: id, differenceTotal: id * 1000 }, items: paged([{ reconciliationResultId: id, reconciliationRunId: id, contractNo: `계약-${id}`, commissionItemName: '기본수수료', resultType: 'AMOUNT_DIFFERENCE', expectedTotalAmount: 1000, actualTotalAmount: 2000, differenceAmount: 1000 }]) }
    }
    else if (path.endsWith('/journals/explorer')) {
      const entries = [1, 2].filter(id => !url.searchParams.get('journalId') || String(id) === url.searchParams.get('journalId')).map(id => ({ journalHeaderId: id, journalNo: `JV-2026-07-000${id}`, journalDate: '2026-07-01', journalType: 'EXPECTED_FC_PAYOUT', status: 'POSTED', debitTotal: id * 1000, creditTotal: id * 1000, lines: [{ lineNo: 1, accountCode: 'EXPENSE', accountName: '예상 지급비용', debitAmount: id * 1000, creditAmount: 0 }] }))
      data = { contracts: paged([{ contractId: 1, contractNo: '계약-1', agentName: '김설계', journalCount: 2 }]), journals: paged(entries), selectedContractId: 1, totals: { journalCount: entries.length, lineCount: entries.length, debitTotal: entries.reduce((sum, row) => sum + row.debitTotal, 0), creditTotal: entries.reduce((sum, row) => sum + row.creditTotal, 0) } }
    }
    else if (/journals\/\d+$/.test(path)) data = { journalHeaderId: 1, journalNo: 'JV-2026-07-0001', lines: [], balanced: true }
    else if (path.endsWith('/exceptions')) data = { ...paged([{ exceptionCaseId: 11, type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', severity: 'HIGH', severityLabel: '높음', status: 'NEW', statusLabel: '신규', contractNo: '계약-1', title: '계약 한도 확인', description: '지급 원천 확인', occurrences: [], actions: [] }]), summary: [{ type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', count: 1 }] }
    else if (path.endsWith('/exceptions/options')) data = { types: [], severities: [], assignees: [], validationMonths: [] }
    else if (path.endsWith('/exceptions/11/actions')) {
      const body = route.request().postDataJSON()
      data = { ...body, actionSeq: 1, fromStatus: 'NEW', toStatus: 'IN_REVIEW', toStatusLabel: '검토중', actionTypeLabel: '검토 시작', actionByLoginId: '담당자' }
    }
    else if (path.endsWith('/transactions')) data = paged([{ commissionTransactionId: 1, sourceBusinessKey: 'SCHEDULE-LINE-GA_TO_FC-202607-01', paymentStage: 'GA_TO_FC', paymentStageLabel: 'GA→설계사', sourceType: 'GA_MANUAL_PAYMENT', sourceTypeLabel: 'GA 수기지급', status: 'DRAFT', statusLabel: '작성중', recipientAgentName: '김설계', commissionItemName: '기본수수료', amount: 650000, attributionCount: 1 }])
    else if (path.endsWith('/journals/accounts')) data = []
    await route.fulfill({ json: envelope(data) })
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  return () => posts
}

test('월 검증은 선 도착 후 버퍼링하고 결과 표시 중 안내를 숨긴다', async ({ page }, info) => {
  test.setTimeout(65000)
  const posts = await fixture(page)
  await page.goto('/validation-runs/7?month=2026-07')
  await page.getByRole('button', { name: '도움말', exact: true }).click()
  const card = page.locator('.screen-step-guide')
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '10단계 진행 상태' })).toBeVisible()
  await expect(page.locator('.help-comparison, .vrun-replay-toolbar')).toHaveCount(0)
  const steps = page.locator('.vrun-stepper-step')
  await expect(steps.nth(0).locator('.vrun-check-mark')).toBeVisible()
  await expect(steps.nth(1)).toHaveAttribute('data-buffering', 'false')
  await expect(steps.nth(1).locator('.vrun-step-spinner')).toBeVisible({ timeout: 3000 })
  await expect(steps.nth(0).locator('.vrun-step-connection')).toHaveClass(/is-filled/)
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '결과 요약 먼저 보기' })).toBeVisible()
  const summary = page.locator('[aria-labelledby="vrun-summary-title"]')
  await expect(summary.locator('.kpi-card')).toHaveCount(6)
  await expect.poll(async () => {
    const a = await summary.boundingBox(), b = await card.boundingBox()
    return !!a && !!b && a.y >= 50 && a.y < 100 && a.y + a.height < b.y
  }).toBe(true)
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '검증 시작' })).toBeVisible()
  await expect.poll(async () => {
    const button = await page.locator('[data-guide-target="validation.execute"]').boundingBox()
    const spotlight = await page.locator('.screen-step-spotlight').boundingBox()
    return !!button && !!spotlight && Math.abs(spotlight.width - button.width - 20) < 2 && spotlight.height < 80
  }).toBe(true)
  expect(posts()).toBe(0)
  await page.getByRole('button', { name: '실행', exact: true }).click()
  await expect(card).toBeHidden()
  await expect.poll(async () => (await summary.boundingBox())?.y ?? 999).toBeLessThan(100)
  await expect(page.getByText(/검증 계산이 끝났습니다/)).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('validation-playing.png') })
  await expect(card.getByRole('heading', { name: '결과 요약', exact: true })).toBeVisible({ timeout: 35000 })
  await expect(page.getByText('검증 계산이 끝났습니다. 결과를 확인해 주세요.', { exact: true })).toBeVisible()
  expect(posts()).toBe(1)
  await page.screenshot({ path: info.outputPath('validation-results.png') })
  for (let index = 0; index < 3; index++) await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '확정 전 8단계 점검' })).toBeVisible()
  await expect.poll(async () => {
    const review = await page.locator('[data-guide-target="validation.finalizeReview"]').boundingBox()
    const spotlight = await page.locator('.screen-step-spotlight').boundingBox()
    const coach = await card.boundingBox()
    return !!review && !!spotlight && !!coach && Math.abs(spotlight.x - review.x + 10) < 2
      && Math.abs(spotlight.width - review.width - 20) < 2
      && spotlight.y + spotlight.height >= Math.min(900, review.y + review.height) - 1 && Math.abs(coach.x - 638) < 2
  }).toBe(true)
  await page.screenshot({ path: info.outputPath('validation-finalize-disabled.png') })
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await page.locator('[data-guide-feature="navigation.menu.reco-w01"]').click()
  await expect(page).toHaveURL(/reconciliations/)
  await expect(page.locator('.screen-step-guide').getByRole('heading', { name: '대사 실행 조건' })).toBeVisible()
})

test('대사 트리 조회와 원장 안내는 선택 후 유지되고 예외함으로 이어진다', async ({ page }, info) => {
  test.setTimeout(45000)
  await fixture(page)
  const writes: string[] = []
  page.on('request', request => { if (/POST|PUT|DELETE|PATCH/.test(request.method()) && !request.url().includes('/auth/')) writes.push(request.url()) })
  await page.goto('/reconciliations?month=2026-07')
  const history = page.locator('[data-guide-target="reconciliation.history"]')
  await expect(history.getByRole('button', { name: '월 통합검증 #2 펼치기' })).toBeVisible()
  await page.getByRole('button', { name: '도움말', exact: true }).click()
  const card = page.locator('.screen-step-guide')
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '실행 이력 펼치기' })).toBeVisible()
  await expect(history).not.toHaveClass(/guide-tree-controls/)
  await expect(history.getByRole('button', { name: '월 통합검증 #2 펼치기' })).toBeVisible()
  await expect.poll(async () => {
    const target = await history.boundingBox(), spotlight = await page.locator('.screen-step-spotlight').boundingBox()
    return !!target && !!spotlight && Math.abs(spotlight.y - target.y + 10) < 2
  }).toBe(true)
  await page.screenshot({ path: info.outputPath('reconciliation-tree-controls.png') })
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '검증원장로 이어가기' })).toBeVisible()
  const box = await card.boundingBox()
  expect(box!.x).toBeGreaterThan(900)
  expect(box!.y + box!.height).toBeLessThanOrEqual(900)
  await expect(page.locator('.screen-step-spotlight')).toHaveCount(1)
  await expect(page.locator('.reco-guide-overview .guide-selection-updated')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('reconciliation-tree-guide.png') })
  await page.locator('[data-guide-feature="navigation.menu.ledg-w01"]').click()
  await expect(page).toHaveURL(/journals/)
  await expect(card.getByRole('heading', { name: '분개 조회 조건' })).toBeVisible()
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '계약과 분개 선택' })).toBeVisible()
  await page.screenshot({ path: info.outputPath('ledger-tree-guide.png') })
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await expect(card.getByRole('heading', { name: '분개 내역과 연결 근거' })).toBeVisible()
  await expect(card.getByRole('button', { name: '다음', exact: true })).toBeDisabled()
  await page.locator('.ledger-entry-table').getByRole('button', { name: 'JV-2026-07-0001', exact: true }).click()
  await expect(card.getByRole('heading', { name: '역분개와 재기표' })).toBeVisible()
  await expect(page.locator('[data-guide-target="ledger.actions"]')).toBeVisible()
  await card.getByRole('button', { name: '다음', exact: true }).click()
  await page.locator('[data-guide-feature="navigation.menu.excp-w01"]').click()
  await expect(page).toHaveURL(/exceptions/)
  await expect(card.getByRole('heading', { name: '유형별 미처리 현황' })).toBeVisible()
  await expect(card).not.toContainText('확인할 점')
  const exceptionBox = await card.boundingBox()
  expect(exceptionBox!.x).toBeGreaterThan(900)
  expect(exceptionBox!.y + exceptionBox!.height).toBeLessThanOrEqual(900)
  await page.screenshot({ path: info.outputPath('exceptions-guide.png') })
  expect(writes).toEqual([])
})

test('예외 상세 모달에서 처리하고 닫으면 목록 조건을 유지한다', async ({ page }, info) => {
  await fixture(page)
  await page.goto('/exceptions?month=2026-07&status=OPEN')
  const list = page.getByRole('table', { name: '예외 목록' })
  await list.getByRole('button', { name: '11', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '예외 상세 · 계약-1', exact: true })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('검출 이력', { exact: true })).toBeVisible()
  await expect(dialog.getByText('처리 이력', { exact: true })).toBeVisible()
  await dialog.getByLabel('조치', { exact: false }).selectOption('START_REVIEW')
  await dialog.getByLabel('처리 사유', { exact: false }).fill('원천 자료 검토')
  await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  const confirm = page.getByRole('dialog', { name: '작성 중인 내용을 닫을까요?' })
  await expect(confirm).toBeVisible()
  await confirm.getByRole('button', { name: '계속 작성' }).click()
  await expect(dialog.getByLabel('처리 사유', { exact: false })).toHaveValue('원천 자료 검토')
  await dialog.getByRole('button', { name: '처리 저장', exact: true }).click()
  await expect(dialog.getByText('1. 검토 시작')).toBeVisible()
  await page.screenshot({ path: info.outputPath('exception-detail-modal.png') })
  await page.locator('.modal-backdrop').filter({ has: dialog }).click({ position: { x: 4, y: 4 } })
  await expect(dialog).toBeHidden()
  await expect(page).toHaveURL(/status=OPEN/)
  await expect(page).not.toHaveURL(/selected=/)
  await expect(list.getByText('검토중', { exact: true })).toBeVisible()
})

test('검증원장 보기 전환은 분개 내역 오른쪽 아이콘에서 바꾸고 계약 요약과 KPI는 분리한다', async ({ page }, info) => {
  await fixture(page)
  await page.goto('/journals?month=2026-07')
  const summary = page.locator('.ledger-contract-summary')
  await expect(summary.getByRole('heading', { name: '계약-1', exact: true })).toBeVisible()
  await expect(summary.locator('.kpi-card')).toHaveCount(0)
  await expect(page.locator('.ledger-balance-summary .kpi-card')).toHaveCount(3)
  expect((await summary.boundingBox())!.height).toBeLessThan(80)
  const view = page.getByRole('region', { name: '계약별 분개 내역' }).getByRole('group', { name: '검증원장 보기 방식' })
  await expect(view.getByRole('button', { name: '계약별 보기', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await view.getByRole('button', { name: '전체 분개 보기', exact: true }).click()
  await expect(page).toHaveURL(/ledgerView=all/)
  await expect(view.getByRole('button', { name: '전체 분개 보기', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.locator('.ledger-contracts')).toHaveCount(0)
  await view.getByRole('button', { name: '계약별 보기', exact: true }).click()
  await expect(page.locator('.ledger-contracts')).toBeVisible()
  await page.screenshot({ path: info.outputPath('ledger-compact-summary.png'), fullPage: true })
})

test('지급 표의 헤더 색과 열 너비 조절이 일치한다', async ({ page }, info) => {
  await fixture(page)
  await page.goto('/transactions?month=2026-07')
  const table = page.locator('.transaction-list-table')
  await expect(table.getByRole('link', { name: / 수정$/ })).toBeVisible()
  const headings = table.locator('thead th')
  expect(await headings.last().evaluate(el => getComputedStyle(el).backgroundColor)).toBe(await headings.first().evaluate(el => getComputedStyle(el).backgroundColor))
  const handle = page.getByRole('separator', { name: '지급 관리번호 열 너비', exact: true })
  const before = Number(await handle.getAttribute('aria-valuenow'))
  const rect = (await handle.boundingBox())!
  await page.mouse.move(rect.x + rect.width / 2, rect.y + rect.height / 2)
  await page.mouse.down()
  await page.mouse.move(rect.x + rect.width / 2 + 140, rect.y + rect.height / 2, { steps: 10 })
  await page.mouse.up()
  await expect.poll(async () => Number(await handle.getAttribute('aria-valuenow'))).toBeGreaterThan(before + 100)
  await handle.focus()
  await page.keyboard.press('ArrowLeft')
  await page.keyboard.press('Home')
  await expect.poll(async () => Number(await handle.getAttribute('aria-valuenow'))).toBeLessThan(before + 5)
  const item = page.getByRole('separator', { name: '수수료 항목 열 너비', exact: true })
  await item.focus()
  for (let i = 0; i < 15; i++) await page.keyboard.press('ArrowRight')
  await table.locator('..').evaluate(el => { el.scrollLeft = el.scrollWidth })
  await expect(table.getByRole('columnheader', { name: '지급/차감', exact: true })).toBeInViewport()
  await expect(table.getByRole('columnheader', { name: '상태', exact: true })).toBeInViewport()
  await expect(table.getByRole('columnheader', { name: '작업', exact: true })).toBeInViewport()
  await page.screenshot({ path: info.outputPath('payment-resize.png') })
})
