import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'

// UI fixture. 실제 PostgreSQL·worker·범위 검사는 ContractStatusHistoryIntegrationTest·AgentDataScopeIntegrationTest가 확인한다.
const envelope = (data: unknown, requestId = 'status-history-browser') => ({ data, error: null, requestId })
const contract = {
  contractId: 1,
  contractNo: 'CONT-HIS-001',
  contractDate: '2026-03-01',
  contractStatus: 'LAPSED',
  contractStatusLabel: '실효',
  insurerName: '시연 보험사',
  productName: '시연 상품',
  agentId: 3,
  agentName: '시연 설계사',
  organizationName: '시연 지점',
  paymentCycleCode: 'MONTHLY',
  premiumPerCycleAmount: 100000,
  firstPremiumAmount: 100000,
  monthlyEquivalentFirstPremium: 100000,
  paymentTermMonths: 120,
  updatedAt: '2026-09-02T00:00:00Z',
}
const holdJob = 'PaymentHoldJob'
const job = (status: string, overrides: object = {}) => ({
  job: holdJob,
  status,
  attemptCount: 0,
  maxAttempts: 5,
  lastAttemptAt: null,
  nextRetryAt: null,
  errorCode: null,
  ...overrides,
})
const event = (
  eventId: number,
  eventSeq: number,
  previous: string | null,
  next: string,
  followUp: object,
  overrides: object = {},
) => ({
  eventId,
  eventSeq,
  previousStatus: previous,
  newStatus: next,
  effectiveAt: `2026-0${Math.min(eventSeq + 2, 9)}-01T00:00:00+09:00`,
  receivedAt: `2026-0${Math.min(eventSeq + 2, 9)}-02T09:00:00+09:00`,
  recordedAt: `2026-0${Math.min(eventSeq + 2, 9)}-02T09:05:00+09:00`,
  sourceSystem: 'FGC_MANUAL',
  sourceEventKey: `key-${eventId}`,
  reasonCode: 'MANUAL_STATUS_CHANGE',
  reason: '보험회사 통보 반영',
  registeredBy: { userId: 3, loginId: 'settle01', userName: '김정산' },
  processings: [],
  followUp,
  ...overrides,
})
const succeeded = (summary: object, processedAt = '2026-09-02T09:06:00+09:00') => ({
  processingJob: holdJob,
  processingStatus: 'SUCCEEDED',
  processedAt,
  job: holdJob,
  status: 'SUCCEEDED',
  summary,
  errorCode: null,
})
const pageOf = (content: object[], page = 1, totalElements = content.length) => ({
  content,
  page,
  size: 20,
  totalElements,
  totalPages: Math.ceil(totalElements / 20),
  sort: 'entityType,entityId',
})

type Answer = { status: number; body: object }
const failure = (status: number, code: string, message: string, requestId = 'status-history-failure'): Answer => ({
  status,
  body: { data: null, error: { code, message }, requestId },
})

async function fixture(
  page: Page,
  {
    events,
    role = 'SETTLEMENT',
  }: { events: () => object[] | Answer; role?: string },
) {
  const unexpected: string[] = []
  const answer = (route: Route, result: object | object[] | Answer) =>
    'status' in result && 'body' in result
      ? route.fulfill({ status: (result as Answer).status, json: (result as Answer).body })
      : route.fulfill({ json: envelope(result) })
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path.endsWith('/auth/refresh'))
      return route.fulfill({ json: envelope({ accessToken: `fixture-${Date.now()}`, expiresIn: 1800 }) })
    if (path.endsWith('/auth/me'))
      return route.fulfill({
        json: envelope({
          loginId: role === 'AGENT' ? 'agent01' : 'settle01',
          userName: role === 'AGENT' ? '김정산' : '정산 담당자',
          roleCode: role,
          agentId: role === 'AGENT' ? 3 : undefined,
          isAgent: role === 'AGENT',
          canUseAgentWorkspace: role === 'AGENT',
          canProcess: role === 'SETTLEMENT',
          demoMonth: '2026-07',
        }),
      })
    if (path === '/api/v1/contracts/1') return route.fulfill({ json: envelope(contract) })
    if (path === '/api/v1/contracts/1/status-events') return answer(route, events())
    if (path === '/api/v1/contracts/2/status-events')
      return answer(route, failure(403, 'FGC-AUTH-005', '본인 데이터만 조회하거나 허용된 확인 기록을 저장할 수 있습니다.'))
    if (path === '/api/v1/contracts/2')
      return answer(route, failure(403, 'FGC-AUTH-005', '본인 데이터만 조회하거나 허용된 확인 기록을 저장할 수 있습니다.'))
    if (path.endsWith('/base/insurers') || path.endsWith('/base/products'))
      return route.fulfill({ json: envelope(pageOf([])) })
    unexpected.push(path)
    return route.fulfill({
      status: 404,
      json: { data: null, error: { code: 'FGC-COMMON-004', message: '없음' }, requestId: 'unexpected' },
    })
  })
  return unexpected
}

const historyRow = (page: Page, seq: number) =>
  page
    .locator('.contract-history-event')
    .nth(seq - 1)

test('상태이력과 후속 상태를 표시하고 상세 버튼과 영향 모달은 표시하지 않는다', async ({
  page,
}, testInfo) => {
  const events = [
    event(
      1,
      1,
      null,
      'ACTIVE',
      { status: 'NOT_REQUIRED', jobs: [] },
      {
        sourceSystem: 'INSURER_FEED',
        reason: null,
        reasonCode: 'NEW_CONTRACT',
        registeredBy: null,
        processings: [
          {
            processingJob: 'DailyChangedContractJob',
            processingStatus: 'SUCCEEDED',
            processedAt: '2026-03-03T02:00:00+09:00',
            summary: null,
          },
        ],
      },
    ),
    event(
      2,
      2,
      'ACTIVE',
      'UNPAID',
      { status: 'SUCCEEDED', jobs: [job('SUCCEEDED', { attemptCount: 2 })] },
      {
        processings: [
          {
            processingJob: holdJob,
            processingStatus: 'FAILED',
            processedAt: '2026-04-02T09:06:00+09:00',
            errorCode: 'IllegalStateException',
            summary: null,
          },
          succeeded(
            { heldPayments: 1, resumedPayments: 0, changedScheduleLines: 2, excludedCount: 1 },
            '2026-04-02T09:08:00+09:00',
          ),
        ],
      },
    ),
    event(
      3,
      3,
      'UNPAID',
      'ACTIVE',
      { status: 'SUCCEEDED', jobs: [job('SUCCEEDED', { attemptCount: 1 })] },
      {
        processings: [succeeded({ heldPayments: 0, resumedPayments: 0, changedScheduleLines: 0, excludedCount: 0 })],
      },
    ),
    event(4, 4, 'ACTIVE', 'UNPAID', {
      status: 'FAILED',
      jobs: [
        job('FAILED', {
          attemptCount: 2,
          nextRetryAt: '2026-06-02T09:12:00+09:00',
          errorCode: 'FGC-IMPL-POLICY_MISSING',
        }),
      ],
    }),
    event(5, 5, 'UNPAID', 'ACTIVE', { status: 'PENDING', jobs: [job('PENDING')] }),
  ]
  const unexpected = await fixture(page, {
    events: () => events,
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('contracts/1')
  const timeline = page.getByRole('list', { name: '계약 상태 변화' })
  await expect(timeline).toBeVisible({ timeout: 30_000 })
  await expect(timeline.locator('.contract-history-event')).toHaveCount(5)
  await expect(historyRow(page, 1)).toContainText('대상 없음')
  await expect(historyRow(page, 1)).toContainText('신규 계약')
  await expect(historyRow(page, 2)).toContainText('재시도 후 성공, 2/5회 시도')
  await expect(historyRow(page, 4)).toContainText('실패, 재시도 대기')
  await expect(historyRow(page, 4)).toContainText('실패 사유: 적용할 정책을 찾지 못했습니다')
  await expect(historyRow(page, 4)).not.toContainText('FGC-IMPL')
  await expect(historyRow(page, 5)).toContainText('처리 대기')
  await expect(page.getByText('끝나지 않은 후속 처리가 있어 10초마다 다시 조회합니다.')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('status-history-table.png'), fullPage: true })

  await expect(page.getByRole('button', { name: /영향 보기|상세 보기/ })).toHaveCount(0)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(unexpected).toEqual([])
})

test('자동 재시도 소진은 운영 담당 확인 안내만 보이고 재시도 버튼이 없다', async ({ page }, testInfo) => {
  await fixture(page, {
    events: () => [
      event(1, 1, null, 'ACTIVE', { status: 'NOT_REQUIRED', jobs: [] }),
      event(2, 2, 'ACTIVE', 'UNPAID', {
        status: 'EXHAUSTED',
        jobs: [job('EXHAUSTED', { attemptCount: 5, errorCode: 'IllegalStateException' })],
      }),
      event(3, 3, 'UNPAID', 'LAPSED', { status: 'PENDING', jobs: [job('PENDING')] }),
    ],
  })
  // 사이드바를 편 1280px에서도 이력과 후속 상태가 가로 넘침 없이 보인다.
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('contracts/1')
  await expect(
    page.getByText(
      '자동 처리에 실패한 건이 있습니다. 이 계약의 뒤 상태 변경도 함께 멈춥니다. 담당자에게 문의하세요.',
    ),
  ).toBeVisible()
  await expect(historyRow(page, 2)).toContainText('자동 처리 실패')
  await expect(historyRow(page, 2)).toContainText('5/5회 시도, 담당자에게 문의하세요')
  await expect(page.getByRole('button', { name: /재시도/ })).toHaveCount(0)
  await expect(page.getByText(/초마다 다시 조회합니다/)).toHaveCount(0)
  const overflow = await page
    .locator('.contract-history-timeline')
    .evaluate((element) => element.scrollWidth - element.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth),
  ).toBeLessThanOrEqual(1)
  await page.screenshot({ path: testInfo.outputPath('status-history-exhausted.png'), fullPage: true })
})

test('처리 중 자동 재조회에도 상태이력과 화면 스크롤을 유지한다', async ({ page }, testInfo) => {
  test.setTimeout(60_000)
  let finished = false
  const manyEvents = (status: string) => [
    ...Array.from({ length: 11 }, (_, index) =>
      event(
        index + 1,
        index + 1,
        'ACTIVE',
        'ACTIVE',
        { status: 'SUCCEEDED', jobs: [job('SUCCEEDED', { attemptCount: 1 })] },
        {
          processings: [succeeded({ heldPayments: 1, resumedPayments: 0, changedScheduleLines: 1, excludedCount: 0 })],
        },
      ),
    ),
    event(12, 12, 'ACTIVE', 'LAPSED', { status, jobs: [job(status, { attemptCount: status === 'PENDING' ? 0 : 1 })] }),
  ]
  await fixture(page, {
    events: () => manyEvents(finished ? 'SUCCEEDED' : 'PENDING'),
  })
  // 긴 이력으로 타임라인과 화면에 생긴 세로 스크롤 위치를 유지한다.
  await page.setViewportSize({ width: 900, height: 720 })
  await page.goto('contracts/1')
  const row = historyRow(page, 12)
  await expect(row).toContainText('처리 대기')
  const viewport = page.locator('.contract-history-timeline')
  await row.scrollIntoViewIfNeeded()
  const scrollLeft = await viewport.evaluate((element) => element.scrollLeft)
  expect(scrollLeft).toBe(0)
  const pageScroll = await page.locator('#main-content').evaluate(el => el.scrollTop)
  const timelineScroll = await viewport.evaluate(el => el.scrollTop)
  expect(timelineScroll + pageScroll).toBeGreaterThan(0)
  finished = true
  await expect(row).toContainText('완료', { timeout: 20_000 })
  expect(await viewport.evaluate((element) => element.scrollLeft)).toBe(scrollLeft)
  expect(await viewport.evaluate(el => el.scrollTop)).toBeCloseTo(timelineScroll, 0)
  await page.screenshot({ path: testInfo.outputPath('status-history-refetched.png') })
  // 처리가 끝나 재조회 안내 줄이 사라진 높이만큼만 달라지고 화면 맨 위로 돌아가지 않는다.
  expect(await page.locator('#main-content').evaluate(el => el.scrollTop)).toBeGreaterThan(pageScroll - 50)
  await expect(row).toBeInViewport()
})

test('AGENT는 본인 계약의 상태이력을 보고 다른 계약 직접 접근은 서버 거절을 그대로 보인다', async ({ page }, testInfo) => {
  const unexpected = await fixture(page, {
    role: 'AGENT',
    events: () => [
      event(1, 1, null, 'ACTIVE', { status: 'NOT_REQUIRED', jobs: [] }),
      event(
        2,
        2,
        'ACTIVE',
        'LAPSED',
        { status: 'SUCCEEDED', jobs: [job('SUCCEEDED', { attemptCount: 1 })] },
        {
          processings: [succeeded({ heldPayments: 1, resumedPayments: 0, changedScheduleLines: 1, excludedCount: 1 })],
        },
      ),
    ],
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('contracts/1')
  await expect(page.getByRole('tab')).toHaveCount(1)
  await expect(historyRow(page, 2)).toContainText('완료')
  await expect(page.getByRole('button', { name: /영향 보기|상세 보기/ })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('status-history-agent.png') })
  await page.goto('contracts/2')
  await expect(page.getByRole('alert')).toContainText('본인 데이터만 조회하거나 허용된 확인 기록을 저장할 수 있습니다.')
  await expect(page.getByRole('table', { name: '상태 변경' })).toHaveCount(0)
  expect(unexpected).toEqual([])
})
