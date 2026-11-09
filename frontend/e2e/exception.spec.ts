import { expect, test } from '@playwright/test'
import { chooseDetailedHelp, readDetailedHelp } from './screenHelp'

// 네트워크를 모킹한 EXCP-W01 시나리오: 목록 조회 → 행 선택 → 조치 저장. 실서버 시나리오는 #418 범위다.
const envelope = (data: unknown) => ({ data, error: null, requestId: 'excp-fixture' })

for (const assignee of [{ id: 7, loginId: 'settle01' }, { id: 8, loginId: 'settle02' }]) {
test(`예외 담당자를 ${assignee.loginId}에게 배정하고 본인 배정이면 검토를 이어간다`, async ({ page }, testInfo) => {
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ json: envelope({ accessToken: 'excp-fixture', tokenType: 'Bearer', expiresIn: 1800 }) }),
  )
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: envelope({
        loginId: 'settle01',
        userName: '정산 담당자',
        roleCode: 'SETTLEMENT',
        canProcess: true,
        canViewAuditLog: false,
        canHandleException: true,
        demoMonth: '2026-07',
      }),
    }),
  )
  await page.route('**/api/v1/exceptions/options', (route) =>
    route.fulfill({
      json: envelope({
        types: [{ code: 'CAP_VIOLATION', label: '1,200% 위반' }],
        reasons: [],
        severities: [{ code: 'HIGH', label: '높음' }],
        assignees: [],
        assignableUsers: [{ userId: 7, loginId: 'settle01' }, { userId: 8, loginId: 'settle02' }],
        validationMonths: [],
      }),
    }),
  )
  const searchUrls: string[] = []
  await page.route('**/api/v1/exceptions/11/review', route => route.fulfill({ json: envelope({ links: [], recheckAvailable: false, administratorReviewRequired: false, rejectionRequiresEvidence: false }) }))
  await page.route('**/api/v1/exceptions?**', (route) => {
    searchUrls.push(route.request().url())
    return route.fulfill({
      json: envelope({
        summary: [{ type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', count: 1 }],
        content: [
          {
            exceptionCaseId: 11,
            type: 'CAP_VIOLATION',
            typeLabel: '1,200% 위반',
            severity: 'HIGH',
            severityLabel: '높음',
            status: 'NEW',
            statusLabel: '신규',
            title: '한도 초과 계약',
            contractNo: 'C-0001',
            contractId: 3,
            detectionCount: 1,
            occurrences: [],
            actions: [],
          },
        ],
        page: 1,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      }),
    })
  })
  const posts: { actionType: string; assignedTo?: number; reason: string }[] = []
  await page.route('**/api/v1/exceptions/11/actions', (route) => {
    const body = route.request().postDataJSON() as (typeof posts)[number]
    posts.push(body)
    return route.fulfill({
      json: envelope({
        actionSeq: posts.length,
        fromStatus: 'NEW',
        toStatus: 'IN_REVIEW',
        actionType: body.actionType,
        actionTypeLabel: body.actionType === 'ASSIGN' ? '담당 배정' : '검토 시작',
        fromStatusLabel: '신규',
        toStatusLabel: '검토중',
        reason: body.reason,
        actionBy: 7,
        actionByLoginId: 'settle01',
        actionAt: '2026-07-11T01:00:00+09:00',
      }),
    })
  })

  await page.goto('exceptions')
  await expect(page.getByRole('heading', { name: '예외함' })).toBeVisible()
  await expect(page.getByRole('link', { name: '1,200% 위반 1건' })).toBeVisible()
  expect(new URL(searchUrls[0]).searchParams.get('status')).toBe('OPEN')

  await chooseDetailedHelp(page)
  await readDetailedHelp(page, page.getByRole('button', { name: '도움말', exact: true }).first(), 3)
  await page.getByRole('button', { name: '11' }).click()
  const dialog = page.getByRole('dialog', { name: /^예외 상세/ })
  const help = dialog.locator('.modal-header').getByRole('button', { name: '도움말', exact: true })
  await expect(help.locator('svg')).toBeVisible()
  await expect(dialog.getByText('예외 정보', { exact: true })).toBeVisible()
  await expect(dialog.getByText('담당 배정 · 검토', { exact: true })).toBeVisible()
  await expect(page.getByLabel('처리 사유')).toHaveCount(0)
  const process = dialog.getByRole('button', { name: '예외 건 처리' })
  await expect(process).toBeDisabled()
  await dialog.locator('.exception-process-tooltip').hover()
  await expect(dialog.getByRole('tooltip')).toContainText('먼저 검토 시작')
  await readDetailedHelp(page, help, 4)
  await expect(page.getByLabel('조치').getByRole('option', { name: '의견', exact: true })).toHaveCount(0)
  const save = page.getByRole('button', { name: '처리 저장' })
  await page.getByLabel('조치').selectOption('ASSIGN')
  await expect(page.getByLabel('배정 담당자').getByRole('option', { name: /settle01/ })).toHaveCount(0)
  await expect(save).toBeDisabled()
  if (assignee.id === 7) await page.getByLabel('조치').selectOption('START_REVIEW')
  else await page.getByLabel('배정 담당자').selectOption(String(assignee.id))
  const reason = assignee.id === 7 ? '지급 원천 검토를 시작합니다.' : '계약 담당자에게 검토를 요청합니다.'
  await page.getByLabel('처리 사유').fill(reason)
  await save.click()
  await expect(page.getByText(assignee.id === 7 ? '1. 검토 시작' : '1. 담당 배정')).toBeVisible()
  await expect(page.getByRole('table', { name: '예외 목록' }).getByText(assignee.loginId)).toBeVisible()
  await expect(page.getByRole('table', { name: '예외 목록' }).getByText('검토중')).toBeVisible()
  if (assignee.id === 7) await expect(process).toBeEnabled()
  else {
    await expect(process).toBeDisabled()
    await dialog.locator('.exception-process-tooltip').hover()
    await expect(dialog.getByRole('tooltip')).toContainText('settle02 담당자가 검토중')
  }
  expect(posts).toHaveLength(1)
  expect(posts[0].reason).toBe(reason)
  await dialog.screenshot({ path: testInfo.outputPath('exception-detail.png') })
  if (assignee.id === 7) {
    await page.route('**/api/v1/contracts/3', route => route.fulfill({ json: envelope({
      contractId: 3, contractNo: 'C-0001', contractStatus: 'ACTIVE', insurerName: '보험회사', productName: '종신보험',
      monthlyEquivalentFirstPremium: 76000, agentName: '순설계', contractDate: '2026-07-01',
    }) }))
    await page.route('**/api/v1/contracts/3/schedules?**', route => route.fulfill({ json: envelope({ headers: [], lines: [] }) }))
    await page.route('**/api/v1/exceptions/11/payment-correction', route => route.fulfill({ json: envelope({
      payments: [], scheduledBasis: true, correctionTarget: 'SCHEDULE', paymentStage: 'GA_TO_FC',
      scheduleLink: '/schedules/11', policyLink: '/policies?tab=management&policyVersionId=4&asOf=2026-07-01',
      capBasis: { capCheck: { includedAmount: 988000, limitAmount: 912000 }, details: [
        { classificationSnapshot: 'INCLUDED', commissionItemName: '기본수수료', amount: 900000 },
        ...Array.from({ length: 11 }, () => ({ classificationSnapshot: 'INCLUDED', commissionItemName: '유지수수료', amount: 8000 })),
      ] },
    }) }))
    await process.click()
    const source = page.getByRole('dialog', { name: '예상 스케줄 확인·재생성' })
    await expect(source.getByText('GA→설계사 · 예상 스케줄 기준 한도 검증')).toBeVisible()
    await expect(source.getByText('988,000원', { exact: true })).toBeVisible()
    await expect(source.getByText('76,000원', { exact: true })).toBeVisible()
    await expect(source.getByText('유지수수료 · 11회')).toBeVisible()
    await expect(source.getByRole('link', { name: '예상 스케줄 확인' })).toHaveAttribute('href', '/schedules/11')
    await expect(source.getByLabel('수정할 지급 건')).toHaveCount(0)
    await expect(source.getByRole('button', { name: '수정 저장' })).toHaveCount(0)
    await expect(source.locator('.modal-footer').getByRole('button', { name: '스케줄 재검증' })).toBeVisible()
    await expect(source.getByRole('button', { name: '스케줄 재생성', exact: true })).toBeVisible()
    await expect(source.getByRole('table', { name: '보험회사→GA 예상 스케줄' })).toBeVisible()
    await expect(source.getByRole('table', { name: 'GA→설계사 예상 스케줄' })).toBeVisible()
    await source.screenshot({ path: testInfo.outputPath('schedule-source.png') })
    await source.getByRole('button', { name: '닫기', exact: true }).click()
    await expect(dialog).toBeVisible()
    const payment = { commissionTransactionId: 77, sourceType: 'GA_MANUAL_PAYMENT', status: 'CONFIRMED',
      paymentStage: 'GA_TO_FC', contractId: 3, agentId: 1, commissionItemId: 1, sourceBusinessKey: 'GA-77',
      commissionItemName: '시책수수료', settlementMonth: '2026-07-01', scheduledPaymentDate: '2026-07-25',
      cashflowType: 'PAYMENT', amount: 205000, updatedAt: '2026-07-31T09:00:00+09:00', attributions: [
        { contractId: 3, attributionDate: '2026-07-25', amount: 205000, inclusionDecisionStatus: 'INCLUDED', attributionMethod: 'DIRECT' },
      ] }
    await page.unroute('**/api/v1/exceptions/11/payment-correction')
    await page.route('**/api/v1/exceptions/11/payment-correction', route => route.fulfill({ json: envelope({ payments: [payment],
      correctionTarget: 'PAYMENT', scheduledBasis: false, remainingIssues: [{ resultId: 8, resultType: 'AMOUNT_DIFFERENCE',
        resultLabel: '금액 차이', expectedAmount: 100000, actualAmount: 205000, differenceAmount: 105000, installmentNo: 1,
        expectedAgent: '순설계', actualAgent: '순설계', sourceLink: '/reconciliations?resultId=8' }],
    }) }))
    await page.route('**/api/v1/base/agents?**', route => route.fulfill({ json: envelope({ content: [
      { agentId: 1, agentCode: 'FC-001', agentName: '순설계', rankCode: 'FC', agentStatus: 'ACTIVE' },
    ] }) }))
    await page.route('**/api/v1/base/commission-items/search?**', route => route.fulfill({ json: envelope({ content: [
      { commissionItemId: 1, itemCode: 'INCENTIVE', itemName: '시책수수료', cashflowType: 'PAYMENT' },
    ] }) }))
    await page.route('**/api/v1/contracts?**', route => route.fulfill({ json: envelope({ content: [
      { contractId: 3, contractNo: 'C-0001', productName: '종신보험', contractDate: '2026-07-01' },
    ] }) }))
    await page.route('**/api/v1/exceptions/11/payment-correction/precheck', route => route.fulfill({ json: envelope({ confirmable: true, capPreview: [], blockers: [] }) }))
    await process.click()
    const correction = page.getByRole('dialog', { name: '수수료 지급 건 등록·수정' })
    const comparison = correction.getByRole('table', { name: '현재 대사 금액 비교' })
    await expect(comparison.getByRole('cell', { name: '100,000원', exact: true })).toBeVisible()
    await expect(comparison.getByRole('cell', { name: '105,000원', exact: true }).locator('span')).toHaveCSS('color', 'rgb(185, 28, 28)')
    await expect(comparison.getByText('금액 차이')).toHaveClass(/status-badge-error/)
    await expect(correction.getByLabel(/금액 \(원\)/)).toBeEnabled()
    const footer = correction.locator('.modal-footer')
    await footer.getByRole('button', { name: '사전검증', exact: true }).click()
    await expect(footer.getByRole('button', { name: '수정 저장', exact: true })).toBeEnabled()
    await expect(footer.getByRole('button')).toHaveText(['calculate사전검증', 'save수정 저장', '닫기'])
    await correction.screenshot({ path: testInfo.outputPath('payment-correction.png') })
  }

})
}
