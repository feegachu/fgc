import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import { ToastRegion } from '../../components/Toast'
import { useToastStore } from '../../stores/toasts'
import ArbitragePage from './ArbitragePage'
import { arbitrageTimelineQuery } from './api'
import type { ArbitrageRow } from './api'
const row: ArbitrageRow = {
  arbitrageCheckId: 2,
  contractId: 10,
  contractNo: 'ARB-001',
  paymentStage: 'GA_TO_FC',
  paymentStageLabel: 'GA→설계사',
  asOfDate: '2026-07-31',
  contractMonthNo: 7,
  cumulativePaidPremium: 800000,
  paidCommissionAmount: 890000,
  plannedCommissionAmount: 12000,
  includedSurrenderValueAmount: 120000,
  netDifferenceAmount: 210000,
  refundAdditionAppliedYn: true,
  surrenderValueSourceType: 'ACTUAL',
  surrenderValueSourceTypeLabel: '실제 해약환급금',
  resultStatus: 'CANDIDATE',
  resultStatusLabel: '검토대상',
  decisionReason: '서버 판정 근거',
}
const old = {
  ...row,
  arbitrageCheckId: 1,
  asOfDate: '2026-07-01',
  netDifferenceAmount: 0,
  resultStatus: 'CLEAR' as const,
  resultStatusLabel: '이상없음',
}
const envelope = (data: unknown) => ({ data, error: null, requestId: '412-test' })
const result = (content: ArbitrageRow[] = [row, old], page = 1, totalPages = 2) =>
  envelope({
    summary: { clearCount: 1, candidateCount: 1, reviewRequiredCount: 0 },
    items: { content, page, totalPages, totalElements: 21 },
  })
function fixtures(path: string) {
  const url = new URL(path, 'http://test')
  if (url.pathname.endsWith('/insurers'))
    return envelope({
      content: [{ insurerId: 1, insurerCode: 'INS1', insurerName: '가상생명', activeYn: false }],
      page: 1,
      totalPages: 1,
    })
  if (url.searchParams.get('size') === '100') return result([row, old], 1, 1)
  return result(undefined, Number(url.searchParams.get('page') ?? 1))
}
function mount(
  path = '/arbitrage-checks?month=2026-07',
  canProcess = true,
  responder: (path: string, options?: unknown) => unknown = fixtures,
) {
  const request = vi
    .spyOn(apiClient, 'request')
    .mockImplementation(async (path, options) => responder(path, options) as never)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  })
  const router = createMemoryRouter(
    [
      {
        element: <Outlet context={{ month: '2026-07', user: { canProcess } }} />,
        children: [{ path: '/arbitrage-checks', element: <ArbitragePage /> }],
      },
    ],
    { initialEntries: [path] },
  )
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
      <ToastRegion />
    </QueryClientProvider>,
  )
  return { request, router, client }
}
afterEach(() => {
  vi.restoreAllMocks()
  useToastStore.setState({ messages: [] })
})
it('restores candidate URL, page and filters; stage is independent and all-stage survives reload', async () => {
  const user = userEvent.setup()
  const { request, router } = mount(
    '/arbitrage-checks?month=2026-07&status=CANDIDATE&stage=GA_TO_FC&page=2&contractNo=ARB',
  )
  await screen.findAllByRole('row', { name: '계약 ARB-001 상세 보기' })
  expect(screen.getByLabelText('계약번호')).toHaveValue('ARB')
  expect(screen.getByLabelText('판정')).toHaveValue('CANDIDATE')
  expect(request.mock.calls.some(([path]) => path.includes('status=CANDIDATE') && path.includes('page=2'))).toBe(true)
  await user.selectOptions(screen.getByLabelText('지급단계'), '')
  expect(router.state.location.search).toContain('stage=')
  expect(router.state.location.search).not.toContain('page=')
  expect(screen.getByLabelText('지급단계')).toHaveValue('')
  await user.clear(screen.getByLabelText('계약번호'))
  await user.type(screen.getByLabelText('계약번호'), 'NEW')
  await user.selectOptions(screen.getByLabelText('판정'), 'CLEAR')
  expect(router.state.location.search).toContain('contractNo=NEW')
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByLabelText('지급단계')).toHaveValue('GA_TO_FC')
  expect(screen.getByLabelText('계약번호')).toHaveValue('')
  await screen.findByRole('option', { name: 'INS1 · 가상생명' })
  expect(screen.getByRole('option', { name: 'INS1 · 가상생명' })).toBeDisabled()
})
it('rows support keyboard activation and timeline uses stored excess without recalculating', async () => {
  const user = userEvent.setup()
  mount()
  await screen.findByRole('img', { name: '기준일별 초과액 추이' })
  const rows = screen.getAllByRole('row', { name: '계약 ARB-001 상세 보기' })
  rows[1].focus()
  await user.keyboard('{Enter}')
  expect(rows[1]).toHaveAttribute('aria-selected', 'true')
  const detail = screen.getByRole('region', { name: '④ 상세' })
  expect(within(detail).getByText('2026-07-01 · 7개월')).toBeVisible()
  const cells = within(detail).getByRole('table').querySelectorAll('tbody tr td:first-child')
  expect([...cells].map((cell) => cell.textContent)).toEqual(['2026-07-01', '2026-07-31'])
})
it('summary filter, page navigation and Enter search preserve month', async () => {
  const user = userEvent.setup()
  const { router, request } = mount()
  await screen.findByText('서버 판정 근거')
  await user.click(screen.getByRole('button', { name: /이상없음.*초과액/ }))
  expect(router.state.location.search).toContain('status=CLEAR')
  await user.click(screen.getByRole('button', { name: '2' }))
  await waitFor(() => expect(request.mock.calls.some(([p]) => p.includes('page=2'))).toBe(true))
  await user.type(screen.getByLabelText('계약번호'), 'ARB-001{Enter}')
  expect(router.state.location.search).toContain('contractNo=ARB-001')
  expect(router.state.location.search).not.toContain('page=')
  expect(router.state.location.search).toContain('month=2026-07')
})
it.each([false, true])('read-only processing permission %s matches action availability', async (canProcess) => {
  const user = userEvent.setup()
  const { request } = mount(undefined, canProcess)
  await screen.findByText('서버 판정 근거')
  const button = screen.getByRole('button', { name: '이 계약만 다시 검증' })
  if (canProcess) {
    expect(button).toBeEnabled()
    await user.click(button)
    expect(screen.getByRole('dialog')).toBeVisible()
  } else {
    expect(button).toBeDisabled()
    expect(screen.getByText(/재검증은 처리 권한/)).toBeVisible()
    await user.click(button)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  }
  expect(request.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
})
it('required reason/date validation, trimmed POST, single submit, refreshed list and success toast', async () => {
  const user = userEvent.setup()
  let resolvePost: (value: unknown) => void = () => {}
  let changed = false
  const { request } = mount(undefined, true, (path, options) => {
    if ((options as { method?: string })?.method === 'POST')
      return new Promise((resolve) => {
        resolvePost = (value) => {
          changed = true
          resolve(value)
        }
      })
    if (changed && path.includes('/arbitrage-checks'))
      return result(
        [{ ...row, resultStatus: 'CLEAR', resultStatusLabel: '이상없음', decisionReason: '재검증 근거' }],
        1,
        1,
      )
    return fixtures(path)
  })
  await screen.findByText('서버 판정 근거')
  await user.click(screen.getByRole('button', { name: '이 계약만 다시 검증' }))
  expect(screen.getByLabelText('검증 기준일')).toHaveFocus()
  await user.click(screen.getByRole('button', { name: '재검증 실행' }))
  expect(screen.getByText('재검증 사유를 입력하세요.')).toBeVisible()
  fireEvent.change(screen.getByLabelText('검증 기준일'), { target: { value: '' } })
  await user.click(screen.getByRole('button', { name: '재검증 실행' }))
  expect(screen.getByText('검증 기준일을 입력하세요.')).toBeVisible()
  fireEvent.change(screen.getByLabelText('검증 기준일'), { target: { value: '2026-07-31' } })
  await user.type(screen.getByLabelText('재검증 사유'), '  자료 변경 확인  ')
  await user.dblClick(screen.getByRole('button', { name: '재검증 실행' }))
  expect(request.mock.calls.filter(([, o]) => o?.method === 'POST')).toHaveLength(1)
  expect(request.mock.calls.find(([, o]) => o?.method === 'POST')).toEqual([
    '/api/v1/contracts/10/arbitrage-check',
    { method: 'POST', body: { asOfDate: '2026-07-31', reason: '자료 변경 확인' } },
  ])
  expect(screen.getByRole('button', { name: '취소' })).toBeDisabled()
  expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true)
  await user.keyboard('{Escape}')
  expect(screen.getByRole('dialog')).toBeVisible()
  await user.keyboard('{Tab}')
  expect(screen.getByRole('button', { name: '모달 닫기' })).toHaveFocus()
  resolvePost(envelope({ arbitrageCheckId: 3, resultStatus: 'CLEAR' }))
  await screen.findByText('재검증 근거')
  await screen.findByText('차익거래 재검증을 완료했습니다.')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '이 계약만 다시 검증' })).toHaveFocus()
})
it('pending recheck keeps focus inside the modal after a single submit', async () => {
  const user = userEvent.setup()
  mount(undefined, true, (path, options) => {
    if ((options as { method?: string })?.method === 'POST') return new Promise(() => {})
    return fixtures(path)
  })
  await screen.findByText('서버 판정 근거')
  await user.click(screen.getByRole('button', { name: '이 계약만 다시 검증' }))
  await user.type(screen.getByLabelText('재검증 사유'), '재확인')
  await user.click(screen.getByRole('button', { name: '재검증 실행' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '취소' })).toBeDisabled())
  expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true)
  await user.keyboard('{Escape}')
  expect(screen.getByRole('dialog')).toBeVisible()
  await user.tab()
  expect(screen.getByRole('button', { name: '모달 닫기' })).toHaveFocus()
})
it('recheck 403 keeps entered reason and server error visible without losing list', async () => {
  const user = userEvent.setup()
  mount(undefined, true, (p, o) => {
    if ((o as { method?: string })?.method === 'POST')
      throw new ApiError({ code: 'FGC-COMMON-403', message: '접근 권한이 없습니다.' }, '403-trace', 403)
    return fixtures(p)
  })
  await screen.findByText('서버 판정 근거')
  await user.click(screen.getByRole('button', { name: '이 계약만 다시 검증' }))
  await user.type(screen.getByLabelText('재검증 사유'), '재확인')
  await user.click(screen.getByRole('button', { name: '재검증 실행' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('접근 권한이 없습니다.')
  expect(screen.getByLabelText('재검증 사유')).toHaveValue('재확인')
  await user.click(screen.getByRole('button', { name: '취소' }))
  expect(screen.getAllByRole('row', { name: '계약 ARB-001 상세 보기' })).toHaveLength(2)
})
it.each([400, 500])('list %s errors use API code and hide 500 internals', async (status) => {
  mount(undefined, true, (p) => {
    if (p.includes('/insurers')) return fixtures(p)
    throw new ApiError({ code: `FGC-COMMON-${status}`, message: 'server-detail' }, 'error-trace', status)
  })
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent(status === 500 ? '요청 ID: error-trace' : 'server-detail')
  if (status === 500) expect(screen.queryByText('server-detail')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '이 계약만 다시 검증' })).toBeDisabled()
})
it('empty 200 clears detail and disables recheck', async () => {
  mount(undefined, true, (p) => (p.includes('/insurers') ? fixtures(p) : result([], 1, 0)))
  expect(await screen.findByText('조건에 맞는 자료가 없습니다.')).toBeVisible()
  expect(screen.getByText('왼쪽 목록에서 한 건을 고르세요.')).toBeVisible()
  expect(screen.getByRole('button', { name: '이 계약만 다시 검증' })).toBeDisabled()
})
it('timeline fetches every page, passes signal, filters exact contract/stage, and sorts dates', async () => {
  const request = vi
    .spyOn(apiClient, 'request')
    .mockResolvedValueOnce(
      result([row, { ...row, arbitrageCheckId: 50, contractId: 999, contractNo: 'ARB-0012' }], 1, 2) as never,
    )
    .mockResolvedValueOnce(
      result([old, { ...row, arbitrageCheckId: 51, paymentStage: 'INSURER_TO_GA' }], 2, 2) as never,
    )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  expect((await client.fetchQuery(arbitrageTimelineQuery(row))).map((r) => r.arbitrageCheckId)).toEqual([1, 2])
  expect(request.mock.calls[1][0]).toContain('page=2&size=100')
  expect(request.mock.calls[0][1]?.signal).toBeInstanceOf(AbortSignal)
  client.clear()
})

it('insurer-to-GA rows remain readable but cannot invoke the GA-only manual API', async () => {
  const user = userEvent.setup()
  const { request } = mount('/arbitrage-checks?month=2026-07&stage=INSURER_TO_GA', true, (p) =>
    p.includes('/insurers')
      ? fixtures(p)
      : result([{ ...row, paymentStage: 'INSURER_TO_GA', paymentStageLabel: '원수사→GA' }], 1, 1),
  )
  await screen.findByText('서버 판정 근거')
  expect(screen.getByText('단건 재검증은 GA→설계사 단계만 지원합니다.')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '이 계약만 다시 검증' }))
  expect(screen.getByRole('button', { name: '이 계약만 다시 검증' })).toBeDisabled()
  expect(request.mock.calls.some(([, o]) => o?.method === 'POST')).toBe(false)
})
it('negative money uses common brackets/red and displays stored excess independently of input amounts', async () => {
  mount(undefined, true, (p) =>
    p.includes('/insurers')
      ? fixtures(p)
      : result([{ ...row, paidCommissionAmount: -1234, netDifferenceAmount: -987 }], 1, 1),
  )
  await screen.findByText('서버 판정 근거')
  for (const amount of screen.getAllByText('(1,234)원')) expect(amount).toHaveClass('is-negative-amount')
  await screen.findByRole('img', { name: '기준일별 초과액 추이' })
  for (const amount of screen.getAllByText('(987)원')) expect(amount).toHaveClass('is-negative-amount')
  const stored = screen.getByRole('region', { name: '④ 상세' }).querySelector('.arb-calculation')!
  expect(stored).toHaveTextContent('지급분 기준 초과액: (987)원')
})

it('timeline cache separates contract IDs even when contract number/stage match', async () => {
  const other = { ...row, contractId: 99, arbitrageCheckId: 99 }
  vi.spyOn(apiClient, 'request').mockResolvedValue(result([row, other], 1, 1) as never)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  expect((await client.fetchQuery(arbitrageTimelineQuery(row))).map((r) => r.contractId)).toEqual([10])
  expect((await client.fetchQuery(arbitrageTimelineQuery(other))).map((r) => r.contractId)).toEqual([99])
  client.clear()
})
it('legacy pagination includes more than five page numbers and moves one page at a time', async () => {
  const user = userEvent.setup()
  const { router } = mount(undefined, true, (p) =>
    p.includes('/insurers')
      ? fixtures(p)
      : result([row], Number(new URL(p, 'http://test').searchParams.get('page') ?? 1), 7),
  )
  await screen.findByText('서버 판정 근거')
  await user.click(screen.getByRole('button', { name: '7' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '7' })).toHaveAttribute('aria-current', 'page'))
  expect(router.state.location.search).toContain('page=7')
  await user.click(screen.getByRole('button', { name: '이전 페이지' }))
  expect(router.state.location.search).toContain('page=6')
})

it('keeps projected-only excess separate from the CLEAR result in list and detail', async () => {
  const projected = { ...row, resultStatus: 'CLEAR' as const, resultStatusLabel: '이상없음',
    netDifferenceAmount: 0, projectedExcessAmount: 100000, candidateType: 'PROJECTED_EXCESS' }
  mount(undefined, true, (path) => path.includes('/insurers') ? fixtures(path) : result([projected], 1, 1))
  await screen.findByText('서버 판정 근거')
  expect(screen.getByText(/지급예정 포함 시 초과 \(모니터링\)/)).toBeVisible()
  expect(screen.getAllByText('이상없음').length).toBeGreaterThan(0)
  expect(screen.getByText(/지급예정 포함 초과액 \(모니터링\)/)).toBeVisible()
})
