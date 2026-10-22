import { readFileSync } from 'node:fs'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '../../app/routes'
import { apiClient } from '../../lib/api/client'
import { useAuthStore } from '../../stores/auth'
import { useWorkspaceStore } from '../../stores/workspace'
import { normalizeCriteria } from './api'
import type { AuditLog } from './api'

const log = (auditLogId: number, overrides: Partial<AuditLog> = {}): AuditLog => ({
  auditLogId,
  occurredAt: '2026-08-16T10:00:07+09:00',
  userId: 12,
  userLoginId: 'settle01',
  actionCode: 'PAYMENT_CONFIRMED',
  entityType: 'COMMISSION_PAYMENT',
  entityId: '42',
  reason: '확정 처리',
  requestId: '20260816-1a2b3c',
  policyVersionId: 3,
  ...overrides,
})
const rows = [log(1), log(2, { userId: undefined, userLoginId: undefined, actionCode: 'VALIDATION_RUN_STARTED' })]
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'audit-test' })
let requested: string[]

function renderAt(path: string, roleCode = 'COMPLIANCE', auditRows = rows) {
  requested = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url) => {
    requested.push(url)
    if (url === '/api/v1/auth/me')
      return envelope({
        loginId: 'audit01',
        userName: '준법 담당자',
        roleCode,
        demoMonth: '2026-07',
        canViewAuditLog: ['COMPLIANCE', 'SYSTEM_ADMIN'].includes(roleCode),
      })
    if (url === '/api/v1/audit-logs/options')
      return envelope({
        actionCodes: [...new Set(auditRows.map((row) => row.actionCode).filter(Boolean))],
        entityTypes: [...new Set(auditRows.map((row) => row.entityType).filter(Boolean))],
        users: [{ userId: 12, loginId: 'settle01' }],
      })
    if (url === '/api/v1/audit-logs/1')
      return envelope({
        log: auditRows[0],
        diff: [
          { field: 'payment.amount', before: '500000', after: '700000', changed: true },
          { field: 'payment.status', before: 'DRAFT', after: 'DRAFT', changed: false },
        ],
      })
    if (url === '/api/v1/audit-logs/2')
      return envelope({ log: auditRows[1], diff: [{ field: 'status', after: 'STARTED', changed: true }] })
    if (url.startsWith('/api/v1/audit-logs?'))
      return envelope({ content: auditRows, page: 1, size: 20, totalElements: 45, totalPages: 3 })
    throw new Error(`unexpected ${url}`)
  })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, actions: userEvent.setup() }
}
const listRequest = () =>
  new URLSearchParams(requested.find((url) => url.startsWith('/api/v1/audit-logs?'))!.split('?')[1])

// 라우트는 화면을 lazy 로 불러온다. 첫 변환 시간이 findBy 기본 대기(1초)를 넘지 않도록 미리 받아 둔다.
beforeAll(async () => {
  await import('./AuditLogPage')
})

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'audit-fixture', loggingOut: false })
  useWorkspaceStore.getState().clear()
})
afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

describe('AUDT-W01 감사로그 조회', () => {
  it('URL 코드 검색조건을 복원하고 목록·선택지에 한글을 표시하며 BATCH 표기를 유지한다', async () => {
    renderAt('/audit-logs?month=2026-07&userId=12&action=PAYMENT_CONFIRMED&from=2026-08-01&to=2026-08-16&page=2')
    expect(await screen.findByRole('heading', { name: '감사로그 조회' })).toBeInTheDocument()
    expect(await screen.findAllByRole('button', { name: '2026-08-16 10:00:07' })).toHaveLength(2)
    expect(Object.fromEntries(listRequest())).toEqual({
      size: '20',
      userId: '12',
      action: 'PAYMENT_CONFIRMED',
      from: '2026-08-01',
      to: '2026-08-16',
      page: '2',
    })
    expect(screen.getByText('BATCH')).toBeInTheDocument()
    expect(screen.getByText('2 / 3 페이지')).toBeInTheDocument()
    expect(screen.getByText('45')).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: 'settle01' })).toBeInTheDocument()
    expect(screen.getByLabelText('행위자')).toHaveValue('12')
    expect(screen.getByLabelText('행위 종류')).toHaveValue('PAYMENT_CONFIRMED')
    expect(screen.getByRole('option', { name: '지급 확정' })).toHaveValue('PAYMENT_CONFIRMED')
    expect(screen.getByRole('option', { name: '수수료 지급' })).toHaveValue('COMMISSION_PAYMENT')
    const list = screen.getByRole('table', { name: '검색조건에 해당하는 감사 기록 목록' })
    expect(within(list).getByText('지급 확정')).toBeInTheDocument()
    expect(within(list).getByText('검증 실행 시작')).toBeInTheDocument()
    expect(screen.getByLabelText('발생 시각 (시작)')).toHaveValue('2026-08-01')
    expect(screen.getByText('왼쪽 목록에서 한 건을 고르세요.')).toBeInTheDocument()
    // 화면정의서 "막아야 할 것": 수정·삭제 버튼 자체를 만들지 않는다.
    expect(screen.queryByRole('button', { name: /수정|삭제/ })).toBeNull()
  })

  it('조회는 검색조건을 URL 에 남기고 page·selected 를 지운다', async () => {
    const { router, actions } = renderAt('/audit-logs?month=2026-07&page=2&selected=1')
    await screen.findByText('payment.amount')
    await actions.type(screen.getByLabelText('대상 ID'), '4104')
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(router.state.location.search).toBe('?month=2026-07&entityId=4104'))
    await actions.click(screen.getByRole('button', { name: '초기화' }))
    await waitFor(() => expect(router.state.location.search).toBe('?month=2026-07'))
  })

  it('#604 헤더를 누르면 정렬 방향이 바뀌고 첫 페이지·선택을 지우며 검색조건을 유지한다', async () => {
    const { router, actions } = renderAt('/audit-logs?month=2026-07&entityType=COMMISSION_PAYMENT&page=2&selected=1')
    const table = await screen.findByRole('table', { name: '검색조건에 해당하는 감사 기록 목록' })
    const lastQuery = () =>
      new URLSearchParams(requested.filter((url) => url.startsWith('/api/v1/audit-logs?')).at(-1)!.split('?')[1])
    // 순서 기준이 없는 행위·대상 종류 컬럼은 정렬 버튼이 없다.
    expect(within(table).queryByRole('button', { name: '행위' })).toBeNull()
    expect(within(table).getByRole('columnheader', { name: /정책버전/ })).toHaveAttribute('aria-sort', 'none')
    await actions.click(within(table).getByRole('button', { name: '정책버전' }))
    await waitFor(() => expect(lastQuery().get('sort')).toBe('policyVersionId,asc'))
    expect(lastQuery().get('page')).toBe('1')
    expect(lastQuery().get('entityType')).toBe('COMMISSION_PAYMENT')
    const params = new URLSearchParams(router.state.location.search)
    expect(params.has('selected')).toBe(false)
    expect(within(table).getByRole('columnheader', { name: /정책버전/ })).toHaveAttribute('aria-sort', 'ascending')
    await actions.click(within(table).getByRole('button', { name: '정책버전' }))
    await waitFor(() => expect(router.state.location.search).toContain('sort=policyVersionId%2Cdesc'))
    expect(within(table).getByRole('columnheader', { name: /정책버전/ })).toHaveAttribute('aria-sort', 'descending')
    // 검색을 다시 제출해도 정렬은 유지되고, 초기화하면 서버 기본 정렬로 돌아간다.
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(router.state.location.search).toContain('sort=policyVersionId%2Cdesc'))
    await actions.click(screen.getByRole('button', { name: '초기화' }))
    await waitFor(() => expect(router.state.location.search).not.toContain('sort='))
  })

  it('행을 고르면 ?selected= 를 남기고 서버 diff 의 바뀐 칸만 강조한다', async () => {
    const { router, actions } = renderAt('/audit-logs?month=2026-07&page=2')
    const [first] = await screen.findAllByRole('button', { name: '2026-08-16 10:00:07' })
    await actions.click(first)
    await waitFor(() => expect(router.state.location.search).toBe('?month=2026-07&page=2&selected=1'))
    const table = await screen.findByRole('table', { name: '선택한 감사 기록의 변경 전후 값 비교' })
    await within(table).findByText('payment.amount')
    expect(table.querySelectorAll('.audit-diff-cell-changed')).toHaveLength(2)
    expect(within(table).getByText('700000')).toHaveClass('audit-diff-cell-changed')
    expect(within(table).getAllByText('DRAFT')[0]).not.toHaveClass('audit-diff-cell-changed')
    expect(screen.getByText('지급 확정 (PAYMENT_CONFIRMED)')).toBeInTheDocument()
    expect(screen.getByText('수수료 지급 (COMMISSION_PAYMENT) #42')).toBeInTheDocument()
  })

  it('한글 인증 이벤트를 선택해도 원본 코드로 검색하고 상세·뒤로/앞으로에서 복원한다', async () => {
    const { router, actions } = renderAt('/audit-logs?month=2026-07', 'COMPLIANCE', [
      log(1, { actionCode: 'TOKEN_REFRESH', entityType: 'APP_USER', entityId: 'admin', userLoginId: 'admin' }),
    ])
    await screen.findByRole('option', { name: '인증 토큰 갱신' })
    await actions.selectOptions(
      screen.getByLabelText('행위 종류'),
      screen.getByRole('option', { name: '인증 토큰 갱신' }),
    )
    await actions.selectOptions(screen.getByLabelText('대상 종류'), screen.getByRole('option', { name: '사용자 계정' }))
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => {
      const query = new URLSearchParams(
        requested
          .filter((url) => url.startsWith('/api/v1/audit-logs?'))
          .at(-1)!
          .split('?')[1],
      )
      expect(query.get('action')).toBe('TOKEN_REFRESH')
      expect(query.get('entityType')).toBe('APP_USER')
    })
    const list = screen.getByRole('table', { name: '검색조건에 해당하는 감사 기록 목록' })
    expect(within(list).getAllByRole('cell')[1]).toHaveTextContent('admin')
    expect(within(list).queryByText('TOKEN_REFRESH')).not.toBeInTheDocument()
    await actions.click(screen.getByRole('button', { name: '2026-08-16 10:00:07' }))
    expect(await screen.findByText('인증 토큰 갱신 (TOKEN_REFRESH)')).toBeInTheDocument()
    expect(screen.getByText('사용자 계정 (APP_USER) #admin')).toBeInTheDocument()
    expect(await screen.findByText('payment.status')).toBeInTheDocument()
    expect(screen.getAllByText('DRAFT')).toHaveLength(2)
    await act(() => router.navigate(-1))
    expect(router.state.location.search).not.toContain('selected=')
    expect(screen.getByLabelText('행위 종류')).toHaveValue('TOKEN_REFRESH')
    await act(() => router.navigate(1))
    expect(await screen.findByText('인증 토큰 갱신 (TOKEN_REFRESH)')).toBeInTheDocument()
  })

  it('미등록 코드는 검색과 상세에서 원문을 유지하고 중복 병기하지 않는다', async () => {
    const { actions } = renderAt('/audit-logs?month=2026-07&selected=1', 'COMPLIANCE', [
      log(1, { actionCode: 'FUTURE_ACTION', entityType: 'FUTURE_ENTITY' }),
    ])
    await screen.findByText('FUTURE_ENTITY #42')
    expect(screen.getByRole('option', { name: 'FUTURE_ACTION' })).toHaveValue('FUTURE_ACTION')
    expect(screen.queryByText('FUTURE_ACTION (FUTURE_ACTION)')).not.toBeInTheDocument()
    await actions.selectOptions(screen.getByLabelText('행위 종류'), 'FUTURE_ACTION')
    await actions.selectOptions(screen.getByLabelText('대상 종류'), 'FUTURE_ENTITY')
    await actions.click(screen.getByRole('button', { name: '조회' }))
    await waitFor(() => expect(requested.at(-1)).toContain('action=FUTURE_ACTION'))
    expect(requested.at(-1)).toContain('entityType=FUTURE_ENTITY')
  })

  it('누락된 코드는 목록과 상세에서 대시로 표시한다', async () => {
    renderAt('/audit-logs?selected=1', 'COMPLIANCE', [log(1, { actionCode: undefined, entityType: '' })])
    await screen.findByText('payment.amount')
    expect(document.querySelector('.audit-diff-context')).toHaveTextContent('- · - #42')
    const row = screen.getByRole('table', { name: '검색조건에 해당하는 감사 기록 목록' }).querySelector('tbody tr')!
    expect(within(row as HTMLElement).getAllByText('-')).toHaveLength(2)
  })

  it('1차처럼 행의 다른 칸을 눌러도 그 행을 선택한다', async () => {
    const { router, actions } = renderAt('/audit-logs?month=2026-07')
    await actions.click(await screen.findByText('BATCH'))
    await waitFor(() => expect(router.state.location.search).toBe('?month=2026-07&selected=2'))
    expect(await screen.findByText('STARTED')).toBeInTheDocument()
  })

  it('단계 진행과 분개 자동 사유를 한글로 표시하고 상세에서 원문과 큰 식별자를 보존한다', async () => {
    const stepReason = 'MonthlyValidationJob advanced to step 7'
    const draftReason =
      'journalType=CONFIRMED_FC_PAYOUT,sourceEntityType=COMMISSION_TRANSACTION,sourceEntityId=9007199254740993,validationRunId=42'
    const { actions } = renderAt('/audit-logs?selected=1', 'COMPLIANCE', [
      log(1, { actionCode: 'VALIDATION_RUN_STEP_ADVANCED', reason: stepReason }),
      log(2, { actionCode: 'JOURNAL_DRAFT_SAVED', reason: draftReason }),
    ])
    const table = await screen.findByRole('table', { name: '검색조건에 해당하는 감사 기록 목록' })
    expect(await within(table).findByText('월 통합검증 7단계 진행')).toBeInTheDocument()
    expect(
      within(table).getByText(
        '확정 지급(GA→설계사) 분개 초안 저장 · 원천: 수수료 거래 #9007199254740993 · 검증 실행 #42',
      ),
    ).toBeInTheDocument()
    expect(within(table).queryByText(stepReason)).not.toBeInTheDocument()
    expect(within(table).queryByText(draftReason)).not.toBeInTheDocument()
    await actions.click(screen.getByText('사유 원문 보기'))
    expect(screen.getByText(stepReason)).toBeVisible()
    await actions.click(screen.getAllByRole('button', { name: '2026-08-16 10:00:07' })[1])
    expect(await screen.findByText(draftReason)).toBeInTheDocument()
    expect(document.querySelector('.audit-reason-original')).toHaveTextContent(draftReason)
  })

  it.each([
    ['EXCEPTION_ACTION', 'MonthlyValidationJob advanced to step 7'],
    ['VALIDATION_RUN_STEP_ADVANCED', 'MonthlyValidationJob advanced to step 7; 재검토 필요'],
    ['VALIDATION_RUN_STEP_ADVANCED', 'MonthlyValidationJob advanced to step 7\n'],
    [
      'JOURNAL_DRAFT_SAVED',
      'journalType=FUTURE_TYPE,sourceEntityType=SCHEDULE_LINE,sourceEntityId=1,validationRunId=null',
    ],
    [
      'JOURNAL_DRAFT_SAVED',
      'journalType=CONFIRMED_FC_PAYOUT,sourceEntityType=FUTURE_SOURCE,sourceEntityId=1,validationRunId=null',
    ],
  ])('사용자 사유나 미등록 형식은 원문을 보존한다 (%s: %s)', async (actionCode, reason) => {
    renderAt('/audit-logs?selected=1', 'COMPLIANCE', [log(1, { actionCode, reason })])
    await screen.findByText('payment.amount')
    const table = await screen.findByRole('table', { name: '검색조건에 해당하는 감사 기록 목록' })
    const reasonCell = within(table).getAllByRole('cell')[5]
    expect(reasonCell.textContent).toBe(reason)
    expect(screen.queryByText('사유 원문 보기')).not.toBeInTheDocument()
  })

  it('감사 행을 키보드 Enter·Space로 선택하고 첫 열 버튼으로도 선택한다 (#418)', async () => {
    const { router, actions } = renderAt('/audit-logs?month=2026-07')
    const batch = await screen.findByText('BATCH')
    const row = batch.closest('tr')!
    row.focus()
    expect(row).toHaveFocus()
    await actions.keyboard('{Enter}')
    await waitFor(() => expect(router.state.location.search).toContain('selected=2'))
    expect(row).toHaveAttribute('aria-selected', 'true')
    const [first] = screen.getAllByRole('button', { name: '2026-08-16 10:00:07' })
    first.focus()
    await actions.keyboard('{Enter}')
    await waitFor(() => expect(router.state.location.search).toContain('selected=1'))
    row.focus()
    await actions.keyboard(' ')
    await waitFor(() => expect(router.state.location.search).toContain('selected=2'))
  })

  it('?selected= 로 새로 열어도 같은 로그의 선택 상태와 diff 를 복원한다', async () => {
    renderAt('/audit-logs?selected=1')
    expect(await screen.findByText('payment.amount')).toBeInTheDocument()
    expect(requested).toContain('/api/v1/audit-logs/1')
    expect(document.querySelector('tr.is-selected')).toHaveTextContent('2026-08-16 10:00:07')
  })

  it('before 가 없는 생성 로그도 오류 없이 이후값만 보여 준다', async () => {
    renderAt('/audit-logs?selected=2')
    const cell = await screen.findByText('STARTED')
    expect(cell).toHaveClass('audit-diff-cell-changed')
    expect(cell.previousElementSibling).toBeEmptyDOMElement()
  })

  it('SETTLEMENT 는 403 화면을 보고 감사로그 API 를 부르지 않는다', async () => {
    renderAt('/audit-logs', 'SETTLEMENT')
    expect(await screen.findByRole('heading', { name: '403 · 권한 없음' })).toBeInTheDocument()
    expect(requested.filter((url) => url.startsWith('/api/v1/audit-logs'))).toEqual([])
  })

  it('시작일이 종료일보다 늦으면 1차처럼 교환해 조회한다', async () => {
    renderAt('/audit-logs?from=2026-08-16&to=2026-08-01&page=0')
    await screen.findByText('BATCH')
    expect(listRequest().get('from')).toBe('2026-08-01')
    expect(listRequest().get('to')).toBe('2026-08-16')
    expect(listRequest().get('page')).toBe('1')
  })
})

describe('normalizeCriteria', () => {
  it('1차 AuditLogViewController 의 보정 규칙을 따른다', () => {
    expect(normalizeCriteria(new URLSearchParams('page=abc&userId=x&from=2026-8-1&to=2026-08-01'))).toEqual({
      entityType: '',
      entityId: '',
      userId: '',
      action: '',
      from: '',
      to: '2026-08-01',
      sort: '',
      page: 1,
    })
    expect(normalizeCriteria(new URLSearchParams('page=999999999')).page).toBe(107374182)
    // #604 허용된 정렬만 남기고 나머지는 서버 기본 정렬로 되돌린다.
    expect(normalizeCriteria(new URLSearchParams('sort=policyVersionId,desc')).sort).toBe('policyVersionId,desc')
    expect(normalizeCriteria(new URLSearchParams('sort=actionCode,asc')).sort).toBe('')
  })
})

it('공통 리사이즈로 대체한 좌우 배치 외의 감사로그 CSS 는 1차와 같다', () => {
  // #597: React의 좌우 배치·모바일 전환은 ResizableSplit에서 담당한다.
  const read = (path: string) => readFileSync(path, 'utf8')
    .replace(/\r\n/g, '\n')
    .replace(/@media \(max-width: 56.25rem\) \{\s*\.audit-layout \{[^}]*\}\s*\}/, '')
    .replace(/\.audit-layout \{[^}]*\}/, '')
    .replace(/\n{3,}/g, '\n\n')
  expect(read('src/features/audit/audit.css')).toBe(read('../src/main/resources/static/css/features/audit.css'))
})
