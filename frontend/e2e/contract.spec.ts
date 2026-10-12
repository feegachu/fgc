import { expect, test } from '@playwright/test'

// #418 인계: 격리한 demo DB에서만 수행한다. 실제 저장/수정이 있으므로 명시적으로 활성화한다.
test('실서버 CONT-W01·W02·W03 전후 대조와 등록 → 상세 → 수정', async ({ page, context }, testInfo) => {
  test.skip(process.env.FGC_CONTRACT_REAL_SERVER !== '1', '격리 검증 DB가 준비된 경우에 실행한다')
  const loginId = process.env.FGC_CONTRACT_QA_LOGIN_ID
  const password = process.env.FGC_CONTRACT_QA_PASSWORD
  if (!loginId || !password) {
    throw new Error('실서버 검증을 활성화하면 FGC_CONTRACT_QA_LOGIN_ID와 FGC_CONTRACT_QA_PASSWORD가 필요합니다.')
  }
  const apiTarget = process.env.FGC_API_TARGET ?? 'http://localhost:8081'
  test.setTimeout(120000)
  page.setDefaultTimeout(15000)
  await page.setViewportSize({ width: 1440, height: 1000 })
  const capture = async (name: string) => {
    if (process.env.FGC_CONTRACT_CAPTURE === '1') {
      const path = testInfo.outputPath(`${name}.png`)
      await page.screenshot({ path, fullPage: true })
      await testInfo.attach(name, { path, contentType: 'image/png' })
    }
  }
  await page.goto(`${apiTarget}/login`)
  await page.getByLabel('아이디', { exact: true }).fill(loginId)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`${apiTarget}/contracts`)
  await expect(page.locator('#filter-insurer')).toBeEnabled()
  const list = (await (await context.request.get(`${apiTarget}/api/v1/contracts?page=1&size=20`)).json()).data
  expect(list.content.length).toBeGreaterThan(0)
  const first = list.content[0]
  const detail = (await (await context.request.get(`${apiTarget}/api/v1/contracts/${first.contractId}`)).json()).data
  await expect(page.locator('.contract-table tbody tr')).toHaveCount(list.content.length)
  await capture('legacy-contract-list')
  await page.goto(`${apiTarget}/contracts/${first.contractId}`)
  await expect(page.locator('main')).toContainText(first.contractNo)
  await capture('legacy-contract-detail')
  await page.goto(`${apiTarget}/contracts/new`)
  await expect(page.locator('select[name="insurerId"]')).toBeEnabled()
  await capture('legacy-contract-form')

  await page.goto('login?redirect=%2Fcontracts')
  await page.getByLabel(/^아이디/).fill(loginId)
  await page.getByLabel(/^비밀번호\s*\*?$/).fill(password)
  const listResponse = page.waitForResponse(
    (response) => response.url().includes('/api/v1/contracts?') && response.ok(),
  )
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  expect((await (await listResponse).json()).data).toEqual(list)
  const table = page.getByRole('table', { name: '보험계약 검색 결과' })
  await expect(table.locator('tbody tr')).toHaveCount(list.content.length)
  await expect(page.getByLabel('보험회사', { exact: true })).toBeEnabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await capture('react-contract-list')
  await table.getByRole('link', { name: first.contractNo, exact: true }).click()
  await expect(page.getByRole('heading', { name: /계약 상세/ })).toBeVisible()
  await expect(page.getByRole('main')).toContainText(first.contractNo)
  await expect(page.getByRole('tabpanel').getByText('불러오는 중입니다.')).toHaveCount(0)
  await capture('react-contract-detail')
  for (const tab of ['예상 스케줄', '1,200%', '차익거래', '원장', '지급·대사']) {
    await page.getByRole('tab', { name: tab, exact: true }).click()
    await expect(page.getByRole('tabpanel')).toBeVisible()
    await expect(page.getByRole('tabpanel').getByText('불러오는 중입니다.')).toHaveCount(0)
    await expect(page.getByRole('tabpanel').getByRole('alert')).toHaveCount(0)
  }
  await page.goto('contracts/new')
  await expect(page.locator('select[name=insurerId]')).toBeEnabled()
  await capture('react-contract-form')
  const unique = `QA-408-${Date.now()}`
  await page.locator('input[name=contractDate]').fill(detail.contractDate)
  await page.locator('select[name=insurerId]').selectOption(String(detail.insurerId))
  await expect(page.locator('select[name=productOfferingId]')).toBeEnabled()
  await page.locator('select[name=productOfferingId]').selectOption(String(detail.productOfferingId))
  await page.locator('input[name=contractNo]').fill(unique)
  await expect(page.locator('select[name=agentId]')).toBeEnabled()
  await page.locator('select[name=agentId]').selectOption(String(detail.agentId))
  for (const field of ['premiumPerCycleAmount', 'firstPremiumAmount', 'monthlyEquivalentFirstPremium']) {
    await page.locator(`input[name="${field}"]`).fill(String(detail[field]))
  }
  const term = page.locator('input[name="paymentTermMonths"]')
  if (await term.isEditable()) await term.fill(String(detail.paymentTermMonths))
  const createResponse = page.waitForResponse(
    (response) => response.request().method() === 'POST' && response.url().endsWith('/api/v1/contracts'),
  )
  await page.locator('button[type=submit]').click()
  const created = await createResponse
  expect(created.ok(), await created.text()).toBe(true)
  const createdId = (await created.json()).data.contractId
  await expect.poll(() => new URL(page.url()).pathname).toBe(`/app/contracts/${createdId}`)
  await expect(page.getByRole('main')).toContainText(unique)
  await expect(page.getByText(/보험계약을 저장했습니다/)).toBeVisible()
  await page.getByRole('button', { name: '수정', exact: true }).click()
  const editedNo = `${unique}-EDIT`
  await page.locator('input[name=contractNo]').fill(editedNo)
  const updateResponse = page.waitForResponse(
    (response) => response.request().method() === 'PUT' && response.url().endsWith(`/api/v1/contracts/${createdId}`),
  )
  await page.locator('button[type=submit]').click()
  const updated = await updateResponse
  expect(updated.ok(), await updated.text()).toBe(true)
  await expect(page.getByRole('main')).toContainText(editedNo)
  await expect(page.getByText(/보험계약을 수정했습니다/)).toBeVisible()
})
