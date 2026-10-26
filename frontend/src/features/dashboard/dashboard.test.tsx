import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient, ApiError } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import type { DashboardSummary } from './api'

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
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'dash-test' })
const clients: QueryClient[] = []
const insights = {
  month: '2026-07-01',
  insurerBreakdown: [
    { insurerName: '가나다생명', capViolation: 9, capWarning: 3 },
    { insurerName: '라마바화재', capViolation: 2, capWarning: 0 },
  ],
  monthlyTrend: ['2026-02', '2026-03', '2026-04', '2026-05', '2026-06', '2026-07'].map((m, i) => ({
    month: `${m}-01`,
    capViolation: i * 10,
    capWarning: i,
    reconMismatch: 2,
  })),
}
let summaryResponse: () => Promise<unknown>
let insightsResponse: () => Promise<unknown>

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
  sessionStorage.clear()
  summaryResponse = async () => envelope(summary)
  insightsResponse = async () => envelope(insights)
  vi.spyOn(apiClient, 'request').mockImplementation((async (path: string) =>
    path.startsWith('/api/v1/dashboard/summary')
      ? summaryResponse()
      : path.startsWith('/api/v1/dashboard/insights')
        ? insightsResponse()
        : envelope(user)) as typeof apiClient.request)
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
const summaryCalls = () =>
  vi
    .mocked(apiClient.request)
    .mock.calls.map(([path]) => path)
    .filter((path) => path.includes('/dashboard/summary'))

describe('DASH-W01 업무 대시보드', () => {
  it('KPI 6종과 최근 예외·실행을 형식 규칙에 맞게 보여 준다', async () => {
    renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    expect(within(kpis).getAllByRole('link')).toHaveLength(6)
    expect(within(kpis).getByRole('link', { name: '1,200% 위반 1,234건' })).toHaveTextContent('1,234건')
    expect(summaryCalls()).toEqual(['/api/v1/dashboard/summary?month=2026-07'])
    const table = screen.getByRole('list', { name: '최근 예외 목록' })
    expect(within(table).getByText(/C-0001/)).toBeInTheDocument()
    // UTC 15:30 은 Asia/Seoul 로 다음 날 00:30 이다.
    expect(within(table).getByText('2026-07-11 00:30')).toBeInTheDocument()
    const run = screen.getByRole('link', { name: /2026-07-01 · 2회차/ })
    expect(within(run).getByRole('progressbar', { name: '검증 진행률' })).toHaveAttribute('aria-valuenow', '40')
    expect(run).toHaveTextContent('4/10 단계 · 실행 settle01')
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
    expect(screen.getByRole('link', { name: /2026-07-01 · 2회차/ })).toHaveAttribute(
      'href',
      '/app/validation-runs/77?month=2026-07',
    )
  })

  it('데이터가 0건이면 KPI는 0건, 목록은 빈 상태 문구를 보여 준다', async () => {
    summaryResponse = async () =>
      envelope({ ...summary, capViolation: 0, capWarning: 0, openException: 0, recentExceptions: [], recentRuns: [] })
    renderDashboard()
    const kpis = await screen.findByRole('region', { name: '주요 검증 지표' })
    expect(within(kpis).getByRole('link', { name: '1,200% 위반 0건' })).toHaveTextContent('0건')
    expect(screen.getAllByText('조건에 맞는 자료가 없습니다.')).toHaveLength(2)
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
    vi.mocked(apiClient.request).mockImplementation((async (path: string) =>
      path.startsWith('/api/v1/dashboard/summary')
        ? envelope(summary)
        : envelope({
            ...user,
            loginId: 'comp01',
            roleCode: 'COMPLIANCE',
            canProcess: false,
            canHandleException: false,
            canReverseJournal: false,
          })) as typeof apiClient.request)
    renderDashboard()
    expect(await screen.findByRole('region', { name: '주요 검증 지표' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '403 · 권한 없음' })).toBeNull()
  })
})

describe('DASH-W01 차트', () => {
  it('최근 6개월 추이와 보험사별 건수를 보여 준다', async () => {
    renderDashboard()
    const trend = await screen.findByRole('table', { name: '최근 월별 검증 건수 추이' })
    expect(within(trend).getAllByRole('row')).toHaveLength(7)
    expect(within(trend).getByRole('row', { name: /7월/ })).toHaveTextContent('50건')
    expect(await screen.findByText('가나다생명')).toBeInTheDocument()
    expect(screen.getByText('위반 9 · 주의 3')).toBeInTheDocument()
    expect(vi.mocked(apiClient.request).mock.calls.map(([path]) => path)).toContain(
      '/api/v1/dashboard/insights?month=2026-07',
    )
  })

  it('차트 조회가 실패해도 KPI는 보이고 다시 시도하면 복구한다', async () => {
    let fail = true
    insightsResponse = async () => {
      if (fail) throw new ApiError({ code: 'FGC-COMMON-500', message: '처리 중 오류가 발생했습니다.' }, 'req-2', 500)
      return envelope(insights)
    }
    const { actions } = renderDashboard()
    expect(await screen.findByRole('region', { name: '주요 검증 지표' })).toBeInTheDocument()
    const alert = await screen.findAllByRole('alert')
    expect(alert.length).toBeGreaterThan(0)
    fail = false
    await actions.click(screen.getAllByRole('button', { name: '다시 시도' })[0])
    expect(await screen.findByText('가나다생명')).toBeInTheDocument()
  })

  it('판정이 난 보험사가 없으면 빈 상태 문구를 보여 준다', async () => {
    insightsResponse = async () => envelope({ ...insights, insurerBreakdown: [] })
    renderDashboard()
    expect(await screen.findByText('이번 달 위반·주의 판정이 난 보험사가 없습니다.')).toBeInTheDocument()
  })
})
