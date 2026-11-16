import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

// #411 UI 회귀. 실제 서버 대조는 FGC_CAP_REAL_SERVER=1일 때 별도로 실행한다(#418 인계).
const envelope = (data: unknown) => ({ data, error: null, requestId: 'cap-ui-fixture' })
const item = {
  capCheckId: 11,
  contractId: 3,
  contractNo: 'C-2026-0001',
  paymentStage: 'GA_TO_FC',
  paymentStageLabel: 'GA → 설계사',
  asOfDate: '2026-07-01',
  basePremiumAmount: 100000,
  refund12mAmount: 0,
  complianceDeductionAmount: 0,
  limitAmount: 1200000,
  includedAmount: 1250000,
  remainingAmount: -50000,
  usagePct: '104.1666679',
  resultStatus: 'VIOLATION',
  resultStatusLabel: '위반',
  capRuleSetId: 7,
}
async function fixture(page: Page) {
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ json: envelope({ accessToken: 'cap-ui-fixture', tokenType: 'Bearer', expiresIn: 1800 }) }),
  )
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: envelope({
        loginId: 'audit01',
        userName: '준법감사자',
        roleCode: 'COMPLIANCE',
        canProcess: false,
        canViewAuditLog: true,
        demoMonth: '2026-07',
      }),
    }),
  )
  await page.route('**/api/v1/base/insurers?**', (route) =>
    route.fulfill({
      json: envelope({ content: [{ insurerId: 1, insurerCode: 'INS-1', insurerName: '한빛생명', activeYn: true }] }),
    }),
  )
  await page.route('**/api/v1/base/organizations?**', (route) =>
    route.fulfill({
      json: envelope({
        content: [{ organizationId: 2, organizationCode: 'ORG-2', organizationName: '서울', activeYn: true }],
      }),
    }),
  )
  await page.route('**/api/v1/cap-checks?**', (route) =>
    route.fulfill({
      json: envelope({
        content: [item],
        summary: { normal: 0, warning: 0, violation: 1, reviewRequired: 0 },
        stageSummary: [],
        agentSummary: [],
        totalElements: 1,
        totalPages: 1,
        page: 1,
        size: 20,
      }),
    }),
  )
  await page.route('**/api/v1/cap-checks/*/details', (route) => {
    if (route.request().url().includes('/999/'))
      return route.fulfill({
        status: 404,
        json: {
          data: null,
          error: { code: 'FGC-COMMON-004', message: '저장된 판정을 찾을 수 없습니다.' },
          requestId: 'cap-missing',
        },
      })
    return route.fulfill({
      json: envelope({
        capCheck: item,
        calculationSnapshot: { premiumMultiplier: 12, warningUsagePct: '90' },
        details: [
          {
            detailSeq: 1,
            commissionItemName: '기본수수료',
            amount: 1040000,
            paymentStatus: 'CONFIRMED',
            classificationSnapshot: 'INCLUDED',
            decisionReason: '저장 당시 산입',
            evidenceRef: 'REG-09',
          },
          {
            detailSeq: 2,
            commissionItemName: '정착지원금(경력)',
            amount: 210000,
            classificationSnapshot: 'INCLUDED',
            paymentStatus: 'DRAFT',
            decisionReason: '경력 설계사 정착지원금의 모집월 신계약 귀속',
            evidenceRef: 'SYNTHETIC:FILMING:SETTLEMENT_SUPPORT',
          },
        ],
      }),
    })
  })
}

test('필터 URL, 계산근거 및 Escape 닫기 및 딥링크를 확인한다', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 })
  await fixture(page)
  await page.goto('cap-checks?month=2026-07')
  const table = page.getByRole('table', { name: '계약별 판정 목록' })
  await expect(table.getByText('104.1%')).toBeVisible()
  await expect(table.getByText('-50,000원')).toHaveCSS('color', 'rgb(185, 28, 28)')
  await expect(table.getByRole('link', { name: 'C-2026-0001' })).toHaveAttribute('href', '/contracts/3?tab=cap')
  await page.getByLabel('계약번호', { exact: true }).fill('C-2026-0001')
  await page.getByRole('button', { name: '조회', exact: true }).click()
  await expect(page).toHaveURL(/contractNo=C-2026-0001/)
  await table.getByRole('button', { name: 'C-2026-0001 계산근거 열기' }).click()
  const dialog = page.getByRole('dialog', { name: '계산근거' })
  await expect(dialog.getByRole('table', { name: '항목별 산입 내역' })).toBeVisible()
  await expect(dialog.getByRole('table', { name: '항목별 산입 내역' })).toBeVisible()
  const lines = dialog.getByRole('table', { name: '항목별 산입 내역' })
  await expect(lines.getByText('확정', { exact: true })).toBeVisible()
  await expect(lines.getByText('작성중', { exact: true })).toBeVisible()
  await expect(lines.getByRole('button', { name: '정착지원금 지급 근거' })).toBeVisible()
  await expect(lines.locator('tfoot')).toContainText('산입 합계1,250,000원')
  await expect(lines.locator('tfoot')).toContainText('한도 1,200,000원')
  await expect(lines.locator('tfoot')).toContainText('초과액 50,000원')
  await expect(lines.locator('.cap-react-detail-excess')).toHaveCSS('color', 'rgb(185, 28, 28)')
  await expect(lines.locator('.cap-react-detail-excess')).toHaveCSS('font-weight', '700')
  await expect(lines.locator('.cap-react-detail-totals')).toHaveCSS('justify-content', /^(flex-start|flow-start|start)$/)
  await page.screenshot({ path: '../output/settlement-cap-detail.png', fullPage: true })
  await expect(dialog.getByRole('link', { name: '예외 목록 보기' })).toHaveAttribute('href', '/exceptions')
  await page.keyboard.press('Escape')
  await expect(dialog).not.toBeVisible()
  await expect(page).not.toHaveURL(/capCheckId/)
  await page.goto('cap-checks?capCheckId=11')
  await expect(dialog.getByRole('table', { name: '항목별 산입 내역' })).toBeVisible()
})

test('잘못된 상세 딥링크는 목록을 막지 않는다', async ({ page }) => {
  await fixture(page)
  await page.goto('cap-checks?capCheckId=999')
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('저장된 판정을 찾을 수 없습니다.')
  await page.getByRole('button', { name: '창 닫기' }).click()
  await expect(
    page.getByRole('table', { name: '계약별 판정 목록' }).getByRole('link', { name: 'C-2026-0001' }),
  ).toBeVisible()
})

test('예외 상세에서 탭 이동 없이 처리 후 계산근거와 이연 내역을 열고 돌아온다', async ({ page }) => {
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.route('**/api/v1/**', route => route.fulfill({ json: envelope({}) }))
  await fixture(page)
  await page.route('**/api/v1/exceptions/options', route => route.fulfill({ json: envelope({ types: [], reasons: [], severities: [], assignees: [], assignableUsers: [], validationMonths: [] }) }))
  await page.route('**/api/v1/exceptions?**', route => route.fulfill({ json: envelope({ content: [{ exceptionCaseId: 11,
    type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', status: 'RESOLVED', statusLabel: '해결', severity: 'CRITICAL', severityLabel: '긴급',
    title: '한도 초과', contractNo: item.contractNo, capCheckId: 11, capBasisLink: '/cap-checks?capCheckId=11',
    description: '지급단계 GA→설계사 기준일 2026-07-01 한도액 1,200,000원 산입액 1,250,000원 잔여 -50,000원 사용률 104.1% 초과액 50,000원',
    actions: [], occurrences: [],
  }], summary: [], page: 1, totalElements: 1, totalPages: 1 }) }))
  let reads = 0
  await page.route('**/api/v1/exceptions/11/cap-basis', route => {
    reads++
    return route.fulfill({ json: envelope({
      detectedBasis: { capCheck: item, details: [{ detailSeq: 1, commissionItemName: '정착지원금', amount: 210000, classificationSnapshot: 'INCLUDED', paymentStatusSnapshot: 'DRAFT' }] },
      currentBasis: { capCheck: { ...item, includedAmount: 1200000, remainingAmount: 0, usagePct: '100', resultStatus: 'NORMAL', resultStatusLabel: '정상' },
        details: [{ detailSeq: 1, commissionItemName: '정착지원금', amount: 160000, classificationSnapshot: 'INCLUDED', paymentStatus: 'CONFIRMED' }] },
      deferredPayments: [{ commissionTransactionId: 12, commissionItemName: '정착지원금', amount: 50000, scheduledPaymentDate: '2027-07-01', status: 'DRAFT', attributions: [{ attributionDate: '2027-07-01' }] }],
    }) })
  })
  await page.goto('exceptions?selected=11&status=ALL')
  const source = page.getByRole('dialog', { name: /^예외 상세/ })
  const open = source.getByRole('button', { name: '1,200% 계산근거 열기' })
  await open.click()
  const basis = page.getByRole('dialog', { name: '계산근거' })
  await expect(basis).toHaveCSS('width', '1440px')
  await expect(basis.getByRole('table', { name: '항목별 산입 내역' })).toContainText('160,000원')
  await expect(basis.getByRole('table', { name: '이연 지급 내역' })).toContainText('50,000원')
  await expect(page).toHaveURL(/exceptions\?selected=11&status=ALL/)
  await page.screenshot({ path: '../output/cap-after-correction.png', fullPage: true })
  await basis.getByRole('button', { name: '검출 당시 보기' }).click()
  await expect(basis.getByRole('table', { name: '항목별 산입 내역' })).toContainText('210,000원')
  await basis.getByRole('button', { name: '뒤로가기' }).click()
  await expect(basis).toHaveCount(0)
  await expect(source).toBeVisible()
  await open.click()
  await expect(basis.getByRole('table', { name: '항목별 산입 내역' })).toContainText('160,000원')
  await expect.poll(() => reads).toBe(2)
})

test('좁은 화면에서도 지급단계의 큰 합계 금액을 모두 표시한다', async ({ page }) => {
  await fixture(page)
  await page.route('**/api/v1/cap-checks?**', (route) =>
    route.fulfill({
      json: envelope({
        content: [],
        summary: { normal: 1234, warning: 0, violation: 0, reviewRequired: 0 },
        stageSummary: ['INSURER_TO_GA', 'GA_TO_FC'].map((paymentStage) => ({
          paymentStage,
          contractCount: 1234,
          limitAmountTotal: 1234567890,
          includedAmountTotal: 1000000000,
          complianceDeductionAmountTotal: 0,
          usagePct: '81.000000',
        })),
        agentSummary: [],
        totalElements: 0,
        totalPages: 0,
        page: 1,
        size: 20,
      }),
    }),
  )
  await page.goto('cap-checks?month=2026-07')
  const cards = page.locator('.cap-react-stage')
  await expect(cards).toHaveCount(2)
  for (const width of [901, 1000, 1100, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    for (const card of await cards.all()) {
      await expect(card.getByText('1,234,567,890원', { exact: true })).toBeVisible()
      await expect(card.getByText('1,000,000,000원', { exact: true })).toBeVisible()
      for (const value of await card.locator('dd').all()) {
        // DOM에 값이 있어도 말줄임으로 숨겨질 수 있으므로 실제 텍스트 영역을 확인한다.
        await expect(async () => {
          const fits = await value.evaluate((element) => {
            const range = document.createRange()
            range.selectNodeContents(element)
            const bounds = element.getBoundingClientRect()
            return Array.from(range.getClientRects()).every(
              (rect) =>
                rect.left >= bounds.left - 1 &&
                rect.right <= bounds.right + 1 &&
                rect.top >= bounds.top - 1 &&
                rect.bottom <= bounds.bottom + 1,
            )
          })
          expect(fits).toBe(true)
        }).toPass()
      }
    }
  }
})
