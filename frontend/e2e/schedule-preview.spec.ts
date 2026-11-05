import { expect, test } from '@playwright/test'

test('4년 목록 → 48회차 상세 → 예정 새 버전, 확정과 지급 진입 차단', async ({ page }) => {
  const envelope = (data: unknown) => ({ data, error: null, requestId: 'fun037-browser' })
  let version = 1
  const header = () => ({
    scheduleHeaderId: version === 1 ? 11 : 12,
    contractId: 600,
    contractNo: 'C-FOUR',
    paymentStage: 'GA_TO_FC',
    scheduleRegime: 'FOUR_YEAR_2027',
    schedulePurpose: 'SIMULATION',
    scheduleVersionNo: version,
    status: 'PLANNED',
    activeYn: true,
    canConfirm: false,
    installmentCount: 48,
    maxInstallmentNo: 48,
    lineCount: 48,
    expectedTotal: 2160000,
    policyVersionLabel: 'FOUR v3',
  })
  const lines = Array.from({ length: 48 }, (_, index) => ({
    scheduleLineId: index + 1,
    lineNo: index + 1,
    installmentNo: index + 1,
    contractMonthNo: index + 1,
    contractId: 600,
    commissionItemId: 25,
    dueDate: new Date(Date.UTC(2027, 2 + index, 2)).toISOString().slice(0, 10),
    expectedAmount: 45000,
    commissionItemName: '유지 수수료',
    feeComponentType: 'MAINTENANCE',
    lineStatus: 'PLANNED',
  }))
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    let data: unknown
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'fixture', tokenType: 'Bearer', expiresIn: 1800 }
    else if (path.endsWith('/auth/me'))
      data = { loginId: 'settle01', userName: '정산', roleCode: 'SETTLEMENT', canProcess: true, demoMonth: '2026-07' }
    else if (path === '/api/v1/schedules')
      data = {
        content: url.searchParams.get('regime') === 'FOUR_YEAR_2027' ? [header()] : [],
        page: 1,
        size: 20,
        totalPages: 1,
        totalElements: 1,
      }
    else if (path.endsWith('/versions')) data = [header()]
    else if (path.endsWith('/regenerate')) {
      expect(route.request().postDataJSON()).toEqual({
        reason: '승인 정책 반영',
        regime: 'FOUR_YEAR_2027',
        policyVersionId: 70,
        expectedSourceVersion: 'source-1',
      })
      expect(route.request().headers()['idempotency-key']).toBeTruthy()
      version = 2
      data = { scheduleHeaderId: 12, versionNo: 2 }
    } else if (/\/schedules\/(11|12)$/.test(path))
      data = {
        header: { ...header(), scheduleHeaderId: Number(path.split("/").at(-1)), activeYn: version === 1 || path.endsWith("/12") },
        lines,
        previewContext: {
          expectedSourceVersion: 'source-1',
          policy: {
            versionNo: 3,
            commissionPolicy: { policyVersionId: 70 },
            selection: {
              resolution: { status: 'SELECTED', declaredFeeRegimeCode: 'FOUR_YEAR_2027' },
              ruleset: { maxInstallments: 48 },
            },
            sourceRefs: ['REG-03'],
          },
        },
      }
    else return route.fulfill({ status: 503, json: { error: { code: 'UNEXPECTED_TEST_API' } } })
    await route.fulfill({ json: envelope(data) })
  })
  await page.goto('/schedules')
  await page.getByLabel('적용 체계', { exact: true }).selectOption('FOUR_YEAR_2027')
  await expect(page).toHaveURL(/regime=FOUR_YEAR_2027/)
  await expect(page.getByLabel('용도', { exact: true })).toHaveValue('SIMULATION')
  await page.getByRole('link', { name: 'C-FOUR', exact: true }).click()
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeDisabled()
  await expect(page.locator('a[href*="/transactions/new"]')).toHaveCount(0)
  await expect(page.getByText('2031-02-02', { exact: true })).toBeVisible()
  await page.getByLabel('생성 사유', { exact: true }).fill('승인 정책 반영')
  await page.getByRole('button', { name: '4년 예정 새 버전 만들기', exact: true }).click()
  await expect(page).toHaveURL(/\/schedules\/12/)
  await page.getByRole('link', { name: '목록으로', exact: true }).click()
  await expect(page).toHaveURL(/regime=FOUR_YEAR_2027/)
  await expect(page.getByLabel('용도', { exact: true })).toHaveValue('SIMULATION')
})
