import { act, render, screen, waitFor, within } from '@testing-library/react'
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
import { failureText, stepState } from './steps'
import { findWorkflowGuide } from '../assistant/workflow-catalog'

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
  Array.from({ length: 8 }, (_, index) => ({
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
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, actions: userEvent.setup(), client, unmount: view.unmount }
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
  handlers['GET /api/v1/validation-runs/7/restartability'] = () => ({ allowed: header.status === 'FAILED' && header.runType !== 'MANUAL_CONTRACT', expectedStateVersion: 'failed-1', lastFailedBatchExecutionId: 1, failedStep: 5, checkpointAvailable: true })
  handlers['GET /api/v1/validation-runs/7/retries'] = () => []

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
  it('실패 사유의 오류코드는 화면에 보이지 않고, 읽을 문장이 없으면 실패한 단계로 안내한다', () => {
    expect(failureText('FGC-VRUN-003', 5)).toBe('5단계에서 실패했습니다.')
    expect(failureText('FGC-VRUN-003 | FGC-COMMON-500', 0)).toBe('처리 중 오류로 실패했습니다.')
    expect(failureText('FGC-VRUN-003 | 스케줄 회차가 중복되었습니다.', 3)).toBe('스케줄 회차가 중복되었습니다.')
    expect(failureText('배치가 실패했습니다', 8)).toBe('배치가 실패했습니다')
    expect(failureText('한도 검증 보류 — 적용 가능한 1,200% 룰셋이 없습니다. 정책을 확인하세요.', 4)).toBe(
      '한도 검증을 보류합니다. 적용 가능한 1,200% 룰셋이 없습니다. 정책을 확인하세요.',
    )
    expect(failureText('journal_no 월간 일련번호 상한(99999) 초과, year=2026', 6)).toBe('6단계에서 실패했습니다.')
    expect(failureText('validation_run 5 은 이미 기동됨(status=RUNNING)', 2)).toBe('2단계에서 실패했습니다.')
    expect(failureText('could not execute statement [ERROR: e2e] | 원장 불균형 1건이 발견되었습니다.', 6)).toBe(
      '원장 불균형 1건이 발견되었습니다.',
    )
    expect(failureText('2단계 처리 중 오류가 발생했습니다. 요청번호 20261104-abcdef를 담당자에게 알려주세요.', 2)).toBe(
      '2단계 처리 중 오류가 발생했습니다. 요청번호 20261104-abcdef를 담당자에게 알려주세요.',
    )
    expect(failureText(null, 4)).toBe('4단계에서 실패했습니다.')
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
    expect(within(table).getAllByText('ga01, 2026-07-11 10:00').length).toBeGreaterThan(0)
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

  it('#604 헤더를 누르면 정렬 방향이 바뀌고 첫 페이지로 돌아가며 상태 조건을 유지한다', async () => {
    handlers['GET /api/v1/validation-runs'] = () => listPage([run()], 3)
    handlers['GET /api/v1/validation-runs/active-monthly'] = () => ({ exists: false })
    const { router, actions } = renderAt('/app/validation-runs?status=FAILED&page=2&month=2026-07')
    const table = await screen.findByRole('table', { name: '월 통합검증 실행 목록' })
    const lastQuery = () => new URLSearchParams(calls('GET /api/v1/validation-runs').at(-1)![0].split('?')[1])
    // 순서 기준이 없는 상태·실행 유형 컬럼은 정렬 버튼이 없다.
    expect(within(table).queryByRole('button', { name: '상태' })).toBeNull()
    expect(within(table).getByRole('columnheader', { name: /^시작/ })).toHaveAttribute('aria-sort', 'none')
    await actions.click(within(table).getByRole('button', { name: '시작' }))
    await waitFor(() => expect(lastQuery().get('sort')).toBe('startedAt,asc'))
    expect(lastQuery().get('page')).toBe('1')
    expect(lastQuery().get('status')).toBe('FAILED')
    expect(within(table).getByRole('columnheader', { name: /^시작/ })).toHaveAttribute('aria-sort', 'ascending')
    await actions.click(within(table).getByRole('button', { name: '시작' }))
    await waitFor(() => expect(router.state.location.search).toContain('sort=startedAt%2Cdesc'))
    expect(within(table).getByRole('columnheader', { name: /^시작/ })).toHaveAttribute('aria-sort', 'descending')
    // 상태 조건을 바꿔 조회해도 정렬은 유지된다.
    await actions.selectOptions(screen.getByLabelText('상태'), 'RUNNING')
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(router.state.location.search).toContain('status=RUNNING'))
    expect(router.state.location.search).toContain('sort=startedAt%2Cdesc')
  })

  it('#604 허용되지 않은 정렬 URL 값은 무시하고 서버 기본 정렬을 쓴다', async () => {
    handlers['GET /api/v1/validation-runs'] = () => listPage([run()], 1)
    handlers['GET /api/v1/validation-runs/active-monthly'] = () => ({ exists: false })
    renderAt('/app/validation-runs?sort=status,asc&month=2026-07')
    await screen.findByRole('table', { name: '월 통합검증 실행 목록' })
    expect(calls('GET /api/v1/validation-runs').every(([url]) => !url.includes('sort='))).toBe(true)
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
    expect(screen.getByText('선택한 검증월에 진행 중인 월간 실행이 있어 새로 만들 수 없습니다.')).toBeInTheDocument()
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

  it('실패한 월 실행을 재시작하고 이전 FAILED 폴링 응답은 무시한다', async () => {
    const failed = run({ status: 'FAILED', statusLabel: '실패', currentStep: 5, failureMessage: '25번째 실패' })
    detailHandlers(failed)
    let polls = 0
    handlers['POST /api/v1/validation-runs/7/execute'] = () => ({ validationRunId: 7, status: 'RUNNING' })
  handlers['POST /api/v1/validation-runs/7/restart'] = () => ({ validationRunId: 7, retryId: 1, status: 'ACCEPTED' })
    handlers['GET /api/v1/validation-runs/7/progress'] = () => {
      polls += 1
      return polls <= 2
        ? { status: 'FAILED', currentStep: 5, failureMessage: '25번째 실패', requestId: 'old-request', attemptId: 10 }
        : {
            status: 'FAILED',
            currentStep: 5,
            failureMessage: '다시 실패',
            requestId: 'other-tab-request',
            attemptId: 11,
          }
    }
    const { actions } = renderAt('/app/validation-runs/7')
    const restart = await screen.findByRole('button', { name: /실패 지점부터 재시작/ })
    await waitFor(() => expect(restart).toBeEnabled())
    await actions.click(restart)
    await actions.type(screen.getByRole('textbox', { name: '재시작 사유' }), '원인 수정')
    await actions.click(screen.getByRole('button', { name: '재시작 요청' }))
    await waitFor(() => expect(polls).toBe(2))
    expect(screen.getByRole('button', { name: /실패 지점부터 재시작/ })).toBeDisabled()
    await waitFor(() => expect(polls).toBeGreaterThan(2), { timeout: 5000 })
    await waitFor(() => expect(screen.getByRole('button', { name: /실패 지점부터 재시작/ })).toBeEnabled())
    expect(calls('POST /api/v1/validation-runs/7/restart')).toHaveLength(1)
  }, 15000)

  it('계약수동 실패는 월 재시작을 할 수 없다', async () => {
    detailHandlers(run({ status: 'FAILED', runType: 'MANUAL_CONTRACT', currentStep: 3 }))
    renderAt('/app/validation-runs/7')
    expect(await screen.findByRole('button', { name: /실행$/ })).toBeDisabled()
    expect(calls('POST /api/v1/validation-runs/7/restart')).toHaveLength(0)
  })

  it('실패한 월 실행도 조회 전용 사용자는 재시작할 수 없다', async () => {
    currentUser = gaAdmin
    detailHandlers(run({ status: 'FAILED', currentStep: 5 }))
    renderAt('/app/validation-runs/7')
    expect(await screen.findByRole('button', { name: /실패 지점부터 재시작/ })).toBeDisabled()
    expect(calls('POST /api/v1/validation-runs/7/restart')).toHaveLength(0)
  })

  it('실패 사유가 오류코드뿐이면 상세에 코드 대신 실패한 단계를 보인다', async () => {
    currentUser = gaAdmin
    detailHandlers(run({ status: 'FAILED', statusLabel: '실패', currentStep: 5, failureMessage: 'FGC-VRUN-003' }))
    renderAt('/app/validation-runs/7')
    expect(await screen.findByText('5단계에서 실패했습니다.')).toHaveAttribute('role', 'alert')
    expect(screen.queryByText(/FGC-VRUN-003/)).not.toBeInTheDocument()
  })

  it('다른 실행에 쓴 멱등키라는 거절(FGC-VRUN-006) 뒤에는 새 멱등키로 다시 확정한다', async () => {
    currentUser = gaAdmin
    detailHandlers()
    const keys: (string | undefined)[] = []
    handlers['POST /api/v1/validation-runs/7/finalize'] = (_path, init) => {
      keys.push(init?.idempotencyKey)
      if (keys.length === 1)
        throw new ApiError(
          { code: 'FGC-VRUN-006', message: '확정 불가 — 다른 확정 요청과 겹쳤습니다. 화면을 새로 열고 다시 확정하세요.' },
          'req-006',
          409,
        )
      return { status: 'FINALIZED', finalizedAt: '2026-07-11T10:00:00+09:00', finalizedBy: 'ga01' }
    }
    const { actions } = renderAt('/app/validation-runs/7')
    await waitFor(() => expect(screen.getByRole('button', { name: '확정' })).toBeEnabled())
    for (const attempt of [1, 2]) {
      await actions.click(screen.getByRole('button', { name: '확정' }))
      const dialog = await screen.findByRole('dialog', { name: '이 검증 실행을 확정할까요?' })
      await actions.click(within(dialog).getByRole('button', { name: '확정' }))
      if (attempt === 1) {
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
        await waitFor(() => expect(screen.getByRole('button', { name: '확정' })).toBeEnabled())
      }
    }
    expect(await screen.findByRole('dialog', { name: '확정했습니다' })).toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[1]).toMatch(/^vrun-finalize-7-/)
    expect(keys[1]).not.toBe(keys[0])
  })

  it('GA_ADMIN 은 8개 조건 통과 후 확정하고 완료 모달을 본다 — 실패 후 재시도는 같은 멱등키를 쓴다', async () => {
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
      expect(within(dialog).getByText(/실제 송금\/회계 마감이 아닙니다/)).toBeInTheDocument()
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
    expect(screen.getByText('확정 조건 8개를 모두 통과해야 합니다. 아래 체크리스트를 확인하세요.')).toBeInTheDocument()
  })

  it('SETTLEMENT 는 확정할 수 없고 사유를 안내한다', async () => {
    detailHandlers()
    renderAt('/app/validation-runs/7')
    // Wait for the passing server checklist, so loading state cannot hide a permission regression.
    expect(await screen.findByText('8. 조건 8', { selector: '.table-cell-preview' })).toBeInTheDocument()
    expect(await screen.findByText('확정은 GA관리자와 시스템관리자만 할 수 있습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '확정' })).toBeDisabled()
    expect(calls('POST /api/v1/validation-runs/7/finalize')).toHaveLength(0)
  })

  it.each([7, 8])('서버 확정 조건 %i 미충족도 표시하고 확정을 차단한다 (#418)', async (no) => {
    currentUser = gaAdmin
    const checklist = conditions(true)
    checklist[no - 1] = { ...checklist[no - 1], passed: false, count: 1 }
    detailHandlers(run(), checklist)
    renderAt('/app/validation-runs/7')
    expect(await screen.findByText(`${no}. 조건 ${no}`, { selector: '.table-cell-preview' })).toBeInTheDocument()
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

it('재시작 수락 후 새 시도가 없으면 자동 조회를 멈추고 수동 조회로 복귀하며 재전송하지 않는다', async () => {
  const nativeTimeout = window.setTimeout.bind(window)
  vi.spyOn(window, 'setTimeout').mockImplementation(((handler: TimerHandler, delay?: number, ...args: unknown[]) =>
    nativeTimeout(handler, delay === 120_000 ? 20 : delay, ...args)) as typeof window.setTimeout)
  detailHandlers(run({ status: 'FAILED', currentStep: 5 }))
  let advanced = false
  handlers['POST /api/v1/validation-runs/7/execute'] = () => ({ validationRunId: 7, status: 'RUNNING' })
  handlers['POST /api/v1/validation-runs/7/restart'] = () => ({ validationRunId: 7, retryId: 1, status: 'ACCEPTED' })
  handlers['GET /api/v1/validation-runs/7/progress'] = () => ({
    status: 'FAILED',
    currentStep: 5,
    attemptId: advanced ? 11 : 10,
  })
  const { actions } = renderAt('/app/validation-runs/7')
  const restart = await screen.findByRole('button', { name: /실패 지점부터 재시작/ })
  await waitFor(() => expect(restart).toBeEnabled())
  await actions.click(restart)
    await actions.type(screen.getByRole('textbox', { name: '재시작 사유' }), '원인 수정')
    await actions.click(screen.getByRole('button', { name: '재시작 요청' }))
  const message = await screen.findByText(
    '재시작 시도의 진행을 확인하지 못했습니다. 자동 조회를 멈췄습니다. 새로고침으로 상태를 확인하세요.',
  )
  expect(message).toHaveAttribute('role', 'alert')
  expect(restart).toBeDisabled()
  expect(calls('POST /api/v1/validation-runs/7/restart')).toHaveLength(1)
  const pollsAfterDeadline = calls('GET /api/v1/validation-runs/7/progress').length
  await new Promise((resolve) => nativeTimeout(resolve, 2100))
  expect(calls('GET /api/v1/validation-runs/7/progress')).toHaveLength(pollsAfterDeadline)
  advanced = true
  await actions.click(screen.getByRole('button', { name: '새로고침' }))
  await waitFor(() => expect(restart).toBeEnabled())
  expect(message).not.toBeVisible()
  expect(calls('POST /api/v1/validation-runs/7/restart')).toHaveLength(1)
}, 15000)

it('finalize help rejects a non-FINALIZED response and waits for acknowledgement after retry', async () => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
  currentUser = gaAdmin
  detailHandlers()
  let attempts = 0
  const keys: (string | undefined)[] = []
  handlers['POST /api/v1/validation-runs/7/finalize'] = (_path, init) => {
    keys.push(init?.idempotencyKey)
    return { status: ++attempts === 1 ? 'COMPLETED' : 'FINALIZED' }
  }
  const { actions } = renderAt('/app/validation-runs/7')
  await waitFor(() => expect(screen.getByRole('button', { name: '확정' })).toBeEnabled())
  await actions.click(screen.getByRole('button', { name: '확정' }))
  const modal = within(await screen.findByRole('dialog', { name: '이 검증 실행을 확정할까요?' }))
  await actions.click(modal.getByRole('button', { name: '도움말' }))
  await waitFor(() => expect(modal.getByRole('button', { name: '다음' })).toBeEnabled())
  await actions.click(modal.getByRole('button', { name: '다음' }))
  expect(attempts).toBe(0)
  expect(modal.getByRole('button', { name: '다음' })).toBeDisabled()
  await actions.click(modal.getByRole('button', { name: '확정' }))
  await modal.findByText('서버의 최종 확정 상태를 확인하지 못했습니다.')
  expect(modal.getByRole('button', { name: '다음' })).toBeDisabled()
  await actions.click(modal.getByRole('button', { name: '확정' }))
  await waitFor(() => expect(modal.getByRole('button', { name: '다음' })).toBeEnabled())
  expect(screen.queryByRole('dialog', { name: '확정했습니다' })).toBeNull()
  await actions.click(modal.getByRole('button', { name: '다음' }))
  expect(await modal.findByText('서버 확정 확인 · 가이드 완료')).toBeVisible()
  await actions.click(modal.getByRole('button', { name: '안내 마치기' }))
  expect(await screen.findByRole('dialog', { name: '확정했습니다' })).toBeVisible()
  expect(keys[0]).toBe(keys[1])
})

it('invalidates a finalized run even if its page unmounts before the response', async () => {
  currentUser = gaAdmin
  detailHandlers()
  let finish!: (value: unknown) => void
  handlers['POST /api/v1/validation-runs/7/finalize'] = () => new Promise(resolve => { finish = resolve })
  const { actions, client, unmount } = renderAt('/app/validation-runs/7')
  client.setQueryData(['validation-runs', 'receipt-test'], { status: 'COMPLETED' })
  await waitFor(() => expect(screen.getByRole('button', { name: '확정' })).toBeEnabled())
  await actions.click(screen.getByRole('button', { name: '확정' }))
  await actions.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '확정' }))
  await waitFor(() => expect(finish).toBeDefined())
  unmount()
  await act(async () => finish({ status: 'FINALIZED' }))
  await waitFor(() => expect(client.getQueryState(['validation-runs', 'receipt-test'])?.isInvalidated).toBe(true))
})

it('the detail help waits for the real finalization result before completing', async () => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
  currentUser = gaAdmin
  detailHandlers()
  handlers['POST /api/v1/validation-runs/7/finalize'] = () => ({ status: 'FINALIZED' })
  const { actions } = renderAt('/app/validation-runs/7?month=2026-07')
  await waitFor(() => expect(screen.getByRole('button', { name: '확정' })).toBeEnabled())
  await actions.click(await screen.findByRole('button', { name: '도움말' }))
  const next = () => screen.getByRole('button', { name: '다음' })
  for (let i = 0; i < 3; i++) { await waitFor(() => expect(next()).toBeEnabled()); await actions.click(next()) }
  expect(next()).toBeDisabled()
  expect(calls('POST /api/v1/validation-runs/7/finalize')).toHaveLength(0)
  await actions.click(screen.getByRole('button', { name: '확정' }))
  await actions.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '확정' }))
  const done = await screen.findByRole('dialog', { name: '확정했습니다' })
  await actions.click(within(done).getByRole('button', { name: '확인' }))
  await waitFor(() => expect(next()).toBeEnabled())
  await actions.click(next())
  expect(await screen.findByText('서버 확정 확인 · 가이드 완료')).toBeVisible()
}, 10000)

it('invalidates a created run even if its page unmounts before the response', async () => {
  handlers['GET /api/v1/validation-runs'] = () => listPage([])
  handlers['GET /api/v1/validation-runs/active-monthly'] = () => ({ exists: false })
  let finish!: (value: unknown) => void
  handlers['POST /api/v1/validation-runs'] = () => new Promise(resolve => { finish = resolve })
  const { actions, client, unmount } = renderAt('/app/validation-runs')
  const create = await screen.findByRole('button', { name: /실행 생성/ })
  await waitFor(() => expect(create).toBeEnabled())
  client.setQueryData(['validation-runs', 'receipt-test'], [])
  await actions.click(create)
  await waitFor(() => expect(finish).toBeDefined())
  unmount()
  await act(async () => finish({ validationRunId: 7, runNo: 2, status: 'CREATED' }))
  await waitFor(() => expect(client.getQueryState(['validation-runs', 'receipt-test'])?.isInvalidated).toBe(true))
})

it('does not allow finalizing a partial contract run even with a passed checklist', async () => {
  currentUser = gaAdmin
  detailHandlers()
  handlers['GET /api/v1/validation-runs/7'] = () => detail(run({ runType: 'MANUAL_CONTRACT' }))
  renderAt('/app/validation-runs/7')
  expect(await screen.findByText('월 전체를 계산한 월간·확정전 실행만 최종 확정할 수 있습니다.')).toBeVisible()
  expect(screen.getByRole('button', { name: '확정' })).toBeDisabled()
  expect(calls('POST /api/v1/validation-runs/7/finalize')).toHaveLength(0)
})


it('resolves tutorial overview and progress targets on a successful detail response', async () => {
  detailHandlers()
  renderAt('/app/validation-runs/7?month=2026-07')
  await screen.findByRole('heading', { name: /진행 단계/ })
  const [overview, progress] = findWorkflowGuide('validation.detail', '1.0')!.steps
  const overviewElement = document.querySelector(`[data-guide-feature="${overview.targetId}"]`)
  expect(overviewElement).toBeVisible()
  expect(overviewElement).toHaveTextContent('검증월')
  expect(document.querySelector(`[data-guide-feature="${progress.targetId}"]`)).toContainElement(screen.getByRole('heading', { name: /진행 단계/ }))
})


it('stops the creation spinner while help acknowledgement keeps duplicate submission blocked', async () => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList)
  Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: vi.fn() })
  handlers['GET /api/v1/validation-runs'] = () => listPage([])
  handlers['GET /api/v1/validation-runs/active-monthly'] = () => ({ exists: false })
  handlers['POST /api/v1/validation-runs'] = () => ({ validationRunId: 7, runNo: 2, status: 'CREATED' })
  const { actions } = renderAt('/app/validation-runs?month=2026-07')
  const create = await screen.findByRole('button', { name: /실행 생성/ })
  await waitFor(() => expect(create).toBeEnabled())
  await actions.click(screen.getByRole('button', { name: '도움말' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled())
  await actions.click(screen.getByRole('button', { name: '다음' }))
  await waitFor(() => expect(screen.getByText('저장 가이드 · 2/2')).toBeVisible())
  await actions.click(create)
  await waitFor(() => expect(calls('POST /api/v1/validation-runs')).toHaveLength(1))
  await waitFor(() => expect(create).not.toHaveAttribute('aria-busy', 'true'))
  expect(create).toBeDisabled()
  await actions.click(create)
  expect(calls('POST /api/v1/validation-runs')).toHaveLength(1)
  expect(screen.getByRole('button', { name: '다음' })).toBeEnabled()
})
