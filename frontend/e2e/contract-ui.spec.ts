import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { chooseDetailedHelp, readDetailedHelp } from './screenHelp'

const contract = {
  contractId: 1,
  contractNo: 'CONT-UI-001',
  contractDate: '2026-07-01',
  contractStatus: 'ACTIVE',
  insurerId: 1,
  insurerName: '시연 보험사',
  productOfferingId: 7,
  productName: '시연 상품',
  offeringVersion: 'v1',
  agentId: 3,
  agentName: '시연 설계사',
  organizationId: 4,
  organizationName: '계약 당시 조직',
  paymentCycleCode: 'MONTHLY',
  premiumPerCycleAmount: 100000,
  firstPremiumAmount: 100000,
  monthlyEquivalentFirstPremium: 100000,
  paymentTermMonths: 120,
  updatedAt: '2026-07-01T00:00:00.123456Z',
}

test('계약 안내는 날짜·보험사·상품 선택을 이어주고 입력과 버튼을 가리지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await fixture(page)
  await page.goto('contracts/new?month=2026-07')
  const writes: string[] = []
  page.on('request', request => { if (/POST|PUT|PATCH|DELETE/.test(request.method()) && !request.url().includes('/auth/')) writes.push(request.url()) })
  const form = page.locator('[data-guide-target="contract.form"]')
  await expect(form).toBeVisible()
  await expect(form.locator('.contract-editor-basic [name]').evaluateAll(nodes => nodes.slice(0, 4).map(node => node.getAttribute('name'))))
    .resolves.toEqual(['contractDate', 'insurerId', 'productOfferingId', 'contractNo'])
  await expect(page.getByText('월납환산 초회보험료의 12배입니다. 예: 10만원 × 12 = 120만원', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '도움말', exact: true }).click()
  const guide = page.locator('.screen-step-guide')
  await expect(guide.getByRole('heading', { name: '계약일', exact: true })).toBeVisible()
  const calendar = page.getByRole('group', { name: '계약일 달력' })
  await expect(calendar).toBeVisible()
  await expect.poll(async () => (await guide.boundingBox())!.x > (await calendar.boundingBox())!.x + (await calendar.boundingBox())!.width).toBe(true)
  // Choosing the displayed date also advances; do not require a value change.
  await calendar.locator('[aria-pressed="true"]').click()
  await expect(guide.getByRole('heading', { name: '보험회사 선택', exact: true })).toBeVisible()
  await page.getByRole('listbox', { name: '보험회사 선택 목록' }).getByRole('option', { name: /시연 보험사/ }).click()
  await expect(guide.getByRole('heading', { name: '상품 판매버전', exact: true })).toBeVisible()
  await page.getByRole('option', { name: /시연 상품/ }).click()
  await expect(guide.getByRole('heading', { name: '계약번호', exact: true })).toBeVisible()
  await expect(form.locator('[name="productOfferingId"]')).toHaveAttribute('data-selected-value', '7')
  await form.locator('[name="contractNo"]').fill('GUIDE-KEEP')
  await guide.getByRole('button', { name: '다음', exact: true }).click()
  await page.getByRole('listbox', { name: '계약상태 선택 목록' }).getByRole('option', { name: '정상', exact: true }).click()
  await expect(guide.getByRole('heading', { name: '모집 설계사와 소속 조직' })).toBeVisible()
  const agent = form.locator('[name="agentId"]')
  await expect(agent).toBeFocused()
  await expect.poll(async () => {
    const coach = await guide.boundingBox(), field = await agent.boundingBox()
    return Boolean(coach && field && coach.y + coach.height < field.y)
  }).toBe(true)
  await page.screenshot({ path: 'test-results/contract-guide-agent.png' })
  await page.getByRole('option', { name: /시연 설계사/ }).click()
  await expect(guide.getByRole('heading', { name: '보험료와 납입 조건' })).toBeVisible()
  await expect.poll(async () => {
    const coach = await guide.boundingBox(), field = await page.locator('.contract-editor-premium').boundingBox()
    return Boolean(coach && field && coach.x + coach.width < field.x)
  }).toBe(true)
  await form.locator('[name="monthlyEquivalentFirstPremium"]').fill('100000')
  await expect(page.getByText('월납환산 초회보험료 100,000원 × 12 = 1,200,000원', { exact: true })).toBeVisible()
  await guide.getByRole('button', { name: '다음', exact: true }).click()
  await guide.getByRole('button', { name: '다음', exact: true }).click()
  await expect(guide.getByRole('heading', { name: '계약 저장' })).toBeVisible()
  await expect.poll(async () => {
    const coach = await guide.boundingBox(), actions = await page.locator('.contract-action-buttons').boundingBox()
    return Boolean(coach && actions && coach.x + coach.width < actions.x)
  }).toBe(true)
  await page.screenshot({ path: 'test-results/contract-guide-save.png' })
  await guide.getByRole('button', { name: '안내 마치기' }).click()
  await expect(page.locator('[data-guide-picker]')).toHaveCount(0)
  await expect(form.locator('[name="contractNo"]')).toHaveValue('GUIDE-KEEP')
  expect(writes).toEqual([])
})

for (const viewport of [{ width: 1440, height: 900 }, { width: 1366, height: 768 }]) {
  test(`보험계약 등록은 입력과 저장 버튼이 한 화면에 들어온다 (${viewport.width})`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await fixture(page)
    await page.goto('contracts/new?month=2026-07')
    const save = page.getByRole('button', { name: '저장하고 예상 스케줄 만들기', exact: true })
    await expect(save).toBeVisible()
    await expect.poll(async () => {
      const box = await save.boundingBox()
      return Boolean(box && box.y >= 0 && box.y + box.height <= viewport.height)
    }).toBe(true)
    await expect(page.getByRole('heading', { name: '보험계약 등록', exact: true })).toBeInViewport()
    await expect(page.getByLabel('납입기간(개월)', { exact: false })).toBeInViewport()
    await page.screenshot({ path: `test-results/contract-form-compact-${viewport.width}.png` })
  })
}

test('모바일 보험계약 목록 도움말도 단계마다 두 줄로 표시한다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await fixture(page)
  await page.goto('contracts?month=2026-07')
  await readDetailedHelp(page, page.getByRole('button', { name: '도움말', exact: true }), 3)
})

test('수정 후 계약 목록은 상단을 유지하고 입력·탭별 상세 도움말도 끝까지 읽는다', async ({ page }) => {
  test.setTimeout(60_000) // Three screens, including the intentionally slower spotlight transitions.
  await page.setViewportSize({ width: 1440, height: 1000 })
  await fixture(page, true, 1, 20)
  await page.goto('contracts?month=2026-07')
  await expect(page.locator('tbody tr')).toHaveCount(20)
  await chooseDetailedHelp(page)
  const help = page.getByRole('button', { name: '도움말', exact: true })
  const top = await page.evaluate(() => scrollY)
  await help.click()
  const guide = page.locator('.screen-step-guide')
  await guide.getByRole('button', { name: '다음', exact: true }).click()
  await expect(guide.getByRole('heading', { name: '계약 목록 읽기' })).toBeVisible()
  await expect.poll(async () => {
    const table = page.locator('[aria-labelledby="contract-results-title"] table')
    const anchor = await table.locator('thead').boundingBox()
    const thirdRow = await table.locator('tbody tr').nth(2).boundingBox()
    const highlight = await page.locator('.screen-step-spotlight').boundingBox()
    return Boolean(anchor && thirdRow && highlight && Math.abs(highlight.y - anchor.y + 10) < 2
      && Math.abs(highlight.y + highlight.height - thirdRow.y - thirdRow.height - 10) < 2)
  }).toBe(true)
  await expect.poll(() => page.evaluate(() => scrollY)).toBe(top)
  await page.screenshot({ path: 'test-results/help-list-top-anchor.png' })
  await expect(page.locator('.screen-step-spotlight')).toHaveCSS('transition-duration', '0.7s, 0.7s, 0.7s, 0.7s')
  await guide.getByRole('button', { name: '종료', exact: true }).click()
  await page.getByRole('link', { name: '신규 등록', exact: true }).click()
  await expect(page.getByRole('heading', { name: '보험계약 등록' })).toBeVisible()
  await page.locator('[name="contractNo"]').fill('HELP-DRAFT')
  const writes: string[] = []
  page.on('request', request => { if (/POST|PUT|PATCH|DELETE/.test(request.method()) && !request.url().includes('/auth/')) writes.push(request.url()) })
  const titles = await readDetailedHelp(page, help, 8)
  expect(titles).toContain('계약 저장')
  await expect(page.locator('[name="contractNo"]')).toHaveValue('HELP-DRAFT')
  expect(writes).toEqual([])
  await page.goto('contracts/1?month=2026-07')
  await expect(page.locator('.contract-summary-number')).toHaveText('CONT-UI-001')
  await chooseDetailedHelp(page)
  expect(await readDetailedHelp(page, help, 4)).toContain('계약 상태이력')
  await page.getByRole('tab', { name: '지급/대사' }).click()
  expect(await readDetailedHelp(page, help, 3)).toContain('계약에 연결된 지급')
})
test('기본 도움말은 현재 계약 목록을 짚고 입력을 보존한다', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await fixture(page)
  await page.goto('contracts?month=2026-07')
  const filter = page.locator('[data-guide-target="contract.filters"]')
  await filter.getByLabel('계약번호', { exact: true }).fill('HELP-KEEP')
  const writes: string[] = []
  page.on('request', request => {
    if (/POST|PUT|PATCH|DELETE/.test(request.method()) && !request.url().includes('/auth/')) writes.push(request.url())
  })
  await page.getByRole('button', { name: '도움말', exact: true }).click()
  const help = page.getByRole('region', { name: '보험계약 목록 도움말' })
  await expect(help.getByRole('heading', { name: '계약 조회 조건' })).toBeVisible()
  await expect(help.getByRole('button', { name: '다음', exact: true })).toBeEnabled()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.screenshot({ path: 'test-results/help-contract-desktop.png' })
  await page.keyboard.press('Escape')
  await expect(filter.getByLabel('계약번호', { exact: true })).toHaveValue('HELP-KEEP')
  await expect(page.getByRole('button', { name: '도움말', exact: true })).toBeFocused()
  expect(writes).toEqual([])
})
test('도움말은 목록이 길어도 첫 프레임에 열리고 바로 닫거나 다시 열 수 있다', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await fixture(page, true, 1, 20)
  await page.goto('contracts?month=2026-07')
  await expect(page.locator('tbody tr')).toHaveCount(20)
  const firstFrame = await page.evaluate(async () => {
    const trigger = document.querySelector<HTMLButtonElement>('.screen-help-trigger')!
    const start = performance.now()
    const painted = new Promise<{ elapsed: number; open: boolean }>(resolve => {
      requestAnimationFrame(() => resolve({
        elapsed: performance.now() - start,
        open: Boolean(document.querySelector('.screen-step-guide')),
      }))
    })
    trigger.click()
    return painted
  })
  console.log('Help first frame', firstFrame)
  expect(firstFrame.open).toBe(true)
  expect(firstFrame.elapsed).toBeLessThan(500)
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  const previewStart = Date.now()
  await page.getByRole('button', { name: '도움말', exact: true }).click()
  await expect(page.locator('.screen-step-count')).toHaveText('화면 요소 안내 · 1/3')
  console.log('Help preview ready after reopen', Date.now() - previewStart, 'ms')
  await expect(page.getByRole('region', { name: '보험계약 목록 도움말' })).toHaveCount(1)
})
async function fixture(page: Page, canProcess = true, totalPages = 1, rowCount = 1) {
  const envelope = (data: unknown) => ({ data, error: null, requestId: 'contract-browser' })
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path.endsWith('/auth/refresh'))
      return route.fulfill({ json: envelope({ accessToken: 'contract-fixture', expiresIn: 1800 }) })
    if (path.endsWith('/auth/me'))
      return route.fulfill({
        json: envelope({
          loginId: 'tester',
          userName: '시연 담당자',
          roleCode: canProcess ? 'SETTLEMENT' : 'COMPLIANCE',
          canProcess,
          demoMonth: '2026-07',
        }),
      })
    if (path.endsWith('/contracts/999999'))
      return route.fulfill({
        status: 400,
        json: {
          data: null,
          error: { code: 'FGC-COMMON-002', message: '존재하지 않는 보험계약입니다.' },
          requestId: 'missing-contract',
        },
      })
    if (path.endsWith('/contracts/1')) {
      if (route.request().method() === 'PUT')
        return route.fulfill({
          status: 409,
          json: {
            data: null,
            error: {
              code: 'FGC-CONT-003',
              message:
                '다른 요청에서 계약을 수정했습니다. 현재 입력은 유지됩니다. 변경 내용을 확인한 뒤 ‘최신 계약 다시 불러오기’를 눌러 다시 수정하세요.',
            },
            requestId: 'conflict-contract',
          },
        })
      return route.fulfill({ json: envelope(contract) })
    }
    if (path.endsWith('/contracts'))
      return route.fulfill({
        json: envelope({ content: Array.from({ length: rowCount }, (_, index) => ({ ...contract, contractId: index + 1 })), page: 1, size: 20, totalPages, totalElements: totalPages > 1 ? 21 : rowCount }),
      })
    if (path.endsWith('/schedules'))
      return route.fulfill({
        json: envelope({
          headers: [{ scheduleHeaderId: 10, scheduleVersionNo: 1, installmentCount: 1, lineCount: 1, status: 'EXPECTED' }],
          lines: [
            {
              lineNo: 1,
              installmentNo: 1,
              dueDate: '2026-07-01',
              commissionItemName: '초회수수료',
              recipientName: '시연 설계사',
              expectedAmount: 100000,
              lineStatus: 'EXPECTED',
            },
          ],
        }),
      })
    if (path.endsWith('/journals'))
      return route.fulfill({
        json: envelope([
          {
            journalHeaderId: 1,
            journalNo: 'JV-2026-07-0001',
            journalDate: '2026-07-01',
            journalType: 'EXPECTED_INSURER_INCOME',
            paymentStage: 'INSURER_TO_GA',
            debitTotal: 700000,
            creditTotal: 700000,
            differenceAmount: 0,
            status: 'POSTED',
          },
          { journalHeaderId: 2, journalNo: 'JV-2026-07-0002', journalType: 'REVERSAL', status: 'POSTED' },
        ]),
      })
    if (path.endsWith('/transactions'))
      return route.fulfill({
        json: envelope({
          transactions: [
            {
              commissionTransactionId: 1,
              settlementMonth: '2026-07',
              paymentStage: 'INSURER_TO_GA',
              amount: 700000,
              attributionTotal: 700000,
              differenceAmount: 0,
              status: 'CONFIRMED',
              sourceType: 'INSURER_STATEMENT',
            },
          ],
          reconciliations: [],
        }),
      })
    if (path.endsWith('/base/insurers'))
      return route.fulfill({
        json: envelope({ content: [{ insurerId: 1, insurerName: '시연 보험사', activeYn: true }] }),
      })
    if (path.endsWith('/base/products'))
      return route.fulfill({
        json: envelope({
          content: [
            {
              productOfferingId: 7,
              productName: '시연 상품',
              offeringVersion: 'v1',
              paymentTermMonths: 120,
              standardDeduction80Yn: false,
            },
          ],
        }),
      })
    if (path.endsWith('/base/agents'))
      return route.fulfill({
        json: envelope({
          content: [
            {
              agentId: 3,
              agentName: '시연 설계사',
              agentCode: 'FC3',
              agentStatus: 'ACTIVE',
              rankCode: 'FC',
              activeYn: true,
              organizationId: 4,
              organizationName: '계약 당시 조직',
            },
          ],
        }),
      })
    return route.fulfill({ json: envelope([]) })
  })
}

test('계약 직접 진입·새로고침·두 스케줄·409 입력 보존·수정 취소를 검증한다', async ({ page }, testInfo) => {
  await fixture(page)
  await page.goto('contracts/1?tab=schedules')
  await expect(page.getByRole('tab', { name: '예상 스케줄' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('table', { name: '보험회사→GA 예상 스케줄' })).toBeVisible()
  await expect(page.getByRole('table', { name: 'GA→설계사 예상 스케줄' })).toBeVisible()
  await expect(page.getByText('버전 1, 1회차', { exact: true })).toHaveCount(2)
  await expect(page.getByRole('link', { name: /버전.*상세/ })).toHaveCount(0)
  await expect(page.locator('.contract-detail-schedule-stages header .status-badge')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('contract-schedule-summary.png'), fullPage: true })
  await page.reload()
  await expect(page.getByRole('table', { name: 'GA→설계사 예상 스케줄' })).toBeVisible()
  await page.getByRole('button', { name: '수정', exact: true }).click()
  const save = page.getByRole('button', { name: '수정 저장' })
  await expect(save).toBeEnabled()
  await page.getByPlaceholder('계약번호 입력').fill('CONT-UI-EDIT')
  await save.click()
  await expect(page.getByRole('alert')).toContainText('다른 요청에서 계약을 수정했습니다.')
  await expect(page.getByText('다른 요청에서 계약을 수정했습니다.', { exact: false })).toHaveCount(1)
  await expect(page.getByPlaceholder('계약번호 입력')).toHaveValue('CONT-UI-EDIT')
  await expect(save).toBeDisabled()
  await page.getByRole('link', { name: '취소', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '저장하지 않은 입력이 있습니다' })).toBeVisible()
  await page.getByRole('button', { name: '돌아가기' }).click()
  await expect(page.getByPlaceholder('계약번호 입력')).toHaveValue('CONT-UI-EDIT')
  await page.getByRole('link', { name: '취소', exact: true }).click()
  await page.getByRole('button', { name: '이동' }).click()
  await expect(page.getByRole('heading', { name: '보험계약 목록' })).toBeVisible()
})

test('CONT-W03 상태 변경은 허용 전이·사유 입력·확인창을 거쳐 사건과 멱등키를 함께 보낸다', async ({
  page,
}, testInfo) => {
  await fixture(page)
  let put: { body: unknown; key?: string } | undefined
  // 나중에 등록한 route가 fixture보다 먼저 응답한다.
  await page.route('**/api/v1/contracts/1', async (route) => {
    const envelope = (data: unknown) => ({ data, error: null, requestId: 'contract-status' })
    if (route.request().method() === 'PUT') {
      put = { body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] }
      return route.fulfill({
        json: envelope({
          contractId: 1,
          scheduleHeaderIds: [],
          regeneratedScheduleIds: [],
          statusEventId: 9,
          followUp: { status: 'PENDING', eventId: 9 },
        }),
      })
    }
    return route.fulfill({
      json: envelope({
        ...contract,
        allowedNextStatuses: ['UNPAID', 'TERMINATED', 'MATURED', 'CANCELLED'],
        statusReasonCodes: ['MANUAL_STATUS_CHANGE'],
      }),
    })
  })
  await page.goto('contracts/1/edit')
  const status = page.getByLabel('변경 상태')
  await expect(status.locator('option')).toHaveText(['변경 안 함', '미납', '해지', '만기', '청약철회'])
  await expect(page.getByRole('textbox', { name: '모집 설계사' })).toHaveAttribute('readonly', '')
  await status.selectOption('TERMINATED')
  await page.getByLabel('효력일시').fill('2026-10-20T00:00')
  await page.getByLabel('접수일시').fill('2026-10-27T10:00')
  await page.getByLabel('사유 구분').selectOption('MANUAL_STATUS_CHANGE')
  await page.getByLabel(/^사유\s*\*?$/).fill('보험회사 해지 접수 반영')
  await expect(page.getByText(/변경 요약: 상태 변경 기록, 해당 미확정 대상 처리/)).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('contract-status-event-input.png'), fullPage: true })
  await page.getByRole('button', { name: '수정 저장' }).click()
  const dialog = page.getByRole('dialog', { name: '상태 변경 저장 확인' })
  await expect(dialog).toContainText('정상 → 해지')
  await expect(dialog).toContainText('2026-10-20 00:00')
  await page.screenshot({ path: testInfo.outputPath('contract-status-event-confirm.png') })
  await dialog.getByRole('button', { name: '확인하고 저장' }).click()
  await expect(page.getByRole('heading', { name: '보험계약 상세' })).toBeVisible()
  await expect(page.getByText('후속 처리 상태를 확인하세요. (후속 처리: 대기 중)', { exact: false })).toBeVisible()
  expect(put?.key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
  expect(put?.body).toMatchObject({
    contractStatus: 'TERMINATED',
    agentId: 3,
    statusEvent: {
      effectiveAt: '2026-10-20T00:00:00+09:00',
      receivedAt: '2026-10-27T10:00:00+09:00',
      reasonCode: 'MANUAL_STATUS_CHANGE',
      reason: '보험회사 해지 접수 반영',
    },
  })
})

test('없는 계약의 기존 오류와 COMPLIANCE의 폼 직접 진입 권한을 검증한다', async ({ page }) => {
  await fixture(page, false)
  await page.goto('contracts/999999')
  await expect(page.getByRole('alert')).toContainText('존재하지 않는 보험계약입니다.')
  await page.goto('contracts/1/edit')
  await expect(page.getByRole('heading', { name: '권한 없음' })).toBeVisible()
  await expect(page.getByRole('button', { name: /저장하고 예상 스케줄/ })).toHaveCount(0)
})

test('상세 값 정렬·가로 한도 안내문·지급 표 전체 열의 가시성을 유지한다', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 900, height: 1000 })
  await fixture(page)
  await page.goto('contracts/1')
  await expect(page.locator('.contract-summary-number')).toHaveText('CONT-UI-001')
  for (const selector of ['.contract-summary-item', '.contract-information-item']) {
    const item = page.locator(selector).first()
    const label = await item.locator('dt').boundingBox()
    const value = await item.locator('dd').boundingBox()
    expect(Math.abs(label!.x - value!.x)).toBeLessThanOrEqual(1)
    expect(value!.y - label!.y - label!.height).toBeLessThanOrEqual(8)
  }
  await page.screenshot({ path: testInfo.outputPath('contract-detail-aligned.png'), fullPage: true })
  await page.getByRole('tab', { name: '1,200%', exact: true }).click()
  const note = page.locator('.contract-tab-guidance')
  await expect(note).toContainText('두 규제를 더하지 마세요')
  await expect(note.locator('.guidance-icon')).toHaveText('warning')
  await expect(page.getByRole('region', { name: '지급단계별 판정' })).toContainText('조회된 자료가 없습니다.')
  expect((await note.boundingBox())!.height).toBeLessThan(80)
  expect((await note.locator('.guidance-copy').boundingBox())!.width).toBeGreaterThan(200)
  await page.screenshot({ path: testInfo.outputPath('contract-cap-compact.png'), fullPage: true })
  await page.getByRole('tab', { name: '지급/대사' }).click()
  const table = page.getByRole('table', { name: '수수료 지급 건' })
  await expect(table.getByRole('cell', { name: '700,000원' })).toHaveCount(2)
  const viewport = await table.locator('..').boundingBox()
  // #589 원천 컬럼이 없어졌으므로 새 마지막 열인 상태 헤더가 뷰포트 안에 들어오는지 확인한다.
  await expect(table.getByRole('columnheader', { name: '원천' })).toHaveCount(0)
  await expect(table.getByRole('cell', { name: 'INSURER_STATEMENT' })).toHaveCount(0)
  const last = await table.getByRole('columnheader', { name: '상태' }).boundingBox()
  expect(last!.x + last!.width).toBeLessThanOrEqual(viewport!.x + viewport!.width + 1)
  const stage = await table.getByRole('cell', { name: '보험회사→GA' }).boundingBox()
  const amount = await table.getByRole('cell', { name: '700,000원' }).first().boundingBox()
  // 원천 열이 빠져 남은 열이 넓어졌으므로 지급단계→금액 거리 상한을 120에서 140으로 맞춘다(여전히 한 화면 안의 근접 배치).
  expect(amount!.x - stage!.x).toBeLessThan(140)
  await page.screenshot({ path: testInfo.outputPath('contract-payment-compact.png'), fullPage: true })
  await page.getByRole('tab', { name: '원장', exact: true }).click()
  const journals = page.getByRole('table', { name: '이 계약의 분개 목록' })
  await expect(journals.getByRole('cell', { name: '예상 수입(보험회사→GA)', exact: true })).toBeVisible()
  await expect(journals.getByRole('cell', { name: '역분개', exact: true })).toBeVisible()
  await expect(journals).not.toContainText('EXPECTED_INSURER_INCOME')
  const typeResize = page.getByRole('separator', { name: '유형 열 너비', exact: true })
  await typeResize.press('Shift+ArrowRight')
  await typeResize.press('Shift+ArrowRight')
  const typeCell = journals.getByRole('cell', { name: '예상 수입(보험회사→GA)', exact: true })
  expect((await typeCell.boundingBox())!.width).toBeGreaterThan(200)
  await page.screenshot({ path: testInfo.outputPath('contract-ledger-korean.png'), fullPage: true })
})

test('페이지네이션은 현재 페이지 hover 색상과 비활성 화살표 표시를 유지한다', async ({ page }) => {
  await fixture(page, true, 2)
  await page.goto('contracts')
  const pagination = page.locator('.contract-pagination')
  const current = pagination.locator('[aria-current="page"]')
  await expect(current).toHaveText('1')
  const colors = () =>
    current.evaluate((element) => {
      const style = getComputedStyle(element)
      return { background: style.backgroundColor, border: style.borderColor, color: style.color }
    })
  await page.mouse.move(0, 0)
  const normal = await colors()
  await current.hover()
  expect(await colors()).toEqual(normal)
  for (const label of ['이전 5페이지', '다음 5페이지']) {
    const arrow = pagination.getByRole('button', { name: label })
    await expect(arrow).toBeDisabled()
    await expect(arrow).toHaveCSS('opacity', '0.4')
    const background = await arrow.evaluate((element) => getComputedStyle(element).backgroundColor)
    const box = await arrow.boundingBox()
    if (!box) throw new Error('페이지네이션 화살표가 표시되지 않습니다.')
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
    await expect(arrow).toHaveCSS('background-color', background)
  }
})


test('전체 업무 가이드의 계약 상세 다음 단계는 카드와 다음 버튼 위치를 유지한다', async ({ page }, info) => {
  await fixture(page)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/?month=2026-07')
  await page.getByRole('button', { name: '전체 업무 가이드', exact: true }).click()
  await page.getByRole('button', { name: /계약 생성부터 시작/ }).click()
  await expect(page).toHaveURL(/contracts\/new/)
  await expect(page.getByRole('complementary', { name: '전체 업무 가이드' })).toHaveAttribute('aria-busy', 'false')
  // Simulate the route transition after saving, keeping the shell guide mounted.
  await page.evaluate(() => { history.pushState({}, '', '/contracts/1?month=2026-07'); window.dispatchEvent(new PopStateEvent('popstate')) })
  const guide = page.getByRole('complementary', { name: '전체 업무 가이드' })
  await expect(guide.getByRole('heading', { name: '선택한 계약 확인' })).toBeVisible()
  const next = guide.getByRole('button', { name: '다음', exact: true })
  await expect(next).toBeEnabled()
  const before = await guide.boundingBox(), buttonBefore = await next.boundingBox()
  await page.screenshot({ path: info.outputPath('workflow-fixed-before.png') })
  await next.click()
  await expect(guide.getByRole('heading', { name: '계약에서 이어지는 작업' })).toBeVisible()
  await expect.poll(async () => JSON.stringify(await guide.boundingBox())).toBe(JSON.stringify(before))
  const buttonAfter = await next.boundingBox()
  expect(Math.abs(buttonAfter!.x - buttonBefore!.x)).toBeLessThanOrEqual(1)
  expect(Math.abs(buttonAfter!.y - buttonBefore!.y)).toBeLessThanOrEqual(1)
  await page.screenshot({ path: info.outputPath('workflow-fixed-after.png') })
})
