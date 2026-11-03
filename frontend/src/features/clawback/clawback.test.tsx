import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import type { RequestOptions } from '../../lib/api/client'
import { useToastStore } from '../../stores/toasts'
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
  effects: [{ processingJob: 'DailyChangedContractJob', processingStatus: 'SUCCEEDED', processedAt: '2026-07-16T09:00:00+09:00' }],
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
  if (url.pathname.endsWith('/base/commission-items'))
    return envelope([{ commissionItemId: 3, itemCode: 'BASE_COMMISSION', itemName: '초회 수수료' }])
  return envelope({ content: [], page: 1, totalPages: 1 })
}
type Responder = (path: string, options?: RequestOptions) => unknown
function mount(path = '/clawbacks', responder: Responder = fixtures) {
  const request = vi.spyOn(apiClient, 'request').mockImplementation(async (p, options) => {
    const value = responder(p, options)
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
  expect(within(rowOf('42')).getByText('-')).toBeInTheDocument()
  expect(within(rowOf('41')).getAllByText('미계산')).toHaveLength(2)
  expect(within(rowOf('41')).getByText('0원')).toBeInTheDocument()
  expect(within(rowOf('41')).getByText('차익거래')).toBeInTheDocument()
  expect(within(rowOf('41')).queryByText(/검증기준일/)).not.toBeInTheDocument()
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
  const evidence = await screen.findByRole('region', { name: '원천 사건, 검증 근거' })
  expect(within(evidence).getByText('상태 사건')).toBeInTheDocument()
  const payment = screen.getByRole('region', { name: '연결 지급, 귀속' })
  expect(within(payment).getByText('900')).toBeInTheDocument()
  // 수수료 항목은 내부 번호(3) 대신 기준정보의 이름으로 보인다.
  expect(await within(payment).findByText('초회 수수료')).toBeInTheDocument()
  expect(within(screen.getByRole('region', { name: '보류, 후속 처리 근거' })).getByText('일일 변경 계약 재검증')).toBeInTheDocument()
  expect(screen.getByText('유효한 환수 정책 없음', { selector: 'li' })).toBeInTheDocument()
  // D-02 이후 경과월 null은 사건 기준일이 계약일보다 앞설 때뿐이다.
  expect(screen.getByText('계산 불가 (사건 기준일이 계약일보다 앞섬)')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '환수액 계산' })).toBeDisabled()
  expect(screen.getByText('계산할 수 없는 후보입니다. 위 검토 사유를 확인하세요.')).toBeInTheDocument()
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
  const single = {
    ...detail,
    candidate: { ...statusRow, policyVersionId: 15 },
    policyCandidates: [
      { policyVersionId: 15, policyCode: 'CLAW-GA-VIRTUAL-2026', versionNo: 1, policyName: '가상 GA 표준 환수율', effectiveFrom: '2026-01-01' },
    ],
  }
  mount('/clawbacks', (p) => (p.includes('/candidates/42') ? envelope(single) : fixtures(p)))
  // 내부 정책버전 번호 대신 정책 코드와 판으로 보이고, 계산에서 확인하기 전이라는 뜻을 남긴다.
  expect(await screen.findByText('CLAW-GA-VIRTUAL-2026 v1, 아래 환수 계산에서 확인')).toBeInTheDocument()
  expect(screen.queryByText(/정책버전 15/)).not.toBeInTheDocument()
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
  const payments = await screen.findByRole('region', { name: '연결 지급, 귀속' })
  expect(within(payments).getByRole('link', { name: '800' })).toHaveAttribute(
    'href',
    '/transactions?contractNo=CLW-001&settlementMonth=&selectedId=800',
  )
  const sources = screen.getByRole('region', { name: '원천 사건, 검증 근거' })
  expect(within(sources).getByRole('link', { name: '계약 사건 이력' })).toHaveAttribute('href', '/contracts/10')
  const table = screen.getByRole('table', { name: '검색 조건에 해당하는 환수 검토 후보' })
  await user.click(within(table).getByText('41'))
  const arbitrage = await screen.findByRole('link', { name: '차익거래 검증' })
  expect(arbitrage).toHaveAttribute('href', '/arbitrage-checks?month=2026-07&contractNo=CLW-002')
})

const policy = {
  policyVersionId: 15,
  policyCode: 'CLAW-GA-VIRTUAL-2026',
  versionNo: 1,
  policyName: '가상 GA 표준 환수율',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
}
const calculable: CandidateDetail = {
  ...detail,
  candidate: {
    ...statusRow,
    calculationStatus: 'UNCALCULATED',
    reviewReasons: [],
    allowedActions: ['CALCULATE'],
    policyVersionId: 15,
  },
  policyCandidates: [policy],
  elapsedMonths: 18,
}
const calculation = {
  clawbackCaseId: 7,
  candidateId: 42,
  calculationVersion: 1,
  sourceVersion: 'claw-v1-abc',
  policyVersionId: 15,
  calculatedAt: '2026-07-20T10:00:00+09:00',
  calculatedBy: 3,
  amount: 600000,
  lines: [
    {
      transactionAttributionId: 900,
      commissionItemId: 3,
      installmentNo: 2,
      elapsedMonths: 18,
      basisCode: 'PAID_ATTRIBUTED_AMOUNT',
      basisAmount: 1200000,
      appliedRatePct: 50,
      clawbackAmount: 600000,
      clawbackRuleId: 11,
    },
  ],
  basis: {
    reason: '등록된 환수 정책으로 계산',
    elapsedMonths: 18,
    excluded: [{ commissionItemId: 3, reason: 'NO_CLAWBACK_RULE' }],
  },
}
const CALC_PATH = '/api/v1/clawbacks/candidates/42/calculations'
/** 계산 요청을 기록하고, 계산 뒤 상세는 최신 계산판을 돌려준다. */
function calculationFixtures(post: (options?: RequestOptions) => unknown) {
  const posts: RequestOptions[] = []
  let calculated = false
  const responder: Responder = (p, options) => {
    if (p === CALC_PATH) {
      posts.push(options ?? {})
      const value = post(options)
      if (!(value instanceof Error)) calculated = true
      return value
    }
    if (p.endsWith('/candidates/42'))
      return envelope(
        calculated
          ? {
              ...calculable,
              candidate: { ...calculable.candidate, calculationStatus: 'CALCULATED', calculatedAmount: 600000 },
              latestCalculation: calculation,
            }
          : calculable,
      )
    return fixtures(p)
  }
  return { posts, responder }
}
async function fillAndCalculate(user: ReturnType<typeof userEvent.setup>, reason = '등록된 환수 정책으로 계산') {
  await user.type(await screen.findByLabelText(/계산 사유/), reason)
  await user.click(screen.getByRole('button', { name: '환수액 계산' }))
}

it('calculates with the chosen policy and a reason, then shows the result lines and total', async () => {
  const user = userEvent.setup()
  const { posts, responder } = calculationFixtures(() => envelope(calculation))
  mount('/clawbacks', responder)
  // 정책 후보가 하나면 미리 골라 두지만, 사유를 쓰기 전에는 계산하지 않는다.
  expect(await screen.findByRole('radio', { name: /^CLAW-GA-VIRTUAL-2026 v1\b/ })).toBeChecked()
  expect(screen.getByRole('button', { name: '환수액 계산' })).toBeDisabled()
  expect(screen.getByText('CLAW-GA-VIRTUAL-2026 v1, 아래 환수 계산에서 확인')).toBeInTheDocument()
  await fillAndCalculate(user)
  await waitFor(() => expect(posts).toHaveLength(1))
  expect(posts[0].method).toBe('POST')
  expect(posts[0].body).toEqual({
    expectedSourceVersion: 'claw-v1-abc',
    policyVersionId: 15,
    reason: '등록된 환수 정책으로 계산',
  })
  expect(posts[0].idempotencyKey).toMatch(/^[A-Za-z0-9._:-]{1,100}$/)
  const result = await screen.findByRole('table', { name: '환수 계산 결과' })
  // 좁은 상세 칸에서도 금액이 보이게 항목과 회차를 한 칸에 둔다.
  const row = within(result).getByText('초회 수수료, 2회차').closest('tr')!
  expect(within(row).getByText('1,200,000원')).toBeInTheDocument()
  expect(within(row).getByText('50.0%')).toBeInTheDocument()
  expect(within(row).getByText('600,000원')).toBeInTheDocument()
  expect(screen.getByText('환수액 합계').closest('p')).toHaveTextContent('600,000원')
  // 좁은 상세 칸에서 쓰는 줄 목록도 같은 항목과 금액을 담는다(어느 쪽을 보일지는 CSS 컨테이너 폭이 정한다).
  expect(screen.getByRole('list', { name: '환수 계산 결과' })).toHaveTextContent(
    '초회 수수료, 2회차기지급 기준액 1,200,000원환수율 50.0%환수액 600,000원',
  )
  expect(screen.getByText(/최신 계산판 v1, .*CLAW-GA-VIRTUAL-2026 v1, 경과월 18개월/)).toBeInTheDocument()
  expect(screen.getByText('계산 사유: 등록된 환수 정책으로 계산')).toBeInTheDocument()
  expect(screen.getByText(/환수 규칙이 없는 수수료 항목이라 계산에서 뺐습니다: 초회 수수료/)).toBeInTheDocument()
  expect(screen.getByText(/계산 결과는 환수 확정이나 회수 완료가 아닙니다/)).toBeInTheDocument()
  expect(useToastStore.getState().messages.at(-1)?.message).toBe('환수액을 계산했습니다. 계산판 v1입니다.')
  // 계산이 끝나면 사유를 비워 같은 요청이 다시 나가지 않게 한다.
  expect(screen.getByLabelText(/계산 사유/)).toHaveValue('')
})

it('stops on missing grounds with plain reasons and never shows the failure as 0 won', async () => {
  const user = userEvent.setup()
  const missing = new ApiError(
    {
      code: 'FGC-IMPL-POLICY_MISSING',
      message: '적용 정책 또는 계산 근거가 부족해 계산할 수 없습니다. (clawbackRule)',
      params: { field: 'clawbackRule', reasons: ['ELAPSED_MONTH_NOT_COVERED'] },
    },
    'CALC-422',
    422,
  )
  const { posts, responder } = calculationFixtures(() => missing)
  mount('/clawbacks', responder)
  await fillAndCalculate(user)
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent(
    '계산 근거가 부족해 계산하지 않았습니다. 경과월에 맞는 환수 규칙 없음. (요청번호: CALC-422)',
  )
  expect(alert).not.toHaveTextContent(/FGC-|clawbackRule|ELAPSED/)
  expect(posts).toHaveLength(1)
  expect(screen.queryByRole('table', { name: '환수 계산 결과' })).not.toBeInTheDocument()
  expect(screen.getByText('저장된 계산판이 없습니다.')).toBeInTheDocument()
  // 입력은 지우지 않아 근거를 보완한 뒤 다시 누를 수 있다.
  expect(screen.getByLabelText(/계산 사유/)).toHaveValue('등록된 환수 정책으로 계산')
})

it('reloads the latest grounds when the source changed and sends the retry with a new key', async () => {
  const user = userEvent.setup()
  let first = true
  const { posts, responder } = calculationFixtures(() => {
    if (!first) return envelope(calculation)
    first = false
    return new ApiError({ code: 'FGC-IMPL-STATE_CHANGED', message: 'changed' }, 'CALC-409', 409)
  })
  const { request } = mount('/clawbacks', (p, options) => {
    // 다시 불러온 상세는 원천 버전이 바뀌어 있다.
    if (p.endsWith('/candidates/42') && posts.length > 0 && first === false && !options?.method)
      return envelope({ ...calculable, candidate: { ...calculable.candidate, sourceVersion: 'claw-v2-def' } })
    return responder(p, options)
  })
  await fillAndCalculate(user)
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '조회한 뒤 원천이나 정책이 바뀌었습니다. 최신 근거를 다시 불러왔으니 확인하고 다시 계산하세요. (요청번호: CALC-409)',
  )
  await waitFor(() =>
    expect(request.mock.calls.filter(([p]) => String(p).endsWith('/candidates/42')).length).toBeGreaterThan(1),
  )
  await user.click(screen.getByRole('button', { name: '환수액 계산' }))
  await waitFor(() => expect(posts).toHaveLength(2))
  expect((posts[1].body as { expectedSourceVersion: string }).expectedSourceVersion).toBe('claw-v2-def')
  expect(posts[1].idempotencyKey).not.toBe(posts[0].idempotencyKey)
})

it('retries a lost response with the same key so the server keeps one calculation', async () => {
  const user = userEvent.setup()
  let lost = true
  const { posts, responder } = calculationFixtures(() => {
    if (!lost) return envelope(calculation)
    lost = false
    return new TypeError('Failed to fetch')
  })
  mount('/clawbacks', responder)
  await fillAndCalculate(user)
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('연결이 끊겨 계산 결과를 확인하지 못했습니다. 같은 내용으로 다시 누르면 중복 없이 이어서 처리합니다.')
  expect(alert).not.toHaveTextContent('Failed to fetch')
  await user.click(screen.getByRole('button', { name: '환수액 계산' }))
  await waitFor(() => expect(posts).toHaveLength(2))
  expect(posts[1].idempotencyKey).toBe(posts[0].idempotencyKey)
  expect(await screen.findByRole('table', { name: '환수 계산 결과' })).toBeInTheDocument()
})

it('locks the button while a calculation is in flight', async () => {
  const user = userEvent.setup()
  let finish: (value: unknown) => void = () => {}
  const pending = new Promise((resolve) => {
    finish = resolve
  })
  const posts: RequestOptions[] = []
  mount('/clawbacks', (p, options) => {
    if (p === CALC_PATH) {
      posts.push(options ?? {})
      return pending
    }
    return p.endsWith('/candidates/42') ? envelope(calculable) : fixtures(p)
  })
  await fillAndCalculate(user)
  const button = screen.getByRole('button', { name: '환수액 계산' })
  await waitFor(() => expect(button).toBeDisabled())
  expect(button).toHaveAttribute('aria-busy', 'true')
  await user.click(button)
  expect(posts).toHaveLength(1)
  finish(envelope(calculation))
  await waitFor(() => expect(button).not.toHaveAttribute('aria-busy'))
})

it('asks for a policy choice when several policies apply', async () => {
  const user = userEvent.setup()
  const second = { ...policy, policyVersionId: 16, versionNo: 2 }
  mount('/clawbacks', (p) =>
    p.endsWith('/candidates/42')
      ? envelope({
          ...calculable,
          candidate: { ...calculable.candidate, policyVersionId: null },
          policyCandidates: [policy, second],
        })
      : fixtures(p),
  )
  expect(await screen.findByText('2개, 아래 환수 계산에서 선택')).toBeInTheDocument()
  expect(screen.getByText('적용할 정책을 표에서 고르세요.')).toBeInTheDocument()
  await user.type(screen.getByLabelText(/계산 사유/), '정책 확인 후 계산')
  expect(screen.getByRole('button', { name: '환수액 계산' })).toBeDisabled()
  await user.click(screen.getByRole('radio', { name: /^CLAW-GA-VIRTUAL-2026 v2\b/ }))
  expect(screen.getByRole('button', { name: '환수액 계산' })).toBeEnabled()
})

it('marks a calculation made before the source changed as stale', async () => {
  mount('/clawbacks', (p) =>
    p.endsWith('/candidates/42')
      ? envelope({
          ...calculable,
          candidate: {
            ...calculable.candidate,
            sourceVersion: 'claw-v2-def',
            reviewReasons: ['CALCULATION_STALE'],
          },
          latestCalculation: calculation,
        })
      : fixtures(p),
  )
  expect(await screen.findByText('계산 후 원천이 바뀌어 다시 계산 필요', { selector: 'li' })).toBeInTheDocument()
  expect(
    screen.getByText('계산한 뒤 원천이 바뀌었습니다. 이 계산판은 지금 근거와 다르니 다시 계산하세요.'),
  ).toHaveAttribute('role', 'status')
})
