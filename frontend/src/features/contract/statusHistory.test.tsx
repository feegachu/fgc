import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { useAuthStore } from '../../stores/auth'
import { ContractStatusHistory } from './ContractStatusHistory'
import type { ContractStatusEffect, ContractStatusEvent } from './api'
import { followUpRunning } from './detailState'

const HISTORY_URL = '/api/v1/contracts/1/status-events'
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'status-history-test' })
type Job = NonNullable<NonNullable<ContractStatusEvent['followUp']>['jobs']>[number]
type Status = NonNullable<ContractStatusEvent['followUp']>['status']

const job = (status: Job['status'], overrides: Partial<Job> = {}): Job => ({
  job: 'PaymentHoldJob',
  status,
  attemptCount: 0,
  maxAttempts: 5,
  ...overrides,
})
const followUp = (status: Status, jobs: Job[] = status === 'NOT_REQUIRED' ? [] : [job(status)]) => ({ status, jobs })
const event = (overrides: Partial<ContractStatusEvent> = {}): ContractStatusEvent => ({
  eventId: 7,
  eventSeq: 2,
  previousStatus: 'ACTIVE',
  newStatus: 'LAPSED',
  effectiveAt: '2026-09-01T00:00:00+09:00',
  receivedAt: '2026-09-02T09:00:00+09:00',
  recordedAt: '2026-09-02T09:05:00+09:00',
  sourceSystem: 'FGC_MANUAL',
  sourceEventKey: 'key-7',
  reasonCode: 'MANUAL_STATUS_CHANGE',
  reason: '보험회사 통보 반영',
  registeredBy: { userId: 3, loginId: 'settle01', userName: '김정산' },
  processings: [],
  followUp: followUp('NOT_REQUIRED'),
  ...overrides,
})
const page = (content: ContractStatusEffect[], overrides: object = {}) => ({
  content,
  page: 1,
  size: 20,
  totalElements: content.length,
  totalPages: content.length ? 1 : 0,
  ...overrides,
})
const effect = (overrides: Partial<ContractStatusEffect>): ContractStatusEffect => ({
  entityType: 'PAYMENT',
  entityId: 501,
  processingJob: 'PaymentHoldJob',
  previousState: 'DRAFT',
  nextState: 'HELD',
  amount: 5000,
  policyVersionId: 12,
  reasonCode: 'STATUS_INACTIVE',
  reason: '귀속일 2026-09-20 시점 계약 상태(실효)에 따라 지급하지 않습니다.',
  ...overrides,
})

type Handler = (url: string) => unknown
let requested: string[]
function renderHistory(handler: Handler) {
  requested = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url) => {
    requested.push(url)
    return envelope(await handler(url)) as never
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ContractStatusHistory id="1" />
    </QueryClientProvider>,
  )
  return { client, actions: userEvent.setup() }
}
const historyTable = () => screen.findByRole('table', { name: '상태 사건' })
const rowCells = (table: HTMLElement, index: number) =>
  within(within(table).getAllByRole('row')[index + 1])
    .getAllByRole('cell')
    .map((cell) => cell.textContent)
const refetchHistory = (client: QueryClient) =>
  act(() => client.refetchQueries({ queryKey: ['contracts', 'detail', '1', 'status-events'], exact: true }))

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'fixture-status-history', loggingOut: false })
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

describe('CONT-W02 상태이력 표', () => {
  it('필수 열을 표시하고 효력, 접수, 처리 일시를 구분하며 처리자와 사유 결측을 채우지 않는다', async () => {
    renderHistory(() => [
      event({
        followUp: followUp('NOT_REQUIRED'),
        processings: [
          {
            processingJob: 'DailyChangedContractJob',
            processingStatus: 'SUCCEEDED',
            processedAt: '2026-09-03T02:00:00+09:00',
          },
        ],
      }),
      event({
        eventId: 8,
        eventSeq: 1,
        previousStatus: undefined,
        newStatus: 'ACTIVE',
        effectiveAt: '2026-03-01T00:00:00+09:00',
        receivedAt: '2026-03-02T06:00:00+09:00',
        recordedAt: '2026-03-03T07:00:00+09:00',
        sourceSystem: 'INSURER_FEED',
        reason: null,
        reasonCode: 'NEW_CONTRACT',
        registeredBy: null,
      }),
      event({ eventId: 9, eventSeq: 3, effectiveAt: '2026-10-01T00:00:00+09:00', reason: null, reasonCode: null }),
    ])
    const table = await historyTable()
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((header) => header.textContent),
    ).toEqual(['순번', '변경 전→후', '효력일시', '접수일시', '처리일시', '처리자', '사유', '후속 상태', '영향'])
    // 효력 순: 사건 순번 1이 먼저다.
    expect(rowCells(table, 0).slice(0, 7)).toEqual([
      '1',
      '최초 → 정상보험사 자료',
      '2026-03-01 00:00',
      '2026-03-02 06:00',
      '2026-03-03 07:00',
      '자료 없음',
      '신규 계약',
    ])
    expect(rowCells(table, 1).slice(0, 7)).toEqual([
      '2',
      '정상 → 실효직접 입력',
      '2026-09-01 00:00',
      '2026-09-02 09:00',
      '2026-09-02 09:05',
      '김정산 (settle01)',
      '보험회사 통보 반영',
    ])
    expect(rowCells(table, 2)[6]).toBe('자료 없음')
    // 배치 처리 시각은 후속 상태 칸에서 작업 이름과 함께 본다.
    expect(rowCells(table, 1)[7]).toContain('일일 변경 계약 재검증')
    expect(rowCells(table, 1)[7]).toContain('2026-09-03 02:00')
    expect(within(table).queryByText('PaymentHoldJob')).toBeNull()
    expect(within(table).queryByText('DailyChangedContractJob')).toBeNull()
  })

  it('정상 0건을 이력 없음으로 표시하고 처음 조회 실패를 0건으로 표시하지 않는다', async () => {
    renderHistory(() => [])
    expect(await screen.findByText('등록된 상태이력이 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('처음 조회 중과 실패, 다시 시도를 구분한다', async () => {
    let fail = true
    let release: (value: ContractStatusEvent[]) => void = () => undefined
    const pending = new Promise<ContractStatusEvent[]>((resolve) => {
      release = resolve
    })
    const { actions } = renderHistory(() => {
      if (fail) throw new ApiError({ code: 'FGC-COMMON-500', message: '내부 상세' }, 'req-1', 500)
      return pending
    })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('요청번호 req-1')
    expect(alert).not.toHaveTextContent('내부 상세')
    expect(screen.queryByText('등록된 상태이력이 없습니다.')).toBeNull()
    fail = false
    await actions.click(screen.getByRole('button', { name: '다시 시도' }))
    release([event()])
    expect(await historyTable()).toBeInTheDocument()
  })

  it.each([
    [followUp('NOT_REQUIRED'), '대상 없음', []],
    [followUp('PENDING'), '처리 대기', []],
    [followUp('PROCESSING', [job('PROCESSING', { attemptCount: 1 })]), '처리 중', []],
    [
      followUp('FAILED', [
        job('FAILED', {
          attemptCount: 2,
          nextRetryAt: '2026-09-02T10:00:00+09:00',
          errorCode: 'FGC-IMPL-POLICY_MISSING',
        }),
      ]),
      '실패, 재시도 대기',
      [
        '2/5회 시도, 다음 재시도 2026-09-02 10:00',
        '실패 사유: 적용할 정책을 찾지 못했습니다',
      ],
    ],
    [
      followUp('EXHAUSTED', [job('EXHAUSTED', { attemptCount: 5, errorCode: null })]),
      '자동 재시도 소진',
      ['5/5회 시도, 운영 담당 확인 필요', '실패 사유: 기록 없이 중단됨'],
    ],
    [followUp('SUCCEEDED', [job('SUCCEEDED', { attemptCount: 1 })]), '완료', []],
    [followUp('SUCCEEDED', [job('SUCCEEDED', { attemptCount: 3 })]), '완료', ['재시도 후 성공, 3/5회 시도']],
  ] as const)('후속 상태 %#를 라벨과 안내로 구분한다', async (state, label, notes) => {
    renderHistory(() => [event({ followUp: state as ContractStatusEvent['followUp'] })])
    const table = await historyTable()
    const cell = within(table).getAllByRole('row')[1]
    expect(within(cell).getByText(label)).toBeInTheDocument()
    for (const note of notes) expect(within(cell).getByText(note)).toBeInTheDocument()
  })

  it('영향 요약은 성공 행의 건수를 보이고 0건과 실패, 배치 행을 구분한다', async () => {
    renderHistory(() => [
      event({
        followUp: followUp('SUCCEEDED', [job('SUCCEEDED', { attemptCount: 1 })]),
        processings: [
          {
            processingJob: 'PaymentHoldJob',
            processingStatus: 'SUCCEEDED',
            processedAt: '2026-09-02T09:06:00+09:00',
            summary: { heldPayments: 2, resumedPayments: 1, changedScheduleLines: 3, excludedCount: 4 },
            errorCode: null,
          },
        ],
      }),
      event({
        eventId: 8,
        eventSeq: 3,
        effectiveAt: '2026-10-01T00:00:00+09:00',
        followUp: followUp('SUCCEEDED', [job('SUCCEEDED', { attemptCount: 1 })]),
        processings: [
          {
            processingJob: 'PaymentHoldJob',
            processingStatus: 'SUCCEEDED',
            processedAt: '2026-10-02T09:06:00+09:00',
            summary: { heldPayments: 0, resumedPayments: 0, changedScheduleLines: 0, excludedCount: 0 },
          },
        ],
      }),
      event({
        eventId: 9,
        eventSeq: 4,
        effectiveAt: '2026-11-01T00:00:00+09:00',
        followUp: followUp('NOT_REQUIRED'),
        processings: [
          {
            processingJob: 'DailyChangedContractJob',
            processingStatus: 'FAILED',
            processedAt: '2026-11-02T02:00:00+09:00',
            summary: null,
          },
        ],
      }),
    ])
    const table = await historyTable()
    expect(rowCells(table, 0)[7]).toContain('지급 보류 2건, 보류 해제 1건, 회차 변경 3건, 처리 제외 4건')
    expect(rowCells(table, 1)[7]).toContain('바뀐 대상 0건')
    expect(rowCells(table, 2)[7]).toContain('실패')
    expect(rowCells(table, 2)[7]).not.toContain('0건')
  })

  it('소진이 있으면 뒤 사건까지 멈춘다고 알리고 재시도 버튼을 만들지 않으며 다시 조회하지 않는다', async () => {
    const events = [
      event({ followUp: followUp('EXHAUSTED', [job('EXHAUSTED', { attemptCount: 5 })]) }),
      event({ eventId: 8, eventSeq: 3, effectiveAt: '2026-10-01T00:00:00+09:00', followUp: followUp('PENDING') }),
    ]
    renderHistory(() => events)
    await historyTable()
    expect(screen.getByText(/이 계약의 뒤 사건도 함께 멈춥니다. 운영 담당 확인이 필요합니다/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /재시도/ })).toBeNull()
    expect(screen.queryByText(/초마다 다시 조회합니다/)).toBeNull()
    expect(followUpRunning(events)).toBe(false)
  })

  it('끝나지 않은 후속 처리만 주기적으로 다시 조회한다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let status: Status = 'PENDING'
    renderHistory(() => [event({ followUp: followUp(status) })])
    await historyTable()
    expect(screen.getByText('끝나지 않은 후속 처리가 있어 10초마다 다시 조회합니다.')).toBeInTheDocument()
    const calls = () => requested.filter((url) => url === HISTORY_URL).length
    expect(calls()).toBe(1)
    status = 'SUCCEEDED'
    await act(() => vi.advanceTimersByTimeAsync(10_000))
    await waitFor(() => expect(calls()).toBe(2))
    expect(await screen.findByText('완료')).toBeInTheDocument()
    expect(screen.queryByText(/초마다 다시 조회합니다/)).toBeNull()
    await act(() => vi.advanceTimersByTimeAsync(30_000))
    expect(calls()).toBe(2)
  })

  it.each(['PENDING', 'PROCESSING', 'FAILED'] as const)('%s는 다시 조회 대상이고 끝난 상태는 아니다', (status) => {
    expect(followUpRunning([event({ followUp: followUp(status) })])).toBe(true)
    expect(
      followUpRunning([event({ followUp: followUp('SUCCEEDED') }), event({ followUp: followUp('NOT_REQUIRED') })]),
    ).toBe(false)
    expect(followUpRunning(undefined)).toBe(false)
  })
})

describe('CONT-W02 사건 영향 패널', () => {
  const openPanel = async (actions: ReturnType<typeof userEvent.setup>, seq = 2) => {
    await actions.click(await screen.findByRole('button', { name: `사건 ${seq} 영향 보기` }))
    return screen.findByRole('dialog', { name: `사건 ${seq} 영향` })
  }
  const effectUrl = (pageNo: number, eventId = 7) => `${HISTORY_URL}/${eventId}/effects?page=${pageNo}&size=20`

  it('지급 건과 회차의 전후 상태, 금액, 정책, 사유를 보이고 지급 보류와 회차 중단 라벨을 구분한다', async () => {
    const { actions } = renderHistory((url) => {
      if (url === HISTORY_URL) return [event({ followUp: followUp('SUCCEEDED') })]
      return page([
        effect({}),
        effect({
          entityId: 502,
          previousState: 'CONFIRMED',
          nextState: 'EXCLUDED',
          reasonCode: 'ALREADY_CONFIRMED',
          amount: null,
          policyVersionId: null,
          reason: null,
        }),
        effect({ entityId: 503, nextState: 'RELEASED', amount: 0, reasonCode: 'STATUS_ACTIVE' }),
        effect({
          entityId: 504,
          nextState: 'EXCLUDED',
          reasonCode: 'NO_RETROACTIVE',
          reason: '부활은 지급 불가 기간을 소급하지 않습니다(NO_RETROACTIVE).',
        }),
        effect({
          entityType: 'SCHEDULE_LINE',
          entityId: 601,
          previousState: 'PLANNED',
          nextState: 'CANCELLED',
          amount: -1000,
          reasonCode: 'STATUS_INACTIVE',
        }),
        effect({
          entityType: 'SCHEDULE_LINE',
          entityId: 602,
          previousState: 'PLANNED',
          nextState: 'HOLD',
          reasonCode: 'STATUS_UNPAID',
        }),
        effect({
          entityType: 'SCHEDULE_LINE',
          entityId: 603,
          previousState: 'CANCELLED',
          nextState: 'PLANNED',
          reasonCode: 'STATUS_ACTIVE',
        }),
        effect({
          entityType: 'SCHEDULE_LINE',
          entityId: 604,
          previousState: 'PLANNED',
          nextState: 'EXCLUDED',
          reasonCode: 'MONTH_LOCKED',
        }),
        effect({ entityType: 'SCHEDULE_LINE', entityId: 605, previousState: null, nextState: 'UNKNOWN_STATE' }),
      ])
    })
    const dialog = await openPanel(actions)
    const table = await within(dialog).findByRole('table', { name: '사건 영향' })
    expect(requested).toContain(effectUrl(1))
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((header) => header.textContent),
    ).toEqual(['대상', '이전 상태', '이후 상태', '금액', '정책', '처리/제외 사유'])
    const rows = within(table)
      .getAllByRole('row')
      .slice(1)
      .map((row) =>
        within(row)
          .getAllByRole('cell')
          .map((cell) => cell.textContent),
      )
    expect(rows[0].slice(0, 5)).toEqual(['지급 건 #501', '작성중', '지급 보류', '5,000원', '정책 버전 #12'])
    // 금액, 정책, 사유의 null은 0원이나 빈칸이 아니라 자료 없음이다.
    expect(rows[1]).toEqual(['지급 건 #502', '확정', '이력 보존, 처리 제외', '자료 없음', '자료 없음', '자료 없음'])
    expect(rows[2].slice(0, 4)).toEqual(['지급 건 #503', '작성중', '보류 해제', '0원'])
    expect(rows[3][2]).toBe('소급 제외')
    expect(rows[4].slice(0, 4)).toEqual(['스케줄 회차 #601', '지급 예정', '지급 예정 중단', '-1,000원'])
    expect(rows[5][2]).toBe('회차 보류')
    expect(rows[6].slice(1, 3)).toEqual(['지급 예정 중단', '지급 예정'])
    expect(rows[7][2]).toBe('이력 보존, 처리 제외')
    expect(rows[8].slice(1, 3)).toEqual(['자료 없음', '확인 필요'])
    expect(within(dialog).queryByRole('button', { name: /복원/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /복원/ })).toBeNull()
  })

  it('영향을 page=1,size=20부터 페이지로 조회하고 갱신 중에는 이전 결과를 유지한다', async () => {
    let release: (value: unknown) => void = () => undefined
    const { actions } = renderHistory((url) => {
      if (url === HISTORY_URL) return [event({ followUp: followUp('SUCCEEDED') })]
      if (url === effectUrl(1)) return page([effect({ entityId: 501 })], { totalElements: 21, totalPages: 2 })
      return new Promise((resolve) => {
        release = resolve
      })
    })
    const dialog = await openPanel(actions)
    expect(await within(dialog).findByText('지급 건 #501')).toBeInTheDocument()
    expect(within(dialog).getByRole('heading', { name: '영향 21건' })).toBeInTheDocument()
    await actions.click(within(dialog).getByRole('button', { name: '2' }))
    expect(await within(dialog).findByText('갱신 중입니다. 이전 결과를 표시합니다.')).toBeInTheDocument()
    expect(within(dialog).getByText('지급 건 #501')).toBeInTheDocument()
    expect(requested).toContain(effectUrl(2))
    release(page([effect({ entityId: 521 })], { page: 2, totalElements: 21, totalPages: 2 }))
    expect(await within(dialog).findByText('지급 건 #521')).toBeInTheDocument()
    expect(within(dialog).queryByText('지급 건 #501')).toBeNull()
  })

  it('늦게 도착한 다른 페이지 응답이 지금 보는 페이지를 덮지 않는다', async () => {
    let release: (value: unknown) => void = () => undefined
    const { actions } = renderHistory((url) => {
      if (url === HISTORY_URL) return [event({ followUp: followUp('SUCCEEDED') })]
      if (url === effectUrl(1)) return page([effect({ entityId: 501 })], { totalElements: 21, totalPages: 2 })
      return new Promise((resolve) => {
        release = resolve
      })
    })
    const dialog = await openPanel(actions)
    await within(dialog).findByText('지급 건 #501')
    await actions.click(within(dialog).getByRole('button', { name: '2' }))
    await within(dialog).findByText('갱신 중입니다. 이전 결과를 표시합니다.')
    await actions.click(within(dialog).getByRole('button', { name: '1' }))
    await waitFor(() => expect(within(dialog).queryByText('갱신 중입니다. 이전 결과를 표시합니다.')).toBeNull())
    await act(async () => release(page([effect({ entityId: 521 })], { page: 2, totalElements: 21, totalPages: 2 })))
    expect(within(dialog).getByText('지급 건 #501')).toBeInTheDocument()
    expect(within(dialog).queryByText('지급 건 #521')).toBeNull()
  })

  it.each([
    ['NOT_REQUIRED', '이 사건은 후속 처리 대상이 아니라 영향이 없습니다.'],
    ['PENDING', '후속 처리 대기 중입니다. 처리가 끝나면 영향이 표시됩니다.'],
    ['PROCESSING', '후속 처리 중입니다. 처리가 끝나면 영향이 표시됩니다.'],
    ['FAILED', '후속 처리에 실패해 영향이 기록되지 않았습니다. 자동 재시도를 기다립니다.'],
    ['EXHAUSTED', '자동 재시도를 모두 써서 영향이 기록되지 않았습니다. 운영 담당 확인이 필요합니다.'],
    ['SUCCEEDED', '후속 처리를 마쳤고 바뀐 대상이 없습니다.'],
  ] as const)('영향 0건을 후속 상태 %s의 이유로 안내한다', async (status, message) => {
    const { actions } = renderHistory((url) =>
      url === HISTORY_URL ? [event({ followUp: followUp(status) })] : page([]),
    )
    const dialog = await openPanel(actions)
    expect(await within(dialog).findByText(message)).toBeInTheDocument()
    expect(within(dialog).getByRole('heading', { name: '영향 0건' })).toBeInTheDocument()
  })

  it.each([
    [401, 'FGC-AUTH-001', '로그인이 필요합니다.'],
    [403, 'FGC-AUTH-005', '본인 담당 자료만 조회·처리할 수 있습니다.'],
    [404, 'FGC-COMMON-004', '요청한 자료를 찾을 수 없습니다.'],
    [409, 'FGC-CONT-003', '최신 상태와 달라졌습니다.'],
    [422, 'FGC-IMPL-POLICY_MISSING', '적용할 정책을 찾지 못했습니다.'],
  ])('영향 조회 %i 오류를 0건으로 표시하지 않고 다시 시도할 수 있다', async (status, code, message) => {
    let fail = true
    const { actions } = renderHistory((url) => {
      if (url === HISTORY_URL) return [event({ followUp: followUp('SUCCEEDED') })]
      if (fail) throw new ApiError({ code, message }, 'req-2', status)
      return page([effect({})])
    })
    const dialog = await openPanel(actions)
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent(message.replace('·', '/'))
    expect(within(dialog).queryByText(/건$/)).toBeNull()
    expect(within(dialog).queryByRole('table')).toBeNull()
    fail = false
    await actions.click(within(dialog).getByRole('button', { name: '다시 시도' }))
    expect(await within(dialog).findByText('지급 건 #501')).toBeInTheDocument()
  })

  it('영향 조회 서버 실패는 내부 상세 없이 요청번호를 보인다', async () => {
    const { actions } = renderHistory((url) => {
      if (url === HISTORY_URL) return [event({ followUp: followUp('SUCCEEDED') })]
      throw new ApiError({ code: 'FGC-COMMON-500', message: 'SQL 내부 상세' }, 'req-3', 500)
    })
    const dialog = await openPanel(actions)
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent('요청번호 req-3')
    expect(alert).not.toHaveTextContent('SQL 내부 상세')
  })

  it('처리 이력 전체와 실패 코드를 패널에서 보여 주고 내부 사유 원문은 쓰지 않는다', async () => {
    const { actions } = renderHistory((url) => {
      if (url === HISTORY_URL)
        return [
          event({
            followUp: followUp('SUCCEEDED', [job('SUCCEEDED', { attemptCount: 2 })]),
            processings: [
              {
                processingJob: 'PaymentHoldJob',
                processingStatus: 'FAILED',
                processedAt: '2026-09-02T09:06:00+09:00',
                errorCode: 'IllegalStateException',
                summary: null,
              },
              {
                processingJob: 'PaymentHoldJob',
                processingStatus: 'SUCCEEDED',
                processedAt: '2026-09-02T09:08:00+09:00',
                summary: { heldPayments: 1, resumedPayments: 0, changedScheduleLines: 0, excludedCount: 0 },
              },
            ],
          }),
        ]
      return page([effect({})])
    })
    const table = await historyTable()
    // 표에는 작업별 마지막 행만, 패널에는 시도 전체가 보인다.
    expect(within(table).getAllByRole('list', { name: '후속 처리 작업' })[0].children).toHaveLength(1)
    const dialog = await openPanel(actions)
    const list = within(dialog).getByRole('list', { name: '처리 이력' })
    expect(list.children).toHaveLength(2)
    expect(within(list).getByText('실패 사유: 시스템 오류')).toBeInTheDocument()
    expect(dialog).not.toHaveTextContent('IllegalStateException')
    expect(within(list).getByText('지급 보류 1건')).toBeInTheDocument()
    expect(within(dialog).getByText('재시도 후 성공, 2/5회 시도')).toBeInTheDocument()
  })

  it('처리 중 재조회와 재조회 실패에도 사건 선택과 패널, 표 스크롤을 유지하고 처리가 끝나면 영향을 다시 읽는다', async () => {
    let status: Status = 'PENDING'
    let failHistory = false
    const { client, actions } = renderHistory((url) => {
      if (url === HISTORY_URL) {
        if (failHistory) throw new ApiError({ code: 'FGC-COMMON-500' }, 'req-4', 500)
        return [
          event({ followUp: followUp(status) }),
          event({ eventId: 8, eventSeq: 3, effectiveAt: '2026-10-01T00:00:00+09:00' }),
        ]
      }
      return status === 'SUCCEEDED' ? page([effect({})]) : page([])
    })
    const table = await historyTable()
    const viewport = table.parentElement!
    viewport.scrollLeft = 120
    const dialog = await openPanel(actions)
    expect(
      await within(dialog).findByText('후속 처리 대기 중입니다. 처리가 끝나면 영향이 표시됩니다.'),
    ).toBeInTheDocument()
    const selectedRow = () => within(table).getAllByRole('row')[1]
    expect(selectedRow()).toHaveClass('is-selected')

    status = 'SUCCEEDED'
    await refetchHistory(client)
    expect(screen.getByRole('dialog', { name: '사건 2 영향' })).toBe(dialog)
    expect(screen.getByRole('table', { name: '상태 사건' })).toBe(table)
    expect(viewport.scrollLeft).toBe(120)
    expect(selectedRow()).toHaveClass('is-selected')
    expect(await within(dialog).findByText('지급 건 #501')).toBeInTheDocument()

    failHistory = true
    await refetchHistory(client)
    expect(await screen.findByText(/이전 조회 결과를 표시합니다/)).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: '사건 2 영향' })).toBe(dialog)
    expect(screen.getByRole('table', { name: '상태 사건' })).toBe(table)
    expect(selectedRow()).toHaveClass('is-selected')
  })

  it('재조회가 403이면 이전 이력과 패널을 계속 보여 주지 않는다', async () => {
    let denied = false
    const { client, actions } = renderHistory((url) => {
      if (url === HISTORY_URL) {
        if (denied)
          throw new ApiError({ code: 'FGC-AUTH-005', message: '본인 담당 자료만 조회할 수 있습니다.' }, 'req-5', 403)
        return [event()]
      }
      return page([])
    })
    await openPanel(actions)
    denied = true
    await refetchHistory(client)
    expect(await screen.findByRole('alert')).toHaveTextContent('본인 담당 자료만 조회할 수 있습니다.')
    expect(screen.queryByRole('table', { name: '상태 사건' })).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('패널을 닫아도 정렬과 사건 선택을 유지하고 포커스를 영향 보기로 돌린다', async () => {
    const { actions } = renderHistory((url) => {
      if (url === HISTORY_URL)
        return [
          event(),
          event({
            eventId: 8,
            eventSeq: 3,
            effectiveAt: '2026-10-01T00:00:00+09:00',
            receivedAt: '2026-08-01T00:00:00+09:00',
          }),
        ]
      return page([])
    })
    const table = await historyTable()
    await actions.click(screen.getByRole('button', { name: '접수 순' }))
    expect(rowCells(table, 0)[0]).toBe('3')
    const open = screen.getByRole('button', { name: '사건 2 영향 보기' })
    await actions.click(open)
    const dialog = await screen.findByRole('dialog', { name: '사건 2 영향' })
    await actions.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(rowCells(table, 0)[0]).toBe('3')
    expect(screen.getByRole('button', { name: '접수 순' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(table).getAllByRole('row')[2]).toHaveClass('is-selected')
    expect(open).toHaveFocus()
    // Esc로 닫아도 같고 다른 사건을 열면 첫 페이지부터 읽는다.
    await actions.click(screen.getByRole('button', { name: '사건 3 영향 보기' }))
    await screen.findByRole('dialog', { name: '사건 3 영향' })
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(requested).toContain(effectUrl(1, 8))
  })
})
