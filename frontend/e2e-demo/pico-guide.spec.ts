import { test, expect, login, api } from './fixtures'

// SRC-028: real HTTP and catalog, AI UI enabled; provider-free fallback is intentional.
for (const width of [1440, 390]) {
  test(`피코 실서버 ${width}px: 키 없음 고정 안내와 읽기 튜토리얼`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    const writes: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('request', (request) => {
      const path = new URL(request.url()).pathname
      if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) &&
          path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/') &&
          !path.startsWith('/api/v1/ai/') && !path.startsWith('/api/v1/assistant/')) writes.push(path)
    })
    await login(page, 'audit01', '/contracts?month=2026-07')
    const launcher = page.getByRole('button', { name: '업무 비서 AI 열기, 피코' })
    await expect(launcher).toBeInViewport()
    await launcher.click()
    const pico = page.getByRole('dialog', { name: '피코 업무 비서' })
    await expect(pico).toBeVisible()
    const replying = page.waitForResponse((response) => response.url().includes('/ai/pico/ask'))
    await pico.getByLabel('피코에게 질문').fill('보험계약 검색 방법을 알려주세요')
    await pico.getByRole('button', { name: '질문 보내기' }).click()
    const response = await replying
    expect(response.ok(), await response.text()).toBe(true)
    expect((await response.json()).data.source).toBe('FIXED')
    await expect(pico.getByText('안내', { exact: true }).first()).toBeVisible()
    await pico.getByRole('tab', { name: '업무 안내', exact: true }).click()
    await expect(pico.getByRole('button', { name: /^지급 초안 저장/ })).toHaveCount(0)
    await expect(pico.getByRole('button', { name: /^지급 사전검증/ })).toHaveCount(0)
    await pico.getByRole('button', { name: /^보험계약 검색\/선택/ }).click()
    const tutorial = page.getByRole('region', { name: '업무 안내', exact: true })
    await expect(tutorial.getByRole('heading', { name: '계약 검색', exact: true })).toBeVisible()
    await expect.poll(async () => {
      const target = await page.locator('[data-guide-feature="contract.filter.input.contractno"]').boundingBox()
      const highlight = await page.locator('.payment-guide-spotlight').boundingBox()
      const coach = await tutorial.boundingBox()
      // Measure both offsets from the actual input: 10px highlight and 24px coach gap.
      return target && highlight && coach ? Math.max(
        Math.abs(highlight.y + highlight.height - target.y - target.height - 10),
        Math.abs(coach.y - target.y - target.height - 24),
      ) : 999
    }).toBeLessThan(2)
    await expect(tutorial.getByRole('button', { name: '이전 안내' })).toBeDisabled()
    await tutorial.getByRole('button', { name: '확인했어요, 다음 안내' }).click()
    await expect(tutorial.getByRole('heading', { name: '조회 결과 확인', exact: true })).toBeVisible()
    await expect(tutorial.getByRole('button', { name: '확인했어요, 다음 안내' })).toBeEnabled()
    await expect(tutorial.getByRole('button', { name: '현재 대상에서 다시 시작' })).toHaveCount(0)
    await page.screenshot({ path: info.outputPath(`pico-${width}.png`), fullPage: true })
    await tutorial.getByRole('button', { name: '업무 안내 종료' }).click()
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })
}

test('전체 업무 가이드를 보존하며 후속 채팅으로 전환한다', async ({ page }) => {
  await login(page, 'settle01', '/?month=2026-07')
  await page.getByRole('button', { name: '전체 업무 가이드', exact: true }).click()
  const settlement = page.getByRole('complementary', { name: '전체 업무 가이드' })
  await expect(settlement).toBeVisible()
  await settlement.getByRole('button', { name: '안내 시작', exact: true }).click()
  await expect(settlement.getByRole('button', { name: '현재 결과로 이어서 안내' })).toBeVisible()
  await expect(page.getByRole('dialog', { name: '피코 업무 비서' })).toHaveCount(0)
  await expect(page.getByRole('region', { name: '업무 안내' })).toHaveCount(0)
  await page.getByRole('button', { name: '업무 비서 AI 열기, 피코' }).click()
  const pico = page.getByRole('dialog', { name: '피코 업무 비서' })
  await expect(pico.getByRole('tab', { name: '대화', exact: true })).toHaveAttribute('aria-selected', 'true')
  await pico.getByRole('textbox', { name: '피코에게 질문' }).fill('현재 단계의 입력 위치를 다시 알려주세요')
  await expect(pico.getByRole('textbox', { name: '피코에게 질문' })).toHaveValue('현재 단계의 입력 위치를 다시 알려주세요')
  await expect(page.getByRole('region', { name: '업무 안내' })).toHaveCount(0)
  // The open chat deliberately hides the background guide. Inspect its preserved state after closing it.
  await pico.getByRole('button', { name: '비서 닫기', exact: true }).click()
  await expect(settlement.getByRole('button', { name: '펼치기' })).toBeVisible()
  await expect(settlement.getByRole('button', { name: '현재 결과로 이어서 안내' })).toBeVisible()
})

for (const width of [1440, 390]) {
  test(`피코 실서버 ${width}px: 저장 지급 계산 카드와 실제 도움말 연결`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 })
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await page.goto('login')
    const rows = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0001&page=1&size=20')
    const contract = await api(page, `/contracts/${rows.content[0].contractId}`)
    const items = await api(page, '/base/commission-items?asOf=2026-07-01')
    const item = items.find((entry: { itemCode: string }) => entry.itemCode === 'BASE_COMMISSION')
    const payment = await api(page, '/transactions', {
      paymentStage: 'GA_TO_FC', sourceType: 'GA_MANUAL_PAYMENT', sourceBusinessKey: 'E2E-PICO-CALC',
      agentId: contract.agentId, commissionItemId: item.commissionItemId,
      cashflowType: 'PAYMENT', settlementMonth: '2026-07-01', scheduledPaymentDate: '2026-07-31', amount: 20,
      attributions: [{ contractId: contract.contractId, amount: 20, attributionDate: '2026-07-31',
        attributionMethod: 'DIRECT', inclusionDecisionStatus: 'INCLUDED', exclusionType: 'NONE', inclusionDecisionReason: 'E2E 직접 귀속', allocationBasis: 'DIRECT' }],
    })
    expect(Number.isSafeInteger(payment.commissionTransactionId)).toBe(true)
    expect(payment.commissionTransactionId).toBeGreaterThan(0)
    const writes: string[] = []
    page.on('request', (request) => {
      const path = new URL(request.url()).pathname
      if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) &&
          /^\/api\/v1\/(transactions|contracts|validation-runs)(\/|$)/.test(path)) writes.push(path)
    })
    await login(page, 'settle01', `/transactions/new?id=${payment.commissionTransactionId}&month=2026-07`)
    await expect(page.locator('#btn-save')).toBeEnabled()
    for (const id of ['btn-save', 'btn-precheck', 'btn-confirm']) {
      const action = page.locator(`#${id}`)
      // 화면 맨 아래의 버튼도 스크롤하면 피코에 가리지 않아야 한다(.page-content 아래 여백).
      await action.evaluate((element) => element.scrollIntoView({ block: 'center', behavior: 'instant' }))
      await expect(action).toBeInViewport()
      expect(await action.evaluate((element) => {
        const box = element.getBoundingClientRect()
        return element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
      }), `${id} center must remain reachable with the launcher visible`).toBe(true)
    }
    await page.screenshot({ path: info.outputPath(`payment-launcher-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: '업무 비서 AI 열기, 피코' }).click()
    const pico = page.getByRole('dialog', { name: '피코 업무 비서' })
    const balancing = page.waitForResponse((response) => response.url().includes('/assistant/calculations') && response.request().postDataJSON()?.kind === 'ATTRIBUTION_BALANCE')
    await pico.getByRole('button', { name: '귀속 차액 조회' }).click()
    const balanceResponse = await balancing
    expect(balanceResponse.ok(), await balanceResponse.text()).toBe(true)
    expect((await balanceResponse.json()).data.kind).toBe('ATTRIBUTION_BALANCE')
    const result = pico.getByRole('region', { name: '계산 조회 결과' })
    await expect(result).toContainText('계산 조회 완료')
    await expect(result).toContainText('20원')
    await expect(result.locator('dl > div').filter({ has: page.getByText('지급액과 귀속 합계의 차이', { exact: true }) }).locator('dd')).toHaveText('0원')
    await page.screenshot({ path: info.outputPath(`payment-calculation-${width}.png`), fullPage: true })
    const candidacy = page.waitForResponse((response) => response.url().includes('/assistant/calculations') && response.request().postDataJSON()?.kind === 'PAYMENT_CANDIDATE')
    await pico.getByRole('button', { name: '지급 후보 검토 조회' }).click()
    const candidateResponse = await candidacy
    expect(candidateResponse.ok(), await candidateResponse.text()).toBe(true)
    expect((await candidateResponse.json()).data.kind).toBe('PAYMENT_CANDIDATE')
    await expect(result).toBeVisible()
    await expect(result).toContainText('저장 상태: 작성중')
    await pico.getByRole('tab', { name: '업무 안내', exact: true }).click()
    await pico.getByRole('button', { name: /^지급 초안 저장/ }).click()
    const guide = page.getByRole('region', { name: '지급 업무 가이드' })
    await expect(guide).toBeVisible()
    await expect(page.getByRole('region', { name: '업무 안내' })).toHaveCount(0)
    await page.getByRole('button', { name: '업무 비서 AI 열기, 피코' }).click()
    await expect(guide.getByRole('button', { name: '펼치기' })).toBeVisible()
    await expect(page.getByRole('textbox', { name: '피코에게 질문' })).toBeVisible()
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })
}
