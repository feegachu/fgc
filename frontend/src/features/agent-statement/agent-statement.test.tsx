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
import type { StatementDetail, StatementEntry, StatementList } from './api'

const agentUser = {
  loginId: 'agent01',
  userName: '김설계',
  roleCode: 'AGENT',
  demoMonth: '2026-07',
  canProcess: false,
  canViewAuditLog: false,
}
const adminUser = { ...agentUser, loginId: 'admin', userName: '관리자', roleCode: 'GA_ADMIN', canProcess: true }
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'statement' })

const entry = (patch: Partial<StatementEntry> = {}): StatementEntry => ({
  entryKey: 'UEFZTUVOVDoxOjE',
  entryType: 'PAYMENT',
  contractId: 7,
  contractNo: 'C-0007',
  commissionItemCode: 'FIRST_YEAR',
  commissionItemName: '초년도 수수료',
  installmentNo: 1,
  basisDate: '2026-07-10',
  statusCode: 'CONFIRMED',
  statusLabel: '지급 확정',
  amount: 123456,
  policyVersionId: 3,
  source: { entityType: 'COMMISSION_TRANSACTION', id: 1 },
  dataStatus: 'AVAILABLE',
  ...patch,
})
const list = (content: StatementEntry[]): StatementList => ({
  items: { content, page: 1, size: 20, totalElements: content.length, totalPages: 1 },
  summary: {
    payment: { count: 1, amount: 123456 },
    held: { count: 2, amount: 50000 },
    adjustment: { count: 1, amount: -7000 },
    clawback: { count: 1, amount: 9000 },
  },
  basis: {
    month: '2026-07',
    range: { from: '2026-07-01', to: '2026-07-31' },
    dateBasis: { PAYMENT: '지급 귀속월', HELD: '보류 예정월', ADJUSTMENT: '조정 처리월', CLAWBACK: '환수 계산월' },
    warnings: [],
  },
})
const detail = (row: StatementEntry): StatementDetail => ({
  entry: row,
  evidence: { ratePct: 12.34, basisAmount: 1000000, internalKey: '내부 키' },
  basis: list([row]).basis,
})

type Handler = (path: string) => unknown
let handler: Handler
let paths: string[]
const clients: QueryClient[] = []

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
  sessionStorage.clear()
  paths = []
  handler = () => list([entry()])
})
afterEach(() => {
  vi.restoreAllMocks()
  for (const client of clients.splice(0)) client.clear()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

function renderAt(path: string, me: typeof agentUser = agentUser) {
  vi.spyOn(apiClient, 'request').mockImplementation((async (requested: string) => {
    if (requested.startsWith('/api/v1/auth/me') || requested.startsWith('/api/v1/me')) return envelope(me)
    if (requested.startsWith('/api/v1/base/commission-items')) {
      return envelope([{ commissionItemId: 5, itemCode: 'FIRST_YEAR', itemName: '초년도 수수료' }])
    }
    if (requested.startsWith('/api/v1/base/agents')) {
      return envelope({
        content: [{ agentId: 9, agentCode: 'A009', agentName: '박설계' }],
        page: 1,
        size: 100,
        totalElements: 1,
        totalPages: 1,
      })
    }
    paths.push(requested)
    const body = await handler(requested)
    return envelope(body)
  }) as typeof apiClient.request)
  useAuthStore.getState().setAccessToken('access')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  clients.push(client)
  const router = createMemoryRouter(routes, { basename: '/app/', initialEntries: [path] })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('RPT-W02 설계사 수수료 명세', () => {
  it('설계사는 본인 경로만 호출하고 설계사 선택 없이 4개 유형 카드와 표를 본다', async () => {
    renderAt('/app/agent/statements?month=2026-07')
    expect(await screen.findByText('C-0007')).toBeInTheDocument()
    expect(paths.every((path) => path.startsWith('/api/v1/agent/me/statements'))).toBe(true)
    expect(paths[0]).not.toContain('agentId')
    expect(screen.queryByLabelText(/설계사$/)).not.toBeInTheDocument()
    for (const [label, amount] of [
      ['지급 요약', '123,456'],
      ['보류 요약', '50,000'],
      ['조정 요약', '-7,000'],
      ['환수 요약', '9,000'],
    ]) {
      expect(screen.getByRole('article', { name: label })).toHaveTextContent(amount)
    }
  })

  it('근거 보기로 상세를 열고 닫아도 조회 조건이 유지된다', async () => {
    const row = entry()
    handler = (path) => (path.includes('/statements/') ? detail(row) : list([row]))
    const router = renderAt('/app/agent/statements?month=2026-07&contractNo=C-0007')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'C-0007 근거 보기' }))
    const panel = await screen.findByRole('region', { name: '명세 근거' })
    expect(within(panel).getByText('지급 귀속월')).toBeInTheDocument()
    expect(within(panel).getByText('12.3%')).toBeInTheDocument()
    expect(within(panel).queryByText('내부 키')).not.toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: '계약 상세 보기' })).toBeInTheDocument()
    expect(router.state.location.search).toContain('entryKey=')
    await user.click(within(panel).getByRole('button', { name: '닫기' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: '명세 근거' })).not.toBeInTheDocument())
    expect(router.state.location.search).toContain('contractNo=C-0007')
    expect(router.state.location.search).not.toContain('entryKey')
  })

  it('금액 자료가 없는 행은 0원이 아니라 금액 미확인으로 표시한다', async () => {
    handler = () => list([entry({ amount: null, dataStatus: 'MISSING' })])
    renderAt('/app/agent/statements?month=2026-07')
    expect(await screen.findByText('금액 미확인')).toBeInTheDocument()
  })

  it('0건과 조회 실패를 다른 문장으로 보여 주고 실패는 다시 시도할 수 있다', async () => {
    handler = () => list([])
    renderAt('/app/agent/statements?month=2026-07')
    expect(await screen.findByText('해당 조건의 명세가 없습니다')).toBeInTheDocument()
  })

  it('403 은 권한 문구로 구별한다', async () => {
    handler = () => {
      throw new ApiError(null, 'req-403', 403)
    }
    renderAt('/app/agent/statements?month=2026-07')
    expect(await screen.findByText('이 명세를 조회할 권한이 없습니다.')).toBeInTheDocument()
    expect(screen.queryByText('해당 조건의 명세가 없습니다')).not.toBeInTheDocument()
  })

  it('서버 오류는 요청 ID 와 함께 실패로 표시하고 0건으로 보이지 않는다', async () => {
    handler = () => {
      throw new ApiError(null, 'req-500', 500)
    }
    renderAt('/app/agent/statements?month=2026-07')
    expect(await screen.findByText('명세를 불러오지 못했습니다')).toBeInTheDocument()
    expect(screen.getByText(/req-500/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('없는 상세는 찾을 수 없다는 문구로 구별한다', async () => {
    handler = (path) => {
      if (path.includes('/statements/')) throw new ApiError(null, 'req-404', 404)
      return list([entry()])
    }
    renderAt('/app/agent/statements?month=2026-07&entryKey=UEFZTUVOVDo5OTk')
    expect(await screen.findByText('요청한 명세를 찾을 수 없습니다.')).toBeInTheDocument()
  })

  it('내부 사용자는 설계사를 고르기 전에는 조회하지 않고 선택 후 내부 경로로 조회한다', async () => {
    renderAt('/app/agent/statements?month=2026-07', adminUser)
    expect(await screen.findByText('조회할 설계사를 선택하세요.')).toBeInTheDocument()
    expect(paths).toEqual([])
  })

  it('내부 사용자는 agentId 가 있으면 내부 경로로 조회한다', async () => {
    renderAt('/app/agent/statements?month=2026-07&agentId=9', adminUser)
    expect(await screen.findByText('C-0007')).toBeInTheDocument()
    expect(paths[0]).toContain('/api/v1/reports/agents/9/statements')
  })

  it('설계사는 수수료 지급 화면을 열 수 없다', async () => {
    renderAt('/app/transactions')
    await waitFor(() => expect(screen.queryByRole('heading', { name: '수수료 지급' })).not.toBeInTheDocument())
  })
})
