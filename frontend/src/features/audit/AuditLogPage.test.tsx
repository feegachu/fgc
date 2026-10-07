import { readFileSync } from 'node:fs'
import { render, screen, waitFor, within } from '@testing-library/react'
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

function renderAt(path: string, roleCode = 'COMPLIANCE') {
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
        actionCodes: ['PAYMENT_CONFIRMED', 'VALIDATION_RUN_STARTED'],
        entityTypes: ['COMMISSION_PAYMENT'],
        users: [{ userId: 12, loginId: 'settle01' }],
      })
    if (url === '/api/v1/audit-logs/1')
      return envelope({
        log: rows[0],
        diff: [
          { field: 'payment.amount', before: '500000', after: '700000', changed: true },
          { field: 'payment.status', before: 'DRAFT', after: 'DRAFT', changed: false },
        ],
      })
    if (url === '/api/v1/audit-logs/2')
      return envelope({ log: rows[1], diff: [{ field: 'status', after: 'STARTED', changed: true }] })
    if (url.startsWith('/api/v1/audit-logs?'))
      return envelope({ content: rows, page: 1, size: 20, totalElements: 45, totalPages: 3 })
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
  it('URL 검색조건으로 목록을 조회하고 1차와 같은 열·BATCH 표기·선택지를 보여 준다', async () => {
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
    expect(screen.getByText('COMMISSION_PAYMENT #42')).toBeInTheDocument()
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
    expect(await screen.findByRole('heading', { name: '접근 권한이 없습니다.' })).toBeInTheDocument()
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
      page: 1,
    })
    expect(normalizeCriteria(new URLSearchParams('page=999999999')).page).toBe(107374182)
  })
})

it('감사로그 CSS 는 1차 static/css/features/audit.css 와 같다 (웨이브 C 전까지 한 벌 유지)', () => {
  // Windows 체크아웃(CRLF)과 LF 차이는 스타일 차이가 아니다.
  const read = (path: string) => readFileSync(path, 'utf8').replace(/\r\n/g, '\n')
  expect(read('src/features/audit/audit.css')).toBe(read('../src/main/resources/static/css/features/audit.css'))
})
