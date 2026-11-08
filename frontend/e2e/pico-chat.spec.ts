import { test, expect } from '@playwright/test'

test('피코는 답변 준비와 순차 출력을 보여주고 이동 버튼을 별도 영역에 둔다', async ({ page }, info) => {
  let release: (() => void) | undefined
  const versionReads: string[] = []
  page.on('request', request => { if (/\/schedules\/\d+\/versions/.test(request.url())) versionReads.push(request.url()) })
  const message = '보험계약은 목록에서 계약번호와 보험회사로 검색할 수 있습니다. 새 계약을 등록하려면 보험회사를 고르고 상품, 설계사, 보험료와 납입 조건을 입력하세요. 아래에서 필요한 업무를 선택하면 해당 화면의 안내를 확인할 수 있습니다.'
  const envelope = (data: unknown) => ({ data, error: null, requestId: 'pico-chat' })
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = { content: [], totalPages: 1, totalElements: 0 }
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'pico-chat' }
    else if (path.endsWith('/auth/me')) data = { loginId: 'pico', userName: '담당자', roleCode: 'SETTLEMENT', canProcess: true, demoMonth: '2026-07' }
    else if (path.endsWith('/schedules/5')) data = { header: { scheduleHeaderId: 5, contractId: 3, contractNo: '계약-3', paymentStage: 'GA_TO_FC', scheduleRegime: 'CURRENT', schedulePurpose: 'OPERATIONAL', status: 'PLANNED', activeYn: true, scheduleVersionNo: 1 }, lines: [] }
    else if (path.endsWith('/schedules/5/versions')) data = []
    else if (path.endsWith('/ai/pico/ask')) {
      await new Promise<void>(resolve => { release = resolve })
      data = { source: 'AI', decision: 'ANSWER_ONLY', message, guideVersion: '1.0', contextRevision: route.request().postDataJSON().clientContextRevision,
        evidenceRefs: [
          { chunkId: 'contract.find:overview', workflowId: 'contract.find', stepId: null, title: '보험계약 검색/선택' },
          { chunkId: 'contract.register:overview', workflowId: 'contract.register', stepId: null, title: '보험계약 등록/수정' },
        ], validatedGuide: null, fallbackReason: null }
    }
    await route.fulfill({ json: envelope(data) })
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/schedules/5?month=2026-07')
  await expect(page.getByRole('heading', { name: '예상 스케줄 상세', exact: true })).toBeVisible()
  await expect(page.getByRole('tablist', { name: '스케줄 정보 보기' })).toHaveCount(0)
  await expect(page.locator('.schedule-page-header').getByRole('button')).toHaveCount(1)
  await expect(page.locator('.schedule-info-actions').getByRole('button')).toHaveCount(3)
  expect(versionReads).toEqual([])
  await page.screenshot({ path: info.outputPath('schedule-unified-summary.png') })
  await expect(page.locator('.app-header').getByRole('button', { name: '업무 비서 AI 열기, 피코' })).toHaveCount(0)
  await page.getByRole('button', { name: '업무 비서 AI 열기, 피코' }).click()
  await expect(page.getByRole('tab', { name: '대화', exact: true }).locator('svg')).toBeVisible()
  await page.getByRole('textbox', { name: '피코에게 질문' }).fill('보험계약은 어떻게 진행해?')
  await page.getByRole('button', { name: '질문 보내기' }).click()
  await expect(page.getByRole('status', { name: '피코 답변 준비 중' })).toBeVisible()
  await page.screenshot({ path: info.outputPath('pico-thinking.png') })
  await expect.poll(() => Boolean(release)).toBe(true)
  release!()
  const text = page.locator('.pico-answer-text')
  await expect(text).toHaveAttribute('data-streaming', 'true')
  await expect.poll(async () => (await text.textContent())!.length).toBeGreaterThan(0)
  expect((await text.textContent())!.length).toBeLessThan(message.length)
  await expect(page.locator('.pico-answer-actions')).toHaveCount(0)
  await expect(text).toHaveText(message)
  await expect(text).toHaveAttribute('data-streaming', 'false')
  const actions = page.locator('.pico-answer-actions')
  await expect(actions.getByRole('button')).toHaveCount(2)
  await expect(actions.getByRole('button').first()).toHaveText('arrow_forward이동하기')
  await expect(page.locator('.pico-answer-note, .pico-context')).toHaveCount(0)
  await expect(page.getByText('업무 계산 도우미', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('pico-answer-actions.png') })
  await actions.getByRole('button').first().click()
  await expect(page).toHaveURL(/\/contracts\?month=2026-07/)
  await expect(page.locator('.screen-step-guide').getByRole('heading', { name: '계약 조회 조건' })).toBeVisible()
  await expect(page.locator('.pico-guide-card')).toHaveCount(0)
  await page.locator('.screen-step-guide').getByRole('button', { name: '종료', exact: true }).click()
  await page.getByRole('button', { name: '업무 비서 AI 열기, 피코' }).click()
  await expect(text).toHaveAttribute('data-streaming', 'false')
  await expect(text).toHaveText(message)
  await page.getByRole('tab', { name: '업무 안내', exact: true }).click()
  await expect(page.getByRole('tab', { name: '업무 안내', exact: true }).locator('.material-symbols-rounded')).toHaveText('menu_book')
  await expect(page.locator('.pico-plan .pico-suggestion-icon')).not.toHaveCount(0)
  await page.screenshot({ path: info.outputPath('pico-guide-icons.png') })
  await page.getByRole('button', { name: /지급 초안 저장/ }).click()
  await expect(page).toHaveURL(/\/transactions\/new\?month=2026-07/)
  await expect(page.locator('.screen-step-guide').getByRole('heading', { name: '지급할 계약 선택' })).toBeVisible()
  await expect(page.locator('.pico-guide-card')).toHaveCount(0)
})
