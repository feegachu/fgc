import { expect, test } from '@playwright/test'

for (const width of [320, 375, 768]) {
  test(`${width}px 명세 요약에서 긴 금액·안내·배지가 잘리지 않고 읽을 수 있다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname
      const fulfill = (data: unknown) => route.fulfill({ json: { data, error: null, requestId: 'statement-layout' } })
      if (path === '/api/v1/auth/refresh') return fulfill({ accessToken: 'fixture', tokenType: 'Bearer', expiresIn: 1800 })
      if (path === '/api/v1/auth/me') return fulfill({
        loginId: 'agent01', userName: '설계사', roleCode: 'AGENT', agentId: 1,
        isAgent: true, canUseAgentWorkspace: true, canProcess: false, canViewAuditLog: false, demoMonth: '2026-07',
      })
      if (path === '/api/v1/base/commission-items') return fulfill([])
      if (path === '/api/v1/agent/me/statements') return fulfill({
        items: { content: [], page: 1, size: 20, totalElements: 0, totalPages: 0 },
        summary: {
          payment: { count: 1, amount: 9999999999999 }, held: { count: 1, amount: 9999999999999 },
          adjustment: { count: 1, amount: -9999999999999 }, clawback: { count: 1, amount: 9999999999999 },
        },
        basis: { month: '2026-07', range: { from: '2026-07-01', to: '2026-07-31' },
          dateBasis: { PAYMENT: '지급 귀속월', HELD: '보류 예정월', ADJUSTMENT: '조정 처리월', CLAWBACK: '환수 계산월' }, warnings: [] },
      })
      return route.fulfill({ status: 403, json: { data: null, error: { code: 'FGC-AUTH-005', message: '권한 없음' }, requestId: 'statement-layout' } })
    })
    await page.goto('agent/statements?month=2026-07')
    const summary = page.getByRole('region', { name: '유형별 요약' })
    await expect(summary.getByRole('article')).toHaveCount(4)
    await expect(summary.getByRole('article', { name: '조정 요약' })).toContainText('-9,999,999,999,999')
    await page.evaluate(() => document.fonts.ready)
    const metrics = await summary.evaluate((element) => {
      const summaryBox = element.getBoundingClientRect()
      const cards = [...element.querySelectorAll<HTMLElement>('.ast-card')]
      const canvas = document.createElement('canvas')
      canvas.width = canvas.height = 1
      const context = canvas.getContext('2d')!
      const luminance = (rgb: Uint8ClampedArray) => {
        const values = [...rgb].slice(0, 3).map((v) => v / 255).map((v) => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)
        return values[0] * 0.2126 + values[1] * 0.7152 + values[2] * 0.0722
      }
      const contrast = (text: Element) => {
        context.fillStyle = '#fff'; context.fillRect(0, 0, 1, 1)
        const ancestors: Element[] = []
        for (let parent: Element | null = text; parent; parent = parent.parentElement) ancestors.unshift(parent)
        for (const ancestor of ancestors) {
          context.fillStyle = getComputedStyle(ancestor).backgroundColor
          context.fillRect(0, 0, 1, 1)
        }
        const background = luminance(context.getImageData(0, 0, 1, 1).data)
        context.fillStyle = getComputedStyle(text).color; context.fillRect(0, 0, 1, 1)
        const foreground = luminance(context.getImageData(0, 0, 1, 1).data)
        return (Math.max(background, foreground) + 0.05) / (Math.min(background, foreground) + 0.05)
      }
      return {
        right: summaryBox.right,
        sameColumn: cards.every((card) => Math.abs(card.getBoundingClientRect().left - cards[0].getBoundingClientRect().left) < 1),
        textFits: cards.every((card) => [...card.querySelectorAll<HTMLElement>('.ast-card-amount, .ast-card-help, .ast-card-note, .ast-type')].every((text) => {
          const range = document.createRange(); range.selectNodeContents(text)
          const box = card.getBoundingClientRect()
          return text.scrollWidth <= text.clientWidth + 1 && [...range.getClientRects()].every((rect) => rect.left >= box.left && rect.right <= box.right)
        })),
        contrasts: [...element.querySelectorAll('.ast-card-help, .ast-card-note, .ast-type')].map(contrast),
      }
    })
    expect(metrics.right).toBeLessThanOrEqual(width)
    if (width < 576) expect(metrics.sameColumn).toBe(true)
    expect(metrics.textFits).toBe(true)
    expect(Math.min(...metrics.contrasts)).toBeGreaterThanOrEqual(4.5)
    await page.screenshot({ path: `e2e-artifacts/773-statement-${width}.png`, fullPage: true })
  })
}
