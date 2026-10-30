import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import ClawbackPage from './ClawbackPage'
import type { CandidateDetail, CandidateRow } from './api'
import { monthRange } from './api'
const statusRow: CandidateRow = {
  candidateId: 42,
  contractId: 10,
  contractNo: 'CLW-001',
  insurerName: '가상생명',
  productName: '종신보험',
  agentName: '김설계',
  triggerType: 'STATUS_EVENT',
  sourceId: 7,
  eventDate: '2026-07-15',
  dateBasisLabel: '효력일',
  contractDate: '2025-01-10',
  sourceVersion: 'claw-v1-abc',
  calculationStatus: 'REVIEW_REQUIRED',
  reviewReasons: ['POLICY_MISSING', 'SCHEDULE_MISSING'],
  paidAmount: 1200000,
  unpaidAmount: null,
  policyVersionId: null,
  clawbackCaseId: null,
  calculatedAmount: null,
  allowedActions: [],
}
const arbitrageRow: CandidateRow = {
  ...statusRow,
  candidateId: 41,
  contractNo: 'CLW-002',
  triggerType: 'ARBITRAGE',
  dateBasisLabel: '검증기준일',
  eventDate: '2026-07-31',
  calculationStatus: 'UNCALCULATED',
  paidAmount: 0,
}
const zeroRow: CandidateRow = { ...statusRow, candidateId: 40, calculationStatus: 'CALCULATED', calculatedAmount: 0 }
const detail: CandidateDetail = {
  candidate: statusRow,
  sourceRefs: [
    { triggerType: 'STATUS_EVENT', sourceId: 7, effectiveAt: '2026-07-15T00:00:00+09:00', status: 'LAPSED' },
  ],
  paymentAttributions: [
    {
      transactionAttributionId: 900,
      commissionTransactionId: 800,
      commissionItemId: 3,
      installmentNo: 2,
      paidOn: '2026-06-25',
      status: 'PAID',
      attributedAmount: 1200000,
    },
  ],
  scheduleLines: [],
  effects: [{ processingJob: 'HOLD', processingStatus: 'APPLIED', processedAt: '2026-07-16T09:00:00+09:00' }],
  policyCandidates: [],
  elapsedMonths: null,
  latestCalculation: undefined,
}
const envelope = (data: unknown) => ({ data, error: null, requestId: 'claw-test' })
const page = (content: CandidateRow[]) => envelope({ content, page: 1, totalPages: 1, totalElements: content.length })
function fixtures(path: string) {
  const url = new URL(path, 'http://test')
  if (url.pathname.endsWith('/insurers'))
    return envelope({
      content: [{ insurerId: 1, insurerCode: 'INS1', insurerName: '가상생명' }],
      page: 1,
      totalPages: 1,
    })
  if (url.pathname.endsWith('/candidates/42')) return envelope(detail)
  if (url.pathname.endsWith('/candidates')) return page([statusRow, arbitrageRow, zeroRow])
  return envelope({ content: [], page: 1, totalPages: 1 })
}
function mount(path = '/clawbacks', responder: (path: string) => unknown = fixtures) {
  const request = vi.spyOn(apiClient, 'request').mockImplementation(async (p) => {
    const value = responder(p)
    if (value instanceof Error) throw value
    return value as never
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const router = createMemoryRouter(
    [
      {
        element: <Outlet context={{ month: '2026-07', user: { canProcess: true } }} />,
        children: [{ path: '/clawbacks', element: <ClawbackPage /> }],
      },
    ],
    { initialEntries: [path] },
  )
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { request, router }
}
const candidateCalls = (request: { mock: { calls: unknown[][] } }) =>
  request.mock.calls
    .map(([p]) => new URL(String(p), 'http://test'))
    .filter((u: URL) => u.pathname.endsWith('/candidates'))
afterEach(() => vi.restoreAllMocks())

it('defaults to the event-date basis for the work month', async () => {
  const { request } = mount()
  await screen.findByRole('table', { name: '검색 조건에 해당하는 환수 검토 후보' })
  const url = candidateCalls(request)[0]
  expect(url.searchParams.get('dateBasis')).toBe('EVENT_DATE')
  expect(url.searchParams.get('dateFrom')).toBe('2026-07-01')
  expect(url.searchParams.get('dateTo')).toBe('2026-07-31')
  expect(monthRange('2026-02')).toEqual({ dateFrom: '2026-02-01', dateTo: '2026-02-28' })
})

it('keeps uncalculated, review-required, missing and real 0 won apart', async () => {
  mount()
  const table = await screen.findByRole('table', { name: '검색 조건에 해당하는 환수 검토 후보' })
  const rowOf = (id: string) => within(table).getByText(id).closest('tr')!
  expect(within(rowOf('42')).getAllByText('검토필요')).toHaveLength(2)
  expect(within(rowOf('42')).getByText('자료 없음')).toBeInTheDocument()
  expect(within(rowOf('41')).getAllByText('미계산')).toHaveLength(2)
  expect(within(rowOf('41')).getByText('0원')).toBeInTheDocument()
  expect(within(rowOf('41')).getByText(/검증기준일/)).toBeInTheDocument()
  expect(within(rowOf('40')).getByText('0원')).toBeInTheDocument()
})

it('applies filters only on 후보 조회 and resets to defaults', async () => {
  const user = userEvent.setup()
  const { request, router } = mount()
  await screen.findByRole('table', { name: '검색 조건에 해당하는 환수 검토 후보' })
  await user.selectOptions(screen.getByLabelText('발생 사유'), 'ARBITRAGE')
  await user.type(screen.getByLabelText('계약번호'), ' CLW ')
  expect(candidateCalls(request)).toHaveLength(1)
  await user.click(screen.getByRole('button', { name: /조회/ }))
  await waitFor(() => expect(router.state.location.search).toContain('triggerType=ARBITRAGE'))
  const last = candidateCalls(request).at(-1)!
  expect(last.searchParams.get('contractNo')).toBe('CLW')
  await user.click(screen.getByRole('button', { name: /초기화/ }))
  await waitFor(() => expect(router.state.location.search).not.toContain('triggerType'))
  expect(screen.getByLabelText('발생 사유')).toHaveValue('')
})

it('blocks an inverted period without calling the server', async () => {
  const user = userEvent.setup()
  const { request } = mount()
  await screen.findByRole('table', { name: '검색 조건에 해당하는 환수 검토 후보' })
  const to = screen.getByLabelText('종료일')
  await user.clear(to)
  await user.type(to, '2026-06-01')
  await user.click(screen.getByRole('button', { name: /조회/ }))
  expect(screen.getByText('종료일이 시작일보다 빠릅니다.')).toBeInTheDocument()
  expect(candidateCalls(request)).toHaveLength(1)
})

it('shows source, payment and hold evidence with calculation locked by allowedActions', async () => {
  mount()
  const evidence = await screen.findByRole('region', { name: '원천 사건 · 검증 근거' })
  expect(within(evidence).getByText('상태 사건')).toBeInTheDocument()
  expect(within(screen.getByRole('region', { name: '연결 지급 · 귀속' })).getByText('900')).toBeInTheDocument()
  expect(within(screen.getByRole('region', { name: '보류 · 후속 처리 근거' })).getByText('HOLD')).toBeInTheDocument()
  expect(screen.getByText('유효한 환수 정책 없음', { selector: 'li' })).toBeInTheDocument()
  // D-02 이후 경과월 null은 사건 기준일이 계약일보다 앞설 때뿐이다.
  expect(screen.getByText('계산 불가 (사건 기준일이 계약일보다 앞섬)')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '환수액 계산' })).toBeDisabled()
})

it('distinguishes an empty result from a failed search', async () => {
  mount('/clawbacks', (p) => (p.includes('/candidates') ? page([]) : fixtures(p)))
  expect(await screen.findByText('조건에 맞는 환수 검토 대상이 없습니다.')).toBeInTheDocument()
  cleanup()
  vi.restoreAllMocks()
  mount('/clawbacks', (p) =>
    p.includes('/candidates')
      ? new ApiError({ code: 'FGC-COMMON-500', message: '서버 오류' } as never, 'req-9', 500)
      : fixtures(p),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent('req-9')
  expect(screen.queryByText('조건에 맞는 환수 검토 대상이 없습니다.')).not.toBeInTheDocument()
})

it('keeps cleared dates as no period limit instead of falling back to the work month', async () => {
  const user = userEvent.setup()
  const { request, router } = mount()
  await screen.findByRole('table', { name: '검색 조건에 해당하는 환수 검토 후보' })
  await user.clear(screen.getByLabelText('시작일'))
  await user.clear(screen.getByLabelText('종료일'))
  expect(screen.getByText('비우면 기간 제한 없음')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: /조회/ }))
  await waitFor(() => expect(router.state.location.search).toContain('dateFrom=&dateTo='))
  await waitFor(() => expect(candidateCalls(request)).toHaveLength(2))
  const last = candidateCalls(request).at(-1)!
  expect(last.searchParams.has('dateFrom')).toBe(false)
  expect(last.searchParams.has('dateTo')).toBe(false)
  expect(screen.getByLabelText('시작일')).toHaveValue('')
  expect(screen.getByLabelText('종료일')).toHaveValue('')
})

it('shows a single policy candidate as a candidate, not as the applied policy', async () => {
  const single = { ...detail, candidate: { ...statusRow, policyVersionId: 15 } }
  mount('/clawbacks', (p) => (p.includes('/candidates/42') ? envelope(single) : fixtures(p)))
  expect(await screen.findByText('후보 1개 · 정책버전 15 (적용 선택 전)')).toBeInTheDocument()
  expect(screen.getByText('정책 후보')).toBeInTheDocument()
  expect(screen.queryByText('적용 정책')).not.toBeInTheDocument()
})

it('links evidence to the source contract, arbitrage check and payment', async () => {
  const arbitrageDetail: CandidateDetail = {
    ...detail,
    candidate: { ...arbitrageRow, contractId: 11 },
    sourceRefs: [{ triggerType: 'ARBITRAGE', sourceId: 5, asOfDate: '2026-07-31', status: 'CANDIDATE' }],
  }
  const user = userEvent.setup()
  mount('/clawbacks', (p) => (p.includes('/candidates/41') ? envelope(arbitrageDetail) : fixtures(p)))
  const payments = await screen.findByRole('region', { name: '연결 지급 · 귀속' })
  expect(within(payments).getByRole('link', { name: '800' })).toHaveAttribute(
    'href',
    '/transactions?contractNo=CLW-001&selectedId=800',
  )
  const sources = screen.getByRole('region', { name: '원천 사건 · 검증 근거' })
  expect(within(sources).getByRole('link', { name: '계약 사건 이력' })).toHaveAttribute('href', '/contracts/10')
  const table = screen.getByRole('table', { name: '검색 조건에 해당하는 환수 검토 후보' })
  await user.click(within(table).getByText('41'))
  const arbitrage = await screen.findByRole('link', { name: '차익거래 검증' })
  expect(arbitrage).toHaveAttribute('href', '/arbitrage-checks?month=2026-07&contractNo=CLW-002')
})

it('keeps the calculate button off until the calculation form is wired even when the server allows it', async () => {
  const allowed = {
    ...detail,
    candidate: { ...statusRow, allowedActions: ['CALCULATE'], reviewReasons: ['CALCULATION_STALE'] },
  }
  mount('/clawbacks', (p) => (p.includes('/candidates/42') ? envelope(allowed) : fixtures(p)))
  expect(await screen.findByText('계산 후 원천이 바뀌어 다시 계산 필요', { selector: 'li' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '환수액 계산' })).toBeDisabled()
  expect(screen.getByText(/계산할 수 있는 후보입니다/)).toBeInTheDocument()
})
