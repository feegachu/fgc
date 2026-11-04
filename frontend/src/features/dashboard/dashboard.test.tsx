import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient, ApiError } from '../../lib/api/client'
import { today } from '../../lib/format'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import type { DashboardSummary, ExceptionCounts, HomeSummary } from './api'

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
const home: HomeSummary = {
  asOf: '2026-11-04',
  agents: { registered: 560, active: 540, terminated: 40 },
  branches: [
    { organizationId: 11, name: '강동지사', agentCount: 300, activeContractCount: 6200, attentionCount: 12 },
    { organizationId: 12, name: '마포지사', agentCount: 260, activeContractCount: 5800, attentionCount: 0 },
  ],
  attentionTotal: 12,
  attentionAgents: [
    {
      agentId: 1,
      name: '김정산',
      code: 'A-FC-001',
      branch: '강동지사',
      status: 'ACTIVE',
      statusLabel: '활동',
      contractCount: 7,
      reasons: [
        { code: 'OPEN_EXCEPTION', label: '미처리 예외', count: 2 },
        { code: 'NEAR_LIMIT', label: '한도 근접', count: 1 },
      ],
    },
    {
      agentId: 4,
      name: '최해촉',
      code: 'A-FC-004',
      branch: '강동지사',
      status: 'TERMINATED',
      statusLabel: '해촉',
      contractCount: 3,
      reasons: [{ code: 'RECENT_STATUS_CHANGE', label: '최근 상태 변화', count: 1 }],
    },
    {
      agentId: 9,
      name: '박중지',
      code: 'A-FC-009',
      branch: '강동지사',
      status: 'ACTIVE',
      statusLabel: '활동',
      activeYn: false,
      contractCount: 1,
      reasons: [{ code: 'NEAR_LIMIT', label: '한도 근접', count: 1 }],
    },
  ],
  basis: '유효 계약은 기준일 상태가 정상, 미납, 부활인 계약입니다.',
}
const agentPage = (names: string[], totalPages = 1, totalElements = names.length) => ({
  content: names.map((name, index) => ({
    agentId: index + 100,
    agentName: name,
    agentCode: `A-${index + 100}`,
    organizationName: '강동1팀',
    agentStatusLabel: '활동',
  })),
  page: 1,
  size: 10,
  totalElements,
  totalPages,
})
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'dash-test' })
const serverError = () => new ApiError({ code: 'FGC-COMMON-500', message: '처리 중 오류가 발생했습니다.' }, 'req-1', 500)
const clients: QueryClient[] = []
let summaryResponse: () => Promise<unknown>
let countsResponse: () => Promise<unknown>
let homeResponse: (path: string) => Promise<unknown>
let agentsResponse: (path: string) => Promise<unknown>
let me: Record<string, unknown>

beforeEach(() => {
  localStorage.clear()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
  sessionStorage.clear()
  summaryResponse = async () => envelope(summary)
  countsResponse = async () => envelope(counts)
  homeResponse = async () => envelope(home)
  agentsResponse = async () => envelope(agentPage([]))
  me = user
  vi.spyOn(apiClient, 'request').mockImplementation((async (path: string) => {
    if (path.startsWith('/api/v1/dashboard/summary')) return summaryResponse()
    if (path.startsWith('/api/v1/exceptions/counts')) return countsResponse()
    if (path.startsWith('/api/v1/home/summary')) return homeResponse(path)
    if (path.startsWith('/api/v1/base/agents')) return agentsResponse(path)
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
const overview = () => screen.findByRole('region', { name: 'GA·설계사 현황' })
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
    localStorage.setItem('fgc.design-settings.v1', JSON.stringify({ kpiStyle: 'cards', buttonDesign: 'c' }))
    renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    const links = within(kpis)
      .getAllByRole('link')
      .map((link) => link.getAttribute('href'))
    expect(kpis.closest('.app-shell')).toHaveAttribute('data-kpi-style', 'summary')
    expect(kpis.closest('.app-shell')).toHaveClass('app-theme')
    expect(screen.queryByRole('button', { name: '개발자' })).not.toBeInTheDocument()
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
    expect(within(table).getByText('C-0001')).toBeInTheDocument()
    // UTC 15:30 은 Asia/Seoul 로 다음 날 00:30 이다.
    expect(within(table).getByText('2026-07-11 00:30')).toBeInTheDocument()
    const run = screen.getByRole('link', { name: /2026년 7월 2회차/ })
    expect(within(run).getByRole('progressbar', { name: '검증 진행률' })).toHaveAttribute('aria-valuenow', '40')
    expect(run).toHaveTextContent('4/10 단계, 실행 settle01')
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
    expect(screen.getByRole('link', { name: /2026년 7월 2회차/ })).toHaveAttribute(
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
    expect(within(kpis).getByRole('link', { name: '1,200% 위반 1,234건' })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: '최근 예외 목록' })).toBeInTheDocument()
    fail = false
    await actions.click(retry)
    expect(await within(kpis).findByRole('link', { name: '미처리 예외 5건' })).toBeInTheDocument()
  })

  it('기준 정산월, 현황 기준일, 갱신 시각을 구분해 보여 주고 코드값을 드러내지 않는다', async () => {
    renderDashboard()
    const region = await overview()
    await within(region).findByRole('table', { name: '확인할 설계사 상위 10명' })
    expect(screen.getByText('기준 정산월 2026년 7월')).toBeInTheDocument()
    expect(within(region).getByLabelText('현황 기준일')).toHaveValue(today())
    expect(within(region).getByText(/^갱신 \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)).toBeInTheDocument()
    expect(callsTo('/home/summary')).toEqual([`/api/v1/home/summary?asOf=${today()}`])
    expect(region).not.toHaveTextContent(/OPEN_EXCEPTION|NEAR_LIMIT|RECENT_STATUS_CHANGE|TERMINATED|ACTIVE/)
  })

  it('등록, 활동 수와 지점별 현황, 확인할 설계사와 사유를 보여 주고 설계사를 고르면 계약 목록을 연다', async () => {
    const { router, actions } = renderDashboard()
    const region = await overview()
    const attention = await within(region).findByRole('table', { name: '확인할 설계사 상위 10명' })
    expect(within(region).getByRole('region', { name: '등록 설계사' })).toHaveTextContent('560')
    expect(within(region).getByRole('region', { name: '활동 설계사' })).toHaveTextContent('540')
    expect(within(region).getByRole('region', { name: '해촉 설계사' })).toHaveTextContent('40')
    const branches = within(region).getByRole('table', { name: '지점별 설계사와 유효 계약 수' })
    const gangdong = within(branches).getByRole('row', { name: /강동지사/ })
    expect(gangdong).toHaveTextContent('300명')
    expect(gangdong).toHaveTextContent('6,200건')
    expect(gangdong).toHaveTextContent('12명')
    expect(within(region).getByText('전체 12명 중 상위 3명')).toBeInTheDocument()
    const first = within(attention).getByRole('row', { name: /A-FC-001/ })
    expect(first).toHaveTextContent('7건')
    expect(first).toHaveTextContent('미처리 예외 2건, 한도 근접 1건')
    expect(within(attention).getByRole('link', { name: '최해촉 (A-FC-004) 계약 목록 열기' })).toHaveTextContent('최해촉 (해촉)')
    expect(within(attention).getByRole('link', { name: '박중지 (A-FC-009) 계약 목록 열기' })).toHaveTextContent('박중지 (사용 중지)')
    expect(within(region).getByText(home.basis!)).toBeInTheDocument()
    await actions.click(within(attention).getByRole('link', { name: '김정산 (A-FC-001) 계약 목록 열기' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/contracts'))
    expect(new URLSearchParams(router.state.location.search).get('agentId')).toBe('1')
    expect(new URLSearchParams(router.state.location.search).get('contractDateTo')).toBe(today())
    expect(new URLSearchParams(router.state.location.search).get('month')).toBe('2026-07')
  })

  it('현황이 0건이면 0명과 빈 상태 문구를 보이고 로딩 중에는 숫자를 보이지 않는다', async () => {
    const pending = deferred<unknown>()
    homeResponse = () => pending.promise
    renderDashboard()
    const region = await overview()
    expect(within(region).getByRole('status')).toHaveTextContent('설계사 현황을 불러오는 중입니다.')
    expect(within(region).queryByRole('region', { name: '등록 설계사' })).not.toBeInTheDocument()
    pending.resolve(
      envelope({
        ...home,
        agents: { registered: 0, active: 0, terminated: 0 },
        branches: [],
        attentionTotal: 0,
        attentionAgents: [],
      }),
    )
    expect(await within(region).findByRole('region', { name: '등록 설계사' })).toHaveTextContent('0')
    expect(within(region).getByText('확인할 설계사가 없습니다')).toBeInTheDocument()
    expect(within(region).getByText('표시할 지점이 없습니다')).toBeInTheDocument()
    expect(within(region).queryByText(/상위 0명/)).not.toBeInTheDocument()
  })

  it('현황 오류는 그 영역에 재시도만 보이고 검증 지표와 설계사 찾기는 유지한다', async () => {
    let fail = true
    homeResponse = async () => {
      if (fail) throw serverError()
      return envelope(home)
    }
    const { actions } = renderDashboard()
    const region = await overview()
    const alert = await within(region).findByRole('alert')
    expect(alert).toHaveTextContent('처리 중 오류가 발생했습니다.')
    expect(alert).toHaveTextContent('아직 불러온 적이 없습니다.')
    expect(within(region).queryByRole('region', { name: '등록 설계사' })).not.toBeInTheDocument()
    expect(await screen.findByRole('region', { name: '주요 검증 지표' })).toBeInTheDocument()
    expect(within(region).getByLabelText('이름 또는 코드')).toBeEnabled()
    fail = false
    await actions.click(within(alert).getByRole('button', { name: '다시 시도' }))
    expect(await within(region).findByRole('region', { name: '등록 설계사' })).toHaveTextContent('560')
  })

  it('갱신에 실패하면 이전 숫자 대신 마지막 갱신 시각과 재시도를 보여 준다', async () => {
    renderDashboard()
    const region = await overview()
    await within(region).findByRole('region', { name: '등록 설계사' })
    homeResponse = async () => {
      throw serverError()
    }
    await clients[0].refetchQueries({ queryKey: ['home'] })
    const alert = await within(region).findByRole('alert')
    expect(alert).toHaveTextContent(/마지막 갱신 \d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
    expect(within(region).queryByRole('region', { name: '등록 설계사' })).not.toBeInTheDocument()
  })

  it('검증 지표 오류가 나도 설계사 현황은 그대로 보인다', async () => {
    summaryResponse = async () => {
      throw serverError()
    }
    renderDashboard()
    const region = await overview()
    expect(await within(region).findByRole('region', { name: '등록 설계사' })).toHaveTextContent('560')
    const alerts = await screen.findAllByRole('alert')
    expect(alerts).toHaveLength(1)
    expect(alerts[0]).toHaveTextContent('처리 중 오류가 발생했습니다.')
    expect(screen.queryByRole('region', { name: '주요 검증 지표' })).not.toBeInTheDocument()
  })

  it('기준일을 바꾸면 늦게 온 이전 응답을 최신처럼 보이지 않는다', async () => {
    const slow = deferred<unknown>()
    homeResponse = (path) =>
      path.includes('asOf=2026-06-30')
        ? Promise.resolve(envelope({ ...home, asOf: '2026-06-30', agents: { registered: 111, active: 100, terminated: 1 } }))
        : slow.promise
    const { router } = renderDashboard()
    const region = await overview()
    fireEvent.change(within(region).getByLabelText('현황 기준일'), { target: { value: '2026-06-30' } })
    await waitFor(() => expect(router.state.location.search).toContain('asOf=2026-06-30'))
    expect(await within(region).findByRole('region', { name: '등록 설계사' })).toHaveTextContent('111')
    slow.resolve(envelope(home))
    await slow.promise
    await new Promise((done) => setTimeout(done))
    expect(within(region).getByRole('region', { name: '등록 설계사' })).toHaveTextContent('111')
    expect(summaryCalls()).toHaveLength(1)
  })

  it('기준일을 치는 도중의 값이나 지운 칸으로는 조회하지 않는다', async () => {
    renderDashboard()
    const region = await overview()
    await within(region).findByRole('region', { name: '등록 설계사' })
    const input = within(region).getByLabelText('현황 기준일')
    const before = callsTo('/home/summary').length
    fireEvent.change(input, { target: { value: '0202-11-04' } })
    expect(input).toHaveValue('0202-11-04')
    fireEvent.change(input, { target: { value: '' } })
    expect(callsTo('/home/summary')).toHaveLength(before)
    fireEvent.blur(input)
    expect(input).toHaveValue(today())
    fireEvent.change(input, { target: { value: '2026-06-30' } })
    await waitFor(() => expect(callsTo('/home/summary').at(-1)).toBe('/api/v1/home/summary?asOf=2026-06-30'))
    expect(input).toHaveValue('2026-06-30')
  })

  it('정산월을 바꿔도 현황 기준일은 그대로이고 설계사 링크만 새 정산월을 따른다', async () => {
    const { actions } = renderDashboard()
    const region = await overview()
    await within(region).findByRole('table', { name: '확인할 설계사 상위 10명' })
    await actions.click(screen.getByRole('button', { name: '기준 정산월 2026-07' }))
    await actions.click(screen.getByRole('gridcell', { name: '8월' }))
    await actions.click(screen.getByRole('button', { name: '적용' }))
    await waitFor(() => expect(summaryCalls()).toContain('/api/v1/dashboard/summary?month=2026-08'))
    expect(screen.getByText('기준 정산월 2026년 8월')).toBeInTheDocument()
    expect(within(region).getByLabelText('현황 기준일')).toHaveValue(today())
    expect(new Set(callsTo('/home/summary'))).toEqual(new Set([`/api/v1/home/summary?asOf=${today()}`]))
    expect(await within(region).findByRole('link', { name: '김정산 (A-FC-001) 계약 목록 열기' })).toHaveAttribute(
      'href',
      `/app/contracts?agentId=1&contractDateTo=${today()}&month=2026-08`,
    )
  })

  it('이름과 코드로 찾기 전에는 설계사를 나열하지 않고, 찾은 설계사의 계약 목록을 연다', async () => {
    agentsResponse = async () => envelope(agentPage(['김정산']))
    const { router, actions } = renderDashboard()
    const region = await overview()
    const search = within(region).getByRole('region', { name: '설계사 찾기' })
    expect(callsTo('/base/agents')).toEqual([])
    expect(within(search).queryByRole('table')).not.toBeInTheDocument()
    fireEvent.change(within(search).getByLabelText('이름 또는 코드'), { target: { value: '  김정산 ' } })
    await actions.click(within(search).getByRole('button', { name: '조회' }))
    const table = await within(search).findByRole('table', { name: '설계사 검색 결과' })
    expect(callsTo('/base/agents')).toEqual([
      `/api/v1/base/agents?asOf=${today()}&keyword=%EA%B9%80%EC%A0%95%EC%82%B0&includeTerminated=true&page=1&size=10`,
    ])
    expect(within(search).getByText('찾은 설계사 1명')).toBeInTheDocument()
    expect(router.state.location.search).toContain('agentKeyword=')
    await actions.click(within(table).getByRole('link', { name: '김정산 (A-100) 계약 목록 열기' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/contracts'))
    expect(new URLSearchParams(router.state.location.search).get('agentId')).toBe('100')
    expect(new URLSearchParams(router.state.location.search).get('contractDateTo')).toBe(today())
  })

  it('지점으로 찾고 쪽을 넘기며, 결과가 없으면 안내 문구를 보인다', async () => {
    agentsResponse = async (path) =>
      envelope(path.includes('page=2') ? agentPage(['둘째쪽'], 2, 11) : agentPage(['첫쪽'], 2, 11))
    const { actions } = renderDashboard()
    const region = await overview()
    await within(region).findByRole('table', { name: '지점별 설계사와 유효 계약 수' })
    const search = within(region).getByRole('region', { name: '설계사 찾기' })
    await actions.selectOptions(within(search).getByLabelText('지점'), '강동지사')
    await actions.click(within(search).getByRole('button', { name: '조회' }))
    expect(await within(search).findByRole('link', { name: /첫쪽/ })).toBeInTheDocument()
    expect(callsTo('/base/agents').at(-1)).toContain('branchId=11')
    expect(within(search).getByText('찾은 설계사 11명')).toBeInTheDocument()
    await actions.click(within(search).getByRole('button', { name: '2' }))
    expect(await within(search).findByRole('link', { name: /둘째쪽/ })).toBeInTheDocument()
    expect(callsTo('/base/agents').at(-1)).toContain('page=2')
    agentsResponse = async () => envelope(agentPage([]))
    fireEvent.change(within(search).getByLabelText('이름 또는 코드'), { target: { value: '없는사람' } })
    await actions.click(within(search).getByRole('button', { name: '조회' }))
    expect(await within(search).findByText('조건에 맞는 설계사가 없습니다')).toBeInTheDocument()
  })

  it('새로 찾는 동안 이전 결과를 보이지 않고, 검색 오류는 그 자리에서 다시 시도한다', async () => {
    agentsResponse = async () => envelope(agentPage(['이전결과']))
    const { actions } = renderDashboard()
    const region = await overview()
    const search = within(region).getByRole('region', { name: '설계사 찾기' })
    fireEvent.change(within(search).getByLabelText('이름 또는 코드'), { target: { value: '이전' } })
    await actions.click(within(search).getByRole('button', { name: '조회' }))
    await within(search).findByRole('link', { name: /이전결과/ })

    const pending = deferred<unknown>()
    agentsResponse = () => pending.promise
    fireEvent.change(within(search).getByLabelText('이름 또는 코드'), { target: { value: '새검색' } })
    await actions.click(within(search).getByRole('button', { name: '조회' }))
    expect(await within(search).findByRole('status')).toHaveTextContent('설계사를 찾는 중입니다.')
    expect(within(search).queryByRole('link', { name: /이전결과/ })).not.toBeInTheDocument()
    pending.reject(serverError())
    const alert = await within(search).findByRole('alert')
    expect(alert).toHaveTextContent('처리 중 오류가 발생했습니다.')
    expect(within(region).getByRole('region', { name: '등록 설계사' })).toHaveTextContent('560')
    agentsResponse = async () => envelope(agentPage(['새결과']))
    await actions.click(within(alert).getByRole('button', { name: '다시 시도' }))
    expect(await within(search).findByRole('link', { name: /새결과/ })).toBeInTheDocument()
  })
})
