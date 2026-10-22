import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient, ApiError } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import type { PaymentPage, PaymentRow } from './list-api'

const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'transaction-test' })
const row: PaymentRow = {
  commissionTransactionId: 41,
  sourceBusinessKey: 'TRAN-410',
  settlementMonth: '2026-07-01',
  paymentStage: 'GA_TO_FC',
  paymentStageLabel: 'GA→설계사',
  sourceType: 'GA_MANUAL_PAYMENT',
  sourceTypeLabel: 'GA 수기지급',
  recipientAgentId: 3,
  recipientAgentName: '홍길동',
  commissionItemId: 2,
  commissionItemName: '모집수수료',
  amount: 10000,
  cashflowType: 'PAYMENT',
  cashflowTypeLabel: '지급',
  status: 'DRAFT',
  statusLabel: '작성중',
  attributionCount: 2,
  differenceAmount: 0,
}
let requests: string[]
let resultRows: PaymentRow[]
let failure: Error | null
let interceptList: ((url: string) => Promise<PaymentPage> | undefined) | undefined
let deleteDraft: () => Promise<void>
let deleteCalls: string[]
function renderAt(path = '/app/transactions', canProcess = true) {
  vi.spyOn(apiClient, 'request').mockImplementation(async (url, options) => {
    requests.push(url)
    if (options?.method === 'DELETE') {
      deleteCalls.push(url)
      await deleteDraft()
      return envelope(null)
    }
    if (url === '/api/v1/auth/me')
      return envelope({
        loginId: 'transaction-user',
        userName: '담당자',
        roleCode: canProcess ? 'SETTLEMENT' : 'COMPLIANCE',
        canProcess,
        demoMonth: '2026-07',
      })
    if (url.startsWith('/api/v1/base/insurers'))
      return envelope({
        content: [{ insurerId: 8, insurerCode: 'LIFE', insurerName: '생명보험' }],
        page: 1,
        totalPages: 1,
      })
    if (url.startsWith('/api/v1/transactions?')) {
      if (failure) throw failure
      const intercepted = interceptList?.(url)
      if (intercepted) return envelope(await intercepted)
      const params = new URLSearchParams(url.split('?')[1])
      return envelope({
        content: resultRows,
        page: Number(params.get('page')),
        totalPages: 7,
        totalElements: 125,
        size: 20,
      })
    }
    throw new Error(`Unexpected API ${url}`)
  })
  const router = createMemoryRouter(routes, { basename: '/app', initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, actions: userEvent.setup() }
}
function lastRequest() {
  return new URLSearchParams(
    requests
      .filter((url) => url.startsWith('/api/v1/transactions?'))
      .at(-1)!
      .split('?')[1],
  )
}
beforeAll(async () => {
  await import('./TransactionListPage')
})
beforeEach(() => {
  requests = []
  resultRows = [row]
  failure = null
  interceptList = undefined
  deleteCalls = []
  deleteDraft = async () => {
    resultRows = []
  }
  useAuthStore.setState({ accessToken: 'fixture', loggingOut: false })
  useWorkspaceStore.getState().clear()
})
afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

describe('TRAN-W01 수수료 지급 목록', () => {
  it('GA 수기지급 이외의 초안은 삭제 요청과 확인창을 열지 않는다', async () => {
    resultRows = [{ ...row, sourceType: 'INSURER_STATEMENT' }]
    const { actions } = renderAt()
    const button = await screen.findByRole('button', { name: 'TRAN-410 삭제' })
    expect(button).toBeDisabled()
    await actions.click(button)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(deleteCalls).toHaveLength(0)
  })

  it('작업 헤더와 초안 삭제 확인창을 표시하고 취소하면 요청하지 않는다', async () => {
    const { actions } = renderAt()
    await screen.findByRole('columnheader', { name: '작업' })
    await actions.click(await screen.findByRole('button', { name: 'TRAN-410 삭제' }))
    const dialog = screen.getByRole('dialog', { name: '지급 초안 삭제' })
    expect(dialog).toHaveTextContent('TRAN-410')
    expect(dialog).toHaveTextContent('10,000원')
    await actions.click(within(dialog).getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(deleteCalls).toHaveLength(0)
  })

  it('삭제 후 필터를 보존하고 마지막 페이지를 보정하여 목록을 다시 조회한다', async () => {
    const { actions, router } = renderAt('/app/transactions?page=3&status=DRAFT&contractNo=C-001')
    await actions.click(await screen.findByRole('button', { name: 'TRAN-410 삭제' }))
    await actions.click(within(screen.getByRole('dialog')).getByRole('button', { name: '삭제' }))
    await waitFor(() => expect(router.state.location.search).toContain('page=2'))
    expect(router.state.location.search).toContain('status=DRAFT')
    expect(router.state.location.search).toContain('contractNo=C-001')
    await waitFor(() => expect(lastRequest().get('page')).toBe('2'))
    expect(deleteCalls).toEqual(['/api/v1/transactions/41'])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('삭제 중 중복 요청과 닫기를 막고 충돌 오류를 확인창에서 알린다', async () => {
    let reject!: (error: Error) => void
    deleteDraft = () =>
      new Promise<void>((_, failure) => {
        reject = failure
      })
    const { actions } = renderAt()
    await actions.click(await screen.findByRole('button', { name: 'TRAN-410 삭제' }))
    const dialog = screen.getByRole('dialog')
    await actions.click(within(dialog).getByRole('button', { name: '삭제' }))
    expect(within(dialog).getByRole('button', { name: /삭제 중/ })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: '취소' })).toBeDisabled()
    await actions.keyboard('{Escape}')
    expect(dialog).toBeVisible()
    await act(async () =>
      reject(
        new ApiError(
          { code: 'FGC-TRAN-005', message: '작성중인 지급 건만 삭제할 수 있습니다.' },
          'delete-conflict',
          409,
        ),
      ),
    )
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('작성중인 지급 건만 삭제')
    expect(deleteCalls).toHaveLength(1)
  })

  it('확정·취소 행에는 삭제가 없고 조회 전용 사용자에게는 비활성이다', async () => {
    resultRows = [
      row,
      { ...row, commissionTransactionId: 42, sourceBusinessKey: 'CONFIRMED', status: 'CONFIRMED' },
      { ...row, commissionTransactionId: 43, sourceBusinessKey: 'CANCELLED', status: 'CANCELLED' },
    ]
    renderAt('/app/transactions', false)
    expect(await screen.findByRole('button', { name: 'TRAN-410 삭제 (조회 전용 권한)' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'CONFIRMED 삭제' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'CANCELLED 삭제' })).not.toBeInTheDocument()
  })
  it('취소된 원 지급은 흐름도 취소로 표시하고 대체 지급은 지급으로 표시한다', async () => {
    resultRows = [
      { ...row, status: 'CANCELLED', statusLabel: '취소', sourceBusinessKey: 'OLD-548' },
      { ...row, commissionTransactionId: 42, status: 'CONFIRMED', statusLabel: '확정', sourceBusinessKey: 'NEW-548' },
    ]
    renderAt()
    const old = (await screen.findByText('OLD-548')).closest('tr')!
    const replacement = (await screen.findByText('NEW-548')).closest('tr')!
    expect(within(old).getAllByText('취소')).toHaveLength(2)
    expect(within(old).queryByText('지급')).not.toBeInTheDocument()
    expect(within(replacement).getByText('지급')).toBeInTheDocument()
    expect(within(replacement).getByText('확정')).toBeInTheDocument()
  })

  it('URL 필터·20행·서버 건수 및 현재 페이지 금액 합계를 유지한다', async () => {
    renderAt(
      '/app/transactions?settlementMonth=2026-08&paymentStage=GA_TO_FC&insurerId=8&contractNo=POL-001&sourceType=GA_MANUAL_PAYMENT&agentId=3&commissionItemId=2&status=DRAFT&noAttributionOnly=true&attributionImbalanceOnly=true&page=2',
    )
    const table = await screen.findByRole('table', { name: '검색 조건에 해당하는 수수료 지급 건' })
    expect(await within(table).findByText('TRAN-410')).toHaveAttribute('title', 'TRAN-410')
    expect(Object.fromEntries(lastRequest())).toMatchObject({
      settlementMonth: '2026-08',
      paymentStage: 'GA_TO_FC',
      insurerId: '8',
      contractNo: 'POL-001',
      sourceType: 'GA_MANUAL_PAYMENT',
      agentId: '3',
      commissionItemId: '2',
      status: 'DRAFT',
      noAttributionOnly: 'true',
      attributionImbalanceOnly: 'true',
      page: '2',
      size: '20',
    })
    expect(screen.getByText('125')).toBeInTheDocument()
    expect(screen.getByText('2 / 7 페이지')).toBeInTheDocument()
    expect(document.getElementById('sum-amount')).toHaveTextContent('10,000원')
    expect(screen.getByLabelText('정산월')).toHaveValue('2026-08')
    expect(screen.getByLabelText('수령 설계사')).toHaveValue('3')
    expect(screen.getByRole('link', { name: 'TRAN-410 수정' })).toHaveAttribute(
      'href',
      '/app/transactions/new?id=41&month=2026-07',
    )
  })
  it('검색 제출은 숨은 불균형 필터를 보존하고 초기화만 해제한다', async () => {
    const { router, actions } = renderAt('/app/transactions?month=2026-07&attributionImbalanceOnly=true&page=4')
    await screen.findByText('TRAN-410')
    await actions.type(screen.getByLabelText('계약번호'), '  POL-2  ')
    expect(lastRequest().get('contractNo')).toBeNull()
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(lastRequest().get('contractNo')).toBe('POL-2'))
    expect(lastRequest().get('attributionImbalanceOnly')).toBe('true')
    expect(lastRequest().get('page')).toBe('1')
    expect(new URLSearchParams(router.state.location.search).get('attributionImbalanceOnly')).toBe('true')
    await actions.click(screen.getByRole('button', { name: '초기화' }))
    await waitFor(() => expect(lastRequest().has('attributionImbalanceOnly')).toBe(false))
    expect(lastRequest().get('settlementMonth')).toBe('2026-07')
  })
  it('페이지는 1부터 시작하고 다음·이전 묶음으로 이동하며 숨은 필터를 유지한다', async () => {
    const { actions } = renderAt('/app/transactions?page=0&attributionImbalanceOnly=true')
    await screen.findByText('TRAN-410')
    expect(lastRequest().get('page')).toBe('1')
    expect(screen.getByRole('button', { name: '이전 5페이지' })).toBeDisabled()
    await actions.click(screen.getByRole('button', { name: '다음 5페이지' }))
    await waitFor(() => expect(screen.getByText('6 / 7 페이지')).toBeInTheDocument())
    expect(lastRequest().get('attributionImbalanceOnly')).toBe('true')
    await actions.click(screen.getByRole('button', { name: '이전 5페이지' }))
    await waitFor(() => expect(lastRequest().get('page')).toBe('5'))
  })
  it('4페이지는 첫 묶음을 표시하고 숫자 선택·묶음 이동·경계·안내를 유지한다', async () => {
    const { actions } = renderAt('/app/transactions?month=2026-07&settlementMonth=2026-07&contractNo=POL-2&page=4')
    await screen.findByText('4 / 7 페이지')
    const pagination = () => screen.getByRole('navigation', { name: '수수료 지급 건 목록 페이지 선택' })
    const controls = () => within(pagination())
    expect(controls().queryByRole('button', { name: '처음' })).toBeNull()
    expect(controls().queryByRole('button', { name: '마지막' })).toBeNull()
    expect(pagination().querySelector('[aria-current="page"]')).toHaveTextContent('4')
    expect(pagination().querySelectorAll('.pagination-button')).toHaveLength(7)
    expect(controls().getByRole('button', { name: '이전 5페이지' })).toBeDisabled()
    expect(controls().getByRole('button', { name: '다음 5페이지' })).toBeEnabled()
    expect(controls().getByRole('button', { name: '다음 5페이지' })).toHaveAttribute('title', '다음 5페이지')
    await actions.click(controls().getByRole('button', { name: '3' }))
    await screen.findByText('3 / 7 페이지')
    await actions.click(controls().getByRole('button', { name: '다음 5페이지' }))
    await screen.findByText('6 / 7 페이지')
    expect(lastRequest().get('contractNo')).toBe('POL-2')
    expect(lastRequest().get('settlementMonth')).toBe('2026-07')
    expect(pagination().querySelectorAll('.pagination-button')).toHaveLength(4)
    expect(controls().getByRole('button', { name: '다음 5페이지' })).toBeDisabled()
    expect(controls().getByRole('button', { name: '이전 5페이지' })).toHaveAttribute('title', '이전 5페이지')
    await actions.click(controls().getByRole('button', { name: '7' }))
    await screen.findByText('7 / 7 페이지')
    await actions.click(controls().getByRole('button', { name: '이전 5페이지' }))
    await screen.findByText('5 / 7 페이지')
  })
  it('정산월을 지운 뒤 조회·재조회해도 전체 월 상태와 빈 입력을 유지한다', async () => {
    const { router, actions } = renderAt('/app/transactions?month=2026-07&settlementMonth=2026-07')
    await screen.findByText('TRAN-410')
    fireEvent.change(screen.getByLabelText('정산월'), { target: { value: '' } })
    expect(screen.getByLabelText('정산월')).toHaveValue('')
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(lastRequest().has('settlementMonth')).toBe(false))
    expect(new URLSearchParams(router.state.location.search).get('settlementMonth')).toBe('')
    expect(screen.getByLabelText('정산월')).toHaveValue('')
    const count = requests.filter((url) => url.startsWith('/api/v1/transactions?')).length
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() =>
      expect(requests.filter((url) => url.startsWith('/api/v1/transactions?')).length).toBe(count + 1),
    )
    expect(lastRequest().has('settlementMonth')).toBe(false)
  })
  it('이전 방문 결과의 설계사·항목 선택지를 보존하고 URL 재진입 값을 복원한다', async () => {
    const { router, actions } = renderAt()
    await screen.findByRole('option', { name: '홍길동' })
    resultRows = [{ ...row, commissionTransactionId: 42, recipientAgentId: 4, recipientAgentName: '김길동' }]
    await actions.click(screen.getByRole('button', { name: '다음 5페이지' }))
    expect(await screen.findByRole('option', { name: '김길동' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: '홍길동' })).toBeInTheDocument()
    await act(() => router.navigate('/transactions?month=2026-07&contractNo=RESTORED&agentId=999'))
    expect(await screen.findByDisplayValue('RESTORED')).toBeInTheDocument()
    expect(screen.getByLabelText('수령 설계사')).toHaveValue('999')
  })
  it('COMPLIANCE는 신규 등록이 없고 수정이 비활성이다', async () => {
    renderAt('/app/transactions', false)
    expect(await screen.findByRole('button', { name: 'TRAN-410 수정 (조회 전용 권한)' })).toBeDisabled()
    expect(screen.queryByRole('link', { name: /신규 등록/ })).toBeNull()
  })
  it('이전 조건의 응답이 늦게 도착해도 새 검색 결과를 덮어쓰지 않는다', async () => {
    let finish!: (value: PaymentPage) => void
    const delayed = new Promise<PaymentPage>((resolve) => {
      finish = resolve
    })
    interceptList = (url) => (url.includes('contractNo=SLOW') ? delayed : undefined)
    const { router } = renderAt('/app/transactions?month=2026-07&contractNo=SLOW')
    await waitFor(() => expect(requests.some((url) => url.includes('contractNo=SLOW'))).toBe(true))
    await act(() => router.navigate('/transactions?month=2026-07&contractNo=FAST'))
    expect(await screen.findByText('TRAN-410')).toBeInTheDocument()
    await act(async () => {
      finish({ content: [{ ...row, sourceBusinessKey: 'OLD-RESULT' }], page: 1, totalPages: 1, totalElements: 1 })
      await delayed
    })
    expect(screen.queryByText('OLD-RESULT')).toBeNull()
    expect(screen.getByText('TRAN-410')).toBeInTheDocument()
  })
  it('COMPLIANCE의 등록 딥링크는 403이며 지급 API를 호출하지 않는다', async () => {
    renderAt('/app/transactions/new?id=41', false)
    expect(await screen.findByRole('heading', { name: '403 · 권한 없음' })).toBeInTheDocument()
    expect(requests.some((path) => path.startsWith('/api/v1/transactions'))).toBe(false)
  })
  it('서버 500의 내부 문구 대신 요청 ID를 보여 주고 재조회할 수 있다', async () => {
    failure = new ApiError({ code: 'FGC-COMMON-500', message: 'private SQL detail' }, 'trace-410', 500)
    const { actions } = renderAt()
    expect(await screen.findByText('요청 ID: trace-410')).toBeInTheDocument()
    expect(screen.queryByText(/private SQL detail/)).toBeNull()
    failure = null
    await actions.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('TRAN-410')).toBeInTheDocument()
  })
  it('음수 금액은 괄호·빨강, 귀속 0건/차액은 확인필요로 표시한다', async () => {
    resultRows = [{ ...row, amount: -1000, attributionCount: 0, differenceAmount: -1000 }]
    renderAt()
    const table = await screen.findByRole('table')
    const amounts = await within(table).findAllByText('(1,000)원')
    expect(amounts.every((element) => element.classList.contains('is-negative-amount'))).toBe(true)
    expect(within(table).getByText('0건 · 확인필요')).toBeInTheDocument()
  })
})
