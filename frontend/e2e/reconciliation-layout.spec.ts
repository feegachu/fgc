import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
const template = readFileSync(new URL('../../src/main/resources/templates/reco/list.html', import.meta.url), 'utf8')
const legacyCss = readFileSync(
  new URL('../../src/main/resources/static/css/features/reco.css', import.meta.url),
  'utf8',
)
for (const width of [1440, 1024, 390]) {
  test(`RECO-W01 원본 빈 화면 배치 ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 })
    await page.route('**/api/v1/**', (route) => {
      const path = new URL(route.request().url()).pathname
      const data = path.endsWith('/auth/refresh')
        ? { accessToken: 'reco-layout' }
        : path.endsWith('/auth/me')
          ? { loginId: 'layout', userName: '담당자', roleCode: 'SETTLEMENT', canProcess: true, demoMonth: '2026-07' }
          : path.endsWith('/base/insurers')
            ? {
                content: [{ insurerId: 1, insurerCode: 'LIFE', insurerName: '테스트생명', activeYn: true }],
                totalPages: 1,
              }
            : { content: [], page: 1, totalPages: 0, totalElements: 0 }
      return route.fulfill({ json: { data, error: null, requestId: 'reco-layout' } })
    })
    await page.goto('reconciliations?month=2026-07')
    await expect(page.getByRole('combobox', { name: '보험회사', exact: true })).toBeEnabled()
    const capture = async (name: string) => {
      if (process.env.FGC_RECO_CAPTURE !== '1') return
      await page.evaluate(() => window.scrollTo(0, 0))
      await page.screenshot({ path: info.outputPath(`${name}-${width}.png`), fullPage: true })
    }
    await page.addStyleTag({ content: legacyCss })
    await page.evaluate((html) => {
      const parsed = new DOMParser().parseFromString(html, 'text/html')
      const main = parsed.querySelector('main')!
      main.classList.remove('page-content')
      main.querySelectorAll('[th\\:each]').forEach((e) => e.remove())
      main.querySelector('#reconciliation-actions-pending')?.remove()
      main.querySelector('.reco-history-panel footer')?.remove()
      main.querySelector('[data-modal]')?.remove()
      main.querySelector<HTMLInputElement>('#r-month')!.value = '2026-07'
      const insurer = main.querySelector('select#r-insurer')
      if (insurer)
        insurer.innerHTML =
          '<option value="">전체 (실행 이력 조회만)</option><option value="1">LIFE · 테스트생명</option>'
      main.querySelector<HTMLSelectElement>('#r-insurer')!.disabled = false
      for (const id of ['#btn-run', '#btn-bulk-exception']) main.querySelector<HTMLButtonElement>(id)!.disabled = true
      document.querySelector('.reco-react-page')!.replaceWith(main)
    }, template)
    await capture('legacy')
    const measure = () =>
      page.evaluate(() => {
        const root = document.querySelector('.reco-page, .reco-react-page')!
        const form = root.querySelector('form')!.getBoundingClientRect()
        const fields = [...root.querySelectorAll('.filter-field')].map((e) => {
          const b = e.getBoundingClientRect()
          return { x: b.x, y: b.y, width: b.width, height: b.height }
        })
        const cards = [...root.querySelectorAll('.kpi-card')].map((e) => {
          const b = e.getBoundingClientRect()
          return { x: b.x, y: b.y, width: b.width, height: b.height }
        })
        const result = root.querySelector('.reco-result-panel, .reco-react-results')!.getBoundingClientRect()
        const history = root.querySelector('.reco-history-panel, .reco-react-history')!.getBoundingClientRect()
        return {
          form: { x: form.x, y: form.y, width: form.width, height: form.height },
          cards,
          fields,
          result: { y: result.y, height: result.height },
          history: { y: history.y },
        }
      })
    const legacy = await measure()
    await page.reload()
    await expect(page.getByRole('combobox', { name: '보험회사', exact: true })).toBeEnabled()
    await capture('react-after')
    const react = await measure()
    await info.attach('geometry', { body: JSON.stringify({ legacy, react }), contentType: 'application/json' })
    for (const key of ['x', 'y', 'width', 'height'] as const)
      expect(Math.abs(react.form[key] - legacy.form[key])).toBeLessThanOrEqual(2)
    for (let i = 0; i < 3; i++)
      for (const key of ['x', 'y', 'width', 'height'] as const)
        expect(Math.abs(react.fields[i][key] - legacy.fields[i][key])).toBeLessThanOrEqual(2)
    for (let i = 0; i < 5; i++)
      for (const key of ['x', 'y', 'width', 'height'] as const)
        expect(Math.abs(react.cards[i][key] - legacy.cards[i][key])).toBeLessThanOrEqual(2)
    expect(Math.abs(react.result.y - legacy.result.y)).toBeLessThanOrEqual(2)
    expect(Math.abs(react.result.height - legacy.result.height)).toBeLessThanOrEqual(2)
    expect(Math.abs(react.history.y - legacy.history.y)).toBeLessThanOrEqual(2)
    const result = page.getByRole('region', { name: '대사 결과', exact: true })
    const footer = result.locator('tfoot')
    await expect(footer).toContainText('합계')
    expect(await footer.locator('td').allTextContents()).toEqual(['0', '0', '0', ''])
    await expect(page.getByRole('region', { name: '차액 합계', exact: true })).toContainText('—원')
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  })
}
