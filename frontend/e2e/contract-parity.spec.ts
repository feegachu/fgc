import { expect, test } from '@playwright/test'
import type { Locator } from '@playwright/test'

test('CONT-W01·W02·W03 기존 화면의 배치·문구·아이콘과 좁은 화면을 유지한다', async ({ page, context }, testInfo) => {
  test.skip(process.env.FGC_CONTRACT_REAL_SERVER !== '1', '격리 QA 서버에서 기존 화면과 직접 대조한다')
  const loginId = process.env.FGC_CONTRACT_QA_LOGIN_ID
  const password = process.env.FGC_CONTRACT_QA_PASSWORD
  if (!loginId || !password) throw new Error('격리 QA 계정 설정이 필요합니다.')
  const backend = process.env.FGC_API_TARGET ?? 'http://localhost:8081'
  test.setTimeout(120000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.goto(`${backend}/login`)
  await page.getByLabel('아이디', { exact: true }).fill(loginId)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
  const list = (await (await context.request.get(`${backend}/api/v1/contracts?page=1&size=20`)).json()).data
  expect(list.content.length).toBeGreaterThan(0)
  const id = list.content[0].contractId
  const box = async (locator: Locator) => {
    await expect(locator).toBeVisible()
    return (await locator.boundingBox())!
  }
  const cases = [
    { name: 'list', path: 'contracts', anchors: ['.page-title', '.contract-filter-card', '.contract-pagination'] },
    {
      name: 'detail',
      path: `contracts/${id}`,
      anchors: ['.page-title', '.contract-information-panel', '.contract-history-panel'],
    },
    {
      name: 'form',
      path: 'contracts/new',
      anchors: ['.page-title', '.contract-basic-form-card', '.contract-agent-card', '.contract-premium-card'],
    },
  ]
  const baseline = new Map<string, Awaited<ReturnType<typeof box>>[]>()
  const metrics = () =>
    page.evaluate(() => {
      const selectors = [
        '.contract-filter-card',
        '.contract-results-header',
        '.contract-table',
        '.contract-table tbody',
        '.contract-pagination',
        '.contract-information-grid',
        '.contract-history-times',
        '.contract-data-panel dl',
      ]
      return Object.fromEntries(
        selectors.map((selector) => {
          const element = document.querySelector(selector)
          if (!element) return [selector, null]
          const rect = element.getBoundingClientRect()
          const style = getComputedStyle(element)
          return [
            selector,
            { y: rect.y, height: rect.height, margin: style.margin, padding: style.padding, fontSize: style.fontSize },
          ]
        }),
      )
    })
  const legacyMetrics = new Map<string, Awaited<ReturnType<typeof metrics>>>()
  const capture = async (name: string) => {
    if (process.env.FGC_CONTRACT_CAPTURE === '1') {
      const path = testInfo.outputPath(`${name}.png`)
      await page.screenshot({ path, fullPage: true })
      await testInfo.attach(name, { path, contentType: 'image/png' })
    }
  }
  for (const item of cases) {
    await page.goto(`${backend}/${item.path}`)
    if (item.name === 'detail') await expect(page.locator('#contract-info-number')).not.toHaveText('—')
    if (item.name === 'form') await expect(page.locator('select[name="insurerId"]')).toBeEnabled()
    baseline.set(item.name, await Promise.all(item.anchors.map((selector) => box(page.locator(selector)))))
    legacyMetrics.set(item.name, await metrics())
    await capture(`legacy-${item.name}`)
  }
  await page.goto('login?redirect=%2Fcontracts')
  await page.getByLabel(/^아이디/).fill(loginId)
  await page.getByLabel(/^비밀번호\s*\*?$/).fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: '보험계약 목록' })).toBeVisible()
  for (const item of cases) {
    await page.goto(item.path)
    if (item.name === 'list') await expect(page.getByRole('table', { name: '보험계약 검색 결과' })).toBeVisible()
    if (item.name === 'detail')
      await expect(page.locator('.contract-history-panel').getByText('불러오는 중입니다.')).toHaveCount(0)
    if (item.name === 'form') await expect(page.locator('select[name="insurerId"]')).toBeEnabled()
    const current = await Promise.all(item.anchors.map((selector) => box(page.locator(selector))))
    const original = baseline.get(item.name)!
    await testInfo.attach(`${item.name}-geometry`, {
      body: JSON.stringify({ legacy: legacyMetrics.get(item.name), react: await metrics() }),
      contentType: 'application/json',
    })
    for (let index = 0; index < current.length; index++) {
      for (const axis of ['x', 'y', 'width'] as const)
        expect
          .soft(
            Math.abs(current[index][axis] - original[index][axis]),
            `${item.name} ${item.anchors[index]} ${axis} (legacy ${original[index][axis]}, react ${current[index][axis]})`,
          )
          .toBeLessThanOrEqual(2)
    }
    if (item.name === 'list') {
      await expect(page.getByPlaceholder('계약번호 입력')).toBeVisible()
      await expect(page.getByRole('columnheader').filter({ hasText: '월납환산 초회보험료' })).toBeVisible()
      await expect(page.getByRole('button', { name: 'CSV 내보내기' }).locator('.material-symbols-rounded')).toHaveText(
        'download',
      )
      await expect(page.getByRole('link', { name: '신규 등록' }).locator('.material-symbols-rounded')).toHaveText('add')
      await expect(page.locator('.contract-section-header').getByRole('button', { name: '초기화' })).toBeVisible()
      await expect(page.getByRole('button', { name: '이전 5페이지' })).toBeDisabled()
      await expect(page.getByRole('button', { name: '다음 5페이지' })).toBeDisabled()
      await expect(page.getByRole('button', { name: '처음', exact: true })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '마지막', exact: true })).toHaveCount(0)
    }
    if (item.name === 'form') {
      await expect(page.getByPlaceholder('계약번호 입력')).toBeVisible()
      await expect(
        page.getByRole('button', { name: '저장하고 예상 스케줄 만들기' }).locator('.material-symbols-rounded'),
      ).toHaveText('save')
    }
    await capture(`react-${item.name}`)
    await page.setViewportSize({ width: 390, height: 844 })
    await expect(page.locator('.app-shell')).toHaveClass(/is-sidebar-collapsed/)
    // The shell animates its grid columns after automatic sidebar collapse.
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth), {
        message: `${item.name} mobile page width`,
      })
      .toBeLessThanOrEqual(390)
    if (item.name === 'form') {
      const basic = await box(page.locator('.contract-basic-form-card'))
      const premium = await box(page.locator('.contract-premium-card'))
      expect(premium.y).toBeGreaterThan(basic.y + basic.height)
    }
    await capture(`react-${item.name}-mobile`)
    await page.setViewportSize({ width: 1920, height: 1080 })
    await expect(page.locator('.app-shell')).not.toHaveClass(/is-sidebar-collapsed/)
  }
})
