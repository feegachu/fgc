import { expect, test } from '@playwright/test'

test('저장된 대사 제목은 상세 원인으로 표시하고 내부 정정 키는 숨긴다', async ({ page }) => {
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = {}
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'fixture', tokenType: 'Bearer', expiresIn: 1800 }
    else if (path.endsWith('/auth/me')) data = { loginId: 'settle01', userName: '정산 담당자', roleCode: 'SETTLEMENT', canProcess: true, canHandleException: true, demoMonth: '2026-10' }
    else if (path.endsWith('/exceptions/options')) data = { types: [], reasons: [], severities: [], assignableUsers: [], assignees: [], validationMonths: [] }
    else if (path.endsWith('/exceptions')) data = { summary: [], content: [{ exceptionCaseId: 373,
      type: 'RECONCILIATION_MISMATCH', typeLabel: '대사 불일치', reasonCode: 'INVALID_CONTRACT_PAYMENT', reasonLabel: '비유효 계약 지급',
      title: '대사 불일치 — 차액 650,000원', contractId: 3, contractNo: 'FGC-FGL01-202610-0166',
      status: 'NEW', statusLabel: '신규', severity: 'HIGH', severityLabel: '높음', validationMonth: '2026-10-01', detectionCount: 1,
      actions: [{ actionSeq: 1, actionTypeLabel: '정정', reason: '원장 정정 후 재대사 일치 확인 (JCG-3595-7aada2ace7524fd2b3d849f5b619302d, 대사 167) · 지급 금액 확인', evidenceRef: 'JCG-3595-7aada2ace7524fd2b3d849f5b619302d' }], occurrences: [] }], page: 1, size: 20, totalElements: 1, totalPages: 1 }
    return route.fulfill({ json: { data, error: null, requestId: 'exception-copy' } })
  })
  await page.goto('/exceptions')
  const row = page.getByRole('row').filter({ has: page.getByRole('button', { name: '373', exact: true }) })
  await expect(row).toContainText('비유효 계약 지급, 차액 650,000원')
  await expect(row).not.toContainText('대사 불일치, 차액')
  await row.getByRole('button', { name: '373', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '예외 상세 FGC-FGL01-202610-0166' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('원장 정정 후 재대사 일치 확인 지급 금액 확인')
  await expect(dialog).not.toContainText('JCG-')
  await expect(dialog.locator('.exception-history-list')).not.toContainText('·')
})
