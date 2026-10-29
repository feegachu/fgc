import { expect, test } from '@playwright/test'

const envelope = (data: unknown) => ({ data, error: null, requestId: 'reco-fixture' })
const result = {
  reconciliationResultId: 7,
  reconciliationRunId: 42,
  contractNo: 'C-001',
  commissionItemName: '초회 수수료',
  installmentNo: 1,
  resultType: 'AMOUNT_DIFFERENCE',
  resultTypeLabel: '금액 차이',
  expectedTotalAmount: 12000,
  actualTotalAmount: 11000,
  differenceAmount: -1000,
  matches: [],
  detailSnapshot: { paymentStage: 'GA_TO_FC' },
}

async function historyFixture(page: import('@playwright/test').Page) {
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/auth/refresh')
      ? { accessToken: 'reco-fixture' }
      : path.endsWith('/auth/me')
        ? { loginId: 'fixture', userName: '담당자', roleCode: 'SETTLEMENT', canProcess: true, demoMonth: '2026-07' }
        : path.endsWith('/base/insurers')
          ? { content: [{ insurerId: 9, insurerCode: 'OLD', insurerName: '과거회사', activeYn: false }], totalPages: 1 }
          : path.endsWith('/results/7')
            ? {
                ...result,
                installmentNo: null,
                expectedTotalAmount: -12000,
                actualTotalAmount: -13000,
                matches: [
                  {
                    matchSeq: 1,
                    matchRole: 'ACTUAL',
                    commissionTransactionId: 12,
                    matchedAmount: -13000,
                    installmentNo: null,
                  },
                ],
              }
            : { content: [], page: 1, totalPages: 0 }
    await route.fulfill({ json: envelope(data) })
  })
}

test('비활성 보험회사 과거 이력 조회는 가능하고 신규 대사는 차단한다', async ({ page }) => {
  await historyFixture(page)
  await page.goto('reconciliations?month=2026-07&stage=GA_TO_FC')
  await expect(page.getByRole('option', { name: 'OLD · 과거회사' })).toBeEnabled()
  await page.getByRole('combobox', { name: '보험회사', exact: true }).selectOption('9')
  await expect(page.getByRole('button', { name: '대사 실행', exact: true })).toBeDisabled()
  const response = page.waitForResponse((res) => new URL(res.url()).searchParams.get('insurerId') === '9')
  await page.getByRole('button', { name: '조회', exact: true }).click()
  expect((await response).ok()).toBe(true)
  await expect(page).toHaveURL(/insurer=9/)
})

test('비교 모달의 null 회차는 대시, 음수 예상·실제·차액은 마이너스와 빨간색으로 표시한다', async ({ page }) => {
  await historyFixture(page)
  await page.goto('reconciliations?month=2026-07&resultId=7')
  await expect(page.getByRole('dialog').getByText('C-001 · 초회 수수료 · —')).toBeVisible()
  await expect(page.getByRole('dialog')).not.toContainText('-회차')
  await expect(page.getByRole('dialog')).not.toContainText('—회차')
  for (const text of ['-12,000원', '-13,000원', '-1,000원']) {
    const amounts = page.getByRole('dialog').getByText(text, { exact: true })
    await expect(amounts.first()).toBeVisible()
    for (const amount of await amounts.all()) {
      await expect(amount).toHaveCSS('color', 'rgb(185, 28, 28)')
    }
  }
})

test('상태 미확인 실행은 일괄 예외 버튼을 활성화하지 않는다', async ({ page }) => {
  await historyFixture(page)
  await page.goto('reconciliations?month=2026-07&runId=999')
  await expect(page.getByRole('button', { name: '불일치 예외 일괄 생성' })).toBeDisabled()
})

test('화면 월 변경은 탭 영향 확인 후 취소하거나 명시적으로 적용한다', async ({ page }) => {
  await historyFixture(page)
  await page.goto('reconciliations?month=2026-07')
  await page.locator('input[type="month"]').fill('2026-08')
  await page.getByRole('button', { name: '조회', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '기준 정산월 변경 확인' })
  await expect(dialog).toContainText('열린 업무 탭')
  await dialog.getByRole('button', { name: '취소', exact: true }).click()
  await expect(page).toHaveURL(/month=2026-07/)
  await page.getByRole('button', { name: '조회', exact: true }).click()
  const response = page.waitForResponse((res) => new URL(res.url()).searchParams.get('month') === '2026-08')
  await dialog.getByRole('button', { name: '변경하고 조회' }).click()
  expect((await response).ok()).toBe(true)
  await expect(page).toHaveURL(/month=2026-08/)
})

test('RECO-W01 실행 → 자동 선택 → RECO-W02 비교 → 불일치 예외', async ({ page }, testInfo) => {
  let created = false
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    let data: unknown
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'reco-fixture' }
    else if (path.endsWith('/auth/me'))
      data = { loginId: 'fixture', userName: '담당자', roleCode: 'SETTLEMENT', canProcess: true, demoMonth: '2026-07' }
    else if (path.endsWith('/base/insurers'))
      data = {
        content: [{ insurerId: 1, insurerCode: 'LIFE', insurerName: '테스트생명', activeYn: true }],
        totalPages: 1,
      }
    else if (path.endsWith('/validation-runs')) data = { content: [{ validationRunId: 3 }] }
    else if (path === '/api/v1/reconciliations' && route.request().method() === 'POST') {
      expect(route.request().postDataJSON()).toEqual({
        settlementMonth: '2026-07',
        paymentStage: 'GA_TO_FC',
        insurerId: 1,
        validationRunId: 3,
      })
      expect(route.request().headers().authorization).toBe('Bearer reco-fixture')
      created = true
      data = { reconciliationRunId: 42, status: 'RUNNING' }
    } else if (path === '/api/v1/reconciliations')
      data = {
        content: created
          ? [
              {
                reconciliationRunId: 42,
                settlementMonth: '2026-07-01',
                status: 'COMPLETED',
                statusLabel: '계산완료',
                targetCount: 1,
                matchedCount: 0,
                exceptionCount: 1,
                differenceTotal: -1000,
              },
            ]
          : [],
        page: 1,
        totalPages: 1,
        totalElements: created ? 1 : 0,
      }
    else if (path === '/api/v1/reconciliations/42/results')
      data = {
        summary: {
          resultCount: 1,
          matchedCount: 0,
          exceptionCount: 1,
          expectedTotal: 12000,
          actualTotal: 11000,
          differenceTotal: -1000,
        },
        items: { content: [result], page: 1, totalPages: 1 },
      }
    else if (path === '/api/v1/reconciliations/results/7') data = result
    else if (path.endsWith('/42/exceptions')) data = { created: 1, skippedDuplicate: 0 }
    else throw new Error(`unexpected ${path}`)
    await route.fulfill({ json: envelope(data) })
  })
  await page.goto('reconciliations')
  await page.getByRole('combobox', { name: '지급단계', exact: true }).selectOption('GA_TO_FC')
  await page.getByRole('combobox', { name: '보험회사', exact: true }).selectOption('1')
  await page.getByRole('button', { name: '대사 실행', exact: true }).click()
  await expect(page).toHaveURL(/runId=42/)
  const differences = page.locator('.reco-react-difference')
  await expect(differences.first()).toBeVisible()
  for (const difference of await differences.all()) {
    await expect(difference).toHaveText(/^-1,000(원)?$/)
    await expect(difference).toHaveCSS('color', 'rgb(185, 28, 28)')
  }
  await page.screenshot({ path: testInfo.outputPath('reconciliation-minus-differences.png'), fullPage: true })
  await page.getByRole('button', { name: '대사 결과 7 비교 보기' }).click()
  await expect(page.getByRole('dialog').getByText('예상 없음', { exact: true })).toBeVisible()
  await expect(page.getByRole('dialog').getByText('실제 없음', { exact: true })).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: '닫기', exact: true }).click()
  await page.getByRole('button', { name: '불일치 예외 일괄 생성' }).click()
  await expect(page.getByRole('link', { name: '예외함에서 확인' })).toBeVisible()
  await expect(page.locator('.reco-react-note[role="status"]')).toContainText('예외 1건 생성, 0건은 이미 있어 건너뜀.')
})
