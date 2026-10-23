import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import type { RequestOptions } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toasts'
import ContractDetailPage from './ContractDetailPage'
import type { ContractDetail, ContractStatusEvent } from './api'
import { sortStatusEvents } from './detailState'

const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'contract-detail-test' })
const contract: ContractDetail = {
  contractId: 1,
  contractNo: 'CONT-0001',
  contractStatus: 'ACTIVE',
  contractStatusLabel: '서버 정상',
  dataOrigin: 'MANUAL',
  dataOriginLabel: '서버 수동 입력',
  insurerName: '한빛생명',
  productName: '종신보험',
  contractDate: '2026-07-01',
  paymentCycleCode: 'MONTHLY',
  paymentCycleCodeLabel: '서버 월납',
  premiumPerCycleAmount: 100000,
  firstPremiumAmount: 100000,
  monthlyEquivalentFirstPremium: 100000,
  paymentTermMonths: 120,
  agentName: '김설계',
  agentId: 600,
  organizationName: '서울지점',
}
type Handler = (url: string, options?: RequestOptions) => unknown
let requested: { url: string; options?: RequestOptions }[]
const calls = (suffix: string) => requested.filter(({ url }) => url === `/api/v1/contracts/1${suffix}`)
const messages = () => useToastStore.getState().messages.map(({ message }) => message)

function renderAt(path: string, handler: Handler = () => undefined, canProcess = true, state?: object) {
  requested = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url, options) => {
    requested.push({ url, options })
    if (url === '/api/v1/auth/me')
      return envelope({
        loginId: 'operator',
        userName: '정산 담당자',
        roleCode: 'ADMIN',
        demoMonth: '2026-07',
        canProcess,
      })
    const result = await handler(url, options)
    if (result !== undefined) return envelope(result)
    if (url === '/api/v1/contracts/1') return envelope(contract)
    if (url === '/api/v1/contracts/1/status-events') return envelope([])
    throw new Error(`unexpected ${url}`)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const initial = new URL(path, 'http://localhost')
  const router = createMemoryRouter(
    [
      { path: '/contracts/:id', element: <ContractDetailPage /> },
      { path: '/contracts/:id/edit', element: <h1>계약 수정 화면</h1> },
      { path: '/contracts', element: <h1>계약 목록 화면</h1> },
      { path: '/schedules/:id', element: <h1>스케줄 상세 화면</h1> },
    ],
    { initialEntries: [{ pathname: initial.pathname, search: initial.search, state }] },
  )
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { client, router, actions: userEvent.setup() }
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'fixture-contract-detail', loggingOut: false })
  useToastStore.setState({ messages: [] })
})
afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

describe('CONT-W02 계약 상세', () => {
  it('지급 등록에 계약·수령인과 상세 화면의 목록 맥락을 전달한다', async () => {
    const { actions, router } = renderAt('/contracts/1?month=2026-07&returnTo=%2Fcontracts%3Fpage%3D3')
    const link = await screen.findByRole('link', { name: '지급 등록' })
    const url = new URL(link.getAttribute('href')!, 'http://local')
    expect(Object.fromEntries(url.searchParams)).toMatchObject({
      contractId: '1',
      agentId: '600',
      attributionDate: '2026-07-01',
      month: '2026-07',
    })
    expect(url.searchParams.get('returnTo')).toBe('/contracts/1?month=2026-07&returnTo=%2Fcontracts%3Fpage%3D3')
    await actions.click(screen.getByRole('link', { name: '목록으로' }))
    expect(router.state.location.pathname + router.state.location.search).toBe('/contracts?page=3')
  })
  it('기본정보는 서버 라벨과 효력 순 이력을 표시하고 다른 탭은 미리 요청하지 않는다', async () => {
    const events: ContractStatusEvent[] = [
      {
        eventSeq: 2,
        previousStatus: 'ACTIVE',
        previousStatusLabel: '정상 처리',
        newStatus: 'UNPAID',
        newStatusLabel: '미납 처리',
        effectiveAt: '2026-07-02T00:00:00Z',
        receivedAt: '2026-07-01T00:00:00Z',
        processings: [{ processingJob: 'SCHEDULE', processingStatus: 'FAILED', processedAt: '2026-07-02T01:00:00Z' }],
      },
      { eventSeq: 1, newStatus: 'ACTIVE', effectiveAt: '2026-07-01T00:00:00Z', receivedAt: '2026-07-02T00:00:00Z' },
    ]
    const { actions } = renderAt('/contracts/1', (url) => (url.endsWith('/status-events') ? events : undefined))
    await screen.findByRole('heading', { name: 'CONT-0001' })
    expect(screen.getAllByText('서버 정상')).toHaveLength(2)
    expect(screen.getByText('서버 월납')).toBeInTheDocument()
    const history = await screen.findByRole('list', { name: '상태 사건' })
    expect(
      within(history)
        .getAllByRole('heading', { level: 4 })
        .map((item) => item.textContent),
    ).toEqual(['#1 최초 → 정상', '#2 정상 처리 → 미납 처리'])
    expect(within(history).getByText('실패')).toBeInTheDocument()
    expect(within(history).getByText('2026-07-02 10:00')).toBeInTheDocument()
    await actions.click(screen.getByRole('button', { name: '도착 순' }))
    expect(within(history).getAllByRole('heading', { level: 4 })[0]).toHaveTextContent('#2 정상 처리 → 미납 처리')
    expect(
      requested.filter(
        ({ url }) => !['/api/v1/auth/me', '/api/v1/contracts/1', '/api/v1/contracts/1/status-events'].includes(url),
      ),
    ).toHaveLength(0)
  })

  it('알 수 없는 탭은 기본정보로 보정하고 이력 정렬은 잘못된 일시를 끝에 둔다', async () => {
    renderAt('/contracts/1?tab=unknown')
    expect(await screen.findByRole('tab', { name: '기본정보' })).toHaveAttribute('aria-selected', 'true')
    const events: ContractStatusEvent[] = [
      { eventSeq: 9 },
      { eventSeq: 3, effectiveAt: '2026-07-01' },
      { eventSeq: 2, effectiveAt: '2026-07-01' },
    ]
    expect(sortStatusEvents(events, 'effectiveAt').map((row) => row.eventSeq)).toEqual([2, 3, 9])
    expect(events.map((row) => row.eventSeq)).toEqual([9, 3, 2])
  })

  it('스케줄 deep link에서도 두 지급단계를 함께 표시하고 기존 읽기 전용 버전 요약을 유지한다', async () => {
    renderAt('/contracts/1?tab=schedules&stage=GA_TO_FC', (url) => {
      if (!url.includes('/schedules?')) return
      const fc = url.endsWith('GA_TO_FC')
      return {
        headers: [
          {
            scheduleHeaderId: fc ? 10 : 20,
            scheduleVersionNo: 2,
            lineCount: 1,
            status: 'CONFIRMED',
            statusLabel: '서버 확정',
          },
        ],
        lines: [
          {
            lineNo: 1,
            installmentNo: 1,
            dueDate: '2026-08-01',
            commissionItemName: '관리자 수수료',
            recipientName: fc ? '팀장' : 'GA',
            expectedAmount: -12500,
            lineStatus: 'CONFIRMED',
          },
        ],
      }
    })
    await screen.findByRole('cell', { name: '팀장' })
    expect(screen.getByRole('tab', { name: '예상 스케줄' })).toHaveAttribute('aria-selected', 'true')
    const fc = screen.getByRole('region', { name: 'GA→설계사' })
    expect(within(fc).getByText('-12,500원')).toHaveClass('is-negative-amount')
    expect(calls('/status-events')).toHaveLength(0)
    expect(calls('/schedules?paymentStage=INSURER_TO_GA')).toHaveLength(1)
    expect(calls('/schedules?paymentStage=GA_TO_FC')).toHaveLength(1)
    expect(await screen.findByRole('cell', { name: 'GA' })).toBeInTheDocument()
    expect(screen.queryByLabelText('지급단계')).toBeNull()
    expect(within(fc).getByText('버전 2 · 1회차')).toBeInTheDocument()
    expect(within(fc).queryByRole('link')).not.toBeInTheDocument()
    expect(within(fc).queryByText('서버 확정')).not.toBeInTheDocument()
  })

  it('탭 키보드 이동과 뒤로가기는 올바른 패널을 표시하고 선택한 탭만 조회한다', async () => {
    const { router } = renderAt('/contracts/1', (url) =>
      url.endsWith('/transactions') ? { transactions: [], reconciliations: [] } : undefined,
    )
    const basic = await screen.findByRole('tab', { name: '기본정보' })
    basic.focus()
    fireEvent.keyDown(basic, { key: 'End' })
    expect(screen.getByRole('tab', { name: '지급·대사' })).toHaveFocus()
    const reconciliation = await screen.findByRole('region', { name: '대사 결과' })
    expect(within(reconciliation).getByText('조회된 자료가 없습니다.')).toBeInTheDocument()
    expect(router.state.location.search).toBe('?tab=payment')
    expect(calls('/cap-checks')).toHaveLength(0)
    await act(() => router.navigate(-1))
    expect(screen.getByRole('tab', { name: '기본정보' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel')).toHaveAttribute('id', 'contract-panel-basic')
  })

  it('CAP는 두 단계를 별개로 표시하고 공통 계산근거 모달을 URL과 함께 열고 닫는다', async () => {
    const { router, actions } = renderAt('/contracts/1?tab=cap', (url) => {
      if (url.endsWith('/cap-checks'))
        return [
          {
            capCheckId: 31,
            result: {
              paymentStage: 'INSURER_TO_GA',
              includedAmount: 100000,
              limitAmount: 200000,
              usagePct: 50,
              resultStatus: 'NORMAL',
            },
          },
          {
            capCheckId: 32,
            result: {
              paymentStage: 'GA_TO_FC',
              includedAmount: 125000,
              limitAmount: 120000,
              remainingAmount: -5000,
              usagePct: 104.166667,
              resultStatus: 'VIOLATION',
            },
          },
        ]
      if (url === '/api/v1/cap-checks/32/details')
        return {
          capCheck: {
            contractNo: 'CONT-0001',
            capCheckId: 32,
            paymentStage: 'GA_TO_FC',
            resultStatus: 'VIOLATION',
            includedAmount: 125000,
            limitAmount: 120000,
            usagePct: '104.166667',
          },
          calculationSnapshot: { premiumMultiplier: 12 },
          details: [
            { detailSeq: 1, commissionItemName: '초회수수료', amount: 125000, classificationSnapshot: 'INCLUDED' },
          ],
        }
    })
    await screen.findByText('104.166667%')
    expect(screen.getByText(/두 규제를 더하지 마세요/)).toBeInTheDocument()
    const open = screen.getByRole('button', { name: 'GA→설계사 계산근거' })
    await actions.click(open)
    const dialog = await screen.findByRole('dialog', { name: '계산근거' })
    await within(dialog).findByRole('cell', { name: '초회수수료' })
    expect(router.state.location.search).toContain('capCheckId=32')
    await actions.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(router.state.location.search).not.toContain('capCheckId')
    expect(open).toHaveFocus()
    expect(requested.filter(({ url }) => url === '/api/v1/cap-checks/32/details')).toHaveLength(1)
    await act(() => router.navigate(-1))
    expect(router.state.location.search).toBe('?tab=cap')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('다른 계약 판정 ID를 URL에 넣어도 계산근거를 요청하지 않는다', async () => {
    renderAt('/contracts/1?tab=cap&capCheckId=999', (url) =>
      url.endsWith('/cap-checks') ? [{ capCheckId: 31, result: { paymentStage: 'INSURER_TO_GA' } }] : undefined,
    )
    await screen.findByRole('button', { name: '원수사→GA 계산근거' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(requested.some(({ url }) => url.includes('/999/details'))).toBe(false)
  })

  it('차익거래·원장·지급·대사는 저장 응답의 라벨과 금액을 재계산 없이 표시한다', async () => {
    const { actions } = renderAt('/contracts/1?tab=arbitrage', (url) => {
      if (url.endsWith('/arbitrage-checks'))
        return [
          {
            arbitrageCheckId: 5,
            resultStatus: 'REVIEW_REQUIRED',
            netDifferenceAmount: -700,
            cumulativePaidPremium: 1000,
          },
        ]
      if (url.endsWith('/journals'))
        return [
          {
            journalHeaderId: 7,
            journalNo: 'JOURNAL-7',
            journalDate: '2026-07-02',
            journalType: 'EXPECTED_FC_PAYOUT',
            debitTotal: 1000,
            creditTotal: 900,
            differenceAmount: 77,
            status: 'CONFIRMED',
            statusLabel: '서버 원장 확정',
          },
          { journalHeaderId: 8, journalNo: 'JOURNAL-8', journalType: 'REVERSAL' },
        ]
      if (url.endsWith('/transactions'))
        return {
          transactions: [
            {
              commissionTransactionId: 8,
              amount: 100,
              attributionTotal: 90,
              differenceAmount: 3,
              statusLabel: '서버 지급 상태',
              sourceType: 'SOURCE_A',
            },
          ],
          reconciliations: [
            {
              reconciliationResultId: 9,
              expectedTotalAmount: 100,
              actualTotalAmount: 50,
              differenceAmount: -12,
              resultTypeLabel: '서버 대사 결과',
              primaryReasonCode: 'REASON_A',
            },
          ],
        }
    })
    await screen.findByText('자료부족')
    expect(screen.queryByText('검토필요')).not.toBeInTheDocument()
    expect(screen.getByText('-700원')).toBeInTheDocument()
    await actions.click(screen.getByRole('tab', { name: '원장' }))
    await screen.findByRole('cell', { name: 'JOURNAL-7' })
    expect(screen.getByText('77원')).toBeInTheDocument()
    expect(screen.getByText('서버 원장 확정')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '예상 지급(GA→설계사)' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '역분개' })).toBeInTheDocument()
    expect(screen.queryByRole('cell', { name: 'EXPECTED_FC_PAYOUT' })).not.toBeInTheDocument()
    expect(screen.queryByRole('cell', { name: 'REVERSAL' })).not.toBeInTheDocument()
    await actions.click(screen.getByRole('tab', { name: '지급·대사' }))
    await screen.findByRole('cell', { name: '서버 대사 결과' })
    expect(screen.getByText('3원')).toBeInTheDocument()
    expect(screen.getByText('-12원')).toBeInTheDocument()

    // #589 수수료 지급 표에는 원천 컬럼이 없다.
    expect(screen.queryByRole('columnheader', { name: '원천' })).not.toBeInTheDocument()
    expect(screen.queryByRole('cell', { name: 'SOURCE_A' })).not.toBeInTheDocument()
    // #588 대사 결과 표에는 주원인 컬럼이 없다(원인 코드는 대사 비교 상세·예외함에서 확인).
    expect(screen.queryByRole('columnheader', { name: '주원인' })).not.toBeInTheDocument()
    expect(screen.queryByRole('cell', { name: 'REASON_A' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('columnheader', { name: '차액' })).toHaveLength(2)
  })

  it('canProcess=false이면 세 작업은 안내와 함께 비활성이고 요청하지 않는다', async () => {
    const { actions } = renderAt('/contracts/1', undefined, false)
    await screen.findByRole('heading', { name: 'CONT-0001' })
    for (const label of ['수정', '스케줄 재생성', '한도 재검증']) {
      const button = screen.getByRole('button', { name: label })
      expect(button).toBeDisabled()
      expect(button).toHaveAccessibleDescription('수정·스케줄 재생성·한도 재검증은 SETTLEMENT 권한이 필요합니다.')
      await actions.click(button)
    }
    expect(requested.some(({ options }) => options?.method === 'POST')).toBe(false)
  })

  it('재검증 중 중복 실행을 막고 성공 후 계약·스케줄·CAP 캐시를 만료시킨다', async () => {
    let finish!: (value: unknown) => void
    const pending = new Promise((resolve) => {
      finish = resolve
    })
    const { client, router, actions } = renderAt('/contracts/1', (url, options) => {
      if (url.endsWith('/cap-check') && options?.method === 'POST') return pending
      if (url.endsWith('/cap-checks')) return []
    })
    client.setQueryData(['contracts', 'list', 'cached'], { content: [] })
    client.setQueryData(['schedules', 'list', 'cached'], { content: [] })
    client.setQueryData(['cap-checks', 'list', 'cached'], { content: [] })
    const button = await screen.findByRole('button', { name: '한도 재검증' })
    await actions.click(button)
    await waitFor(() => expect(button).toHaveAttribute('aria-busy', 'true'))
    await actions.click(button)
    expect(screen.getByRole('button', { name: '수정' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '스케줄 재생성' })).toBeDisabled()
    expect(calls('/cap-check')).toHaveLength(1)
    await act(async () => {
      finish([])
      await pending
    })
    await waitFor(() => expect(router.state.location.search).toBe('?tab=cap'))
    expect(messages()).toContain('1,200% 한도 재검증을 완료했습니다.')
    for (const prefix of ['contracts', 'schedules', 'cap-checks'])
      expect(client.getQueryState([prefix, 'list', 'cached'])?.isInvalidated).toBe(true)
  })

  it('스케줄 재생성 사유를 API에 보내고 최신 스케줄 탭으로 이동한다', async () => {
    const { router, actions } = renderAt('/contracts/1', (url) => {
      if (url.endsWith('/schedules/regenerate?reason=CONTRACT_DETAIL_MANUAL')) return [100, 101]
      if (url.includes('/schedules?')) return { headers: [], lines: [] }
    })
    await actions.click(await screen.findByRole('button', { name: '스케줄 재생성' }))
    await waitFor(() => expect(screen.getAllByText('현재 운영 스케줄이 없습니다.')).toHaveLength(2))
    expect(router.state.location.search).toBe('?tab=schedules')
    expect(calls('/schedules/regenerate?reason=CONTRACT_DETAIL_MANUAL')[0].options?.method).toBe('POST')
    expect(messages()).toContain('예상 스케줄을 새 버전으로 재생성했습니다.')
  })

  it('조회 오류는 재시도하고 COMMON-500 내부 메시지는 요청 ID로 대체한다', async () => {
    let failed = true
    const { actions } = renderAt('/contracts/1?tab=ledger', (url) => {
      if (!url.endsWith('/journals')) return
      if (failed) throw new ApiError({ code: 'FGC-COMMON-500', message: 'SECRET SQL detail' }, 'ledger-trace', 500)
      return []
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('요청 ID: ledger-trace')
    expect(screen.queryByText(/SECRET SQL/)).not.toBeInTheDocument()
    failed = false
    await actions.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('조회된 분개가 없습니다.')).toBeInTheDocument()
  })

  it('작업 권한 거절은 실패로 남고 500 Toast에도 내부 상세를 노출하지 않는다', async () => {
    const { router, actions } = renderAt('/contracts/1', (url) => {
      if (url.endsWith('/cap-check'))
        throw new ApiError({ code: 'FGC-AUTH-403', message: '작업 권한이 없습니다.' }, 'permission-trace', 403)
      if (url.includes('/schedules/regenerate'))
        throw new ApiError({ code: 'FGC-COMMON-500', message: 'SECRET SQL detail' }, 'mutation-trace', 500)
    })
    await actions.click(await screen.findByRole('button', { name: '한도 재검증' }))
    await waitFor(() => expect(messages().some((message) => message.includes('작업 권한이 없습니다.'))).toBe(true))
    expect(router.state.location.search).toBe('')
    await actions.click(screen.getByRole('button', { name: '스케줄 재생성' }))
    await waitFor(() => expect(messages()).toContain('요청 ID: mutation-trace'))
    expect(messages().some((message) => message.includes('SECRET'))).toBe(false)
    expect(messages().some((message) => message.includes('완료했습니다'))).toBe(false)
  })

  it('저장 성공 메시지는 상세에서 한 번 소비하고 수정 화면으로 이동한다', async () => {
    const { router, actions } = renderAt('/contracts/1', undefined, true, { message: '계약을 등록했습니다.' })
    await screen.findByRole('heading', { name: 'CONT-0001' })
    await waitFor(() => expect(router.state.location.state).toBeNull())
    expect(messages()).toEqual(['계약을 등록했습니다.'])
    await actions.click(screen.getByRole('button', { name: '수정' }))
    expect(router.state.location.pathname).toBe('/contracts/1/edit')
    await act(() => router.navigate(-1))
    await screen.findByRole('heading', { name: 'CONT-0001' })
    expect(messages()).toEqual(['계약을 등록했습니다.'])
  })

  it('잘못된 식별자는 계약 API를 요청하지 않는다', async () => {
    renderAt('/contracts/not-an-id')
    expect(await screen.findByRole('alert')).toHaveTextContent('계약 식별자를 확인할 수 없습니다.')
    expect(requested.some(({ url }) => url.startsWith('/api/v1/contracts'))).toBe(false)
  })

  it('존재하지 않는 숫자 계약은 기존 COMMON-002 응답을 표시하고 재시도할 수 있다', async () => {
    renderAt('/contracts/999999', (url) => {
      if (url === '/api/v1/contracts/999999')
        throw new ApiError(
          { code: 'FGC-COMMON-002', message: '존재하지 않는 보험계약입니다.' },
          'missing-contract',
          400,
        )
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('존재하지 않는 보험계약입니다.')
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled()
    expect(requested.some(({ url }) => url.endsWith('/status-events'))).toBe(false)
  })

  it('처리 중 다른 계약으로 이동하면 이전 작업 완료가 현재 계약의 탭을 바꾸지 않는다', async () => {
    let finish!: (value: unknown) => void
    const pending = new Promise((resolve) => {
      finish = resolve
    })
    const { router, actions, client } = renderAt('/contracts/1', (url) => {
      if (url.endsWith('/cap-check')) return pending
      if (url === '/api/v1/contracts/2') return { ...contract, contractId: 2, contractNo: 'CONT-0002' }
      if (url === '/api/v1/contracts/2/journals') return []
    })
    const invalidated = vi.spyOn(client, 'invalidateQueries')
    await actions.click(await screen.findByRole('button', { name: '한도 재검증' }))
    await act(() => router.navigate('/contracts/2?tab=ledger'))
    await screen.findByRole('heading', { name: 'CONT-0002' })
    await act(async () => {
      finish([])
      await pending
    })
    await waitFor(() => expect(invalidated).toHaveBeenCalledWith({ queryKey: ['contracts'] }))
    expect(router.state.location.pathname).toBe('/contracts/2')
    expect(router.state.location.search).toBe('?tab=ledger')
    expect(screen.getByRole('tab', { name: '원장' })).toHaveAttribute('aria-selected', 'true')
    expect(messages()).toEqual([])
  })
})
