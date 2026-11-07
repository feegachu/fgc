import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient, ApiError } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import type { DashboardSummary, ExceptionCounts, BranchDashboard, DashboardCommission } from './api'

const user = {
  loginId: 'settle01',
  userName: '정산 담당자',
  roleCode: 'SETTLEMENT',
  demoMonth: '2026-07',
  canProcess: true,
  canViewAuditLog: false,
  canHandleException: true,
  canReverseJournal: true,
  canFinalizeValidation: false,
}
const summary: DashboardSummary = {
  month: '2026-07-01',
  capViolation: 1234,
  capWarning: 2,
  arbitrageCandidate: 3,
  reconMismatch: 4,
  journalImbalance: 0,
  openException: 5,
  recentExceptions: [
    {
      exceptionCaseId: 11,
      severity: 'HIGH',
      severityLabel: '높음',
      exceptionType: 'CAP_VIOLATION',
      exceptionTypeLabel: '1,200% 위반',
      contractNo: 'C-0001',
      title: '한도 초과 계약',
      status: 'NEW',
      statusLabel: '신규',
      createdAt: '2026-07-10T15:30:00Z',
    },
  ],
  recentRuns: [
    {
      validationRunId: 77,
      validationMonth: '2026-07-01',
      runNo: 2,
      status: 'RUNNING',
      statusLabel: '실행 중',
      currentStep: 4,
      triggeredBy: 'settle01',
    },
  ],
}
const counts: ExceptionCounts = {
  total: 5,
  bySeverity: [{ severity: 'HIGH', severityLabel: '높음', count: 5 }],
  byType: [{ type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', count: 5 }],
}
const branches: BranchDashboard = {
  month: '2026-07-01', asOf: '2026-07-31',
  branches: [
    { organizationId: 11, name: '강동지사', registeredAgents: 47, activeAgents: 42, newContracts: 21, validContracts: 980, gaAmount: 9000000, agentAmount: 6000000, openExceptions: 9 },
    { organizationId: 12, name: '마포지사', registeredAgents: 45, activeAgents: 40, newContracts: 24, validContracts: 960, gaAmount: 6000000, agentAmount: 4000000, openExceptions: 3 },
  ],
}
const commissions: DashboardCommission = { months: [
  { month: '2026-06-01', gaAmount: 12000000, agentAmount: 8000000 },
  { month: '2026-07-01', gaAmount: 15000000, agentAmount: 10000000 },
] }
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'dash-test' })
const serverError = () => new ApiError({ code: 'FGC-COMMON-500', message: '처리 중 오류가 발생했습니다.' }, 'req-1', 500)
const clients: QueryClient[] = []
let summaryResponse: () => Promise<unknown>
let countsResponse: () => Promise<unknown>
let branchResponse: (path: string) => Promise<unknown>
let commissionResponse: () => Promise<unknown>
let me: Record<string, unknown>

beforeEach(() => {
  localStorage.clear()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
  sessionStorage.clear()
  summaryResponse = async () => envelope(summary)
  countsResponse = async () => envelope(counts)
  branchResponse = async () => envelope(branches)
  commissionResponse = async () => envelope(commissions)
  me = user
  vi.spyOn(apiClient, 'request').mockImplementation((async (path: string) => {
    if (path.startsWith('/api/v1/dashboard/summary')) return summaryResponse()
    if (path.startsWith('/api/v1/exceptions/counts')) return countsResponse()
    if (path.startsWith('/api/v1/dashboard/branches')) return branchResponse(path)
    if (path.startsWith('/api/v1/dashboard/monthly-commissions')) return commissionResponse()
    if (path.startsWith('/api/v1/dashboard/branch-details')) {
      const params = new URL(path, 'http://localhost').searchParams
      const page = Number(params.get('page'))
      return envelope({ content: [{ id: page, label: `상세-${page}`, detail: 'A-001', status: 'ACTIVE', value: 9000000, secondaryValue: 6000000 }], page, totalPages: 2, totalElements: 11, size: 10 })
    }
    return envelope(me)
  }) as typeof apiClient.request)
})
afterEach(() => {
  vi.restoreAllMocks()
  for (const client of clients.splice(0)) client.clear()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

function renderDashboard(path = '/app/') {
  useAuthStore.getState().setAccessToken('access')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter(routes, { basename: '/app/', initialEntries: [path] })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, actions: userEvent.setup() }
}
const callsTo = (fragment: string) =>
  vi
    .mocked(apiClient.request)
    .mock.calls.map(([path]) => path)
    .filter((path) => path.includes(fragment))
const summaryCalls = () => callsTo('/dashboard/summary')
const overview = () => screen.findByRole('region', { name: '지점별 현황' })
/** 외부에서 응답 시점을 정하는 약속. 늦은 응답과 로딩 상태를 재현한다. */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('DASH-W01 업무 대시보드', () => {
  it('이전 브라우저 시안 설정을 무시하고 확정된 한 줄 KPI와 조회 링크를 제공한다', async () => {
    localStorage.setItem('fgc.ui-revision.v1', 'before')
    localStorage.setItem('fgc.design-settings.v1', JSON.stringify({ kpiStyle: 'cards', buttonDesign: 'c' }))
    renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    const links = within(kpis)
      .getAllByRole('link')
      .map((link) => link.getAttribute('href'))
    expect(kpis.closest('.app-shell')).toHaveAttribute('data-kpi-style', 'summary')
    expect(kpis.closest('.app-shell')).toHaveClass('app-theme')
    expect(screen.queryByRole('button', { name: /개발자|변경 전|변경 후/ })).not.toBeInTheDocument()
    expect(await screen.findByRole('region', { name: '월별 수수료' })).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: /버튼 색상|버튼 표시 방식|전후 비교/ })).not.toBeInTheDocument()
    expect(links[0]).toContain('/cap-checks?status=VIOLATION')
    expect(within(kpis).getByRole('link', { name: '1,200% 위반 1,234건' })).toHaveTextContent('1,234건')
    expect(summaryCalls()).toHaveLength(1)
  })
  it('미처리 예외를 포함한 KPI 6종과 도넛, 최근 예외와 실행을 보여 준다', async () => {
    renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    expect(within(kpis).getAllByRole('link')).toHaveLength(6)
    expect(within(kpis).getByRole('link', { name: '미처리 예외 5건' })).toHaveAttribute(
      'href', '/app/exceptions?status=OPEN&month=2026-07',
    )
    expect(screen.getByRole('heading', { name: '검증 항목별 건수 비중' })).toBeInTheDocument()
    expect(within(kpis).getByRole('link', { name: '1,200% 위반 1,234건' })).toHaveTextContent('1,234건')
    expect(summaryCalls()).toEqual(['/api/v1/dashboard/summary?month=2026-07'])
    const table = screen.getByRole('table', { name: '최근 예외 목록' })
    expect(within(table).queryByText('C-0001')).not.toBeInTheDocument()
    expect(within(table).getByRole('link', { name: '한도 초과 계약' })).toHaveAttribute('href', '/app/exceptions?contractNo=C-0001&month=2026-07')
    expect(table.querySelector('time')).toBeNull()
    const run = screen.getByRole('link', { name: /2026년 7월 2차 실행/ })
    expect(within(run).getByRole('progressbar', { name: '검증 진행률' })).toHaveAttribute('aria-valuenow', '40')
    expect(run).toHaveTextContent('4/10 단계')
  })

  it('도넛과 범례의 hover·focus로 항목·건수·비중을 확인하며 기준월 링크를 유지한다', async () => {
    const { actions } = renderDashboard()
    const chart = await screen.findByRole('region', { name: '검증 항목별 건수 비중' })
    expect(screen.queryByRole('heading', { name: /처리할 예외/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: '대시보드 전후 비교' })).not.toBeInTheDocument()
    expect(chart.querySelectorAll('.risk-donut-segment')).toHaveLength(4)
    expect(within(chart).getByRole('link', { name: /1,200% 위반 1,234건, 비중/ })).toHaveAttribute(
      'href', '/app/cap-checks?status=VIOLATION&month=2026-07',
    )
    expect(within(chart).getByRole('link', { name: /원장 불균형 0건, 비중 0.0%/ })).toHaveAttribute(
      'href', '/app/journals?month=2026-07',
    )
    expect(within(chart).queryByRole('tooltip')).not.toBeInTheDocument()
    const segment = chart.querySelector('.risk-donut-segment')!
    await actions.hover(segment)
    let detail = within(chart).getByRole('tooltip')
    expect(detail).toHaveTextContent('1,200% 위반')
    expect(detail).toHaveTextContent('1,234건')
    expect(detail).toHaveTextContent('비중 99.2%')
    await actions.unhover(segment)
    expect(within(chart).queryByRole('tooltip')).not.toBeInTheDocument()
    const zero = within(chart).getByRole('link', { name: /원장 불균형 0건/ })
    fireEvent.focus(zero)
    detail = within(chart).getByRole('tooltip')
    expect(detail).toHaveTextContent('원장 불균형0건비중 0.0%')
    expect(zero).toHaveAttribute('aria-describedby', detail.id)
    const warning = within(chart).getByRole('link', { name: /1,200% 주의 2건/ })
    await actions.hover(warning)
    expect(within(chart).getByRole('tooltip')).toHaveTextContent('1,200% 주의2건비중 0.1%')
    await actions.unhover(warning)
    expect(within(chart).getByRole('tooltip')).toHaveTextContent('원장 불균형0건비중 0.0%')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(within(chart).queryByRole('tooltip')).not.toBeInTheDocument()
    fireEvent.blur(zero)
    await actions.hover(segment)
    expect(within(chart).getByRole('tooltip')).toHaveTextContent('1,200% 위반')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(within(chart).queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('기준월을 바꾸면 해당 월로 다시 조회한다', async () => {
    const { router, actions } = renderDashboard()
    await screen.findByRole('region', { name: '주요 검증 지표' })
    await actions.click(screen.getByRole('button', { name: '기준 정산월 2026-07' }))
    await actions.click(screen.getByRole('gridcell', { name: '8월' }))
    await actions.click(screen.getByRole('button', { name: '적용' }))
    await waitFor(() => expect(summaryCalls()).toContain('/api/v1/dashboard/summary?month=2026-08'))
    expect(router.state.location.search).toContain('month=2026-08')
  })

  it('KPI 카드와 행 클릭은 필터·기준월을 유지한 채 업무 화면으로 이동한다', async () => {
    const { router, actions } = renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    expect(within(kpis).getByRole('link', { name: '대사 불일치 4건' })).toHaveAttribute(
      'href',
      '/app/reconciliations?onlyMismatch=true&month=2026-07',
    )
    // 원장은 불균형만 거르는 목록 조건이 없어 기준월 원장 목록으로 보낸다.
    expect(within(kpis).getByRole('link', { name: '원장 불균형 0건' })).toHaveAttribute('href', '/app/journals?month=2026-07')
    await actions.click(within(kpis).getByRole('link', { name: '1,200% 위반 1,234건' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/cap-checks'))
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('VIOLATION')
    expect(new URLSearchParams(router.state.location.search).get('month')).toBe('2026-07')
  })

  it('예외 행은 계약번호로, 실행 행은 실행 상세로 연결한다', async () => {
    renderDashboard()
    await screen.findByRole('region', { name: '주요 검증 지표' })
    expect(screen.getByRole('link', { name: '한도 초과 계약' })).toHaveAttribute(
      'href',
      '/app/exceptions?contractNo=C-0001&month=2026-07',
    )
    expect(screen.getByRole('link', { name: /2026년 7월 2차 실행/ })).toHaveAttribute(
      'href',
      '/app/validation-runs/77?month=2026-07',
    )
  })

  it('데이터가 0건이면 KPI는 0건, 목록은 빈 상태 문구를 보여 준다', async () => {
    summaryResponse = async () =>
      envelope({ ...summary, capViolation: 0, capWarning: 0, arbitrageCandidate: 0, reconMismatch: 0,
        journalImbalance: 0, openException: 0, recentExceptions: [], recentRuns: [] })
    renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    expect(within(kpis).getByRole('link', { name: '1,200% 위반 0건' })).toHaveTextContent('0건')
    expect(screen.getAllByText('조건에 맞는 자료가 없습니다.')).toHaveLength(2)
    expect(screen.getByText('이번 달 표시할 위험 건이 없습니다.')).toBeInTheDocument()
  })

  it('API 오류는 화면에 안내하고 다시 시도하면 복구한다', async () => {
    let fail = true
    summaryResponse = async () => {
      if (fail) throw new ApiError({ code: 'FGC-COMMON-500', message: '처리 중 오류가 발생했습니다.' }, 'req-1', 500)
      return envelope(summary)
    }
    const { actions } = renderDashboard()
    expect(await screen.findByRole('alert')).toHaveTextContent('처리 중 오류가 발생했습니다.')
    fail = false
    await actions.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('region', { name: '주요 검증 지표' })).toBeInTheDocument()
  })

  it('COMPLIANCE 역할도 대시보드를 열 수 있다', async () => {
    me = {
      ...user,
      loginId: 'comp01',
      roleCode: 'COMPLIANCE',
      canProcess: false,
      canHandleException: false,
      canReverseJournal: false,
    }
    renderDashboard()
    expect(await screen.findByRole('region', { name: '주요 검증 지표' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '권한 없음' })).toBeNull()
  })

  it('미처리 예외 KPI는 예외함과 같은 조건의 집계 API 건수를 보여 준다', async () => {
    renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    expect(await within(kpis).findByRole('link', { name: '미처리 예외 5건' })).toHaveAttribute(
      'href',
      '/app/exceptions?status=OPEN&month=2026-07',
    )
    expect(callsTo('/exceptions/counts')).toEqual(['/api/v1/exceptions/counts?status=OPEN'])
  })

  it('미처리 예외가 0건이면 0건과 안내를 보이고, 건수를 받기 전과 실패했을 때는 0을 보이지 않는다', async () => {
    const pending = deferred<unknown>()
    countsResponse = () => pending.promise
    renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    expect(within(kpis).queryByRole('link', { name: /미처리 예외/ })).not.toBeInTheDocument()
    expect(within(kpis).getByRole('region', { name: '미처리 예외' })).toHaveTextContent('불러오는 중')
    pending.resolve(envelope({ total: 0, bySeverity: [], byType: [] }))
    expect(await within(kpis).findByRole('link', { name: '미처리 예외 0건' })).toHaveTextContent('미처리 예외가 없습니다')
  })

  it('미처리 건수 오류는 그 카드에만 재시도를 보이고 다른 지표는 유지한다', async () => {
    let fail = true
    countsResponse = async () => {
      if (fail) throw serverError()
      return envelope(counts)
    }
    const { actions } = renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    const retry = await within(kpis).findByRole('button', { name: '미처리 예외 다시 불러오기' })
    expect(within(kpis).getByRole('alert')).toHaveTextContent('불러오지 못했습니다')
    expect(within(within(kpis).getByRole('region', { name: '미처리 예외' })).queryByText('0')).not.toBeInTheDocument()
    expect(within(within(kpis).getByRole('region', { name: '미처리 예외' })).queryByText(/^갱신 /)).not.toBeInTheDocument()
    expect(within(kpis).getByRole('link', { name: '1,200% 위반 1,234건' })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: '최근 예외 목록' })).toBeInTheDocument()
    fail = false
    await actions.click(retry)
    expect(await within(kpis).findByRole('link', { name: '미처리 예외 5건' })).toBeInTheDocument()
  })

  it('미처리 건수를 다시 불러오다 실패하면 그 카드에 갱신 시각을 보인다', async () => {
    renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    await within(kpis).findByRole('link', { name: '미처리 예외 5건' })
    countsResponse = async () => {
      throw serverError()
    }
    await clients[0].refetchQueries({ queryKey: ['exceptions', 'counts', 'OPEN'] })
    await within(kpis).findByRole('alert')
    const card = within(kpis).getByRole('region', { name: '미처리 예외' })
    expect(within(card).getByText(/^갱신 \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)).toBeInTheDocument()
  })

  it('월별 수수료 합계와 전월 증감을 보여 주고 막대에 초점을 주면 해당 월 금액을 표시한다', async () => {
    const { actions } = renderDashboard()
    const region = await screen.findByRole('region', { name: '월별 수수료' })
    expect(await within(region).findByText('15,000,000원')).toBeInTheDocument()
    expect(within(region).getAllByText('전월 대비 25.0% 증가')).toHaveLength(2)
    const june = within(region).getByRole('button', { name: /2026년 6월, GA 수령액/ })
    await actions.hover(june)
    expect(within(region).getByText('12,000,000원')).toBeInTheDocument()
    expect(within(region).queryByRole('group', { name: /보기 방식/ })).not.toBeInTheDocument()
  })

  it('지표를 바꾸면 상세 목록을 조회하고 페이지 로딩·완료 후에도 페이지 이동 위치에 초점을 유지한다', async () => {
    const pending = deferred<unknown>()
    const request = vi.mocked(apiClient.request).getMockImplementation()!
    vi.mocked(apiClient.request).mockImplementation(((path: string, options: unknown) =>
      path.includes('/branch-details') && path.endsWith('page=2') ? pending.promise : request(path, options as never)
    ) as typeof apiClient.request)
    const { actions } = renderDashboard()
    const region = await overview()
    await within(region).findByRole('button', { name: '강동지사 수수료 상세 보기' })
    await actions.click(within(region).getByRole('button', { name: '설계사' }))
    await actions.click(within(region).getByRole('button', { name: '강동지사 설계사 상세 보기' }))
    const dialog = await screen.findByRole('dialog', { name: '강동지사 · 설계사 상세' })
    expect(await within(dialog).findByText('상세-1')).toBeInTheDocument()
    expect(callsTo('/branch-details').at(-1)).toBe('/api/v1/dashboard/branch-details?month=2026-07&organizationId=11&metric=AGENTS&page=1')
    await actions.click(within(dialog).getByRole('button', { name: '2' }))
    const pagination = within(dialog).getByRole('group', { name: '지점 상세 페이지 이동' })
    expect(pagination).toHaveFocus()
    expect(within(dialog).getByRole('status')).toHaveTextContent('2페이지를 불러오는 중입니다. 현재 1페이지입니다.')
    expect(within(dialog).getByText('상세-1')).toBeInTheDocument()
    pending.resolve(envelope({ content: [{ id: 2, label: '상세-2', detail: 'A-002', status: 'ACTIVE' }], page: 2, totalPages: 2, totalElements: 11, size: 10 }))
    expect(await within(dialog).findByText('상세-2')).toBeInTheDocument()
    expect(callsTo('/branch-details').at(-1)).toContain('metric=AGENTS&page=2')
    expect(pagination).toHaveFocus()
    await actions.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('지점 조회 중에는 숫자를 만들지 않고 결과가 비면 빈 상태를 표시한다', async () => {
    const pending = deferred<unknown>()
    branchResponse = () => pending.promise
    renderDashboard()
    const region = await overview()
    expect(within(region).getByRole('status')).toHaveTextContent('불러오는 중')
    expect(region.querySelectorAll('.branch-metric-button')).toHaveLength(0)
    pending.resolve(envelope({ ...branches, branches: [] }))
    expect(await within(region).findByText('표시할 지점이 없습니다.')).toBeInTheDocument()
  })

  it('지점 조회 실패는 그 영역에서 재시도하고 나머지 KPI와 수수료를 유지한다', async () => {
    branchResponse = async () => { throw serverError() }
    const { actions } = renderDashboard()
    const region = await overview()
    expect(await within(region).findByRole('alert')).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: '주요 검증 지표' })).toBeInTheDocument()
    expect(await screen.findByText('15,000,000원')).toBeInTheDocument()
    branchResponse = async () => envelope(branches)
    await actions.click(within(region).getByRole('button', { name: '다시 시도' }))
    expect(await within(region).findByRole('button', { name: '강동지사 수수료 상세 보기' })).toBeInTheDocument()
  })

  it('수수료 조회 실패는 0원 대신 재시도를 보이고 복구한다', async () => {
    commissionResponse = async () => { throw serverError() }
    const { actions } = renderDashboard()
    const region = await screen.findByRole('region', { name: '월별 수수료' })
    expect(await within(region).findByRole('alert')).toBeInTheDocument()
    expect(region.querySelectorAll('.commission-total')).toHaveLength(0)
    commissionResponse = async () => envelope(commissions)
    await actions.click(within(region).getByRole('button', { name: '다시 시도' }))
    expect(await within(region).findByText('15,000,000원')).toBeInTheDocument()
  })

  it('정산월 변경 뒤 늦게 도착한 이전 지점 응답을 표시하지 않는다', async () => {
    const old = deferred<unknown>()
    branchResponse = async path => path.includes('2026-07') ? old.promise : envelope({ ...branches, branches: [{ ...branches.branches![0], name: '8월 지점' }] })
    const { actions } = renderDashboard()
    await overview()
    await actions.click(screen.getByRole('button', { name: '기준 정산월 2026-07' }))
    await actions.click(screen.getByRole('gridcell', { name: '8월' }))
    await actions.click(screen.getByRole('button', { name: '적용' }))
    expect(await screen.findByRole('button', { name: '8월 지점 수수료 상세 보기' })).toBeInTheDocument()
    old.resolve(envelope(branches))
    await waitFor(() => expect(screen.queryByRole('button', { name: '강동지사 수수료 상세 보기' })).not.toBeInTheDocument())
    expect(callsTo('/monthly-commissions').at(-1)).toContain('month=2026-08')
  })

  it('최근 예외는 접근 가능한 두 행만 제공하고 협회 링크는 새 탭으로 연다', async () => {
    summaryResponse = async () => envelope({ ...summary, recentExceptions: [1, 2, 3].map(id => ({ ...summary.recentExceptions![0], exceptionCaseId: id, title: `예외-${id}` })) })
    renderDashboard()
    const table = await screen.findByRole('table', { name: '최근 예외 목록' })
    expect(within(table).getAllByRole('row')).toHaveLength(3)
    expect(within(table).getAllByRole('link')).toHaveLength(2)
    const link = screen.getByRole('link', { name: '보험GA협회 홈페이지 열기 (새 탭)' })
    expect(link).toHaveAttribute('href', 'https://www.igaa.or.kr/')
    expect(link).toHaveAttribute('target', '_blank')
  })
})
