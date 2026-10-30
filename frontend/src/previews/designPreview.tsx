import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, Link, RouterProvider } from 'react-router'
import { AppShellView } from '../app/shell/AppShell'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import type { DashboardSummary } from '../features/dashboard/api'
import { Button } from '../components/Button'
import { Field } from '../components/Field'
import { StatusBadge } from '../components/StatusBadge'
import '../index.css'
import '../styles/variables.css'
import '../styles/reset.css'
import '../styles/layout.css'
import '../styles/components.css'
import '../styles/utilities.css'
import '../styles/react.css'
import '../features/dashboard/dashboard.css'

if (!import.meta.env.DEV) throw new Error('Design preview is only available in the development server.')

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
  if (url.pathname === '/api/v1/dashboard/summary' && (!init?.method || init.method === 'GET'))
    return Response.json({
      data: { ...summary, month: `${url.searchParams.get('month') ?? '2026-07'}-01` },
      error: null,
      requestId: 'design-preview',
    })
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
    element: (
      <AppShellView
        user={{
          loginId: 'design-preview',
          userName: '디자인 미리보기',
          roleCode: 'SETTLEMENT',
          canProcess: true,
          canViewAuditLog: false,
        }}
        defaultMonth="2026-07"
      />
    ),
    children: [
      {
        path: '/',
        element: (
          <>
            <p className="page-description" style={{ marginBottom: '1rem' }}>
              디자인 미리보기용 예시 데이터입니다. 상단 개발자 탭에서 설정을 변경하세요.
            </p>
            <DashboardPage />
            <section className="surface design-component-samples" aria-label="공통 컴포넌트 미리보기">
              <h2>공통 컴포넌트 미리보기</h2>
              <p className="page-description">개발자 설정에서 버튼, 입력, 테이블, 상태 표시를 각각 바꿔보세요.</p>
              <div className="design-sample-controls">
                <Field label="미리보기 입력">
                  <input placeholder="내용을 입력하세요" />
                </Field>
                <Field label="미리보기 선택">
                  <select>
                    <option>전체</option>
                    <option>검토 중</option>
                  </select>
                </Field>
                <Button>기본 버튼</Button>
                <Button variant="secondary">보조 버튼</Button>
                <Button disabled>비활성</Button>
              </div>
              <div className="design-sample-statuses">
                <StatusBadge tone="neutral">신규</StatusBadge>
                <StatusBadge tone="success">정상</StatusBadge>
                <StatusBadge tone="warning">주의</StatusBadge>
                <StatusBadge tone="error">위반</StatusBadge>
              </div>
            </section>
          </>
        ),
      },
      {
        path: '*',
        element: (
          <div className="empty-state">
            <p>이 미리보기에서는 대시보드 디자인을 확인할 수 있습니다.</p>
            <Link to="/">대시보드로 돌아가기</Link>
          </div>
        ),
      },
    ],
  },
])
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}>
    <RouterProvider router={router} />
  </QueryClientProvider>,
)
