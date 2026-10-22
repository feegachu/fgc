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
                matches: [
                  {
                    matchSeq: 1,
                    matchRole: 'ACTUAL',
                    commissionTransactionId: 12,
                    matchedAmount: 11000,
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

test('비교 모달의 null 회차는 대시로 표시한다', async ({ page }) => {
  await historyFixture(page)
  await page.goto('reconciliations?month=2026-07&resultId=7')
  await expect(page.getByRole('dialog').getByText('C-001 · 초회 수수료 · —')).toBeVisible()
  await expect(page.getByRole('dialog')).not.toContainText('-회차')
  await expect(page.getByRole('dialog')).not.toContainText('—회차')
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

/** #418 실서버 재현용. 인증정보와 쓰기 opt-in을 지정한 격리 QA DB에서만 실행한다. */
test('실서버 RECO-W01·W02 전후 비교·일괄 예외·자동 선택·중복 오류', async ({ page }) => {
  test.setTimeout(60000)
  const loginId = process.env.FGC_RECO_QA_LOGIN_ID
  const password = process.env.FGC_RECO_QA_PASSWORD
  test.skip(!loginId || !password || process.env.FGC_RECO_QA_WRITE !== 'true', '격리 QA DB와 인증 환경변수 필요')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('http://localhost:8081/login')
  await page.locator('input[name="username"]').fill(loginId!)
  await page.locator('input[name="password"]').fill(password!)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.waitForURL((url) => !url.pathname.endsWith('/login'))
  const allRuns = (
    await (await page.request.get('http://localhost:8081/api/v1/reconciliations?month=2026-07&page=1&size=100')).json()
  ).data.content
  const insurers = (await (await page.request.get('http://localhost:8081/api/v1/base/insurers?page=1&size=100')).json())
    .data.content
  const candidate = insurers.find(
    (insurer: { insurerId: number; activeYn: boolean }) =>
      insurer.activeYn &&
      !allRuns.some(
        (run: { insurerId: number; paymentStage: string }) =>
          run.insurerId === insurer.insurerId && run.paymentStage === 'GA_TO_FC',
      ),
  )
  expect(candidate, '미실행 QA 보험회사 조합 필요').toBeTruthy()
  const query = 'month=2026-07&stage=GA_TO_FC&insurer=1'
  await page.goto(`http://localhost:8081/reconciliations?${query}`)
  const legacyHistory = await page.locator('#run-body tr[data-reconciliation-run-id]').allTextContents()
  expect(legacyHistory.length).toBeGreaterThan(0)
  const runId = await page
    .locator('#run-body tr[data-reconciliation-run-id]')
    .first()
    .getAttribute('data-reconciliation-run-id')
  const legacyResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === `/api/v1/reconciliations/${runId}/results`,
  )
  await page.locator(`#run-body tr[data-reconciliation-run-id="${runId}"]`).click()
  const oldResults = (await (await legacyResponse).json()).data
  const mismatch = oldResults.items.content.find((item: { resultType: string }) => item.resultType !== 'MATCHED')
  expect(mismatch).toBeTruthy()
  await expect(page.locator(`[data-reco-compare-id="${mismatch.reconciliationResultId}"]`)).toBeVisible()
  const legacyDetailResponse = page.waitForResponse((response) =>
    response.url().endsWith(`/api/v1/reconciliations/results/${mismatch.reconciliationResultId}`),
  )
  await page.locator(`[data-reco-compare-id="${mismatch.reconciliationResultId}"]`).click()
  const oldDetail = (await (await legacyDetailResponse).json()).data
  await expect(page.locator('#h-contract')).toHaveText(oldDetail.contractNo)
  await page.goto(`login?redirect=${encodeURIComponent(`/reconciliations?${query}`)}`)
  await page.getByRole('textbox', { name: /아이디/ }).fill(loginId!)
  await page.getByRole('textbox', { name: /비밀번호/ }).fill(password!)
  const reactHistoryResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/reconciliations',
  )
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  const newHistory = (await (await reactHistoryResponse).json()).data
  expect(newHistory.content.length).toBe(legacyHistory.length)
  for (let i = 0; i < newHistory.content.length; i++)
    expect(legacyHistory[i].trim().split(/\s+/)[0]).toBe(String(newHistory.content[i].reconciliationRunId))
  const newResultsResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === `/api/v1/reconciliations/${runId}/results`,
  )
  await page.getByRole('button', { name: runId!, exact: true }).click()
  expect((await (await newResultsResponse).json()).data).toEqual(oldResults)
  await expect(
    page.getByRole('button', { name: `대사 결과 ${mismatch.reconciliationResultId} 비교 보기` }),
  ).toBeVisible()
  const newDetailResponse = page.waitForResponse((response) =>
    response.url().endsWith(`/api/v1/reconciliations/results/${mismatch.reconciliationResultId}`),
  )
  await page.getByRole('button', { name: `대사 결과 ${mismatch.reconciliationResultId} 비교 보기` }).click()
  expect((await (await newDetailResponse).json()).data).toEqual(oldDetail)
  await expect(page.getByRole('dialog').getByText('허용오차 0원 · 시스템 계산 결과')).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: '닫기', exact: true }).click()
  const bulkResponse = page.waitForResponse((response) => response.url().endsWith(`/${runId}/exceptions`))
  await page.getByRole('button', { name: '불일치 예외 일괄 생성' }).click()
  const bulk = (await (await bulkResponse).json()).data
  expect(bulk.created + bulk.skippedDuplicate).toBeGreaterThan(0)
  await expect(page.locator('.reco-react-note[role="status"]')).toContainText(
    `예외 ${bulk.created}건 생성, ${bulk.skippedDuplicate}건은 이미 있어 건너뜀.`,
  )
  const exceptionResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/v1/exceptions',
  )
  await page.getByRole('link', { name: '예외함에서 확인' }).click()
  const exceptionSearch = await exceptionResponse
  expect(exceptionSearch.ok()).toBe(true)
  expect(new URL(exceptionSearch.url()).searchParams.get('validationMonth')).toBe('2026-07-01')
  const exceptions = await exceptionSearch.json()
  expect(exceptions.error).toBeNull()
  expect(exceptions.data.content.length).toBeGreaterThan(0)
  await expect(page.getByRole('heading', { name: '예외함', exact: true })).toBeVisible()
  await expect(page.getByRole('table', { name: '예외 목록' }).getByRole('row')).toHaveCount(
    exceptions.data.content.length + 1,
  )
  await page.goto(`reconciliations?month=2026-07&stage=GA_TO_FC&insurer=${candidate.insurerId}`)
  await expect(page.getByRole('button', { name: '대사 실행', exact: true })).toBeEnabled()
  const createResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/reconciliations' && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '대사 실행', exact: true }).click()
  const created = (await (await createResponse).json()).data
  await expect(page).toHaveURL(new RegExp(`runId=${created.reconciliationRunId}`))
  await expect(page.getByRole('region', { name: '실행 이력', exact: true }).getByText('계산완료')).toBeVisible()
  await page.getByRole('button', { name: '대사 실행', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: 'FGC-RECO-002' })).toBeVisible()
})
