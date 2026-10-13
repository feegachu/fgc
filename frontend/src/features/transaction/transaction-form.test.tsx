import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, Outlet } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import type { RequestOptions } from '../../lib/api/client'
import { useToastStore } from '../../stores/toasts'
import TransactionFormPage from './TransactionFormPage'
import { draftQuery } from './form-api'
import type { Payment, Precheck } from './form-api'

const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'TRAN-TEST' })
const agents = [
  { agentId: 1, agentCode: 'FC-001', agentName: '설계사', rankCode: 'FC', agentStatus: 'ACTIVE' },
  { agentId: 2, agentCode: 'BM-001', agentName: '지점장', rankCode: 'BM', agentStatus: 'ACTIVE' },
]
const items = [
  { commissionItemId: 1, itemCode: 'BASE', itemName: '기본 수수료', cashflowType: 'PAYMENT' },
  { commissionItemId: 2, itemCode: 'NEWCOMER_SUPPORT', itemName: '신인 지원', cashflowType: 'PAYMENT' },
  { commissionItemId: 3, itemCode: 'DEDUCTION', itemName: '차감 수수료', cashflowType: 'DEDUCTION' },
]
const contracts = [
  { contractId: 3, contractNo: 'C-003', productName: '상품', contractDate: '2026-07-18' },
  { contractId: 7, contractNo: 'C-007', productName: '미분류', contractDate: '2026-07-01' },
]
const saved: Payment = {
  commissionTransactionId: 77,
  status: 'DRAFT',
  paymentStage: 'GA_TO_FC',
  agentId: 1,
  commissionItemId: 1,
  sourceBusinessKey: 'DRAFT-77',
  settlementMonth: '2026-07-01',
  cashflowType: 'PAYMENT',
  amount: 250000,
  attributions: [
    {
      contractId: 3,
      attributionDate: '2026-07-18',
      amount: 250000,
      inclusionDecisionStatus: 'INCLUDED',
      attributionMethod: 'DIRECT',
    },
  ],
}
const clients: QueryClient[] = []
let precheckResult: Precheck
let mutation: (path: string, options: RequestOptions) => unknown
let referenceFailure: ApiError | null
let draftResponse: Payment
beforeEach(() => {
  precheckResult = { confirmable: true, capPreview: [], blockers: [] }
  referenceFailure = null
  draftResponse = saved
  mutation = (path) => (path.endsWith('/precheck') ? precheckResult : saved)
  useToastStore.setState({ messages: [] })
  vi.spyOn(apiClient, 'request').mockImplementation((async (path: string, options: RequestOptions = {}) => {
    if (options.method && options.method !== 'GET') return envelope(await mutation(path, options))
    if (referenceFailure) throw referenceFailure
    if (path.startsWith('/api/v1/base/agents')) return envelope({ content: agents })
    if (path.startsWith('/api/v1/base/commission-items')) return envelope(items)
    if (path.startsWith('/api/v1/contracts')) return envelope({ content: contracts })
    if (path === '/api/v1/transactions/77') return envelope(draftResponse)
    throw new Error(`Unexpected API path: ${path}`)
  }) as typeof apiClient.request)
})
afterEach(() => {
  vi.restoreAllMocks()
  clients.splice(0).forEach((client) => client.clear())
})
function mount(path = '/app/transactions/new?month=2026-07', canProcess = true, cached?: Payment) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  if (cached) client.setQueryData(draftQuery('77').queryKey, cached)
  const router = createMemoryRouter(
    [
      {
        element: <Outlet context={{ user: { canProcess }, month: '2026-07' }} />,
        children: [
          { path: '/transactions/new', element: <TransactionFormPage /> },
          { path: '/transactions', element: <h1>지급 목록 결과</h1> },
          { path: '/exceptions', element: <h1>예외함 결과</h1> },
        ],
      },
    ],
    { basename: '/app', initialEntries: [path] },
  )
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, user: userEvent.setup() }
}
const button = (id: string) => document.getElementById(id) as HTMLButtonElement
const writes = () =>
  vi.mocked(apiClient.request).mock.calls.filter(([, options]) => ['POST', 'PUT'].includes(options?.method ?? ''))
async function fillNew(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByLabelText(/수령 설계사/)).toBeEnabled())
  await user.selectOptions(screen.getByLabelText(/수령 설계사/), '1')
  await waitFor(() => expect(button('btn-add-attr')).toBeEnabled())
  await user.selectOptions(screen.getByLabelText(/수수료 항목/), '1')
  await user.click(button('btn-add-attr'))
  await user.selectOptions(screen.getByLabelText('1번 귀속행 계약번호'), '3')
}
async function saveAndPrecheck(user: ReturnType<typeof userEvent.setup>) {
  await fillNew(user)
  await user.click(button('btn-save'))
  await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
  await user.click(button('btn-precheck'))
  await waitFor(() => expect(button('btn-confirm')).toBeEnabled())
  await user.click(button('btn-confirm'))
  return screen.findByRole('dialog', { name: '지급 건 확정' })
}

describe('TRAN-W02 React form workflow', () => {
  it('keeps a failed precheck unconfirmed until an explicit retry succeeds', async () => {
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
    mutation = () => {
      throw new TypeError('Failed to fetch')
    }
    await user.click(button('btn-precheck'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to fetch')
    expect(button('btn-confirm')).toBeDisabled()
    expect(screen.getAllByText('사전검증 후 서버 판정이 표시됩니다.')).toHaveLength(6)
    mutation = () => precheckResult
    await user.click(button('btn-precheck'))
    await waitFor(() => expect(button('btn-confirm')).toBeEnabled())
  })
  it('redacts a save 500 in both inline feedback and toast and preserves a retryable draft', async () => {
    const { user } = mount()
    await fillNew(user)
    mutation = () => {
      throw new ApiError({ code: 'FGC-COMMON-500', message: 'SQL password: internal' }, 'SAVE-REQ', 500)
    }
    await user.click(button('btn-save'))
    expect(await screen.findByRole('alert')).toHaveTextContent('요청 ID: SAVE-REQ')
    expect(screen.queryByText(/SQL password/)).not.toBeInTheDocument()
    expect(useToastStore.getState().messages.at(-1)?.message).toBe('요청을 처리하지 못했습니다. 요청 ID: SAVE-REQ')
    expect(button('btn-save')).toBeEnabled()
    expect(button('btn-precheck')).toBeDisabled()
  })
  it('does not render an editable form for a missing draft', async () => {
    referenceFailure = new ApiError(
      { code: 'FGC-COMMON-404', message: '지급 건을 찾을 수 없습니다.' },
      'NOT-FOUND',
      404,
    )
    mount('/app/transactions/new?id=77&month=2026-07')
    expect(await screen.findByRole('alert')).toHaveTextContent('지급 건을 찾을 수 없습니다.')
    expect(button('btn-save')).toBeNull()
    expect(writes()).toHaveLength(0)
  })
  it('reports a server permission rejection without changing the draft state', async () => {
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-save')).toBeEnabled())
    mutation = () => {
      throw new ApiError({ code: 'FGC-AUTH-003', message: '접근 권한이 없습니다.' }, 'FORBIDDEN', 403)
    }
    await user.click(button('btn-save'))
    expect(await screen.findByRole('alert')).toHaveTextContent('접근 권한이 없습니다.')
    expect(screen.getByText('작성중 · #77')).toBeVisible()
    expect(screen.getByLabelText(/업무키/)).toBeEnabled()
  })
  it('hydrates from the fresh draft GET instead of a previous opening cache', async () => {
    draftResponse = { ...saved, amount: 300000 }
    mount('/app/transactions/new?id=77&month=2026-07', true, saved)
    await waitFor(() => expect(screen.getByLabelText(/금액 \(원\)/)).toHaveValue(300000))
  })
  it('requires a visible contract choice when an old draft contract is missing from lookup options', async () => {
    draftResponse = { ...saved, attributions: [{ ...saved.attributions![0], contractId: 999 }] }
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-save')).toBeEnabled())
    await user.click(button('btn-save'))
    expect(await screen.findByText('필수값을 확인하세요: 귀속 계약')).toBeVisible()
    expect(writes()).toHaveLength(0)
  })
  it('registers, locks the draft, previews, confirms once and returns to the list', async () => {
    const { user, router } = mount()
    const dialog = await saveAndPrecheck(user)
    expect(screen.getByLabelText('1번 귀속행 귀속일')).toHaveValue('2026-07-18')
    expect(screen.getByLabelText(/금액 \(원\)/)).toBeDisabled()
    expect(button('btn-add-attr')).toBeDisabled()
    expect(screen.getByText('저장된 초안을 검증하는 동안 입력이 잠겼습니다')).toBeVisible()
    expect(screen.getAllByText(/서버 사전검증 결과 통과했습니다/)).toHaveLength(6)
    let complete: ((value: Payment) => void) | undefined
    mutation = () =>
      new Promise<Payment>((resolve) => {
        complete = resolve
      })
    await user.dblClick(within(dialog).getByRole('button', { name: '확정 실행' }))
    expect(writes().filter(([path]) => path.endsWith('/confirm'))).toHaveLength(1)
    expect(within(dialog).getByRole('button', { name: '취소' })).toBeDisabled()
    complete!({ ...saved, status: 'CONFIRMED' })
    await screen.findByRole('heading', { name: '지급 목록 결과' })
    expect(router.state.location.search).toBe('?month=2026-07')
    expect(writes()[0][1]?.body).toMatchObject({
      sourceType: 'GA_MANUAL_PAYMENT',
      amount: 250000,
      settlementMonth: '2026-07-01',
      attributions: [{ contractId: 3, attributionDate: '2026-07-18', amount: 250000 }],
    })
    expect(writes().at(-1)?.[1]?.idempotencyKey).toBe('TRAN-CONFIRM-77')
  })
  it('retries a lost confirmation response with the exact same idempotency key', async () => {
    const { user } = mount()
    const dialog = await saveAndPrecheck(user)
    let attempts = 0
    mutation = () => {
      if (++attempts === 1) throw new TypeError('Failed to fetch')
      return { ...saved, status: 'CONFIRMED' }
    }
    await user.click(within(dialog).getByRole('button', { name: '확정 실행' }))
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '확정 실행' })).toBeEnabled())
    await user.click(within(dialog).getByRole('button', { name: '확정 실행' }))
    await screen.findByRole('heading', { name: '지급 목록 결과' })
    expect(
      writes()
        .filter(([path]) => path.endsWith('/confirm'))
        .map(([, options]) => options?.idempotencyKey),
    ).toEqual(['TRAN-CONFIRM-77', 'TRAN-CONFIRM-77'])
  })
  it('records a CAP rejection and links the blocker contract; only a real returned CAP ID creates its legacy link', async () => {
    precheckResult = {
      confirmable: false,
      blockers: [{ code: 'FGC-CAP-002', message: '산입 판정 필요', contractId: 7 }],
      capPreview: [],
    }
    const { user } = mount()
    const dialog = await saveAndPrecheck(user)
    expect(within(dialog).queryByRole('link', { name: '계산근거 확인' })).not.toBeInTheDocument()
    expect(screen.getByText('4. 검토필요 귀속 해소 · 차단')).toBeVisible()
    expect(screen.getByText('5. 1,200% 사전검증 · 미확인')).toBeVisible()
    mutation = () => {
      throw new ApiError({ code: 'FGC-CAP-002', message: '검토필요', params: { capCheckId: 99 } }, 'CAP-REQ', 409)
    }
    await user.click(within(dialog).getByRole('button', { name: '확정 시도 · 예외 등록' }))
    const followUp = await within(dialog).findByRole('link', { name: '예외함에서 처리하기' })
    expect(followUp.getAttribute('href')).toContain('type=CAP_REVIEW_REQUIRED&contractNo=C-007')
    expect(within(dialog).getByRole('link', { name: '귀속행 판정 다시 확인' }).getAttribute('href')).toContain(
      '/app/transactions/new?id=77',
    )
    expect(within(dialog).getByRole('link', { name: '계산근거 확인' })).toHaveAttribute(
      'href',
      '/app/cap-checks?capCheckId=99&month=2026-07',
    )
    expect(within(dialog).queryByRole('button', { name: '확정 시도 · 예외 등록' })).not.toBeInTheDocument()
    expect(screen.getByText('작성중 · #77')).toBeVisible()
  })
  it('does not attempt confirmation for an input blocker before a recordable CAP blocker', async () => {
    precheckResult = {
      confirmable: false,
      blockers: [
        { code: 'FGC-TRAN-002', message: '귀속 계약 누락' },
        { code: 'FGC-CAP-002', message: '미분류' },
      ],
    }
    const { user } = mount()
    const dialog = await saveAndPrecheck(user)
    expect(within(dialog).queryByRole('button', { name: /확정 실행|확정 시도/ })).not.toBeInTheDocument()
    expect(writes().filter(([path]) => path.endsWith('/confirm'))).toHaveLength(0)
  })
  it('loads and updates a draft, invalidates stale precheck on edit and preserves server field validation', async () => {
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
    await user.click(button('btn-precheck'))
    await waitFor(() => expect(button('btn-confirm')).toBeEnabled())
    await user.clear(screen.getByLabelText(/업무키/))
    await user.type(screen.getByLabelText(/업무키/), 'DUPLICATE')
    expect(button('btn-precheck')).toBeDisabled()
    expect(button('btn-confirm')).toBeDisabled()
    mutation = () => {
      throw new ApiError({ code: 'FGC-TRAN-004', message: '중복 업무키', field: 'sourceBusinessKey' }, 'DUP-REQ', 409)
    }
    await user.click(button('btn-save'))
    await waitFor(() => expect(screen.getByLabelText(/업무키/)).toHaveAttribute('aria-invalid', 'true'))
    expect(button('btn-save')).toBeEnabled()
    expect(writes().at(-1)?.[1]?.method).toBe('PUT')
    expect(screen.getByLabelText(/업무키/)).toHaveFocus()
  })
  it('requires attribution and evidence but allows an imbalanced draft for server precheck', async () => {
    const { user } = mount()
    await fillNew(user)
    await user.selectOptions(screen.getByLabelText('1번 귀속행 산입 판정'), 'EXCLUDED:NEW_AGENT_SUPPORT')
    await user.click(button('btn-save'))
    expect(await screen.findByText('제외 귀속행은 제외유형과 증빙을 입력해야 합니다.')).toBeVisible()
    expect(writes()).toHaveLength(0)
    await user.type(screen.getByLabelText('1번 귀속행 증빙'), '증빙-1')
    await user.clear(screen.getByLabelText('1번 귀속행 귀속금액'))
    await user.type(screen.getByLabelText('1번 귀속행 귀속금액'), '1')
    expect(screen.getByText('불일치 · 차액 249,999원')).toBeVisible()
    await user.click(button('btn-save'))
    await screen.findByText('저장된 초안을 검증하는 동안 입력이 잠겼습니다')
    expect(writes()).toHaveLength(1)
  })
  it('switches newcomer, manager and insurer modes with legacy clearing behavior', async () => {
    const { user } = mount()
    await fillNew(user)
    await user.selectOptions(screen.getByLabelText(/수수료 항목/), '2')
    expect(screen.queryByLabelText('1번 귀속행 계약번호')).not.toBeInTheDocument()
    expect(screen.getByLabelText('1번 귀속행 산입 판정')).toHaveValue('EXCLUDED:NEW_AGENT_SUPPORT')
    await user.selectOptions(screen.getByLabelText(/수수료 항목/), '1')
    expect(screen.getByLabelText('1번 귀속행 계약번호')).toHaveValue('')
    expect(screen.getByLabelText('1번 귀속행 귀속 방법')).toHaveValue('DIRECT')
    await user.selectOptions(screen.getByLabelText(/수령 설계사/), '2')
    await waitFor(() => expect(button('btn-add-attr')).toBeEnabled())
    expect(screen.getByText('귀속행을 추가하세요.')).toBeVisible()
    expect(
      vi
        .mocked(apiClient.request)
        .mock.calls.filter(([path]) => path.startsWith('/api/v1/contracts'))
        .at(-1)?.[0],
    ).toBe('/api/v1/contracts?page=1&size=100')
    await user.click(button('btn-add-attr'))
    await user.selectOptions(screen.getByLabelText(/지급단계/), 'INSURER_TO_GA')
    expect(screen.queryByLabelText(/수령 설계사/)).not.toBeInTheDocument()
    expect(screen.getByLabelText(/수수료 항목/)).toHaveValue('')
    expect(
      within(screen.getByLabelText(/수수료 항목/)).queryByRole('option', { name: '신인 지원' }),
    ).not.toBeInTheDocument()
    expect(screen.getByText('귀속행을 추가하세요.')).toBeVisible()
  })
  it('blocks lookup failures and viewer writes, and redacts internal 500 details', async () => {
    referenceFailure = new ApiError({ code: 'FGC-COMMON-500', message: 'database secret stack trace' }, 'SAFE-REQ', 500)
    const { user } = mount()
    expect(await screen.findByRole('alert')).toHaveTextContent('요청 ID: SAFE-REQ')
    expect(screen.queryByText(/database secret/)).not.toBeInTheDocument()
    expect(button('btn-save')).toBeDisabled()
    referenceFailure = null
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(button('btn-save')).toBeEnabled())
  })
  it('keeps all processing actions unavailable for a read-only account', async () => {
    mount('/app/transactions/new?id=77&month=2026-07', false)
    await screen.findByText('현재 계정은 조회만 가능하며 저장·사전검증·확정을 실행할 수 없습니다.')
    expect(button('btn-save')).toBeDisabled()
    expect(button('btn-precheck')).toBeDisabled()
    expect(button('btn-confirm')).toBeDisabled()
    expect(writes()).toHaveLength(0)
  })
})
