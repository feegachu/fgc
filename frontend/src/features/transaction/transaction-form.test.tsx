import { selectOption } from '../../test/selectOption'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
let contractDetailFailure = false
beforeEach(() => {
  precheckResult = { confirmable: true, capPreview: [], blockers: [] }
  referenceFailure = null
  draftResponse = saved
  contractDetailFailure = false
  mutation = (path) => (path.endsWith('/precheck') ? precheckResult : saved)
  useToastStore.setState({ messages: [] })
  vi.spyOn(apiClient, 'request').mockImplementation((async (path: string, options: RequestOptions = {}) => {
    if (options.method && options.method !== 'GET') return envelope(await mutation(path, options))
    if (referenceFailure) throw referenceFailure
    if (path.startsWith('/api/v1/base/agents')) return envelope({ content: agents })
    if (path.startsWith('/api/v1/base/commission-items/search'))
      return envelope({
        content: items.filter(
          (item) =>
            item.cashflowType === (new URL(path, 'http://local').searchParams.get('cashflowType') || item.cashflowType),
        ),
      })
    if (path.startsWith('/api/v1/base/commission-items')) return envelope(items)
    if (path === '/api/v1/contracts/103') {
      if (contractDetailFailure)
        throw new ApiError({ code: 'FGC-COMMON-404', message: '계약 조회 실패' }, 'LOOKUP', 404)
      return envelope({ contractId: 103, contractNo: 'C-103' })
    }
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
  const month = new URL(path, 'http://local').searchParams.get('month') ?? '2026-07'
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  if (cached) client.setQueryData(draftQuery('77').queryKey, cached)
  const router = createMemoryRouter(
    [
      {
        element: <Outlet context={{ user: { canProcess }, month }} />,
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
  await selectOption(user, screen.getByLabelText(/수령 설계사/), '1')
  await waitFor(() => expect(button('btn-add-attr')).toBeEnabled())
  await selectOption(user, screen.getByLabelText(/수수료 항목/), '1')
  await user.click(button('btn-add-attr'))
  await selectOption(user, screen.getByLabelText('1번 귀속행 계약번호'), '3')
  await user.type(screen.getByLabelText('1번 귀속행 귀속일'), '2026-07-18')
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
  it('수동 신규 첫 행과 추가 행 모두 실제 귀속일 입력 전에는 저장하지 않는다', async () => {
    const { user } = mount()
    await waitFor(() => expect(button('btn-save')).toBeEnabled())
    expect(screen.getByLabelText('1번 귀속행 귀속일')).toHaveValue('')
    await selectOption(user, screen.getByLabelText(/수령 설계사/), '1')
    await selectOption(user, screen.getByLabelText(/수수료 항목/), '1')
    await user.click(button('btn-add-attr'))
    await selectOption(user, screen.getByLabelText('1번 귀속행 계약번호'), '3')
    const date = screen.getByLabelText('1번 귀속행 귀속일')
    expect(date).toHaveValue('')
    await user.click(button('btn-save'))
    expect(date).toHaveAttribute('aria-invalid', 'true')
    expect(writes()).toHaveLength(0)
  })
  it('회차 없는 계약 맥락은 귀속일을 비워 두고 입력 전 저장을 차단한다', async () => {
    const { user } = mount(
      '/app/transactions/new?month=2027-07&contractId=3&agentId=1&commissionItemId=1',
    )
    await waitFor(() => expect(button('btn-save')).toBeEnabled())
    const date = screen.getByLabelText('1번 귀속행 귀속일')
    expect(date).toHaveValue('')
    await user.click(button('btn-save'))
    expect(date).toHaveAttribute('aria-invalid', 'true')
    expect(date).toHaveFocus()
    expect(writes()).toHaveLength(0)
    fireEvent.change(document.getElementById('settlementMonth')!, { target: { value: '2027-08' } })
    expect(date).toHaveValue('')
  })
  it.each(['2027-07-17', '2027-07-18'])(
    '계약 12개월 경계의 실제 귀속일 %s를 정산월과 별도로 저장한다',
    async (attributionDate) => {
      const { user } = mount(
        '/app/transactions/new?month=2027-07&contractId=3&agentId=1&commissionItemId=1',
      )
      await waitFor(() => expect(button('btn-save')).toBeEnabled())
      await user.type(screen.getByLabelText('1번 귀속행 귀속일'), attributionDate)
      await user.click(button('btn-save'))
      await waitFor(() => expect(writes()).toHaveLength(1))
      expect(writes()[0][1]?.body).toMatchObject({
        settlementMonth: '2027-07-01',
        attributions: [expect.objectContaining({ contractId: 3, attributionDate })],
      })
    },
  )
  it('이미 저장된 초안은 URL의 새 지급 맥락으로 덮어쓰지 않는다', async () => {
    mount(
      '/app/transactions/new?id=77&month=2026-07&contractId=7&agentId=2&commissionItemId=3&amount=1&cashflowType=DEDUCTION',
    )
    await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
    expect(screen.getByLabelText('1번 귀속행 계약번호')).toHaveValue('C-003 · 상품')
    expect(document.getElementById('amount')).toHaveValue(250000)
  })
  it('계약 상세에서 전달한 맥락으로 실제 선택값을 채우고 저장한다', async () => {
    const { user } = mount(
      '/app/transactions/new?month=2026-07&contractId=3&agentId=1&commissionItemId=1&attributionDate=2026-07-20&amount=150000&returnTo=%2Fcontracts%2F3',
    )
    await waitFor(() => expect(button('btn-save')).toBeEnabled())
    expect(screen.getByLabelText('1번 귀속행 계약번호')).toHaveValue('C-003 · 상품')
    expect(screen.getByLabelText('1번 귀속행 귀속일')).toHaveValue('2026-07-20')
    expect(
      vi
        .mocked(apiClient.request)
        .mock.calls.some(
          ([path]) =>
            path.startsWith('/api/v1/contracts?') && new URL(path, 'http://local').searchParams.get('agentId') === '1',
        ),
    ).toBe(true)
    await user.click(button('btn-save'))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0][1]?.body).toMatchObject({
      agentId: 1,
      commissionItemId: 1,
      amount: 150000,
      attributions: [expect.objectContaining({ contractId: 3, attributionDate: '2026-07-20' })],
    })
    expect(screen.getByRole('link', { name: '목록으로' })).toHaveAttribute('href', '/app/contracts/3')
  })
  it('shows a non-applicable precheck reason without a usage gauge or numeric amounts', async () => {
    precheckResult = {
      confirmable: true,
      blockers: [],
      capPreview: [
        {
          contractId: 3,
          contractNo: 'C-003',
          paymentStage: 'GA_TO_FC',
          paymentStageLabel: 'GA → 설계사',
          resultStatus: 'NOT_APPLICABLE',
          resultStatusLabel: '적용대상 아님',
          limitAmount: null,
          includedAmount: null,
          remainingAmount: null,
          candidateAmount: null,
          existingIncludedAmount: null,
          usagePct: null,
          applicabilityReasonCode: 'CONTRACT_BEFORE_EFFECTIVE_DATE',
          applicabilityReason: '적용대상 아님 — 시행 전 계약',
        },
      ],
    }
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
    await user.click(button('btn-precheck'))
    const panel = screen.getByRole('region', { name: '1,200% 사전검증' })
    expect(await within(panel).findByText('적용대상 아님 — 시행 전 계약')).toBeVisible()
    expect(within(panel).getByText('사유 코드: CONTRACT_BEFORE_EFFECTIVE_DATE')).toBeVisible()
    expect(within(panel).getByText('적용대상 아님')).toHaveClass('status-badge-neutral')
    expect(within(panel).queryByRole('progressbar')).not.toBeInTheDocument()
    expect(within(panel).queryByText('이번 건 산입액')).not.toBeInTheDocument()
    expect(button('btn-confirm')).toBeEnabled()
  })

  it.each([false, true])('shows outside-first-year reasons alongside in-period previews: %s', async (mixed) => {
    precheckResult = {
      confirmable: true,
      blockers: [],
      capPreview: mixed
        ? [
            {
              contractId: 3,
              contractNo: 'C-003',
              paymentStage: 'GA_TO_FC',
              resultStatus: 'NORMAL',
              resultStatusLabel: '정상',
              usagePct: '10.0',
              candidateAmount: 100000,
            },
          ]
        : [],
      capExclusions: [
        {
          transactionAttributionId: 201,
          contractId: 3,
          paymentStage: 'GA_TO_FC',
          attributionDate: '2027-07-18',
          firstYearStartInclusive: '2026-07-18',
          firstYearEndExclusive: '2027-07-18',
          excludedAmount: 200000,
          resultStatus: 'NOT_APPLICABLE',
          reasonCode: 'OUTSIDE_FIRST_YEAR',
          reason: '초년도 밖 — 판정 대상 아님',
        },
      ],
    }
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
    await user.click(button('btn-precheck'))
    const panel = screen.getByRole('region', { name: '1,200% 사전검증' })
    expect(await within(panel).findByText('초년도 밖 — 판정 대상 아님')).toBeVisible()
    expect(within(panel).getByText('사유 코드: OUTSIDE_FIRST_YEAR')).toBeVisible()
    expect(within(panel).getByText('200,000원')).toBeVisible()
    expect(within(panel).getByText(/귀속일 2027-07-18/)).toBeVisible()
    expect(within(panel).getByText(/초년도 종료일 2027-07-18/)).toBeVisible()
    expect(within(panel).queryByText('표시할 1,200% 검증 결과가 없습니다.')).not.toBeInTheDocument()
    expect(within(panel).queryAllByRole('progressbar')).toHaveLength(mixed ? 1 : 0)
    expect(button('btn-confirm')).toBeEnabled()
  })

  it('shows conflicting payment dates as a blocker instead of a valid exclusion', async () => {
    const message =
      '초년도 밖 귀속은 지급예정일도 초년도 종료일 이후여야 합니다. 귀속일: 2027-07-18, 지급예정일: 2026-07-31, 초년도 종료일(미포함): 2027-07-18'
    precheckResult = {
      confirmable: false,
      capPreview: [],
      capExclusions: [],
      blockers: [{ code: 'FGC-CAP-002', message, contractId: 3, transactionAttributionId: 201 }],
    }
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
    await user.click(button('btn-precheck'))
    expect(await screen.findByText(message)).toBeVisible()
    expect(screen.queryByText('초년도 밖 — 판정 대상 아님')).not.toBeInTheDocument()
    expect(screen.getByText('4. 검토필요 귀속 해소 · 차단')).toBeVisible()
  })

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
    await selectOption(user, screen.getByLabelText('1번 귀속행 산입 판정'), 'EXCLUDED:NEW_AGENT_SUPPORT')
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
    await selectOption(user, screen.getByLabelText(/수수료 항목/), '2')
    expect(screen.queryByLabelText('1번 귀속행 계약번호')).not.toBeInTheDocument()
    expect(screen.getByLabelText('1번 귀속행 산입 판정')).toHaveValue('EXCLUDED:NEW_AGENT_SUPPORT')
    await selectOption(user, screen.getByLabelText(/수수료 항목/), '1')
    expect(screen.getByLabelText('1번 귀속행 계약번호')).toHaveValue('')
    expect(screen.getByLabelText('1번 귀속행 귀속 방법')).toHaveValue('DIRECT')
    await selectOption(user, screen.getByLabelText(/수령 설계사/), '2')
    await waitFor(() => expect(button('btn-add-attr')).toBeEnabled())
    expect(screen.getByText('귀속행을 추가하세요.')).toBeVisible()
    expect(
      vi
        .mocked(apiClient.request)
        .mock.calls.filter(([path]) => path.startsWith('/api/v1/contracts'))
        .at(-1)?.[0],
    ).toBe('/api/v1/contracts?page=1&size=20')
    await user.click(button('btn-add-attr'))
    await selectOption(user, screen.getByLabelText(/지급단계/), 'INSURER_TO_GA')
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

describe('TRAN-W02 legacy defect regressions (#410)', () => {
  it.each(['network', '500', 'empty response'])(
    'reopens confirmation after %s failure with the same key without losing recovery to precheck',
    async (kind) => {
      const { user } = mount()
      let dialog = await saveAndPrecheck(user)
      mutation = () => {
        if (kind === 'network') throw new TypeError('Failed to fetch')
        if (kind === 'empty response') return null
        throw new ApiError({ code: 'FGC-COMMON-500', message: 'private error' }, 'CONFIRM-REQ', 500)
      }
      await user.click(within(dialog).getByRole('button', { name: '확정 실행' }))
      await waitFor(() => expect(within(dialog).getByRole('button', { name: '확정 실행' })).toBeEnabled())
      await user.click(within(dialog).getByRole('button', { name: '취소' }))
      expect(button('btn-confirm')).toBeEnabled()
      expect(button('btn-precheck')).toBeDisabled()
      expect(button('btn-save')).toBeDisabled()
      expect(screen.getByLabelText(/업무키/)).toBeDisabled()
      await user.click(button('btn-precheck'))
      expect(writes().filter(([path]) => path.endsWith('/precheck'))).toHaveLength(1)
      expect(screen.getByText(/확정 창을 다시 열어/)).toBeVisible()
      mutation = () => ({ ...saved, status: 'CONFIRMED' })
      await user.click(button('btn-confirm'))
      dialog = await screen.findByRole('dialog', { name: '지급 건 확정' })
      await user.click(within(dialog).getByRole('button', { name: '확정 실행' }))
      await screen.findByRole('heading', { name: '지급 목록 결과' })
      expect(
        writes()
          .filter(([path]) => path.endsWith('/confirm'))
          .map(([, options]) => options?.idempotencyKey),
      ).toEqual(['TRAN-CONFIRM-77', 'TRAN-CONFIRM-77'])
      expect(writes().filter(([path]) => path === '/api/v1/transactions')).toHaveLength(1)
    },
  )
  it('requires a fresh precheck after a business rejection rather than replaying it', async () => {
    const { user } = mount()
    const dialog = await saveAndPrecheck(user)
    mutation = () => {
      throw new ApiError({ code: 'FGC-TRAN-005', message: '확정 불가' }, 'REJECT', 409)
    }
    await user.click(within(dialog).getByRole('button', { name: '확정 실행' }))
    await within(dialog).findByText(/FGC-TRAN-005/)
    expect(within(dialog).queryByRole('button', { name: '확정 실행' })).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '취소' }))
    expect(button('btn-confirm')).toBeDisabled()
    expect(button('btn-precheck')).toBeEnabled()
    mutation = () => precheckResult
    await user.click(button('btn-precheck'))
    await waitFor(() => expect(button('btn-confirm')).toBeEnabled())
  })
  it.each(['preview', 'detail', 'lookup failure'])(
    'retains the exception link for an out-of-page contract using %s',
    async (source) => {
      draftResponse = {
        ...saved,
        paymentStage: 'INSURER_TO_GA',
        agentId: undefined,
        attributions: [{ ...saved.attributions![0], contractId: 103 }],
      }
      const code = source === 'preview' ? 'FGC-CAP-001' : 'FGC-CAP-002'
      precheckResult = {
        confirmable: false,
        blockers: [{ code, message: '계약 차단', contractId: 103 }],
        capPreview: source === 'preview' ? [{ contractId: 103, contractNo: 'C-103' }] : [],
      }
      contractDetailFailure = source === 'lookup failure'
      const { user } = mount('/app/transactions/new?id=77&month=2026-07')
      await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
      await user.click(button('btn-precheck'))
      await waitFor(() => expect(button('btn-confirm')).toBeEnabled())
      await user.click(button('btn-confirm'))
      const dialog = await screen.findByRole('dialog', { name: '지급 건 확정' })
      expect(within(dialog).queryByRole('link', { name: '예외함에서 처리하기' })).not.toBeInTheDocument()
      mutation = () => {
        throw new ApiError({ code, message: '계약 차단', params: {} }, 'CAP', 422)
      }
      await user.click(within(dialog).getByRole('button', { name: '확정 시도 · 예외 등록' }))
      const link = await within(dialog).findByRole('link', { name: '예외함에서 처리하기' })
      if (source !== 'lookup failure')
        await waitFor(() => expect(link.getAttribute('href')).toContain('contractNo=C-103'))
      else expect(link.getAttribute('href')).toContain('type=CAP_REVIEW_REQUIRED')
      if (source === 'preview')
        expect(
          vi.mocked(apiClient.request).mock.calls.filter(([path]) => path === '/api/v1/contracts/103'),
        ).toHaveLength(1)
      if (source === 'lookup failure')
        await waitFor(() =>
          expect(vi.mocked(apiClient.request)).toHaveBeenCalledWith('/api/v1/contracts/103', expect.anything()),
        )
      expect(link.getAttribute('href')).not.toContain('C-003')
      expect(within(dialog).queryByRole('link', { name: '계산근거 확인' })).not.toBeInTheDocument()
    },
  )
  it('displays an empty saved draft as empty and uses the server missing-attribution blocker', async () => {
    draftResponse = { ...saved, attributions: [] }
    precheckResult = { confirmable: false, blockers: [{ code: 'FGC-TRAN-002', message: '귀속행 없음' }] }
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
    expect(screen.queryByLabelText('1번 귀속행 계약번호')).not.toBeInTheDocument()
    await user.click(button('btn-precheck'))
    await waitFor(() => expect(button('btn-confirm')).toBeEnabled())
    await user.click(button('btn-confirm'))
    const dialog = await screen.findByRole('dialog', { name: '지급 건 확정' })
    expect(within(dialog).getByText(/귀속행 없음/)).toBeVisible()
    expect(within(dialog).queryByRole('button', { name: /확정 실행|확정 시도/ })).not.toBeInTheDocument()
    expect(writes().filter(([path]) => !path.endsWith('/precheck'))).toHaveLength(0)
  })
  it('requires saving silently normalized draft values and then enables precheck for the stored values', async () => {
    draftResponse = {
      ...saved,
      paymentStage: 'INSURER_TO_GA',
      agentId: undefined,
      attributions: [
        {
          ...saved.attributions![0],
          inclusionDecisionStatus: 'EXCLUDED',
          exclusionType: 'NEW_AGENT_SUPPORT',
          evidenceRef: 'EVIDENCE',
        },
      ],
    }
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-save')).toBeEnabled())
    expect(screen.getByLabelText('1번 귀속행 산입 판정')).toHaveValue('INCLUDED')
    expect(button('btn-precheck')).toBeDisabled()
    expect(button('btn-confirm')).toBeDisabled()
    expect(screen.getByText(/현재 지급 단계·항목에 맞춰 귀속 입력을 조정/)).toBeVisible()
    mutation = (_path, options) => ({ ...draftResponse, ...(options.body as object) })
    await user.click(button('btn-save'))
    await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
    expect(writes()[0][0]).toBe('/api/v1/transactions/77')
    expect(writes()[0][1]?.body).toMatchObject({
      paymentStage: 'INSURER_TO_GA',
      agentId: null,
      attributions: [{ inclusionDecisionStatus: 'INCLUDED', attributionMethod: 'DIRECT' }],
    })
    expect(screen.queryByText(/귀속 입력을 조정/)).not.toBeInTheDocument()
  })
})

describe('CAP rejection recovery and resolved transport failures (#410)', () => {
  it('links the existing violation after a lost CAP-001 rejection is retried and returns CAP-003', async () => {
    precheckResult = {
      confirmable: false,
      blockers: [{ code: 'FGC-CAP-001', contractId: 7, message: '한도 위반 — 사용률 104.166667%' }],
      capPreview: [{ contractId: 7, contractNo: 'C-007', usagePct: '104.166667', resultStatus: 'VIOLATION' }],
    }
    const { user } = mount()
    let dialog = await saveAndPrecheck(user)
    expect(document.getElementById('gate-list')).toHaveTextContent('한도 위반 — 사용률 104.1%')
    expect(within(dialog).getByText('FGC-CAP-001 · 한도 위반 — 사용률 104.1%')).toBeVisible()
    const progress = document.querySelector('.transaction-cap-progress')
    expect(progress).toHaveAttribute('aria-label', '한도 사용률 104.1%')
    expect(progress).toHaveAttribute('value', '100')
    expect(screen.queryByText(/104\.166667%/)).not.toBeInTheDocument()
    mutation = () => {
      throw new TypeError('Lost rejection response')
    }
    await user.click(within(dialog).getByRole('button', { name: '확정 시도 · 예외 등록' }))
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '확정 시도 · 예외 등록' })).toBeEnabled())
    await user.click(within(dialog).getByRole('button', { name: '취소' }))
    mutation = () => {
      throw new ApiError({ code: 'FGC-CAP-003', message: '미해결 위반 존재' }, 'EXISTING', 422)
    }
    await user.click(button('btn-confirm'))
    dialog = await screen.findByRole('dialog', { name: '지급 건 확정' })
    await user.click(within(dialog).getByRole('button', { name: '확정 시도 · 예외 등록' }))
    const link = await within(dialog).findByRole('link', { name: '예외함에서 처리하기' })
    expect(link.getAttribute('href')).toContain('type=CAP_VIOLATION')
    expect(link.getAttribute('href')).not.toContain('contractNo=')
    expect(within(dialog).queryByRole('button', { name: '확정 시도 · 예외 등록' })).not.toBeInTheDocument()
    expect(
      writes()
        .filter(([path]) => path.endsWith('/confirm'))
        .map(([, args]) => args?.idempotencyKey),
    ).toEqual(['TRAN-CONFIRM-77', 'TRAN-CONFIRM-77'])
    await user.click(within(dialog).getByRole('button', { name: '취소' }))
    precheckResult = {
      confirmable: false,
      blockers: [{ code: 'FGC-CAP-003', message: '미해결 위반 존재' }],
      capPreview: [],
    }
    mutation = () => precheckResult
    await user.click(button('btn-precheck'))
    await waitFor(() => expect(button('btn-confirm')).toBeEnabled())
    await user.click(button('btn-confirm'))
    expect(await screen.findByRole('link', { name: '예외함에서 처리하기' })).toHaveAttribute(
      'href',
      '/app/exceptions?type=CAP_VIOLATION&month=2026-07',
    )
  })
  it('unlocks a reopened draft after the explicit retry returns a business rejection', async () => {
    const { user } = mount('/app/transactions/new?id=77&month=2026-07')
    await waitFor(() => expect(button('btn-precheck')).toBeEnabled())
    await user.click(button('btn-precheck'))
    await waitFor(() => expect(button('btn-confirm')).toBeEnabled())
    await user.click(button('btn-confirm'))
    let dialog = await screen.findByRole('dialog', { name: '지급 건 확정' })
    mutation = () => {
      throw new ApiError({ code: 'FGC-COMMON-500', message: 'internal' }, 'FAILED', 500)
    }
    await user.click(within(dialog).getByRole('button', { name: '확정 실행' }))
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '확정 실행' })).toBeEnabled())
    await user.click(within(dialog).getByRole('button', { name: '취소' }))
    expect(screen.getByLabelText(/업무키/)).toBeDisabled()
    mutation = () => {
      throw new ApiError({ code: 'FGC-TRAN-004', message: '증빙 필요' }, 'REJECT', 422)
    }
    await user.click(button('btn-confirm'))
    dialog = await screen.findByRole('dialog', { name: '지급 건 확정' })
    await user.click(within(dialog).getByRole('button', { name: '확정 실행' }))
    await within(dialog).findByText(/FGC-TRAN-004/)
    await user.click(within(dialog).getByRole('button', { name: '취소' }))
    expect(screen.getByLabelText(/업무키/)).toBeEnabled()
    expect(button('btn-save')).toBeEnabled()
    expect(button('btn-precheck')).toBeEnabled()
    expect(button('btn-confirm')).toBeDisabled()
  })
})
