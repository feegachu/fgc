import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { useAuthStore } from '../../stores/auth'
import { ContractStatusHistory } from './ContractStatusHistory'
import type { ContractStatusEvent } from './api'
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
type Handler = (url: string) => unknown
let requested: string[]
function renderHistory(handler: Handler, compact = false) {
  requested = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url) => {
    requested.push(url)
    return envelope(await handler(url)) as never
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ContractStatusHistory id="1" compact={compact} />
    </QueryClientProvider>,
  )
  return { client, actions: userEvent.setup() }
}
const historyTable = () => screen.findByRole('table', { name: '상태 변경' })
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
  it.each([false, true])('상태이력은 상세 버튼과 영향 모달 없이 표시한다: compact=%s', async (compact) => {
    renderHistory(() => [event()], compact)
    await screen.findByText('보험회사 통보 반영')
    expect(screen.queryByRole('button', { name: /영향 보기|상세 보기/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(requested).toEqual([HISTORY_URL])
  })

  it('재조회 실패에는 기존 이력을 유지하고 조회 권한이 사라지면 이력을 숨긴다', async () => {
    let failure: ApiError | undefined
    const { client } = renderHistory(() => {
      if (failure) throw failure
      return [event()]
    })
    const table = await historyTable()
    table.parentElement!.scrollLeft = 120
    failure = new ApiError({ code: 'FGC-COMMON-500' }, 'refresh-failed', 500)
    await refetchHistory(client)
    expect(await screen.findByText(/이전 조회 결과를 표시합니다/)).toBeInTheDocument()
    expect(screen.getByRole('table', { name: '상태 변경' })).toBe(table)
    expect(table.parentElement!.scrollLeft).toBe(120)
    failure = new ApiError({ code: 'FGC-AUTH-005', message: '조회 권한이 없습니다.' }, 'refresh-denied', 403)
    await refetchHistory(client)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('조회 권한이 없습니다.'))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

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
    ).toEqual(['순번', '변경 전→후', '효력일시', '접수일시', '처리일시', '처리자', '사유', '후속 상태'])
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
      '자동 처리 실패',
      ['5/5회 시도, 담당자에게 문의하세요', '실패 사유: 기록 없이 중단됨'],
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
    expect(screen.getByText(/이 계약의 뒤 상태 변경도 함께 멈춥니다. 담당자에게 문의하세요/)).toBeInTheDocument()
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
