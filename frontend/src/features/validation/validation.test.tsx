import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient, ApiError } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useToastStore } from '../../stores/toasts'
import { useWorkspaceStore } from '../../stores/workspace'
import { stepState } from './steps'

const baseUser = {
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
const gaAdmin = { ...baseUser, loginId: 'ga01', roleCode: 'GA_ADMIN', canProcess: false, canFinalizeValidation: true }
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'vrun-test' })

const run = (over: Record<string, unknown> = {}) => ({
  validationRunId: 7,
  validationMonth: '2026-07-01',
  runNo: 2,
  runType: 'MONTHLY',
  runTypeLabel: '월간',
  status: 'COMPLETED',
  statusLabel: '계산완료',
  currentStep: 8,
  triggeredBy: 'settle01',
  startedAt: '2026-07-10T09:00:00+09:00',
  completedAt: '2026-07-10T09:30:00+09:00',
  ...over,
})
const detail = (header = run()) => ({
  header,
  targets: [
    {
      validationTargetId: 1,
      contractNo: 'C-0001',
      selectionStatus: 'SELECTED',
      selectionStatusLabel: '선정',
      productName: '종신보험',
      offeringVersion: 'v1',
      refundRateTableId: 5,
      selectionReason: '정상',
    },
  ],
  targetSummary: { selectedCount: 1, excludedCount: 0, reviewRequiredCount: 0 },
  capSummary: { checkedCount: 1200, violationCount: 3, warningCount: 2, reviewRequiredCount: 1 },
  arbitrageSummary: { checkedCount: 10, candidateCount: 4, reviewRequiredCount: 0 },
  ledgerSummary: { journalCount: 30, imbalanceCount: 0 },
  reconciliationSummary: { resultCount: 22, mismatchCount: 5 },
  exceptionSummary: {
    detectedCount: 9,
    newCount: 4,
    recurringCount: 3,
    reopenedCount: 1,
    notDetectedCount: 2,
    openWorkItemCount: 8,
  },
})
const conditions = (passed: boolean) =>
  Array.from({ length: 6 }, (_, index) => ({
    no: index + 1,
    label: `조건 ${index + 1}`,
    passed: passed || index > 0,
    count: passed || index > 0 ? 0 : 3,
    linkUrl: !passed && index === 0 ? '/exceptions?status=OPEN&type=CAP_VIOLATION' : null,
  }))

const clients: QueryClient[] = []
let currentUser = baseUser
let handlers: Record<
  string,
  (path: string, init?: { method?: string; body?: unknown; idempotencyKey?: string }) => unknown
>

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
  useToastStore.setState({ messages: [] })
  sessionStorage.clear()
  currentUser = baseUser
  handlers = {}
  vi.spyOn(apiClient, 'request').mockImplementation((async (
    path: string,
    init?: { method?: string; body?: unknown; idempotencyKey?: string },
  ) => {
    if (path.startsWith('/api/v1/auth/me')) return envelope(currentUser)
    const key = `${init?.method ?? 'GET'} ${path.split('?')[0]}`
    const handler = handlers[key]
    if (!handler) throw new Error(`unexpected request: ${key}`)
    return envelope(await handler(path, init))
  }) as typeof apiClient.request)
})
afterEach(() => {
  vi.restoreAllMocks()
  for (const client of clients.splice(0)) client.clear()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

function renderAt(path: string) {
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
const calls = (key: string) =>
  vi
    .mocked(apiClient.request)
    .mock.calls.filter(([path, init]) => `${init?.method ?? 'GET'} ${path.split('?')[0]}` === key)

const listPage = (content: unknown[], totalPages = 1) => ({
  content,
  page: 1,
  size: 20,
  totalElements: content.length,
  totalPages,
})

function detailHandlers(header = run(), checklist = conditions(true)) {
  handlers['GET /api/v1/validation-runs/7'] = () => detail(header)
  handlers['GET /api/v1/validation-runs'] = () => listPage([header])
  handlers['GET /api/v1/validation-runs/7/finalize-checklist'] = () => ({
    validationRunId: 7,
    passed: checklist.every((condition) => condition.passed),
    conditions: checklist,
  })
}

describe('스텝 규칙', () => {
  it('1차 stepClass 와 같은 상태를 계산한다', () => {
    expect(stepState(4, 'RUNNING', 3)).toBe('running')
    expect(stepState(3, 'RUNNING', 3)).toBe('done')
    expect(stepState(9, 'RUNNING', 8)).toBe('pending')
    expect(stepState(5, 'FAILED', 5)).toBe('failed')
    expect(stepState(10, 'FINALIZED', 10)).toBe('done')
    expect(stepState(2, 'CREATED', 1)).toBe('pending')
  })
})

describe('VRUN-W01 실행 목록', () => {
  it('목록을 표시하고 상태 필터·페이지를 URL 로 유지한다', async () => {
    handlers['GET /api/v1/validation-runs'] = () =>
      listPage(
        [
          run({ status: 'FAILED', statusLabel: '실패', failureMessage: '배치가 실패했습니다' }),
          run({
            validationRunId: 6,
            runNo: 1,
            status: 'FINALIZED',
            statusLabel: '확정(잠김)',
            currentStep: 10,
            finalizedAt: '2026-07-11T10:00:00+09:00',
            finalizedBy: 'ga01',
          }),
        ],
        3,
      )
    handlers['GET /api/v1/validation-runs/active-monthly'] = () => ({ exists: false })
    const { router, actions } = renderAt('/app/validation-runs?status=FAILED&page=2&month=2026-07')
    const table = await screen.findByRole('table', { name: '월 통합검증 실행 목록' })
    expect(await within(table).findByText('8/10 단계 (80%)')).toBeInTheDocument()
    expect(within(table).getAllByText('배치가 실패했습니다').length).toBeGreaterThan(0)
    expect(within(table).getAllByText('ga01 · 2026-07-11 10:00').length).toBeGreaterThan(0)
    expect(within(table).getByRole('link', { name: '2026-07 2회차 실행 열기' })).toHaveAttribute(
      'href',
      '/app/validation-runs/7?month=2026-07',
    )
    const listCall = calls('GET /api/v1/validation-runs')[0][0]
    expect(new URLSearchParams(listCall.split('?')[1]).get('status')).toBe('FAILED')
    expect(new URLSearchParams(listCall.split('?')[1]).get('page')).toBe('2')
    await actions.selectOptions(screen.getByLabelText('상태'), 'RUNNING')
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(router.state.location.search).toContain('status=RUNNING'))
  })

  it('새 실행을 만들면 상세로 이동한다', async () => {
    handlers['GET /api/v1/validation-runs'] = () => listPage([])
    handlers['GET /api/v1/validation-runs/active-monthly'] = () => ({ exists: false })
    handlers['POST /api/v1/validation-runs'] = (_path, init) => {
      expect(init?.body).toEqual({ validationMonth: '2026-07', runType: 'MONTHLY' })
      return { validationRunId: 7, runNo: 2, status: 'CREATED' }
    }
    detailHandlers(
      run({ status: 'CREATED', statusLabel: '생성됨', currentStep: 0, startedAt: undefined, completedAt: undefined }),
    )
    const { router, actions } = renderAt('/app/validation-runs')
    await actions.click(await screen.findByRole('button', { name: /실행 생성/ }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/validation-runs/7'))
    expect(await screen.findAllByText('실행 전', { selector: '.kpi-unit' }, { timeout: 3000 })).toHaveLength(4)
  })

  it('같은 달에 진행 중인 월간 실행이 있으면 생성 버튼을 끄고, 서버 오류는 안내한다', async () => {
    handlers['GET /api/v1/validation-runs'] = () => listPage([])
    handlers['GET /api/v1/validation-runs/active-monthly'] = () => ({ exists: true })
    renderAt('/app/validation-runs')
    const button = await screen.findByRole('button', { name: /실행 생성/ })
    await waitFor(() => expect(button).toBeDisabled())
    expect(screen.getByText('기준월에 진행 중인 월간 실행이 있어 새로 만들 수 없습니다.')).toBeInTheDocument()
  })

  it('서버가 중복 생성을 거절하면 오류를 안내하고 목록에 남는다', async () => {
    handlers['GET /api/v1/validation-runs'] = () => listPage([])
    handlers['GET /api/v1/validation-runs/active-monthly'] = () => ({ exists: false })
    handlers['POST /api/v1/validation-runs'] = () => {
      throw new ApiError({ code: 'FGC-VRUN-001', message: '이미 진행 중인 월간 실행이 있습니다.' }, 'req-1', 409)
    }
    const { router, actions } = renderAt('/app/validation-runs')
    await actions.click(await screen.findByRole('button', { name: /실행 생성/ }))
    expect(await screen.findByText(/이미 진행 중인 월간 실행이 있습니다/)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/validation-runs')
  })
})

describe('VRUN-W02 상세·실행·확정', () => {
  it('없는 실행은 404 안내를 보여 준다', async () => {
    handlers['GET /api/v1/validation-runs/99'] = () => {
      throw new ApiError({ code: 'FGC-COMMON-004', message: '없음' }, 'req-404', 404)
    }
    handlers['GET /api/v1/validation-runs'] = () => listPage([])
    renderAt('/app/validation-runs/99')
    expect(await screen.findByText('존재하지 않는 검증 실행입니다.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '실행 목록으로' })).toBeInTheDocument()
  })

  it('실행하면 진행률을 폴링하고 끝나면 결과를 다시 불러온다', async () => {
    let state = 0
    const created = run({ status: 'CREATED', statusLabel: '생성됨', currentStep: 0, completedAt: undefined })
    handlers['GET /api/v1/validation-runs/7'] = () => detail(state < 2 ? created : run())
    handlers['GET /api/v1/validation-runs'] = () => listPage([created])
    handlers['GET /api/v1/validation-runs/7/finalize-checklist'] = () => ({
      validationRunId: 7,
      passed: true,
      conditions: conditions(true),
    })
    handlers['POST /api/v1/validation-runs/7/execute'] = () => ({
      validationRunId: 7,
      status: 'RUNNING',
      statusLabel: '실행중',
      currentStep: 0,
      totalSteps: 10,
    })
    handlers['GET /api/v1/validation-runs/7/progress'] = () => {
      state += 1
      return state === 1
        ? { status: 'RUNNING', statusLabel: '실행중', currentStep: 3, totalSteps: 10, progressPct: 30 }
        : { status: 'COMPLETED', statusLabel: '계산완료', currentStep: 8, totalSteps: 10, progressPct: 80 }
    }
    const { actions } = renderAt('/app/validation-runs/7')
    await actions.click(await screen.findByRole('button', { name: /실행$/ }))
    expect(await screen.findByText('3/10 단계 (30%)', undefined, { timeout: 4000 })).toBeInTheDocument()
    expect(await screen.findByText('8/10 단계 (80%)', undefined, { timeout: 6000 })).toBeInTheDocument()
    await waitFor(() => expect(calls('GET /api/v1/validation-runs/7/finalize-checklist').length).toBeGreaterThan(0), {
      timeout: 4000,
    })
  }, 20000)

  it('GA_ADMIN 은 6개 조건 통과 후 확정하고 완료 모달을 본다 — 실패 후 재시도는 같은 멱등키를 쓴다', async () => {
    currentUser = gaAdmin
    detailHandlers()
    const keys: (string | undefined)[] = []
    handlers['POST /api/v1/validation-runs/7/finalize'] = (_path, init) => {
      keys.push(init?.idempotencyKey)
      if (keys.length === 1) throw new ApiError({ code: 'FGC-COMMON-500', message: '일시 오류' }, 'req-500', 500)
      return { status: 'FINALIZED', finalizedAt: '2026-07-11T10:00:00+09:00', finalizedBy: 'ga01' }
    }
    const { actions } = renderAt('/app/validation-runs/7')
    const finalize = await screen.findByRole('button', { name: '확정' })
    await waitFor(() => expect(finalize).toBeEnabled())
    for (const attempt of [1, 2]) {
      await actions.click(screen.getByRole('button', { name: '확정' }))
      const dialog = await screen.findByRole('dialog', { name: '이 검증 실행을 확정할까요?' })
      expect(within(dialog).getByText(/실제 송금·회계 마감이 아닙니다/)).toBeInTheDocument()
      await actions.click(within(dialog).getByRole('button', { name: '확정' }))
      if (attempt === 1) {
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
        await waitFor(() => expect(screen.getByRole('button', { name: '확정' })).toBeEnabled())
      }
    }
    expect(await screen.findByRole('dialog', { name: '확정했습니다' })).toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[0]).toMatch(/^vrun-finalize-7-/)
    expect(keys[1]).toBe(keys[0])
  })

  it('확정 조건이 미충족이면 확정을 막고 바로가기를 보여 준다', async () => {
    currentUser = gaAdmin
    detailHandlers(run(), conditions(false))
    renderAt('/app/validation-runs/7')
    expect(await screen.findByText('미충족 3건')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '조건 1 미충족 건 바로가기' })).toHaveAttribute(
      'href',
      '/app/exceptions?status=OPEN&type=CAP_VIOLATION',
    )
    expect(screen.getByRole('button', { name: '확정' })).toBeDisabled()
    expect(screen.getByText('확정 조건 6개를 모두 통과해야 합니다. 아래 체크리스트를 확인하세요.')).toBeInTheDocument()
  })

  it('SETTLEMENT 는 확정할 수 없고 사유를 안내한다', async () => {
    detailHandlers()
    renderAt('/app/validation-runs/7')
    expect(await screen.findByText('확정 권한은 GA_ADMIN 또는 SYSTEM_ADMIN 에게만 있습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '확정' })).toBeDisabled()
    expect(calls('POST /api/v1/validation-runs/7/finalize')).toHaveLength(0)
  })

  it('예외함 열기 링크는 검증월 필터를 싣고 외부 주소 바로가기는 막는다', async () => {
    currentUser = gaAdmin
    detailHandlers(
      run(),
      conditions(true).map((c, i) => (i === 0 ? { ...c, passed: false, count: 1, linkUrl: '//evil.example/x' } : c)),
    )
    renderAt('/app/validation-runs/7')
    expect(await screen.findByRole('link', { name: '예외함 열기' })).toHaveAttribute(
      'href',
      '/app/exceptions?status=OPEN&validationMonth=2026-07-01&month=2026-07',
    )
    await screen.findByText('미충족 1건')
    expect(screen.queryByRole('link', { name: /바로가기/ })).toBeNull()
  })
})
