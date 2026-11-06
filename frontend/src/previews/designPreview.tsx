import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, Link, RouterProvider } from 'react-router'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import type { BranchDashboard, BranchDetailPage, DashboardCommission, DashboardSummary, HomeSummary } from '../features/dashboard/api'
import { Button } from '../components/Button'
import { Field } from '../components/Field'
import { StatusBadge } from '../components/StatusBadge'
import { ContractStatusBadge } from '../features/contract/badges'
import CapListPage from '../features/cap/CapListPage'
import AcquisitionCostPage from '../features/acquisition-cost/AcquisitionCostPage'
import ArbitragePage from '../features/arbitrage/ArbitragePage'
import { ExceptionPage } from '../features/exception/ExceptionPage'
import ReconciliationPage from '../features/reconciliation/ReconciliationPage'
import ValidationRunDetailPage from '../features/validation/ValidationRunDetailPage'
import { AgentHomePage } from '../features/agent-home/AgentHomePage'
import ClawbackPage from '../features/clawback/ClawbackPage'
import ContractListPage from '../features/contract/ContractListPage'
import TransactionListPage from '../features/transaction/TransactionListPage'
import AgentStatementPage from '../features/agent-statement/AgentStatementPage'
import ScheduleListPage from '../features/schedule/ScheduleListPage'
import LedgerPage from '../features/ledger/LedgerPage'
import ValidationRunListPage from '../features/validation/ValidationRunListPage'
import AuditLogPage from '../features/audit/AuditLogPage'
import { BasePage } from '../features/base/BasePage'
import { designPreviewData } from './designFixtures'
import { DesignPreviewShell, PreviewNavigation } from './DesignPreviewShell'
import '../index.css'
import '../styles/variables.css'
import '../styles/reset.css'
import '../styles/layout.css'
import '../styles/components.css'
import '../styles/utilities.css'
import '../styles/react.css'
import '../features/dashboard/dashboard.css'
import '../features/arbitrage/arbitrage.css'
import '../features/exception/exception.css'
import './designPreview.css'

if (!import.meta.env.DEV) throw new Error('Design preview is only available in the development server.')

const homeSummary: HomeSummary = {
  asOf: '2026-11-04',
  agents: { registered: 560, active: 540, terminated: 40 },
  branches: [
    { organizationId: 1, name: '예시 강동지사', agentCount: 47, activeContractCount: 980, attentionCount: 6 },
    { organizationId: 2, name: '예시 마포지사', agentCount: 45, activeContractCount: 960, attentionCount: 3 },
  ],
  attentionTotal: 9,
  attentionAgents: [
    {
      agentId: 1,
      name: '예시 설계사',
      code: 'PREVIEW-FC-001',
      branch: '예시 강동지사',
      status: 'ACTIVE',
      statusLabel: '활동',
      contractCount: 12,
      reasons: [
        { code: 'OPEN_EXCEPTION', label: '미처리 예외', count: 2 },
        { code: 'NEAR_LIMIT', label: '한도 근접', count: 1 },
      ],
    },
  ],
  basis: '디자인 미리보기 예시 값입니다.',
}
const summary: DashboardSummary = {
  month: '2026-07-01',
  capViolation: 3,
  capWarning: 0,
  arbitrageCandidate: 6,
  reconMismatch: 29,
  journalImbalance: 0,
  openException: 41,
  recentExceptions: [
    {
      exceptionCaseId: 1,
      severity: 'HIGH',
      severityLabel: '높음',
      exceptionType: 'RECON_MISMATCH',
      exceptionTypeLabel: '대사 불일치',
      contractNo: 'PREVIEW-2026-001',
      title: '실제 지급 누락 (예상 5,000원)',
      status: 'NEW',
      statusLabel: '신규',
      createdAt: '2026-10-29T07:04:00Z',
    },
    {
      exceptionCaseId: 2,
      severity: 'CRITICAL',
      severityLabel: '긴급',
      exceptionType: 'CAP_VIOLATION',
      exceptionTypeLabel: '1,200% 위반',
      contractNo: 'PREVIEW-2026-002',
      title: '1,200% 한도 초과 (사용률 108.3%)',
      status: 'IN_REVIEW',
      statusLabel: '검토 중',
      createdAt: '2026-10-29T07:04:00Z',
    },
    {
      exceptionCaseId: 3,
      severity: 'HIGH',
      severityLabel: '높음',
      exceptionType: 'ARBITRAGE_CANDIDATE',
      exceptionTypeLabel: '차익거래 검토대상',
      contractNo: 'PREVIEW-2026-003',
      title: '차익거래 검토대상',
      status: 'NEW',
      statusLabel: '신규',
      createdAt: '2026-10-29T07:03:00Z',
    },
    {
      exceptionCaseId: 4,
      severity: 'MEDIUM',
      severityLabel: '주의',
      exceptionType: 'RECON_MISMATCH',
      exceptionTypeLabel: '대사 불일치',
      contractNo: 'PREVIEW-2026-004',
      title: '예상 지급액과 실제 지급액 차이',
      status: 'IN_REVIEW',
      statusLabel: '검토 중',
      createdAt: '2026-10-29T07:02:00Z',
    },
  ],
  recentRuns: [
    {
      validationRunId: 1,
      validationMonth: '2026-07-01',
      runNo: 1,
      status: 'CALCULATED',
      statusLabel: '계산 완료',
      currentStep: 8,
      triggeredBy: 'preview',
    },
  ],
}

// This separate preview document never falls through to a real API.
const originalFetch = globalThis.fetch
globalThis.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.origin)
  if (!url.pathname.startsWith('/api/')) return originalFetch(input, init)
  const method = init?.method ?? (input instanceof Request ? input.method : 'GET')
  if (method !== 'GET')
    return Response.json(
      {
        data: null,
        error: { code: 'PREVIEW_READ_ONLY', message: '예시 데이터 조회만 지원합니다.' },
        requestId: 'design-preview',
      },
      { status: 405 },
    )
  if (url.pathname === '/api/v1/dashboard/summary')
    return Response.json({
      data: { ...summary, month: `${url.searchParams.get('month') ?? '2026-07'}-01` },
      error: null,
      requestId: 'design-preview',
    })
  if (url.pathname === '/api/v1/dashboard/monthly-commissions') {
    const selected = url.searchParams.get('month') ?? '2026-07'
    const [year, month] = selected.split('-').map(Number)
    const data: DashboardCommission = {
      months: [0.7, 0.85, 0.78, 1.1, 1, 1.25].map((factor, index) => ({
        month: new Date(Date.UTC(year, month - 6 + index, 1)).toISOString().slice(0, 10),
        gaAmount: 12000000 * factor,
        agentAmount: 8000000 * factor,
      })),
    }
    return Response.json({ data, error: null, requestId: 'design-preview' })
  }
  if (url.pathname === '/api/v1/dashboard/branches') {
    const selected = url.searchParams.get('month') ?? '2026-07'
    const data: BranchDashboard = {
      month: `${selected}-01`, asOf: `${selected}-28`,
      branches: [
        { organizationId: 1, name: '강동지사', registeredAgents: 47, activeAgents: 42, newContracts: 21, validContracts: 980, gaAmount: 9000000, agentAmount: 6000000, openExceptions: 9 },
        { organizationId: 2, name: '마포지사', registeredAgents: 45, activeAgents: 40, newContracts: 24, validContracts: 960, gaAmount: 6000000, agentAmount: 4000000, openExceptions: 3 },
        { organizationId: 3, name: '동부본부', registeredAgents: 1, activeAgents: 1, newContracts: 0, validContracts: 0, gaAmount: 0, agentAmount: 0, openExceptions: 0 },
      ],
    }
    return Response.json({ data, error: null, requestId: 'design-preview' })
  }
  if (url.pathname === '/api/v1/dashboard/branch-details') {
    const metric = url.searchParams.get('metric')
    const organizationId = url.searchParams.get('organizationId') ?? '1'
    const page = Number(url.searchParams.get('page') ?? '1')
    const count = page === 1 ? 10 : 2
    const data: BranchDetailPage = { page, size: 10, totalPages: 2, totalElements: 12, sort: 'id,desc', content: Array.from({ length: count }, (_, index) => ({
      id: index + (page - 1) * 10 + 1,
      label: metric === 'AGENTS' ? `예시 설계사 ${index + 1}` : metric === 'EXCEPTIONS' ? `미처리 예외 ${index + 1}` : `BRANCH-${organizationId}-${(page - 1) * 10 + index + 1}`,
      detail: metric === 'AGENTS' ? `FC-${organizationId}-${index + 1}` : '2026-07-01',
      status: metric === 'EXCEPTIONS' ? 'NEW' : 'ACTIVE', value: 750000, secondaryValue: 500000,
    })) }
    return Response.json({ data, error: null, requestId: 'design-preview' })
  }
  if (url.pathname === '/api/v1/exceptions/counts')
    return Response.json({
      data: { total: summary.openException, bySeverity: [], byType: [] },
      error: null,
      requestId: 'design-preview',
    })
  if (url.pathname === '/api/v1/home/summary')
    return Response.json({ data: homeSummary, error: null, requestId: 'design-preview' })
  if (url.pathname === '/api/v1/acquisition-cost-checks')
    return Response.json({ data: { content: [], totalElements: 0, totalPages: 0, page: 1, size: 20 }, error: null, requestId: 'design-preview' })
  const data = designPreviewData(url)
  if (data !== undefined) return Response.json({ data, error: null, requestId: 'design-preview' })
  return Response.json(
    {
      data: null,
      error: { code: 'PREVIEW_ONLY', message: '디자인 미리보기에서는 지원하지 않습니다.' },
      requestId: 'design-preview',
    },
    { status: 404 },
  )
}
const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
const router = createMemoryRouter([
  {
    element: <DesignPreviewShell />,
    children: [
      {
        path: '/',
        element: (
          <>
            <PreviewNavigation />
            <p className="page-description" style={{ marginBottom: '1rem' }}>
              확정된 화면 디자인을 예시 데이터로 확인하는 미리보기입니다.
            </p>
            <DashboardPage />
            <section data-guide-feature="previews.design-preview.section" className="surface design-component-samples" aria-label="공통 컴포넌트 미리보기">
              <h2>공통 컴포넌트 미리보기</h2>
              <p className="page-description">A안 공통 버튼과 상태별 반투명 배지를 확인할 수 있습니다.</p>
              <div className="design-sample-controls">
                <Field label="미리보기 입력">
                  <input data-guide-feature="previews.design-preview.input" placeholder="내용을 입력하세요" />
                </Field>
                <Field label="미리보기 선택">
                  <select data-guide-feature="previews.design-preview.select">
                    <option>전체</option>
                    <option>검토 중</option>
                  </select>
                </Field>
                <Link data-guide-feature="previews.design-preview.link" className="button button-primary" to="/">
                  ＋ 신규 등록
                </Link>
                <Button data-guide-feature="previews.design-preview.button">조회</Button>
                <Button data-guide-feature="previews.design-preview.button-2" variant="secondary">
                  <span className="material-symbols-rounded" aria-hidden="true">
                    restart_alt
                  </span>
                  초기화
                </Button>
                <Button data-guide-feature="previews.design-preview.button-3" disabled>비활성</Button>
              </div>
              <div className="design-sample-statuses">
                <StatusBadge tone="neutral">신규</StatusBadge>
                <StatusBadge tone="success">정상</StatusBadge>
                <StatusBadge tone="warning">주의</StatusBadge>
                <StatusBadge tone="error">위반</StatusBadge>
                <ContractStatusBadge code="ACTIVE" />
                <ContractStatusBadge code="UNPAID" />
                <ContractStatusBadge code="LAPSED" />
              </div>
            </section>
          </>
        ),
      },
      ...[
        { path: '/contracts', element: <ContractListPage /> },
        { path: '/transactions', element: <TransactionListPage /> },
        { path: '/agent/statements', element: <AgentStatementPage /> },
        { path: '/schedules', element: <ScheduleListPage /> },
        { path: '/journals', element: <LedgerPage /> },
        { path: '/base', element: <BasePage /> },
        { path: '/audit-logs', element: <AuditLogPage /> },
        { path: '/validation-runs', element: <ValidationRunListPage /> },
        { path: '/cap-checks', element: <CapListPage /> },
        { path: '/acquisition-cost-checks', element: <AcquisitionCostPage /> },
        { path: '/arbitrage-checks', element: <ArbitragePage /> },
        { path: '/clawbacks', element: <ClawbackPage /> },
        { path: '/exceptions', element: <ExceptionPage /> },
        { path: '/reconciliations', element: <ReconciliationPage /> },
        { path: '/validation-runs/:id', element: <ValidationRunDetailPage /> },
        { path: '/agent-home', element: <AgentHomePage /> },
      ].map((route) => ({
        ...route,
        element: (
          <>
            <PreviewNavigation />
            {route.element}
          </>
        ),
      })),
      {
        path: '*',
        element: (
          <div className="empty-state">
            <p>이 미리보기에서는 대시보드 디자인을 확인할 수 있습니다.</p>
            <Link data-guide-feature="previews.design-preview.link-2" to="/">대시보드로 돌아가기</Link>
          </div>
        ),
      },
    ],
  },
])
const root = createRoot(document.getElementById('root')!)
root.render(
  <QueryClientProvider client={client}>
    <RouterProvider router={router} />
  </QueryClientProvider>,
)
if (import.meta.hot) {
  import.meta.hot.accept()
  import.meta.hot.dispose(() => {
    root.unmount()
    router.dispose()
    client.clear()
    globalThis.fetch = originalFetch
  })
}
