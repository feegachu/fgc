import { expect, test } from '@playwright/test'

const envelope = (data: unknown) => ({ data, error: null, requestId: 'exception-reco-fixture' })

test('청약철회 지급 원인과 계약 타임라인을 보고 예외 상세로 돌아온다', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1600, height: 1000 })
  const item = { exceptionCaseId: 13, type: 'RECONCILIATION_MISMATCH', typeLabel: '대사 불일치', reasonCode: 'INVALID_CONTRACT_PAYMENT', reasonLabel: '비유효 계약 지급',
    severity: 'HIGH', severityLabel: '높음', status: 'NEW', statusLabel: '미처리', contractId: 3, contractNo: 'FGC-FGL01-202610-0166', agentName: '손설계',
    title: '대사 불일치, 차액 650,000원', description: '지급단계 GA→설계사 예상 0원 / 실제 650,000원 차액 650,000원',
    sourceEntityType: 'RECONCILIATION_RESULT', sourceEntityId: '1473', sourceLink: '/reconciliations?resultId=1473', validationMonth: '2026-10-01', detectionCount: 1, occurrences: [], actions: [] }
  const invalid = { reconciliationResultId: 1473, reconciliationRunId: 41, contractId: 3, contractNo: item.contractNo, commissionItemName: '기본수수료', expectedTotalAmount: 0,
    actualTotalAmount: 650000, differenceAmount: 650000, resultType: 'INVALID_CONTRACT_PAYMENT', resultTypeLabel: '비유효 계약 지급', matches: [], detailSnapshot: { paymentStage: 'GA_TO_FC' }, createdAt: '2026-11-16T09:37:00+09:00' }
  const matched = { ...invalid, reconciliationResultId: 1474, commissionItemName: '유지수수료(현행)', expectedTotalAmount: 4000, actualTotalAmount: 4000, differenceAmount: 0, resultType: 'MATCHED', resultTypeLabel: '일치' }
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    let data: unknown = {}
    if (path === '/api/v1/auth/refresh') data = { accessToken: 'fixture', tokenType: 'Bearer', expiresIn: 1800 }
    else if (path === '/api/v1/auth/me') data = { loginId: 'settle01', userName: '정산 담당자', roleCode: 'SETTLEMENT', canProcess: true, canHandleException: true, demoMonth: '2026-10' }
    else if (path === '/api/v1/dashboard/summary') data = { capWarning: 0, arbitrageCandidate: 0 }
    else if (path === '/api/v1/exceptions/options') data = { types: [{ code: 'RECONCILIATION_MISMATCH', label: '대사 불일치' }], reasons: [], severities: [{ code: 'HIGH', label: '높음' }], assignees: [], assignableUsers: [], validationMonths: [] }
    else if (path === '/api/v1/exceptions') data = { summary: [{ type: item.type, typeLabel: item.typeLabel, count: 1 }], content: [item], page: 1, size: 20, totalPages: 1, totalElements: 1 }
    else if (path === '/api/v1/contracts/3') data = { contractId: 3, contractStatus: 'CANCELLED', contractStatusLabel: '청약철회' }
    else if (path === '/api/v1/contracts/3/status-events') data = [
      { eventId: 1, eventSeq: 1, newStatus: 'ACTIVE', newStatusLabel: '정상', effectiveAt: '2026-10-01T00:00:00+09:00', reason: '신규 계약', followUp: { status: 'NOT_REQUIRED' } },
      { eventId: 2, eventSeq: 2, previousStatus: 'ACTIVE', newStatus: 'CANCELLED', newStatusLabel: '청약철회', effectiveAt: '2026-10-12T00:00:00+09:00', reason: '계약자 청약철회 접수', followUp: { status: 'SUCCEEDED' } },
    ]
    else if (path === '/api/v1/reconciliations/results/1473/basis') data = { detected: invalid, currentRunId: null, currentResults: [] }
    else if (path === '/api/v1/reconciliations/results/1473') data = invalid
    else if (path === '/api/v1/reconciliations/results/1474') data = matched
    else if (path === '/api/v1/reconciliations/41/results') {
      expect(url.searchParams.get('contractId')).toBe('3')
      data = { items: { content: [invalid, matched], totalPages: 1 } }
    }
    await route.fulfill({ json: envelope(data) })
  })
  await page.goto('/exceptions?month=2026-10&status=NEW&selected=13')
  const parent = page.getByRole('dialog', { name: /^예외 상세/ })
  await expect(parent.getByRole('list', { name: '계약 상태 변화' })).toContainText('청약철회')
  await expect(parent.getByText(/검출 당시 계약 상태가 지급 조건에 맞지 않아/)).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('exception-detail.png'), fullPage: true })
  await parent.getByRole('textbox', { name: /증빙/ }).fill('기존 입력 보존 확인')
  await expect(parent.getByRole('button', { name: '대사 결과 열기' }).locator('svg')).toBeVisible()
  const originalUrl = page.url()
  await parent.getByRole('button', { name: '대사 결과 열기' }).click()
  const modal = page.getByRole('dialog', { name: '계약 대사 결과' })
  await expect(modal.getByRole('list', { name: '계약 상태 변화' })).toHaveCount(0)
  await expect(modal.getByText(/검출 당시 계약 상태가 지급 조건에 맞지 않아/)).toHaveCount(0)
  await expect(modal.getByRole('table')).toContainText('유지수수료(현행)')
  await expect(modal.getByRole('tablist')).toHaveCount(0)
  await expect(modal.locator('.exception-reco-amounts .kpi-card')).toHaveCount(3)
  await expect(modal.locator('.exception-reco-amounts .kpi-value').first()).toHaveCSS('animation-name', 'none')
  const cause = modal.locator('.exception-reco-cause')
  const [causeBox, amountBox] = await Promise.all([cause.boundingBox(), modal.locator('.exception-reco-amounts').boundingBox()])
  expect(Math.abs(causeBox!.width - amountBox!.width)).toBeLessThan(2)
  const firstRow = modal.getByRole('table').locator('tbody tr').first()
  await expect(firstRow).toHaveCSS('background-color', 'rgb(255, 255, 255)')
  expect((await firstRow.boundingBox())!.height).toBeLessThanOrEqual(48)
  expect(Number.parseFloat(await modal.locator('.exception-reco-current-status .status-badge').evaluate(el => getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(24)
  await expect(modal.locator('.exception-reco-current-status .status-badge')).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
  expect(await modal.locator('.exception-reco-current-status .status-badge').evaluate(el => getComputedStyle(el, '::before').width)).toBe('12px')
  await page.screenshot({ path: testInfo.outputPath('contract-reconciliation.png'), fullPage: true })
  await modal.getByRole('button', { name: '뒤로가기' }).click()
  await expect(modal).toHaveCount(0)
  expect(page.url()).toBe(originalUrl)
  await expect(parent.getByRole('textbox', { name: /증빙/ })).toHaveValue('기존 입력 보존 확인')
  await expect(parent.getByRole('list', { name: '계약 상태 변화' })).toBeVisible()
})

