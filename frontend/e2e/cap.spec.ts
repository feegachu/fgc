import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { usageRate, won } from '../src/lib/format'

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
            amount: 1250000,
            classificationSnapshot: 'INCLUDED',
            decisionReason: '저장 당시 산입',
            evidenceRef: 'REG-09',
          },
        ],
      }),
    })
  })
}

test('필터 URL·계산근거·Escape 닫기·딥링크를 확인한다', async ({ page }) => {
  await fixture(page)
  await page.goto('cap-checks?month=2026-07')
  const table = page.getByRole('table', { name: '계약별 판정 목록' })
  await expect(table.getByText('104.1%')).toBeVisible()
  await expect(table.getByText('-50,000원')).toHaveCSS('color', 'rgb(185, 28, 28)')
  await expect(table.getByRole('link', { name: 'C-2026-0001' })).toHaveAttribute('href', '/app/contracts/3?tab=cap')
  await page.getByLabel('계약번호', { exact: true }).fill('C-2026-0001')
  await page.getByRole('button', { name: '조회', exact: true }).click()
  await expect(page).toHaveURL(/contractNo=C-2026-0001/)
  await table.getByRole('button', { name: 'C-2026-0001 계산근거 열기' }).click()
  const dialog = page.getByRole('dialog', { name: '계산근거' })
  await expect(dialog.getByText('① 입력값')).toBeVisible()
  await expect(dialog.getByRole('table', { name: '항목별 산입 내역' })).toBeVisible()
  await expect(dialog.getByRole('link', { name: '예외 목록 보기' })).toHaveAttribute('href', '/app/exceptions')
  await page.keyboard.press('Escape')
  await expect(dialog).not.toBeVisible()
  await expect(page).not.toHaveURL(/capCheckId/)
  await page.goto('cap-checks?capCheckId=11')
  await expect(dialog.getByText('① 입력값')).toBeVisible()
})

test('잘못된 상세 딥링크는 목록을 막지 않는다', async ({ page }) => {
  await fixture(page)
  await page.goto('cap-checks?capCheckId=999')
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('저장된 판정을 찾을 수 없습니다.')
  await page.getByRole('button', { name: '모달 닫기' }).click()
  await expect(
    page.getByRole('table', { name: '계약별 판정 목록' }).getByRole('link', { name: 'C-2026-0001' }),
  ).toBeVisible()
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

test('실서버 CAP-W01·W02 전후 비교 및 캡처', async ({ page, context }) => {
  test.skip(process.env.FGC_CAP_REAL_SERVER !== '1', '격리 DB와 실서버를 준비한 뒤 실행한다')
  const qaLoginId = process.env.FGC_CAP_QA_LOGIN_ID
  const qaPassword = process.env.FGC_CAP_QA_PASSWORD
  test.skip(!qaLoginId || !qaPassword, 'QA 계정 정보는 환경변수로 제공한다')
  test.setTimeout(60000)
  const month = process.env.FGC_CAP_QA_MONTH ?? '2026-07'
  await page.setViewportSize({ width: 1440, height: 1000 })
  // 이전 세션 폼 로그인: UI 밖으로 쿠키/토큰을 출력·저장하지 않는다.
  await page.goto('http://localhost:8081/login')
  await page.getByLabel('아이디', { exact: true }).fill(qaLoginId!)
  await page.locator('input[name="password"]').fill(qaPassword!)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
  const legacyResponsePromise = page.waitForResponse(
    (response) => response.url().includes('/api/v1/cap-checks?') && response.status() === 200,
  )
  await page.goto(`http://localhost:8081/cap-checks?month=${month}`)
  const legacyResponse = await legacyResponsePromise
  const legacy = (await legacyResponse.json()).data
  expect(legacy.content.length).toBeGreaterThan(0)
  await expect(page.locator('#cap-total-count')).toHaveText(String(legacy.totalElements))
  const id = legacy.content[0].capCheckId
  await page.locator(`[data-cap-detail-id="${id}"]`).click()
  await expect(page.locator('#cap-detail-content')).toBeVisible()
  const legacyDetail = await context.request.get(`http://localhost:8081/api/v1/cap-checks/${id}/details`)
  const stored = (await legacyDetail.json()).data
  // SPA는 별도 JWT로 인증. 이 로그인은 동일한 임시 QA DB의 계정이다.
  await page.goto(`login?redirect=${encodeURIComponent(`/cap-checks?month=${month}`)}`)
  await page.getByLabel(/^아이디/).fill(qaLoginId!)
  await page.getByLabel(/^비밀번호\s*\*?$/).fill(qaPassword!)
  const reactResponsePromise = page.waitForResponse(
    (response) => response.url().includes('/api/v1/cap-checks?') && response.status() === 200,
  )
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('table', { name: '계약별 판정 목록' }).getByRole('link').first()).toBeVisible()
  const tokenList = await reactResponsePromise
  // 동일 GET 응답/건수는 화면과 HTTP 응답으로 대조한다. DB의 판정 변경은 수행하지 않는다.
  expect((await tokenList.json()).data).toEqual(legacy)
  await expect(page.getByText(`총 ${legacy.totalElements}건`)).toBeVisible()
  const first = legacy.content[0]
  const firstRow = page.getByRole('table', { name: '계약별 판정 목록' }).locator('tbody tr').first()
  await expect(firstRow).toContainText(first.contractNo)
  await expect(firstRow).toContainText(won(first.includedAmount))
  await expect(firstRow).toContainText(won(first.remainingAmount))
  await expect(firstRow).toContainText(`${usageRate(first.usagePct)}%`)
  await expect(firstRow).toContainText(first.resultStatusLabel)
  await page.goto(`cap-checks?month=${month}&capCheckId=${id}`)
  const dialog = page.getByRole('dialog', { name: '계산근거' })
  await expect(dialog.getByText('① 입력값')).toBeVisible()
  await expect(dialog.getByText(stored.capCheck.contractNo, { exact: true })).toBeVisible()
  await expect(dialog).toContainText(`${usageRate(stored.capCheck.usagePct)}%`)
  await expect(dialog.getByRole('table', { name: '항목별 산입 내역' }).locator('tbody tr')).toHaveCount(
    stored.details.length || 1,
  )
})
