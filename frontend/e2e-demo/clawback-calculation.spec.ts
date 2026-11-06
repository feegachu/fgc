import { test, expect, login } from './fixtures'

const won = (value: number) => `${value.toLocaleString('ko-KR')}원`

// FUN-055, IF-API-85: 시연 데이터의 환수 후보를 실제 서버에서 정책과 사유로 계산한다.
test('환수 후보를 정책과 사유로 계산하면 서버 계산 결과의 항목과 합계를 보여 준다', async ({ page }) => {
  await login(page, 'settle01', '/clawbacks?month=2026-07')
  const calc = page.getByRole('region', { name: '환수 계산' })
  await expect(calc).toBeVisible()
  // 정책 후보가 하나라 미리 골라 두지만, 사유를 쓰기 전에는 계산하지 않는다.
  await expect(calc.getByRole('radio', { name: /^표준 환수율 v1\b/ })).toBeChecked()
  const button = calc.getByRole('button', { name: '환수액 계산' })
  await expect(button).toBeDisabled()
  await calc.getByLabel(/계산 사유/).fill('등록된 환수 정책으로 계산')
  await expect(button).toBeEnabled()

  const created = page.waitForResponse(
    (response) =>
      /\/api\/v1\/clawbacks\/candidates\/\d+\/calculations$/.test(new URL(response.url()).pathname) &&
      response.request().method() === 'POST',
  )
  await button.click()
  const response = await created
  expect(response.status(), await response.text()).toBe(201)
  const calculation = (await response.json()).data
  expect(calculation.amount).toBeGreaterThan(0)
  expect(calculation.lines.length).toBeGreaterThan(0)

  // 상세 칸 폭에 따라 표나 줄 목록 중 하나로 보인다(1280px 시험 화면은 목록).
  const lines = calc.getByRole('table', { name: '환수 계산 결과' }).or(calc.getByRole('list', { name: '환수 계산 결과' }))
  await expect(lines.filter({ visible: true })).toHaveCount(1)
  await expect(lines.filter({ visible: true })).toContainText(won(calculation.lines[0].clawbackAmount))
  await expect(calc.getByText('환수액 합계')).toContainText(won(calculation.amount))
  await expect(calc.getByText(/최신 계산 결과 v1, .*표준 환수율 v1/)).toBeVisible()
  await expect(calc.getByText(/계산 결과는 환수 확정이나 회수 완료가 아닙니다/)).toBeVisible()
  await expect(calc.getByRole('alert')).toHaveCount(0)
  // 같은 요청이 다시 나가지 않게 사유를 비운다.
  await expect(calc.getByLabel(/계산 사유/)).toHaveValue('')

  const list = page.getByRole('table', { name: '검색 조건에 해당하는 환수 검토 후보' })
  const row = list
    .getByRole('row')
    .filter({ has: page.getByRole('cell', { name: String(calculation.candidateId), exact: true }) })
  await expect(row).toContainText('계산완료')
  await expect(row).toContainText(won(calculation.amount))
  await page.screenshot({ path: 'e2e-artifacts/629-clawback-calculation.png', fullPage: true })
  // 시연 화면 폭(1536px)에서는 상세 칸이 넓어 결과를 표로 보인다.
  await page.setViewportSize({ width: 1536, height: 864 })
  await expect(calc.getByRole('table', { name: '환수 계산 결과' })).toBeVisible()
  await expect(calc.getByRole('list', { name: '환수 계산 결과' })).toBeHidden()
  await page.screenshot({ path: 'e2e-artifacts/629-clawback-calculation-1536.png', fullPage: true })
})
