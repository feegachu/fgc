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
const effect = (entityId: number, overrides: object = {}) => ({
  entityType: 'PAYMENT',
  entityId,
  processingJob: holdJob,
  previousState: 'DRAFT',
  nextState: 'HELD',
  amount: 5000,
  policyVersionId: 12,
  reasonCode: 'STATUS_INACTIVE',
  reason: '귀속일 2026-09-20 시점 계약 상태(실효)에 따라 지급하지 않습니다.',
  ...overrides,
})
const mixedEffects = [
  effect(501),
  effect(502, {
    previousState: 'CONFIRMED',
    nextState: 'EXCLUDED',
    reasonCode: 'ALREADY_CONFIRMED',
    amount: 0,
    reason: '이미 확정된 지급 건이라 보류하지 않습니다(이력 보존).',
  }),
  effect(503, {
    nextState: 'RELEASED',
    amount: null,
    policyVersionId: null,
    reasonCode: 'STATUS_ACTIVE',
    reason: null,
  }),
  effect(601, {
    entityType: 'SCHEDULE_LINE',
    previousState: 'PLANNED',
    nextState: 'CANCELLED',
    amount: 1000,
    reason: '지급예정일 2026-09-25 시점 계약 상태(실효)에 따라 지급하지 않습니다.',
  }),
  effect(602, {
    entityType: 'SCHEDULE_LINE',
    previousState: 'PLANNED',
    nextState: 'HOLD',
    amount: 1000,
    reasonCode: 'STATUS_UNPAID',
    reason: '미납 기간 회차를 보류합니다.',
  }),
  effect(603, {
    entityType: 'SCHEDULE_LINE',
    previousState: 'CONFIRMED',
    nextState: 'EXCLUDED',
    amount: 1000,
    reasonCode: 'MONTH_LOCKED',
    reason: '확정된 검증월의 회차라 바꾸지 않습니다(이력 보존).',
  }),
]
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
    effects,
    role = 'SETTLEMENT',
  }: { events: () => object[] | Answer; effects: (eventId: string, pageNo: number) => object | Answer; role?: string },
) {
  const unexpected: string[] = []
  const answer = (route: Route, result: object | object[] | Answer) =>
    'status' in result && 'body' in result
      ? route.fulfill({ status: (result as Answer).status, json: (result as Answer).body })
      : route.fulfill({ json: envelope(result) })
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const effectPath = path.match(/^\/api\/v1\/contracts\/1\/status-events\/(\d+)\/effects$/)
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
    if (effectPath) return answer(route, effects(effectPath[1], Number(url.searchParams.get('page'))))
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
    .getByRole('table', { name: '상태 사건' })
    .getByRole('row')
    .filter({ has: page.getByRole('button', { name: `사건 ${seq} 영향 보기` }) })

test('필수 열과 후속 상태를 구분하고 영향 패널에서 지급 보류와 회차 중단을 다른 라벨로 보인다', async ({
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
    effects: (eventId, pageNo) =>
      eventId === '2'
        ? pageNo === 1
          ? pageOf([...mixedEffects, ...Array.from({ length: 14 }, (_, index) => effect(700 + index))], 1, 21)
          : pageOf([effect(721)], 2, 21)
        : pageOf([]),
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('contracts/1')
  const table = page.getByRole('table', { name: '상태 사건' })
  await expect(table).toBeVisible({ timeout: 30_000 })
  await expect(table.getByRole('columnheader')).toHaveText([
    '순번',
    '변경 전→후',
    '효력일시',
    '접수일시',
    '처리일시',
    '처리자',
    '사유',
    '후속 상태',
    '영향',
  ])
  await expect(historyRow(page, 1)).toContainText('자료 없음')
  await expect(historyRow(page, 1)).toContainText('대상 없음')
  await expect(historyRow(page, 1)).toContainText('신규 계약')
  await expect(historyRow(page, 2)).toContainText('재시도 후 성공, 2/5회 시도')
  await expect(historyRow(page, 2)).toContainText('지급 보류 1건, 회차 변경 2건, 처리 제외 1건')
  await expect(historyRow(page, 3)).toContainText('바뀐 대상 0건')
  await expect(historyRow(page, 4)).toContainText('실패, 재시도 대기')
  await expect(historyRow(page, 4)).toContainText('실패 사유: 적용할 정책을 찾지 못했습니다')
  await expect(historyRow(page, 4)).not.toContainText('FGC-IMPL')
  await expect(historyRow(page, 5)).toContainText('처리 대기')
  await expect(page.getByText('끝나지 않은 후속 처리가 있어 10초마다 다시 조회합니다.')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('status-history-table.png'), fullPage: true })

  await page.getByRole('button', { name: '사건 2 영향 보기' }).click()
  const panel = page.getByRole('dialog', { name: '사건 2 영향' })
  await expect(panel.getByRole('heading', { name: '영향 21건' })).toBeVisible()
  await expect(panel.getByRole('row', { name: /지급 건 #501/ })).toContainText('지급 보류')
  await expect(panel.getByRole('row', { name: /지급 건 #502/ })).toContainText('이력 보존, 처리 제외')
  await expect(panel.getByRole('row', { name: /지급 건 #502/ })).toContainText('0원')
  await expect(panel.getByRole('row', { name: /지급 건 #503/ })).toContainText('자료 없음')
  await expect(panel.getByRole('row', { name: /스케줄 회차 #601/ })).toContainText('지급 예정 중단')
  await expect(panel.getByRole('row', { name: /스케줄 회차 #602/ })).toContainText('회차 보류')
  await expect(page.getByRole('button', { name: /전체 회차 복원/ })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('status-history-effects.png') })
  await panel.getByRole('button', { name: '2', exact: true }).click()
  await expect(panel.getByRole('row', { name: /지급 건 #721/ })).toBeVisible()
  await panel.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(panel).toHaveCount(0)
  await expect(historyRow(page, 2)).toHaveClass(/is-selected/)
  await expect(page.getByRole('button', { name: '사건 2 영향 보기' })).toBeFocused()

  await page.getByRole('button', { name: '사건 5 영향 보기' }).click()
  const pending = page.getByRole('dialog', { name: '사건 5 영향' })
  await expect(pending.getByText('후속 처리 대기 중입니다. 처리가 끝나면 영향이 표시됩니다.')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('status-history-effects-pending.png') })
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
    effects: () => pageOf([]),
  })
  // 사이드바를 편 1280px에서 영향 보기까지 가로 스크롤 없이 보인다.
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('contracts/1')
  await expect(
    page.getByText(
      '자동 재시도를 모두 쓴 후속 처리가 있습니다. 이 계약의 뒤 사건도 함께 멈춥니다. 운영 담당 확인이 필요합니다.',
    ),
  ).toBeVisible()
  await expect(historyRow(page, 2)).toContainText('자동 재시도 소진')
  await expect(historyRow(page, 2)).toContainText('5/5회 시도, 운영 담당 확인 필요')
  await expect(page.getByRole('button', { name: /재시도/ })).toHaveCount(0)
  await expect(page.getByText(/초마다 다시 조회합니다/)).toHaveCount(0)
  const overflow = await page
    .locator('.contract-history-panel .data-table-viewport')
    .evaluate((element) => element.scrollWidth - element.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth),
  ).toBeLessThanOrEqual(1)
  await page.screenshot({ path: testInfo.outputPath('status-history-exhausted.png'), fullPage: true })
})

test('처리 중 자동 재조회에도 사건 선택과 패널, 표와 화면 스크롤을 유지한다', async ({ page }, testInfo) => {
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
    effects: () => (finished ? pageOf(mixedEffects) : pageOf([])),
  })
  // 좁은 화면이라 표가 가로로 스크롤되고 긴 이력 때문에 화면도 세로로 스크롤된다.
  await page.setViewportSize({ width: 900, height: 720 })
  await page.goto('contracts/1')
  const row = historyRow(page, 12)
  await expect(row).toContainText('처리 대기')
  const viewport = page.locator('.contract-history-panel .data-table-viewport')
  await row.getByRole('button', { name: '사건 12 영향 보기' }).click()
  const panel = page.getByRole('dialog', { name: '사건 12 영향' })
  await expect(panel.getByText('후속 처리 대기 중입니다. 처리가 끝나면 영향이 표시됩니다.')).toBeVisible()
  // 클릭이 버튼을 화면 안으로 스크롤한 뒤의 위치가 기준이다.
  const scrollLeft = await viewport.evaluate((element) => element.scrollLeft)
  expect(scrollLeft).toBeGreaterThan(0)
  const pageScroll = await page.evaluate(() => window.scrollY)
  expect(pageScroll).toBeGreaterThan(0)
  finished = true
  await expect(panel.getByRole('row', { name: /스케줄 회차 #601/ })).toBeVisible({ timeout: 20_000 })
  await expect(panel).toBeVisible()
  await expect(row).toHaveClass(/is-selected/)
  await expect(row).toContainText('완료')
  expect(await viewport.evaluate((element) => element.scrollLeft)).toBe(scrollLeft)
  await page.screenshot({ path: testInfo.outputPath('status-history-refetched.png') })
  await panel.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(row).toHaveClass(/is-selected/)
  expect(await viewport.evaluate((element) => element.scrollLeft)).toBe(scrollLeft)
  // 처리가 끝나 재조회 안내 줄이 사라진 높이만큼만 달라지고 화면 맨 위로 돌아가지 않는다.
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(pageScroll - 50)
  await expect(row).toBeInViewport()
})

test('영향 조회 401은 기존 인증 복구로 다시 받고 403과 서버 실패는 0건으로 표시하지 않는다', async ({ page }) => {
  test.setTimeout(60_000)
  let calls = 0
  let mode: 'auth' | 'denied' | 'broken' | 'ok' = 'auth'
  await fixture(page, {
    events: () => [event(1, 1, null, 'ACTIVE', { status: 'SUCCEEDED', jobs: [job('SUCCEEDED', { attemptCount: 1 })] })],
    effects: () => {
      calls += 1
      if (mode === 'auth' && calls === 1) return failure(401, 'FGC-AUTH-002', '다시 로그인해 주세요.')
      if (mode === 'denied') return failure(403, 'FGC-AUTH-005', '본인 데이터만 조회하거나 허용된 확인 기록을 저장할 수 있습니다.')
      if (mode === 'broken') return failure(500, 'FGC-COMMON-500', 'SQL 내부 상세', 'req-500')
      return pageOf([effect(501)])
    },
  })
  await page.goto('contracts/1')
  await page.getByRole('button', { name: '사건 1 영향 보기' }).click()
  const panel = page.getByRole('dialog', { name: '사건 1 영향' })
  await expect(panel.getByRole('row', { name: /지급 건 #501/ })).toBeVisible()
  expect(calls).toBe(2)
  await panel.getByRole('button', { name: '닫기', exact: true }).click()

  for (const [next, message] of [
    ['denied', '본인 데이터만 조회하거나 허용된 확인 기록을 저장할 수 있습니다.'],
    ['broken', '요청번호 req-500'],
  ] as const) {
    mode = next
    await page.reload()
    await page.getByRole('button', { name: '사건 1 영향 보기' }).click()
    const reopened = page.getByRole('dialog', { name: '사건 1 영향' })
    await expect(reopened.getByRole('alert')).toContainText(message)
    await expect(reopened.getByRole('alert')).not.toContainText('SQL 내부 상세')
    await expect(reopened.getByRole('table')).toHaveCount(0)
    await expect(reopened.getByText(/영향 \d+건/)).toHaveCount(0)
    await expect(reopened.getByRole('button', { name: '다시 시도' })).toBeVisible()
    await reopened.getByRole('button', { name: '닫기', exact: true }).click()
  }
})

test('AGENT는 본인 계약의 영향을 보고 다른 계약 직접 접근은 서버 거절을 그대로 보인다', async ({ page }, testInfo) => {
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
    effects: (eventId) => (eventId === '2' ? pageOf(mixedEffects.slice(0, 4)) : pageOf([])),
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('contracts/1')
  await expect(page.getByRole('tab')).toHaveCount(1)
  await page.getByRole('button', { name: '사건 2 영향 보기' }).click()
  const panel = page.getByRole('dialog', { name: '사건 2 영향' })
  await expect(panel.getByRole('row', { name: /지급 건 #501/ })).toContainText('지급 보류')
  await page.screenshot({ path: testInfo.outputPath('status-history-agent.png') })
  await panel.getByRole('button', { name: '닫기', exact: true }).click()
  await page.goto('contracts/2')
  await expect(page.getByRole('alert')).toContainText('본인 데이터만 조회하거나 허용된 확인 기록을 저장할 수 있습니다.')
  await expect(page.getByRole('table', { name: '상태 사건' })).toHaveCount(0)
  expect(unexpected).toEqual([])
})
