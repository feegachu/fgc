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
import type { AgentContract } from './model'

const user = {
  loginId: 'agent01',
  userName: '김설계',
  roleCode: 'AGENT',
  demoMonth: '2026-07',
  canProcess: false,
  canViewAuditLog: false,
}
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'agent-home' })
const page = (content: unknown[]) => envelope({ content, page: 1, size: 100, totalElements: content.length, totalPages: 1 })
const contract = (patch: Partial<AgentContract>): AgentContract => ({
  contractId: 1,
  contractNo: 'C-0001',
  contractDate: '2026-07-05',
  contractStatus: 'ACTIVE',
  contractStatusLabel: '정상',
  capResultStatus: 'NORMAL',
  capResultStatusLabel: '정상',
  insurerName: 'A생명',
  productName: '건강보험 A',
  monthlyEquivalentFirstPremium: 120000,
  ...patch,
})
const insurers = [
  { insurerId: 1, insurerName: 'A생명', insurerType: 'LIFE' },
  { insurerId: 2, insurerName: 'B손해', insurerType: 'NON_LIFE' },
]
const products = [{ productName: '건강보험 A', productGroupCode: 'HEALTH_PROTECTION' }]

let contracts: () => Promise<unknown>
let payments: () => Promise<unknown>
const clients: QueryClient[] = []

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
  sessionStorage.clear()
  contracts = async () => page([])
  payments = async () => page([])
  vi.spyOn(apiClient, 'request').mockImplementation((async (path: string) => {
    if (path.startsWith('/api/v1/contracts')) return contracts()
    if (path.startsWith('/api/v1/transactions')) return payments()
    if (path.startsWith('/api/v1/base/insurers')) return page(insurers)
    if (path.startsWith('/api/v1/base/products')) return page(products)
    return envelope(user)
  }) as typeof apiClient.request)
})
afterEach(() => {
  vi.restoreAllMocks()
  for (const client of clients.splice(0)) client.clear()
  useAuthStore.setState({ accessToken: null, loggingOut: false })
})

function renderHome(path = '/app/agent-home') {
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
const kpi = (name: string) => screen.findByRole('article', { name })

describe('AGNT-W01 포트폴리오', () => {
  it('프로필·이번달 현황 지표를 본인 계약과 지급에서 만든다', async () => {
    contracts = async () =>
      page([
        contract({}),
        contract({ contractId: 2, contractNo: 'C-0002', contractDate: '2026-07-20', monthlyEquivalentFirstPremium: 80000 }),
        contract({ contractId: 3, contractNo: 'C-0003', contractDate: '2026-06-02', monthlyEquivalentFirstPremium: 100000 }),
      ])
    const fc = { paymentStage: 'GA_TO_FC', cashflowType: 'PAYMENT' }
    payments = async () =>
      page([
        { ...fc, amount: 300000, status: 'CONFIRMED' },
        { ...fc, amount: 200000, status: 'DRAFT' },
        { ...fc, amount: 900000, status: 'CANCELLED' },
        { ...fc, amount: 50000, status: 'CONFIRMED', cashflowType: 'DEDUCTION' },
        { ...fc, amount: 700000, status: 'CONFIRMED', paymentStage: 'INSURER_TO_GA' },
      ])
    renderHome()
    expect(await kpi('신규 계약 건수')).toHaveTextContent('2건')
    expect(screen.getByRole('article', { name: '신규 계약 건수' })).toHaveTextContent('전월 1건')
    expect(screen.getByRole('article', { name: '월 환산 초회보험료' })).toHaveTextContent('200,000')
    await waitFor(() => expect(screen.getByRole('article', { name: '수령 예정 수수료' })).toHaveTextContent('500,000'))
    expect(screen.getByRole('article', { name: '설계사 프로필' })).toHaveTextContent('김설계')
  })

  it('확인 필요 계약 카드를 열면 한도 근접·검토 필요 계약이 보이고 닫을 수 있다', async () => {
    contracts = async () =>
      page([
        contract({ contractId: 2, contractNo: 'C-0002', capResultStatus: 'WARNING', capResultStatusLabel: '주의' }),
        contract({ contractId: 3, contractNo: 'C-0003', capResultStatus: 'REVIEW_REQUIRED', capResultStatusLabel: '검토필요' }),
      ])
    renderHome()
    const card = await screen.findByRole('button', { name: '확인 필요 계약' })
    expect(card).toHaveTextContent('2건')
    await userEvent.click(card)
    const dialog = screen.getByRole('dialog', { name: '확인 필요 계약 2건' })
    expect(within(dialog).getByText(/C-0002/)).toBeInTheDocument()
    expect(within(dialog).getByText(/C-0003/)).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /C-0002/ })).toHaveAttribute('href', '/app/contracts/2')
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('판매 관리 필터로 기간과 보험사 구분을 바꾸고 도넛 범례로 보험사를 고른다', async () => {
    contracts = async () =>
      page([
        contract({}),
        contract({ contractId: 2, contractNo: 'C-0002', insurerName: 'B손해', productName: '장기 B' }),
      ])
    renderHome()
    const panel = await screen.findByRole('region', { name: '설계사 판매 관리 그래프' })
    expect(within(panel).getByText('최근 12개월 · 전체 보험사')).toBeInTheDocument()
    expect(within(panel).getByText('합계 2건')).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: '손해' }))
    expect(within(panel).getByText('최근 12개월 · 손해보험')).toBeInTheDocument()
    expect(within(panel).getByText('합계 1건')).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: '6개월' }))
    expect(within(panel).getByText('최근 6개월 · 손해보험')).toBeInTheDocument()
    await userEvent.click(within(panel).getAllByRole('button', { name: '전체' })[1])
    await userEvent.click(within(panel).getByRole('button', { name: /B손해/ }))
    expect(within(panel).getByText('B손해 필터 적용 중')).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: '보험사 필터 해제' }))
    expect(within(panel).queryByText('B손해 필터 적용 중')).not.toBeInTheDocument()
  })

  it('이번 달 계약이 없으면 지표가 0이어도 화면이 깨지지 않는다', async () => {
    renderHome()
    expect(await kpi('신규 계약 건수')).toHaveTextContent('0건')
    expect(screen.getByRole('button', { name: '확인 필요 계약' })).toHaveTextContent('0건')
    expect(screen.getByRole('region', { name: '설계사 판매 관리 그래프' })).toHaveTextContent('합계 0건')
  })

  it('수수료 조회만 실패하면 그 카드만 오류를 표시하고 나머지는 정상 표시한다', async () => {
    contracts = async () => page([contract({})])
    payments = async () => {
      throw new ApiError(null, 'req-1', 500)
    }
    renderHome()
    await waitFor(() => expect(screen.getByRole('article', { name: '수령 예정 수수료' })).toHaveTextContent('확인 불가'))
    expect(screen.getByRole('article', { name: '신규 계약 건수' })).toHaveTextContent('1건')
    expect(within(screen.getByRole('article', { name: '수령 예정 수수료' })).getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('계약 조회가 실패하면 오류와 다시 시도를 보여 준다', async () => {
    contracts = async () => {
      throw new ApiError(null, 'req-2', 500)
    }
    renderHome()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('런처를 누르면 상품 추천 AI 화면이 열리고 닫기 요청으로 닫힌다', async () => {
    renderHome()
    await userEvent.click(await screen.findByRole('button', { name: /상품 비교분석 추천 AI 열기/ }))
    expect(screen.getByTitle('상품 추천 AI')).toHaveAttribute('src', expect.stringContaining('chatbot/fun079-modal.html?embed=1'))
    window.dispatchEvent(new MessageEvent('message', { data: { type: 'fgc-chatbot-close' } }))
    await waitFor(() => expect(screen.queryByTitle('상품 추천 AI')).not.toBeInTheDocument())
  })

  it('관리자 역할은 포트폴리오에 접근할 수 없다', async () => {
    vi.mocked(apiClient.request).mockImplementation((async (path: string) =>
      path.startsWith('/api/v1/contracts') ? contracts() : envelope({ ...user, roleCode: 'SETTLEMENT' })) as typeof apiClient.request)
    renderHome()
    expect(await screen.findByRole('heading', { name: '403 · 권한 없음' })).toBeInTheDocument()
    expect(vi.mocked(apiClient.request).mock.calls.some(([path]) => path.startsWith('/api/v1/contracts'))).toBe(false)
  })
})
