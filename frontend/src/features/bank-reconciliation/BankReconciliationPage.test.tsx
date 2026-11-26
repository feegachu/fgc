import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import { goToBank } from './api'
import type { BankLink, BankReconciliation } from './api'

vi.mock('./api', async (original) => ({ ...(await original<typeof import('./api')>()), goToBank: vi.fn() }))

const linked: BankLink = {
  linked: true,
  connected: true,
  bankName: '모의은행',
  accountNumMasked: '110-***-***001',
  accountHolderName: '가상지에이',
  linkedAt: '2026-11-18T10:00:00+09:00',
  mock: true,
}
const notLinked: BankLink = { linked: false, connected: false, mock: true }
const result: BankReconciliation = {
  bankReconciliationRunId: 12,
  settlementMonth: '2026-10-01',
  inquiryFrom: '2026-11-18',
  inquiryTo: '2026-11-18',
  bankName: '모의은행',
  accountNumMasked: '110-***-***001',
  withdrawalCount: 3,
  createdBy: 'settle01',
  createdAt: '2026-11-18T10:05:00+09:00',
  matchedCount: 1,
  amountMismatchCount: 1,
  notTransferredCount: 1,
  unknownWithdrawalCount: 1,
  items: [
    {
      bankReconciliationItemId: 101,
      result: 'AMOUNT_MISMATCH',
      agentCode: 'A-FC-002',
      agentName: '이보험',
      referenceCode: 'FGC2610-000002',
      expectedAmount: 1100000,
      bankAmount: 1090000,
      differenceAmount: -10000,
      bankTranDate: '2026-11-18',
      printContent: 'FGC2610-000002',
    },
    {
      bankReconciliationItemId: 102,
      result: 'NOT_TRANSFERRED',
      agentCode: 'A-FC-003',
      agentName: '박신인',
      referenceCode: 'FGC2610-000003',
      expectedAmount: 500000,
      bankAmount: 0,
      differenceAmount: -500000,
    },
    {
      bankReconciliationItemId: 103,
      result: 'UNKNOWN_WITHDRAWAL',
      expectedAmount: 0,
      bankAmount: 55000,
      differenceAmount: 55000,
      bankTranDate: '2026-11-18',
      printContent: '관리비',
    },
    {
      bankReconciliationItemId: 104,
      result: 'MATCHED',
      agentCode: 'A-FC-001',
      agentName: '김정산',
      referenceCode: 'FGC2610-000001',
      expectedAmount: 890000,
      bankAmount: 890000,
      differenceAmount: 0,
      bankTranDate: '2026-11-18',
      printContent: 'FGC2610-000001',
    },
  ],
}
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'bank-test' })
let requested: { url: string; method?: string; body?: unknown }[]
let link: BankLink
let latest: BankReconciliation | null
let runFailure: ApiError | null
/** 응답을 붙잡아 두는 문. 요청 순서와 응답 순서가 뒤바뀌는 경우를 만든다. */
let linkGate: Promise<void> | null
let runGate: Promise<void> | null

function renderAt(path: string, roleCode = 'SETTLEMENT') {
  requested = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url, options) => {
    requested.push({ url, method: options?.method, body: options?.body })
    if (url === '/api/v1/auth/me')
      return envelope({
        loginId: 'settle01',
        userName: '정산담당자',
        roleCode,
        demoMonth: '2026-10',
        canProcess: ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode),
      })
    if (url === '/api/v1/bank/link' && options?.method === 'POST') {
      link = linked
      return envelope(linked)
    }
    if (url === '/api/v1/bank/link') {
      const answered = link
      if (linkGate) await linkGate
      return envelope(answered)
    }
    if (url === '/api/v1/bank/link/authorize-url')
      return envelope({ authorizeUrl: '/api/v1/mock-bank/oauth/2.0/authorize?state=abc' })
    if (url === '/api/v1/bank-reconciliations' && options?.method === 'POST') {
      if (runFailure) throw runFailure
      if (runGate) await runGate
      latest = result
      return envelope(result)
    }
    // 결과는 2026-10 의 것이다. 다른 달은 아직 대사하지 않았다.
    if (url.startsWith('/api/v1/bank-reconciliations?')) return envelope(url.endsWith('month=2026-10') ? latest : null)
    throw new Error(`unexpected ${url}`)
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, client, actions: userEvent.setup() }
}
const posts = (url: string) => requested.filter((request) => request.url === url && request.method === 'POST')

// 라우트는 화면을 lazy 로 불러온다. 첫 변환 시간이 findBy 기본 대기(1초)를 넘지 않도록 미리 받아 둔다.
beforeAll(async () => {
  await import('./BankReconciliationPage')
})

beforeEach(() => {
  link = linked
  latest = result
  runFailure = null
  linkGate = null
  runGate = null
  vi.mocked(goToBank).mockClear()
  useAuthStore.setState({ accessToken: 'bank-fixture', loggingOut: false })
  useWorkspaceStore.getState().clear()
})
afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

describe('BANK-W01 지급 이체 대사', () => {
  it('연결한 계좌와 그 달의 대사 결과를 코드가 아니라 우리말로 보여 준다', async () => {
    renderAt('/bank-reconciliations')

    expect(await screen.findByText('모의은행 110-***-***001')).toBeInTheDocument()
    // 화면 문구 규칙(e2e-demo/screen-language)이 "시연" 같은 팀 안의 말을 금지한다.
    expect(screen.getByText('실제 은행이 아니라 모의 은행입니다.')).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent(/시연|데모/)
    const table = await screen.findByRole('table', { name: '지급 이체 대사 결과' })
    await within(table).findByText('금액 다름')
    const [, mismatch, missing, unknown, matched] = within(table).getAllByRole('row')
    expect(within(mismatch).getByText('이보험')).toBeInTheDocument()
    expect(within(mismatch).getByText('1,100,000원')).toBeInTheDocument()
    expect(within(mismatch).getByText('1,090,000원')).toBeInTheDocument()
    expect(within(mismatch).getByText('-10,000원')).toBeInTheDocument()
    expect(within(missing).getByText('이체 없음')).toBeInTheDocument()
    expect(within(unknown).getByText('확인 안 된 출금')).toBeInTheDocument()
    expect(within(unknown).getByText('관리비')).toBeInTheDocument()
    expect(within(matched).getByText('일치')).toBeInTheDocument()
    for (const code of ['AMOUNT_MISMATCH', 'NOT_TRANSFERRED', 'UNKNOWN_WITHDRAWAL', 'MATCHED'])
      expect(table).not.toHaveTextContent(code)
    expect(requested.map((request) => request.url)).toContain('/api/v1/bank-reconciliations?month=2026-10')
  })

  it('연결한 계좌가 없으면 은행 연결을 누르게 하고, 누르면 은행 동의 화면으로 보낸다', async () => {
    link = notLinked
    latest = null
    const { actions } = renderAt('/bank-reconciliations')

    expect(await screen.findByText('연결한 계좌가 없습니다.')).toBeInTheDocument()
    expect(await screen.findByText('아직 대사하지 않았습니다.')).toBeInTheDocument()
    await actions.click(screen.getByRole('button', { name: '은행 연결' }))

    await waitFor(() =>
      expect(goToBank).toHaveBeenCalledWith('/api/v1/mock-bank/oauth/2.0/authorize?state=abc'),
    )
    expect(posts('/api/v1/bank/link/authorize-url')).toHaveLength(1)
  })

  it('은행에서 돌아오면 받은 값으로 연결을 마치고 주소에서 그 값을 지운다', async () => {
    link = notLinked
    latest = null
    const { router } = renderAt('/bank-reconciliations?code=CODE-1&scope=login%20inquiry&state=abc123')

    expect(await screen.findByText('은행 계좌를 연결했습니다.')).toBeInTheDocument()
    expect(posts('/api/v1/bank/link')).toEqual([
      { url: '/api/v1/bank/link', method: 'POST', body: { code: 'CODE-1', state: 'abc123' } },
    ])
    expect(await screen.findByText('모의은행 110-***-***001')).toBeInTheDocument()
    await waitFor(() => expect(router.state.location.search).not.toMatch(/code=|state=|scope=/))
  })

  it('은행 동의 화면에서 취소하고 돌아오면 연결하지 않고 알려 준다', async () => {
    link = notLinked
    latest = null
    const { router } = renderAt('/bank-reconciliations?error=access_denied&state=abc123')

    expect(await screen.findByText('은행 연결을 취소했습니다.')).toBeInTheDocument()
    expect(posts('/api/v1/bank/link')).toHaveLength(0)
    await waitFor(() => expect(router.state.location.search).not.toMatch(/error=|state=/))
  })

  it('대사를 누르면 보고 있는 정산월로 실행하고 결과를 바꿔 보여 준다', async () => {
    latest = null
    const { actions } = renderAt('/bank-reconciliations')
    expect(await screen.findByText('아직 대사하지 않았습니다.')).toBeInTheDocument()

    await actions.click(screen.getByRole('button', { name: '은행 거래내역으로 대사' }))

    const table = await screen.findByRole('table', { name: '지급 이체 대사 결과' })
    expect(await within(table).findByText('금액 다름')).toBeInTheDocument()
    expect(posts('/api/v1/bank-reconciliations')).toEqual([
      { url: '/api/v1/bank-reconciliations', method: 'POST', body: { settlementMonth: '2026-10' } },
    ])
  })

  it('대사를 돌리는 사이 정산월을 바꿔도 결과는 돌린 달에만 남는다', async () => {
    latest = null
    let finish = () => {}
    runGate = new Promise<void>((resolve) => {
      finish = resolve
    })
    const { actions, router, client } = renderAt('/bank-reconciliations')
    await actions.click(await screen.findByRole('button', { name: '은행 거래내역으로 대사' }))
    await waitFor(() => expect(posts('/api/v1/bank-reconciliations')).toHaveLength(1))

    await router.navigate('/bank-reconciliations?month=2026-09')
    await waitFor(() => expect(requested.map((request) => request.url)).toContain('/api/v1/bank-reconciliations?month=2026-09'))
    finish()
    await waitFor(() => expect(client.getQueryData(['bank', 'reconciliation', '2026-10'])).toEqual(result))

    // 10월 결과가 캐시에 들어간 뒤에도 9월 화면은 비어 있다.
    expect(screen.getByText('아직 대사하지 않았습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: '지급 이체 대사 결과' })).not.toBeInTheDocument()
  })

  it('연결 조회 응답이 늦게 와도 방금 마친 연결을 연결 전 상태로 되돌리지 않는다', async () => {
    link = notLinked
    latest = null
    let finish = () => {}
    linkGate = new Promise<void>((resolve) => {
      finish = resolve
    })
    renderAt('/bank-reconciliations?code=CODE-1&state=STATE-1')

    await waitFor(() => expect(posts('/api/v1/bank/link')).toHaveLength(1))
    expect(await screen.findByText('모의은행 110-***-***001')).toBeInTheDocument()
    // 화면을 열 때 보낸 조회가 이제야 "연결 전"으로 답한다.
    await act(async () => {
      finish()
      await linkGate
    })

    expect(screen.getByText('모의은행 110-***-***001')).toBeInTheDocument()
    expect(screen.queryByText('연결한 계좌가 없습니다.')).not.toBeInTheDocument()
  })

  it('내용이 같은 출금 두 건도 서로 다른 줄로 보여 준다', async () => {
    const repeated = { ...result.items![2], bankReconciliationItemId: 105 }
    latest = { ...result, unknownWithdrawalCount: 2, items: [...result.items!, repeated] }
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    renderAt('/bank-reconciliations')

    const table = await screen.findByRole('table', { name: '지급 이체 대사 결과' })
    await within(table).findByText('금액 다름')
    expect(within(table).getAllByText('관리비')).toHaveLength(2)
    // 줄을 내용으로 구분하면 React 가 같은 키가 둘이라고 알린다.
    expect(errors.mock.calls.flat().join(' ')).not.toMatch(/same key/)
  })

  it('서버가 대사를 거절하면 그 이유를 보여 준다', async () => {
    latest = null
    runFailure = new ApiError(
      { code: 'FGC-BANK-002', message: '확정된 정산월만 대사할 수 있습니다. 월 통합검증을 확정한 뒤 다시 시도하세요.' },
      'bank-test',
      409,
    )
    const { actions } = renderAt('/bank-reconciliations')

    await actions.click(await screen.findByRole('button', { name: '은행 거래내역으로 대사' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('확정된 정산월만 대사할 수 있습니다.')
  })

  it('정산 처리 역할이 아니면 결과만 보고 연결과 대사 단추는 보이지 않는다', async () => {
    renderAt('/bank-reconciliations', 'GA_ADMIN')

    expect(await screen.findByRole('table', { name: '지급 이체 대사 결과' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 연결' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '은행 거래내역으로 대사' })).not.toBeInTheDocument()
  })

  it('서버를 다시 띄워 은행 연결이 끊겼으면 다시 연결하라고 알려 준다', async () => {
    link = { ...linked, connected: false }
    renderAt('/bank-reconciliations')

    expect(await screen.findByText('다시 연결해야 은행 거래내역을 가져올 수 있습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 연결' })).toBeInTheDocument()
  })
})
