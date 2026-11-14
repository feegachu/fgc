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
import type { AgentHome } from './api'

const user = {
  loginId: 'agent01',
  userName: '김설계',
  roleCode: 'AGENT',
  demoMonth: '2026-07',
  canProcess: false,
  canViewAuditLog: false,
}
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'agent-home' })
const bucket = (amount: number, count: number) => ({ amount, count })
const baseHome = (patch: Partial<AgentHome> = {}): AgentHome => ({
  asOf: '2026-07-31',
  period: '12',
  range: { from: '2025-08-01', to: '2026-07-31' },
  filters: { insurerType: 'ALL' },
  priorityContracts: [],
  priorityContractTotal: 0,
  myCommission: {
    month: '2026-07',
    expected: bucket(500000, 3),
    held: bucket(70000, 1),
    clawback: bucket(20000, 1),
    dataStatus: 'AVAILABLE',
  },
  portfolio: {
    contractCount: 3,
    insurers: [
      { insurerId: 1, name: 'A생명', contractCount: 2, monthlyEquivalentFirstPremiumSum: 200000 },
      { insurerId: 2, name: 'B손해', contractCount: 1, monthlyEquivalentFirstPremiumSum: 100000 },
    ],
    monthly: [
      { month: '2026-06', newContractCount: 1, monthlyEquivalentFirstPremiumSum: 100000 },
      { month: '2026-07', newContractCount: 2, monthlyEquivalentFirstPremiumSum: 200000 },
    ],
    categories: [{ code: 'HEALTH_PROTECTION', label: '건강보장', count: 3 }],
    statuses: [{ code: 'ACTIVE', label: '정상', count: 3 }],
  },
  basis: { asOf: '2026-07-31', warnings: [] },
  dataStatus: 'AVAILABLE',
  ...patch,
})

let home: (path: string) => Promise<unknown>
let capCount: (status: string) => number
let focusHome: (path: string) => Promise<unknown> | undefined
let check: (body: unknown, key: string) => Promise<unknown>
const clients: QueryClient[] = []

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, loggingOut: false })
  useWorkspaceStore.getState().clear()
  sessionStorage.clear()
  home = async () => envelope(baseHome())
  capCount = () => 0
  focusHome = () => undefined
  check = async () => envelope({ checkId: 1, checkedAt: '2026-07-31T10:00:00+09:00', contractId: 91, contextVersion: 'contract91-event4' })
  vi.spyOn(apiClient, 'request').mockImplementation((async (path: string, options?: { idempotencyKey?: string; body?: unknown }) => {
    if (path.startsWith('/api/v1/agent/me/home')) return (path.includes('insurerId=') && focusHome(path)) || home(path)
    if (path.startsWith('/api/v1/agent/me/contract-checks')) return check(options?.body, options?.idempotencyKey ?? '')
    if (path.startsWith('/api/v1/contracts')) {
      const status = new URLSearchParams(path.split('?')[1]).get('capResultStatus') ?? ''
      return envelope({ content: [], page: 1, size: 1, totalElements: capCount(status), totalPages: 1 })
    }
    if (path.startsWith('/api/v1/ai/compare-chat/categories')) return envelope([{ code: 'HEALTH_PROTECTION', label: '건강보험', insurerCount: 2 }])
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
const kpi = (name: string) => screen.findByLabelText(name)
const homeCalls = () =>
  vi.mocked(apiClient.request).mock.calls.map(([path]) => String(path)).filter((path) => path.startsWith('/api/v1/agent/me/home'))

describe('AGNT-W01 포트폴리오', () => {
  it('프로필·이번달 현황 지표를 서버 집계에서 만든다', async () => {
    renderHome()
    expect(await kpi('신규 계약 건수')).toHaveTextContent('2건')
    expect(screen.getByRole('article', { name: '신규 계약 건수' })).toHaveTextContent('전월 1건')
    expect(screen.getByRole('article', { name: '월납환산 초회보험료' })).toHaveTextContent('200,000')
    await waitFor(() => expect(screen.getByRole('link', { name: '수령 예정 수수료' })).toHaveTextContent('500,000'))
    expect(screen.getByRole('link', { name: '수령 예정 수수료' })).toHaveTextContent('보류 70,000원 환수 계산 20,000원')
    expect(screen.getByRole('article', { name: '설계사 프로필' })).toHaveTextContent('김설계')
    expect(homeCalls()[0]).toContain('asOf=2026-07-31')
  })

  it('확인 필요 계약 카드는 건수를 보여 주고 누르면 보험계약 목록으로 간다', async () => {
    capCount = (status) => (status === 'WARNING,VIOLATION' ? 1 : status === 'REVIEW_REQUIRED' ? 1 : 0)
    renderHome()
    const card = await screen.findByRole('link', { name: '확인 필요 계약' })
    await waitFor(() => expect(card).toHaveTextContent('2건'))
    expect(card).toHaveAttribute(
      'href',
      '/app/contracts?capResultStatus=WARNING%2CVIOLATION%2CREVIEW_REQUIRED&contractDateFrom=2026-07-01&contractDateTo=2026-07-31',
    )
    expect(card).not.toHaveTextContent('내역 보기')
  })

  it('수령 예정 수수료 카드는 해당 기준월의 명세 화면으로 연결된다', async () => {
    renderHome()
    const card = await screen.findByRole('link', { name: '수령 예정 수수료' })
    expect(card).toHaveAttribute('href', '/app/agent/statements?month=2026-07')
  })

  it('판매 관리 필터는 서버에 기간·보험사 구분·보험사를 보내 다시 집계한다', async () => {
    renderHome()
    const panel = await screen.findByRole('region', { name: '설계사 판매 관리 그래프' })
    expect(within(panel).getByRole('button', { name: '12개월' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(panel).getByText('합계 3건')).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: '손해' }))
    await waitFor(() => expect(homeCalls().some((p) => p.includes('insurerType=NON_LIFE'))).toBe(true))
    expect(within(panel).getByRole('button', { name: '손해' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(within(panel).getByRole('button', { name: '6개월' }))
    await waitFor(() => expect(homeCalls().some((p) => p.includes('period=6') && p.includes('insurerType=NON_LIFE'))).toBe(true))
    await userEvent.click(within(panel).getAllByRole('button', { name: '전체' })[1])
    await userEvent.click(within(panel).getByRole('button', { name: /B손해/ }))
    await waitFor(() => expect(homeCalls().some((p) => p.includes('insurerId=2'))).toBe(true))
    expect(within(panel).getByText('B손해 필터 적용 중')).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: '보험사 필터 해제' }))
    expect(within(panel).queryByText('B손해 필터 적용 중')).not.toBeInTheDocument()
  })

  it('판매 관리 블록의 보조 문구(기간·보험사 구분, 기준일·집계 기간·계약)는 보이지 않고 수수료 기준월은 그대로 표시한다', async () => {
    renderHome()
    const panel = await screen.findByRole('region', { name: '설계사 판매 관리 그래프' })
    expect(panel).not.toHaveTextContent('최근 12개월, 전체 보험사')
    expect(panel).not.toHaveTextContent('기준일')
    expect(panel).not.toHaveTextContent('집계 기간')
    expect(await screen.findByLabelText('수령 예정 수수료')).toHaveTextContent('2026-07 기준')
  })

  it('한도 건수 조회도 기준일까지로 맞춘다', async () => {
    renderHome()
    const card = await screen.findByRole('link', { name: '확인 필요 계약' })
    expect(card.getAttribute('href')).toContain('contractDateTo=2026-07-31')
    const calls = vi.mocked(apiClient.request).mock.calls.map(([path]) => String(path)).filter((p) => p.includes('capResultStatus'))
    expect(calls.every((p) => p.includes('contractDateTo=2026-07-31'))).toBe(true)
  })

  it('한도 건수 조회가 실패하면 로딩으로 두지 않고 확인 불가와 다시 시도를 보여 준다', async () => {
    capCount = () => {
      throw new ApiError(null, 'req-4', 500)
    }
    renderHome()
    await waitFor(() => expect(screen.getByRole('article', { name: '확인 필요 계약' })).toHaveTextContent('확인 불가'))
    expect(within(screen.getByRole('article', { name: '확인 필요 계약' })).getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('보험사를 고른 뒤 그 집계가 로딩 중이거나 실패하면 0건 차트로 그리지 않는다', async () => {
    let release = () => {}
    const held = new Promise<void>((resolve) => (release = resolve))
    focusHome = () => held.then(() => envelope(baseHome()))
    renderHome()
    const panel = await screen.findByRole('region', { name: '설계사 판매 관리 그래프' })
    await userEvent.click(within(panel).getByRole('button', { name: /B손해/ }))
    expect(await within(panel).findByText('선택한 보험사 집계를 불러오는 중입니다.')).toBeInTheDocument()
    expect(within(panel).queryByText(/^합계/)).not.toBeInTheDocument()
    release()
    await waitFor(() => expect(within(panel).queryByText('선택한 보험사 집계를 불러오는 중입니다.')).not.toBeInTheDocument())
  })

  it('보험사 집계 조회가 실패하면 오류와 다시 시도를 보여 준다', async () => {
    focusHome = () => Promise.reject(new ApiError(null, 'req-5', 500))
    renderHome()
    const panel = await screen.findByRole('region', { name: '설계사 판매 관리 그래프' })
    await userEvent.click(within(panel).getByRole('button', { name: /B손해/ }))
    expect(await within(panel).findByRole('alert')).toHaveTextContent('선택한 보험사의 집계를 불러오지 못했습니다.')
    expect(within(panel).getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('경고는 선택한 보험사의 집계에서 읽는다', async () => {
    focusHome = () =>
      Promise.resolve(envelope(baseHome({ basis: { asOf: '2026-07-31', warnings: [] } })))
    home = async () =>
      envelope(baseHome({ dataStatus: 'PARTIAL', basis: { asOf: '2026-07-31', warnings: ['상태 이력이 없는 계약 2건은 상태 미확인으로 집계했다.'] } }))
    renderHome()
    const panel = await screen.findByRole('region', { name: '설계사 판매 관리 그래프' })
    expect(await within(panel).findByRole('note')).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: /B손해/ }))
    await waitFor(() => expect(within(panel).queryByRole('note')).not.toBeInTheDocument())
  })

  it('기준일 시점 상태 이력이 부족하면 서버 경고를 그래프 위에 보여 준다', async () => {
    home = async () =>
      envelope(baseHome({ dataStatus: 'PARTIAL', basis: { asOf: '2026-07-31', warnings: ['상태 이력이 없는 계약 1건은 상태 미확인으로 집계했다.'] } }))
    renderHome()
    expect(await screen.findByRole('note')).toHaveTextContent('상태 미확인')
  })

  it('계약이 없으면 지표가 0이어도 화면이 깨지지 않는다', async () => {
    home = async () =>
      envelope(
        baseHome({
          myCommission: { month: '2026-07', expected: bucket(0, 0), held: bucket(0, 0), clawback: bucket(0, 0), dataStatus: 'AVAILABLE' },
          // 서버는 계약이 없는 달도 0건으로 채워 보낸다.
          portfolio: {
            contractCount: 0,
            insurers: [],
            monthly: [
              { month: '2026-06', newContractCount: 0, monthlyEquivalentFirstPremiumSum: 0 },
              { month: '2026-07', newContractCount: 0, monthlyEquivalentFirstPremiumSum: 0 },
            ],
            categories: [],
            statuses: [],
          },
        }),
      )
    renderHome()
    expect(await kpi('신규 계약 건수')).toHaveTextContent('0건')
    expect(screen.getByRole('region', { name: '설계사 판매 관리 그래프' })).toHaveTextContent('합계 0건')
  })

  it('홈 조회가 실패하면 0건으로 보이지 않고 오류와 다시 시도를 보여 준다', async () => {
    home = async () => {
      throw new ApiError(null, 'req-2', 500)
    }
    renderHome()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    expect(screen.queryByLabelText('신규 계약 건수')).not.toBeInTheDocument()
  })
})

describe('AGNT-W01 로딩·권한·늦은 응답 (#714 DoD)', () => {
  it('홈 조회가 끝나기 전에는 불러오는 중만 보이고 0건 지표를 그리지 않는다', async () => {
    home = () => new Promise(() => {})
    renderHome()
    expect(await screen.findByText('불러오는 중입니다.')).toBeInTheDocument()
    expect(screen.queryByLabelText('신규 계약 건수')).not.toBeInTheDocument()
    expect(screen.queryByText('0건')).not.toBeInTheDocument()
  })

  it('홈 조회가 500으로 실패하면 0건이 아니라 오류와 다시 시도를 보여 준다', async () => {
    home = async () => {
      throw new ApiError(null, 'req-500', 500)
    }
    renderHome()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    expect(screen.queryByLabelText('신규 계약 건수')).not.toBeInTheDocument()
    expect(screen.queryByText('0건')).not.toBeInTheDocument()
  })

  it('필터를 연달아 바꿨을 때 늦게 도착한 이전 응답이 최신 조건의 화면을 덮어쓰지 않는다', async () => {
    const pending: Record<string, (value: unknown) => void> = {}
    const base = home
    home = (path) => {
      if (path.includes('insurerType=NON_LIFE')) return new Promise((resolve) => (pending.nonLife = resolve))
      if (path.includes('insurerType=LIFE')) return new Promise((resolve) => (pending.life = resolve))
      return base(path)
    }
    renderHome()
    const panel = await screen.findByRole('region', { name: '설계사 판매 관리 그래프' })
    await userEvent.click(within(panel).getByRole('button', { name: '손해' }))
    await waitFor(() => expect(pending.nonLife).toBeDefined())
    await userEvent.click(within(panel).getByRole('button', { name: '생명' }))
    await waitFor(() => expect(pending.life).toBeDefined())
    // 응답을 구분하는 값으로 합계 건수를 쓴다(최신 조건=생명 7건, 늦게 온 이전 조건=손해 9건).
    pending.life(envelope(baseHome({ portfolio: { ...baseHome().portfolio, contractCount: 7 } })))
    await waitFor(() => expect(panel).toHaveTextContent('합계 7건'))
    pending.nonLife(envelope(baseHome({ portfolio: { ...baseHome().portfolio, contractCount: 9 } })))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(panel).toHaveTextContent('합계 7건')
    expect(panel).not.toHaveTextContent('합계 9건')
  })
})

describe('상품 추천 AI', () => {
  it('런처를 누르면 상품 추천 AI 모달이 열리고 Esc로 닫으면 런처로 돌아온다', async () => {
    renderHome()
    const launcher = await screen.findByRole('button', { name: '상품 추천 AI' })
    // 메인 화면 런처의 피코는 가만히 있는 모션이다
    expect(launcher.querySelector('[data-pico-motion]')).toHaveAttribute('data-pico-motion', 'still')
    await userEvent.click(launcher)
    const dialog = await screen.findByRole('dialog', { name: '상품 추천 AI' })
    expect(within(dialog).getByText('어떤 카테고리의 상품을 비교해 볼까요?')).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '상품 추천 AI' })).not.toBeInTheDocument())
    expect(launcher).toHaveFocus()
  })

  it('모달을 열고 닫아도 선택한 홈 조건이 유지된다', async () => {
    renderHome()
    const panel = await screen.findByRole('region', { name: '설계사 판매 관리 그래프' })
    await userEvent.click(within(panel).getByRole('button', { name: '6개월' }))
    await userEvent.click(await screen.findByRole('button', { name: '상품 추천 AI' }))
    await screen.findByRole('dialog', { name: '상품 추천 AI' })
    await userEvent.keyboard('{Escape}')
    expect(within(await screen.findByRole('region', { name: '설계사 판매 관리 그래프' })).getByRole('button', { name: '6개월' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('관리자 역할은 포트폴리오에 접근할 수 없다', async () => {
    vi.mocked(apiClient.request).mockImplementation((async (path: string) =>
      path.startsWith('/api/v1/agent/me/home') ? home(path) : envelope({ ...user, roleCode: 'SETTLEMENT' })) as typeof apiClient.request)
    renderHome()
    expect(await screen.findByRole('heading', { name: '권한 없음' })).toBeInTheDocument()
    expect(homeCalls()).toHaveLength(0)
  })
})
