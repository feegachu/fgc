import { selectOption } from '../../test/selectOption'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient, ApiError } from '../../lib/api/client'
import type { RequestOptions } from '../../lib/api/client'
import { useToastStore } from '../../stores/toasts'
import ContractFormPage from './ContractFormPage'
import type { ContractDetail, ContractUpdateRequest } from './api'
import {
  changesSchedule,
  createRequest,
  fillCyclePremium,
  initialDraft,
  statusEventRequest,
  validateDraft,
  validateStatusEvent,
} from './form'

const timestamp = '2026-10-11T01:00:00.123456Z'
const contract: ContractDetail = {
  contractId: 21,
  contractNo: 'CONT-21',
  contractDate: '2026-07-10',
  contractStatus: 'ACTIVE',
  insurerId: 1,
  insurerName: '보험사',
  productOfferingId: 7,
  productName: '상품',
  offeringVersion: 'v1',
  agentId: 3,
  agentName: '설계사',
  organizationId: 4,
  organizationName: '저장 당시 조직',
  paymentCycleCode: 'QUARTERLY',
  premiumPerCycleAmount: 299000,
  firstPremiumAmount: 270000,
  monthlyEquivalentFirstPremium: 100000,
  paymentTermMonths: 120,
  standardSurrenderDeductionAmount: 45000,
  updatedAt: timestamp,
}
const product = {
  productOfferingId: 7,
  productName: '상품',
  offeringVersion: 'v1',
  paymentTermMonths: 240,
  standardDeduction80Yn: false,
}
const agent = {
  agentId: 3,
  agentName: '설계사',
  agentCode: 'FC-3',
  rankCode: 'FC',
  agentStatus: 'ACTIVE',
  activeYn: true,
  organizationId: 5,
  organizationName: '현재 조직',
}
const envelope = <T,>(data: T) => ({ data, error: null, requestId: 'form-test' })
type Handler = (url: string, options?: RequestOptions) => unknown
function renderAt(path = '/contracts/21/edit', handler?: Handler, cached?: ContractDetail) {
  const writes: { url: string; options?: RequestOptions }[] = []
  vi.spyOn(apiClient, 'request').mockImplementation(async (url, options) => {
    if (options?.method) writes.push({ url, options })
    const override = await handler?.(url, options)
    if (override !== undefined) return envelope(override)
    if (url === '/api/v1/contracts/21' && !options?.method) return envelope(contract)
    if (options?.method) return envelope({ contractId: 21, scheduleHeaderIds: [31, 32] })
    if (url.startsWith('/api/v1/base/insurers'))
      return envelope({
        content: [
          { insurerId: 1, insurerName: '보험사', insurerCode: 'INS-1', activeYn: true },
          { insurerId: 2, insurerName: '다른 보험사', insurerCode: 'INS-2', activeYn: true },
          { insurerId: 9, insurerName: '비활성 보험사', insurerCode: 'INS-9', activeYn: false },
        ],
      })
    if (url.startsWith('/api/v1/base/products'))
      return envelope({
        content: [
          product,
          {
            productOfferingId: 8,
            productName: '새 상품',
            offeringVersion: 'v2',
            paymentTermMonths: 360,
            standardDeduction80Yn: true,
          },
        ],
      })
    if (url.startsWith('/api/v1/base/agents'))
      return envelope({
        content: [
          agent,
          { ...agent, agentId: 11, agentName: '퇴사 설계사', agentStatus: 'TERMINATED' },
          { ...agent, agentId: 12, agentName: '팀장', rankCode: 'TEAM_LEADER' },
          { ...agent, agentId: 13, agentName: '비활성 설계사', activeYn: false },
        ],
      })
    throw new Error(`unexpected ${url}`)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  if (cached) client.setQueryData(['contracts', 'detail', '21'], cached)
  const router = createMemoryRouter(
    [
      { path: '/contracts', element: <p>계약 목록</p> },
      { path: '/contracts/new', element: <ContractFormPage /> },
      { path: '/contracts/:id/edit', element: <ContractFormPage /> },
      { path: '/contracts/:id', element: <p>계약 상세</p> },
    ],
    { initialEntries: [path] },
  )
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { writes, router, user: userEvent.setup(), client }
}
const save = () => screen.getByRole('button', { name: /^(수정 저장|저장하고 예상 스케줄 만들기)$/ })
const field = (label: string) =>
  screen.getByLabelText(label, { exact: false, selector: 'input, select' }) as HTMLInputElement
async function ready() {
  await waitFor(() => expect(save()).toBeEnabled())
}
afterEach(() => {
  vi.restoreAllMocks()
  useToastStore.setState({ messages: [] })
})

describe('[FGC-FUN-018] 보험계약 입력 안전성', () => {
  it('수정 화면은 상세 캐시의 옛 값 대신 새 조회가 끝난 값으로 초기화한다', async () => {
    let finish!: (value: ContractDetail) => void
    const pending = new Promise<ContractDetail>((resolve) => {
      finish = resolve
    })
    renderAt(
      '/contracts/21/edit',
      (url, options) => {
        if (url === '/api/v1/contracts/21' && !options?.method) return pending
      },
      { ...contract, contractNo: 'STALE-CACHED-NO' },
    )
    expect(screen.queryByDisplayValue('STALE-CACHED-NO')).not.toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    await act(async () => finish({ ...contract, contractNo: 'FRESH-SERVER-NO' }))
    await ready()
    expect(field('계약번호')).toHaveValue('FRESH-SERVER-NO')
  })

  it('규제 근거는 REG-07·08·12의 조문·판정 범위를 생략하지 않는다', async () => {
    const { user } = renderAt()
    await ready()
    await user.click(screen.getByRole('button', { name: '모집 설계사 근거' }))
    expect(screen.getByRole('tooltip')).toHaveTextContent('제4-32조제12항제1·2호 [현행규정]')
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      '유지관리수수료·장기유지수수료의 지급 대상은 최초 모집자로 고정되지 않고',
    )
    // 2차는 최초 연결 설계사를 고정하므로 담당 분리 관리를 전제로 한 안내를 하지 않는다(화면정의서 CONT-W03).
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      '2차 범위에서는 이 설계사를 고정하고 유지관리 담당 변경·승계는 지원하지 않습니다',
    )
    expect(screen.getByRole('tooltip')).not.toHaveTextContent('별도로 관리')
    await user.click(screen.getByRole('button', { name: '모집 설계사 근거' }))
    await user.click(screen.getByRole('button', { name: '표준해약공제액 근거' }))
    expect(screen.getByRole('tooltip')).toHaveTextContent('REG-08 · 제4-32조제5항 [현행규정]')
    expect(screen.getByRole('tooltip')).toHaveTextContent('1,200% 한도 계산에서')
    expect(screen.getByRole('tooltip')).toHaveTextContent('REG-12 · 제4-32조제6항 [현행규정]')
  })

  it.each(['duplicate', 'network'])('저장 실패 %s는 입력을 보존하고 다시 저장할 수 있다', async (kind) => {
    const message = kind === 'duplicate' ? '저장 불가 — 이미 등록된 계약번호입니다.' : 'Failed to fetch'
    const { user, writes } = renderAt('/contracts/21/edit', (_url, options) => {
      if (options?.method) {
        if (kind === 'duplicate')
          throw new ApiError({ code: 'FGC-CONT-001', message, field: 'contractNo' }, 'duplicate-save', 409)
        throw new TypeError(message)
      }
    })
    await ready()
    await user.click(save())
    await waitFor(() => expect(writes).toHaveLength(1))
    await waitFor(() => expect(save()).toBeEnabled())
    expect(field('계약번호')).toHaveValue(contract.contractNo)
    if (kind === 'duplicate') expect(field('계약번호')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getAllByText(new RegExp(message))).not.toHaveLength(0)
  })

  it.each(['/contracts/new', '/contracts/21/edit'])('등록·수정 취소는 목록으로 이동한다: %s', async (path) => {
    const { user, router, writes } = renderAt(path)
    await screen.findByRole('link', { name: '취소' })
    await user.click(screen.getByRole('link', { name: '취소' }))
    expect(router.state.location.pathname).toBe('/contracts')
    expect(writes).toHaveLength(0)
  })

  it('없는 숫자 계약의 수정 화면은 기존 서버 오류를 표시하고 저장 폼을 열지 않는다', async () => {
    renderAt('/contracts/999999/edit', (url) => {
      if (url === '/api/v1/contracts/999999')
        throw new ApiError({ code: 'FGC-COMMON-002', message: '존재하지 않는 보험계약입니다.' }, 'missing-edit', 400)
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('존재하지 않는 보험계약입니다.')
    expect(screen.queryByRole('button', { name: /수정 저장|스케줄 만들기/ })).not.toBeInTheDocument()
  })
  it('등록·수정용 상품 조회는 계약일을 사용하고 판매 종료 버전 포함을 활성화하지 않는다', async () => {
    renderAt()
    await ready()
    const calls = vi
      .mocked(apiClient.request)
      .mock.calls.map(([path]) => new URL(path, 'http://localhost'))
      .filter((url) => url.pathname === '/api/v1/base/products')
    expect(calls.length).toBeGreaterThan(0)
    for (const url of calls) {
      expect(url.searchParams.get('asOf')).toBe(contract.contractDate)
      expect(url.searchParams.has('includeExpired')).toBe(false)
    }
  })

  it('최초 수정 시 저장된 보험료·납입기간·공제액·소속과 마이크로초 토큰을 보존한다', async () => {
    const { user, writes, router } = renderAt()
    await ready()
    expect(field('납입기간(개월)')).toHaveValue(120)
    expect(field('표준해약공제액(원)')).toBeDisabled()
    expect(field('표준해약공제액(원)')).toHaveValue(45000)
    expect(field('소속 조직')).toHaveValue('저장 당시 조직')
    fireEvent.change(field('계약번호'), { target: { value: '  USER-EDIT  ' } })
    fireEvent.change(field('월납환산 초회보험료(원)'), { target: { value: '110000' } })
    await selectOption(user, field('납입주기'), 'ANNUAL')
    expect(field('주기 보험료(원)')).toHaveValue(299000)
    await user.click(save())
    await screen.findByText('계약 상세')
    expect(writes).toHaveLength(1)
    expect(writes[0].options).toMatchObject({
      method: 'PUT',
      body: {
        contractNo: 'USER-EDIT',
        premiumPerCycleAmount: 299000,
        firstPremiumAmount: 270000,
        monthlyEquivalentFirstPremium: 110000,
        paymentTermMonths: 120,
        standardSurrenderDeductionAmount: 45000,
        organizationId: 4,
        expectedUpdatedAt: timestamp,
      },
    })
    expect(router.state.location.state.message).toContain('산정정보 변경에 따라 예상 스케줄 2건을 새 버전으로')
  })

  it('409 충돌 후 입력과 토큰을 유지하고, 명시적인 최신 정보 재조회 후에만 저장한다', async () => {
    const latest = '2026-10-12T11:05:00.654321+09:00'
    let reads = 0
    let attempts = 0
    const { user, writes } = renderAt(undefined, (url, options) => {
      if (url === '/api/v1/contracts/21' && !options?.method)
        return ++reads === 1 ? contract : { ...contract, updatedAt: latest, contractNo: 'LATEST' }
      if (options?.method && ++attempts === 1)
        throw new ApiError(
          {
            code: 'FGC-CONT-003',
            message:
              '다른 요청에서 계약을 수정했습니다. 현재 입력은 유지됩니다. 변경 내용을 확인한 뒤 ‘최신 계약 다시 불러오기’를 눌러 다시 수정하세요.',
          },
          'conflict-trace',
          409,
        )
    })
    await ready()
    fireEvent.change(field('계약번호'), { target: { value: 'MY-DRAFT' } })
    await user.click(save())
    await screen.findByText(/현재 입력은 유지됩니다/)
    expect(screen.getByText(/FGC-CONT-003/)).toHaveTextContent('conflict-trace')
    expect(screen.getByText(/FGC-CONT-003/)).toHaveTextContent('다른 요청에서 계약을 수정했습니다.')
    expect(screen.getAllByText(/현재 입력은 유지됩니다/)).toHaveLength(1)
    expect(field('계약번호')).toHaveValue('MY-DRAFT')
    expect(save()).toBeDisabled()
    fireEvent.submit(screen.getByRole('form'))
    expect(writes).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: '최신 계약 다시 불러오기' }))
    await user.click(screen.getByRole('button', { name: '입력 유지' }))
    expect(reads).toBe(1)
    expect(field('계약번호')).toHaveValue('MY-DRAFT')
    await user.click(screen.getByRole('button', { name: '최신 계약 다시 불러오기' }))
    await user.click(screen.getByRole('button', { name: '최신 정보로 바꾸기' }))
    await waitFor(() => expect(field('계약번호')).toHaveValue('LATEST'))
    await ready()
    await user.click(save())
    await screen.findByText('계약 상세')
    expect(writes[1].options?.body).toMatchObject({ expectedUpdatedAt: latest })
  })

  it('상세에 수정 시각이 없으면 PUT을 보내지 않는다', async () => {
    const { writes } = renderAt(undefined, (url, options) =>
      url === '/api/v1/contracts/21' && !options?.method ? { ...contract, updatedAt: undefined } : undefined,
    )
    await screen.findByText(/수정 시각을 확인할 수 없습니다/)
    expect(save()).toBeDisabled()
    fireEvent.submit(screen.getByRole('form'))
    expect(writes).toHaveLength(0)
  })

  it('저장 중 중복 submit 요청과 입력 변경을 막는다', async () => {
    let finish!: (value: unknown) => void
    const pending = new Promise((resolve) => {
      finish = resolve
    })
    const { writes } = renderAt(undefined, (_url, options) => (options?.method ? pending : undefined))
    await ready()
    const form = screen.getByRole('form')
    fireEvent.submit(form)
    fireEvent.submit(form)
    await waitFor(() => expect(writes).toHaveLength(1))
    expect(save()).toBeDisabled()
    expect(field('계약번호')).toBeDisabled()
    await act(async () => finish({ contractId: 21, scheduleHeaderIds: [] }))
    await screen.findByText('계약 상세')
    expect(writes).toHaveLength(1)
  })

  it('저장 중 목록으로 이동하면 완료 후 캐시는 만료시키되 현재 화면을 바꾸지 않는다', async () => {
    let finish!: (value: unknown) => void
    const pending = new Promise((resolve) => {
      finish = resolve
    })
    const { client, router, user } = renderAt(undefined, (_url, options) => (options?.method ? pending : undefined))
    const invalidated = vi.spyOn(client, 'invalidateQueries')
    await ready()
    await user.click(save())
    await user.click(screen.getByRole('link', { name: '목록으로' }))
    await screen.findByText('계약 목록')
    await act(async () => finish({ contractId: 21, scheduleHeaderIds: [31] }))
    await waitFor(() => expect(invalidated).toHaveBeenCalledWith({ queryKey: ['cap-checks'], refetchType: 'none' }))
    for (const prefix of ['contracts', 'schedules', 'cap-checks']) {
      expect(invalidated).toHaveBeenCalledWith({ queryKey: [prefix], refetchType: 'none' })
    }
    expect(router.state.location.pathname).toBe('/contracts')
    expect(router.state.location.state).toBeNull()
    expect(useToastStore.getState().messages).toEqual([])
  })

  it('상품을 직접 바꿀 때만 상품 납입기간·공제액 규칙을 반영한다', async () => {
    const { user } = renderAt()
    await ready()
    await selectOption(user, field('상품 판매버전'), '8')
    expect(field('납입기간(개월)')).toHaveValue(360)
    expect(field('납입기간(개월)')).toHaveAttribute('readonly')
    expect(field('표준해약공제액(원)')).toBeEnabled()
    await selectOption(user, field('상품 판매버전'), '7')
    expect(field('납입기간(개월)')).toHaveValue(240)
    expect(field('표준해약공제액(원)')).toHaveValue(null)
    expect(field('표준해약공제액(원)')).toBeDisabled()
  })

  it('신규 등록은 유효한 FC와 소속을 선택하고 POST하며 수정 토큰을 보내지 않는다', async () => {
    const { user, writes } = renderAt('/contracts/new')
    await waitFor(() => expect(field('보험회사')).toBeEnabled())
    expect(save()).toBeDisabled()
    expect(screen.getByRole('option', { name: /비활성 보험사/ })).toBeDisabled()
    await selectOption(user, field('보험회사'), '1')
    await waitFor(() => expect(field('상품 판매버전')).toBeEnabled())
    await selectOption(user, field('상품 판매버전'), '7')
    await selectOption(user, field('모집 설계사'), '3')
    expect(field('소속 조직')).toHaveValue('현재 조직')
    expect(screen.queryByRole('option', { name: /퇴사 설계사|팀장|비활성 설계사/ })).not.toBeInTheDocument()
    fireEvent.change(field('계약번호'), { target: { value: 'NEW-21' } })
    fireEvent.change(field('월납환산 초회보험료(원)'), { target: { value: '100000' } })
    fireEvent.change(field('초회 보험료(원)'), { target: { value: '95000' } })
    expect(field('주기 보험료(원)')).toHaveValue(100000)
    await ready()
    await user.click(save())
    await screen.findByText('계약 상세')
    expect(writes[0]).toMatchObject({
      url: '/api/v1/contracts',
      options: { method: 'POST', body: { organizationId: 5, paymentTermMonths: 240 } },
    })
    expect(writes[0].options?.body).not.toHaveProperty('expectedUpdatedAt')
  })

  it('신규 등록에서 계약일을 바꾸면 기존 상품·설계사·소속을 지워 과거 기준정보로 저장하지 않는다', async () => {
    const { user } = renderAt('/contracts/new')
    await waitFor(() => expect(field('보험회사')).toBeEnabled())
    await selectOption(user, field('보험회사'), '1')
    await waitFor(() => expect(field('상품 판매버전')).toBeEnabled())
    await selectOption(user, field('상품 판매버전'), '7')
    await selectOption(user, field('모집 설계사'), '3')
    fireEvent.change(field('계약일'), { target: { value: '2026-06-01' } })
    expect(field('상품 판매버전')).toHaveValue('')
    expect(field('모집 설계사')).toHaveValue('')
    expect(field('소속 조직')).toHaveValue('')
    expect(save()).toBeDisabled()
  })

  it('수정에서 계약일을 바꾸면 상품은 지우고, 그날 자격이 있는 고정 설계사의 현재 소속으로 저장한다', async () => {
    const { user, writes } = renderAt()
    await ready()
    expect(field('모집 설계사')).toHaveAttribute('readonly')
    fireEvent.change(field('계약일'), { target: { value: '2026-06-01' } })
    expect(field('상품 판매버전')).toHaveValue('')
    expect(field('모집 설계사')).toHaveValue('설계사')
    expect(save()).toBeDisabled()
    await waitFor(() =>
      expect(apiClient.request).toHaveBeenCalledWith(expect.stringContaining('asOf=2026-06-01'), expect.anything()),
    )
    await waitFor(() => expect(field('소속 조직')).toHaveValue('현재 조직'))
    await selectOption(user, field('상품 판매버전'), '7')
    await ready()
    await user.click(save())
    await screen.findByText('계약 상세')
    expect(writes[0].options?.body).toMatchObject({ contractDate: '2026-06-01', agentId: 3, organizationId: 5 })
  })

  it('수정에서 바꾼 계약일에 고정 설계사가 자격이 없으면 이유를 표시하고 저장을 막는다', async () => {
    renderAt(undefined, (url) => (url.startsWith('/api/v1/base/agents') ? { content: [] } : undefined))
    await ready()
    fireEvent.change(field('계약일'), { target: { value: '2026-06-01' } })
    expect(await screen.findByText(/바꾼 계약일에 이 설계사가 활동 중인 FC가 아닙니다/)).toBeInTheDocument()
    expect(field('소속 조직')).toHaveValue('')
    expect(save()).toBeDisabled()
  })

  it('수정에서 바꾼 계약일의 고정 설계사 조회가 실패하면 자격 없음이 아니라 기준정보 오류로 알리고 다시 불러온다', async () => {
    let failing = true
    const { user } = renderAt(undefined, (url) => {
      if (!url.startsWith('/api/v1/base/agents') || !url.includes('asOf=2026-06-01')) return undefined
      // 첫 페이지에 없으면 selectedId로 따로 찾는다(useEntityChoices). 그 조회만 실패시킨다.
      if (!url.includes('selectedId=3')) return { content: [{ ...agent, agentId: 99, agentName: '다른 설계사' }] }
      if (failing) throw new ApiError({ code: 'FGC-COMMON-500', message: '설계사 조회 오류' }, 'agent-lookup', 500)
      return { content: [agent] }
    })
    await ready()
    fireEvent.change(field('계약일'), { target: { value: '2026-06-01' } })
    await screen.findByRole('button', { name: '기준정보 다시 불러오기' })
    expect(screen.queryByText(/활동 중인 FC가 아닙니다/)).not.toBeInTheDocument()
    expect(save()).toBeDisabled()
    failing = false
    await user.click(screen.getByRole('button', { name: '기준정보 다시 불러오기' }))
    await waitFor(() => expect(field('소속 조직')).toHaveValue('현재 조직'))
  })

  it('상품 조회의 이전 응답이 늦게 도착해도 새 보험사 옵션을 덮어쓰지 않는다', async () => {
    let release!: (value: unknown) => void
    const old = new Promise((resolve) => {
      release = resolve
    })
    const { user } = renderAt('/contracts/new', (url) => {
      if (url.startsWith('/api/v1/base/products') && url.includes('insurerId=1')) return old
      if (url.startsWith('/api/v1/base/products') && url.includes('insurerId=2'))
        return { content: [{ ...product, productOfferingId: 99, productName: '두 번째 보험사 상품' }] }
    })
    await waitFor(() => expect(field('보험회사')).toBeEnabled())
    await selectOption(user, field('보험회사'), '1')
    await selectOption(user, field('보험회사'), '2')
    await user.click(field('상품 판매버전'))
    await screen.findByRole('option', { name: /두 번째 보험사 상품/ })
    await act(async () => release({ content: [{ ...product, productName: '늦게 도착한 상품' }] }))
    expect(screen.queryByRole('option', { name: /늦게 도착한 상품/ })).not.toBeInTheDocument()
  })

  it('기준정보 실패 시 저장을 잠그고 재조회로 복구한다', async () => {
    let failed = true
    const { user, writes } = renderAt(undefined, (url) => {
      if (url.startsWith('/api/v1/base/products') && failed)
        throw new ApiError({ code: 'FGC-COMMON-500', message: '상품 기준정보 오류' }, 'reference-trace', 500)
    })
    await screen.findByRole('button', { name: '기준정보 다시 불러오기' })
    expect(save()).toBeDisabled()
    fireEvent.submit(screen.getByRole('form'))
    expect(writes).toHaveLength(0)
    expect(useToastStore.getState().messages.map((item) => item.message)).toContain('요청 ID: reference-trace')
    expect(useToastStore.getState().messages.some((item) => item.message.includes('상품 기준정보 오류'))).toBe(false)
    failed = false
    await user.click(screen.getByRole('button', { name: '기준정보 다시 불러오기' }))
    await ready()
  })

  it('서버 message를 그대로 필드 오류·추적 ID와 함께 표시하고 detail로 대체하지 않는다', async () => {
    const { user } = renderAt(undefined, (_url, options) => {
      if (options?.method)
        throw new ApiError(
          {
            code: 'FGC-COMMON-002',
            message: '입력값 오류',
            detail: '조직 계층에 팀장 수령자가 없습니다.',
            field: 'agentId',
          },
          'save-trace',
          400,
        )
    })
    await ready()
    await user.click(save())
    await waitFor(() => expect(field('모집 설계사')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText(/FGC-COMMON-002/)).toHaveTextContent('입력값 오류')
    expect(screen.queryByText('조직 계층에 팀장 수령자가 없습니다.')).not.toBeInTheDocument()
    expect(screen.getByText(/FGC-COMMON-002/)).toHaveTextContent('save-trace')
    expect(save()).toBeEnabled()
  })
})

describe('[FGC-FUN-018] 서버 오류·과거 기준정보', () => {
  it('저장 오류 COMMON-500은 내부 message·detail·필드를 노출하지 않고 요청 ID만 표시한다', async () => {
    const { user } = renderAt(undefined, (_url, options) => {
      if (options?.method)
        throw new ApiError(
          { code: 'FGC-COMMON-500', message: '내부 데이터베이스 메시지', detail: '내부 스택', field: 'agentId' },
          'write-500',
          500,
        )
    })
    await ready()
    await user.click(save())
    expect(await screen.findByText('요청 ID: write-500')).toBeInTheDocument()
    expect(screen.queryByText(/내부 데이터베이스 메시지|내부 스택|FGC-COMMON-500/)).not.toBeInTheDocument()
    expect(field('모집 설계사')).not.toHaveAttribute('aria-invalid')
  })

  it('최초 계약 로드 COMMON-500도 요청 ID만 표시한다', async () => {
    renderAt(undefined, (url, options) => {
      if (url === '/api/v1/contracts/21' && !options?.method)
        throw new ApiError({ code: 'FGC-COMMON-500', message: '내부 조회 메시지' }, 'load-500', 500)
    })
    expect(await screen.findByText('요청 ID: load-500')).toBeInTheDocument()
    expect(screen.queryByText(/내부 조회 메시지|FGC-COMMON-500/)).not.toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
  })

  it('충돌 후 최신 재조회 실패 시에도 입력을 보존하고 서버 내부 정보를 숨긴다', async () => {
    let reads = 0
    const { user, writes } = renderAt(undefined, (url, options) => {
      if (url === '/api/v1/contracts/21' && !options?.method && ++reads > 1)
        throw new ApiError({ code: 'FGC-COMMON-500', message: '재조회 내부 오류' }, 'reload-500', 500)
      if (options?.method) throw new ApiError({ code: 'FGC-CONT-003', message: '동시 수정 오류' }, 'conflict', 409)
    })
    await ready()
    fireEvent.change(field('계약번호'), { target: { value: 'KEEP-DRAFT' } })
    await user.click(save())
    await user.click(await screen.findByRole('button', { name: '최신 계약 다시 불러오기' }))
    await user.click(screen.getByRole('button', { name: '최신 정보로 바꾸기' }))
    expect(await screen.findByText('요청 ID: reload-500')).toBeInTheDocument()
    expect(screen.queryByText(/재조회 내부 오류|FGC-COMMON-500/)).not.toBeInTheDocument()
    expect(field('계약번호')).toHaveValue('KEEP-DRAFT')
    expect(save()).toBeDisabled()
    expect(writes).toHaveLength(1)
  })

  it('과거 계약의 기준정보가 현재 목록에서 사라져도 기존 선택과 소속을 보존한다', async () => {
    const { user, writes } = renderAt(undefined, (url) =>
      url.startsWith('/api/v1/base/') ? { content: [] } : undefined,
    )
    await ready()
    expect(field('보험회사')).toHaveValue('1')
    expect(field('상품 판매버전')).toHaveValue('상품 · v1')
    expect(field('모집 설계사')).toHaveValue('설계사')
    expect(field('소속 조직')).toHaveValue('저장 당시 조직')
    await user.click(save())
    await screen.findByText('계약 상세')
    expect(writes[0].options?.body).toMatchObject({ insurerId: 1, productOfferingId: 7, agentId: 3, organizationId: 4 })
  })
})

describe('[FGC-FUN-025·026] CONT-W03 상태 변경 사건 입력', () => {
  const statusContract: ContractDetail = {
    ...contract,
    allowedNextStatuses: ['UNPAID', 'TERMINATED', 'MATURED', 'CANCELLED'],
    statusReasonCodes: ['MANUAL_STATUS_CHANGE'],
  }
  const saved = (extra = {}) => ({ contractId: 21, scheduleHeaderIds: [], regeneratedScheduleIds: [], ...extra })
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  const reason = () => screen.getByLabelText(/^사유/, { selector: 'textarea' })
  const confirm = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(save())
    const dialog = await screen.findByRole('dialog', { name: '상태 변경 저장 확인' })
    await user.click(within(dialog).getByRole('button', { name: '확인하고 저장' }))
  }
  const hint = () => screen.getByText(/^저장할 준비가 되었습니다/)
  async function fillEvent(user: ReturnType<typeof userEvent.setup>) {
    fireEvent.change(field('효력일시'), { target: { value: '2026-10-20T00:00' } })
    fireEvent.change(field('접수일시'), { target: { value: '2026-10-27T10:00' } })
    await selectOption(user, field('사유코드'), 'MANUAL_STATUS_CHANGE')
    fireEvent.change(reason(), { target: { value: '  보험회사 해지 접수 반영  ' } })
    await ready()
  }
  async function terminate(user: ReturnType<typeof userEvent.setup>) {
    await ready()
    await selectOption(user, field('변경 상태'), 'TERMINATED')
    await fillEvent(user)
  }

  it('변경 상태는 서버 허용 전이만 보이고, 상태를 그대로 두면 사건 없이 멱등키와 함께 저장한다', async () => {
    const { user, writes, router } = renderAt(undefined, (url, options) =>
      options?.method
        ? saved({ followUp: { status: 'NOT_REQUIRED' } })
        : url === '/api/v1/contracts/21'
          ? statusContract
          : undefined,
    )
    await ready()
    expect(screen.getByText('현재 상태').closest('.field')).toHaveTextContent('정상')
    expect(
      within(field('변경 상태'))
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['변경 안 함', '미납', '해지', '만기', '청약철회'])
    expect(screen.queryByRole('group', { name: '상태 변경 사유' })).not.toBeInTheDocument()
    expect(hint()).toHaveTextContent('계약 정보만 저장(상태 사건·스케줄 새 버전 없음)')
    fireEvent.change(field('계약번호'), { target: { value: 'NO-STATUS' } })
    await user.click(save())
    await screen.findByText('계약 상세')
    expect(writes[0].options?.idempotencyKey).toMatch(uuid)
    expect((writes[0].options?.body as ContractUpdateRequest).statusEvent).toBeUndefined()
    expect(router.state.location.state.message).toBe('계약 수정이 저장되었습니다.')
  })

  it('상태를 바꾸면 사유 영역을 채우고 확인창에서 전후 상태·일시·사유를 검토한 뒤 서울 offset으로 보낸다', async () => {
    const { user, writes, router } = renderAt(undefined, (url, options) =>
      options?.method
        ? saved({ statusEventId: 501, followUp: { status: 'PENDING', eventId: 501 } })
        : url === '/api/v1/contracts/21'
          ? statusContract
          : undefined,
    )
    await ready()
    await selectOption(user, field('변경 상태'), 'TERMINATED')
    expect(screen.getByRole('group', { name: '상태 변경 사유' })).toBeInTheDocument()
    expect(field('접수일시').value).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/)
    expect(save()).toBeDisabled()
    await fillEvent(user)
    expect(hint()).toHaveTextContent('상태 사건 기록·해당 미확정 대상 처리')
    expect(hint()).not.toHaveTextContent('스케줄 새 버전')
    await user.click(save())
    await user.click(await screen.findByRole('button', { name: '다시 확인' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(writes).toHaveLength(0)
    expect(reason()).toHaveValue('  보험회사 해지 접수 반영  ')
    await user.click(save())
    const dialog = await screen.findByRole('dialog', { name: '상태 변경 저장 확인' })
    for (const text of [
      '정상 → 해지',
      '2026-10-20 00:00',
      '2026-10-27 10:00',
      '수기 상태 변경',
      '보험회사 해지 접수 반영',
    ])
      expect(dialog).toHaveTextContent(text)
    expect(writes).toHaveLength(0)
    await user.click(within(dialog).getByRole('button', { name: '확인하고 저장' }))
    await screen.findByText('계약 상세')
    expect(writes).toHaveLength(1)
    expect(writes[0].options).toMatchObject({
      method: 'PUT',
      idempotencyKey: expect.stringMatching(uuid),
      body: {
        contractStatus: 'TERMINATED',
        agentId: 3,
        expectedUpdatedAt: timestamp,
        statusEvent: {
          effectiveAt: '2026-10-20T00:00:00+09:00',
          receivedAt: '2026-10-27T10:00:00+09:00',
          reasonCode: 'MANUAL_STATUS_CHANGE',
          reason: '보험회사 해지 접수 반영',
        },
      },
    })
    expect(router.state.location.state.message).toBe(
      '계약 수정이 저장되었습니다. 후속 처리 상태를 확인하세요. (후속 처리: 대기 중)',
    )
  })

  it('422 일자 범위 오류는 그 일시 옆에 표시하고 입력을 보존하며, 같은 본문은 같은 멱등키로 다시 보낸다', async () => {
    let attempts = 0
    const { user, writes } = renderAt(undefined, (url, options) => {
      if (url === '/api/v1/contracts/21' && !options?.method) return statusContract
      if (options?.method && ++attempts <= 2)
        throw new ApiError(
          {
            code: 'FGC-IMPL-EVENT_ORDER',
            message: '저장 불가 — 효력일시는 최신 사건의 효력일시 이후여야 합니다.',
            field: 'statusEvent.effectiveAt',
          },
          'event-order',
          422,
        )
      if (options?.method) return saved({ statusEventId: 502, followUp: { status: 'PENDING', eventId: 502 } })
    })
    await terminate(user)
    await confirm(user)
    await waitFor(() => expect(field('효력일시')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText(/FGC-IMPL-EVENT_ORDER/)).toHaveTextContent('event-order')
    expect(field('효력일시')).toHaveValue('2026-10-20T00:00')
    expect(reason()).toHaveValue('  보험회사 해지 접수 반영  ')
    await confirm(user)
    await waitFor(() => expect(writes).toHaveLength(2))
    expect(writes[1].options?.idempotencyKey).toBe(writes[0].options?.idempotencyKey)
    fireEvent.change(field('효력일시'), { target: { value: '2026-10-21T00:00' } })
    await ready()
    await confirm(user)
    await screen.findByText('계약 상세')
    expect(writes[2].options?.idempotencyKey).toMatch(uuid)
    expect(writes[2].options?.idempotencyKey).not.toBe(writes[0].options?.idempotencyKey)
  })

  it('409 허용되지 않은 전이는 변경 상태 옆에 표시하고 최신 계약을 다시 불러와야 저장할 수 있다', async () => {
    const { user, writes } = renderAt(undefined, (url, options) => {
      if (url === '/api/v1/contracts/21' && !options?.method) return statusContract
      if (options?.method)
        throw new ApiError(
          {
            code: 'FGC-IMPL-TRANSITION_DENIED',
            message: '저장 불가 — 정상에서 해지(으)로 바꿀 수 없습니다.',
            field: 'contractStatus',
          },
          'transition-denied',
          409,
        )
    })
    await terminate(user)
    await confirm(user)
    await waitFor(() => expect(field('변경 상태')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByRole('button', { name: '최신 계약 다시 불러오기' })).toBeInTheDocument()
    expect(field('변경 상태')).toHaveValue('TERMINATED')
    expect(save()).toBeDisabled()
    expect(writes).toHaveLength(1)
  })

  it('저장 버튼이 잠겨도 입력한 사건 일시의 순서·미래 접수 오류는 그 필드 옆에 바로 보인다', async () => {
    const { user, writes } = renderAt(undefined, (url, options) =>
      url === '/api/v1/contracts/21' && !options?.method ? statusContract : undefined,
    )
    await ready()
    await selectOption(user, field('변경 상태'), 'TERMINATED')
    // 아직 입력하지 않은 빈 필드는 오류로 덮지 않는다.
    expect(field('효력일시')).not.toHaveAttribute('aria-invalid')
    fireEvent.change(field('접수일시'), { target: { value: '2026-10-27T10:00' } })
    fireEvent.change(field('효력일시'), { target: { value: '2026-10-28T00:00' } })
    await selectOption(user, field('사유코드'), 'MANUAL_STATUS_CHANGE')
    fireEvent.change(reason(), { target: { value: '보험회사 해지 접수 반영' } })
    expect(save()).toBeDisabled()
    expect(field('효력일시')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('효력일시는 접수일시보다 늦을 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByText('상태 변경 사유의 필수 항목(*)과 일시를 확인해 주세요.')).toBeInTheDocument()
    fireEvent.change(field('접수일시'), { target: { value: '2999-01-01T00:00' } })
    expect(field('접수일시')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('접수일시는 현재 시각보다 늦을 수 없습니다.')).toBeInTheDocument()
    expect(writes).toHaveLength(0)
  })

  it('끝 상태는 변경 상태를 잠그고, 일반 수정만 할 수 있다', async () => {
    renderAt(undefined, (url, options) =>
      url === '/api/v1/contracts/21' && !options?.method
        ? { ...statusContract, contractStatus: 'TERMINATED', allowedNextStatuses: [] }
        : undefined,
    )
    await ready()
    expect(field('변경 상태')).toBeDisabled()
    expect(screen.getByText('이 상태에서는 바꿀 수 있는 상태가 없습니다.')).toBeInTheDocument()
  })

  it('상태 사건 일시·사유코드·사유를 서버와 같은 경계로 검사하고 요청에는 서울 offset을 붙인다', () => {
    const codes = ['MANUAL_STATUS_CHANGE']
    const event = {
      effectiveAt: '2026-10-20T00:00',
      receivedAt: '2026-10-27T10:00',
      reasonCode: 'MANUAL_STATUS_CHANGE',
      reason: ' 사유 ',
    }
    expect(validateStatusEvent(event, '2026-10-27T10:00', codes)).toEqual({})
    expect(validateStatusEvent({ ...event, effectiveAt: event.receivedAt }, '2026-10-27T10:00', codes)).toEqual({})
    expect(validateStatusEvent({ ...event, effectiveAt: '2026-10-27T10:01' }, '2026-10-28T00:00', codes)).toEqual({
      'statusEvent.effectiveAt': expect.any(String),
    })
    expect(validateStatusEvent(event, '2026-10-27T09:59', codes)).toEqual({
      'statusEvent.receivedAt': expect.any(String),
    })
    expect(
      validateStatusEvent(
        { effectiveAt: '', receivedAt: '', reasonCode: 'OTHER', reason: '   ' },
        '2026-10-27T10:00',
        codes,
      ),
    ).toEqual({
      'statusEvent.effectiveAt': expect.any(String),
      'statusEvent.receivedAt': expect.any(String),
      'statusEvent.reasonCode': expect.any(String),
      'statusEvent.reason': expect.any(String),
    })
    expect(validateStatusEvent({ ...event, reason: '가'.repeat(2000) }, '2026-10-27T10:00', codes)).toEqual({})
    expect(validateStatusEvent({ ...event, reason: '가'.repeat(2001) }, '2026-10-27T10:00', codes)).toEqual({
      'statusEvent.reason': expect.any(String),
    })
    expect(statusEventRequest(event)).toEqual({
      effectiveAt: '2026-10-20T00:00:00+09:00',
      receivedAt: '2026-10-27T10:00:00+09:00',
      reasonCode: 'MANUAL_STATUS_CHANGE',
      reason: '사유',
    })
  })

  it('변경 요약은 서버가 스케줄을 새로 만드는 필드만 산정정보 변경으로 본다', () => {
    const draft = initialDraft('2026-10-12', contract)
    expect(changesSchedule(createRequest(draft), contract)).toBe(false)
    expect(
      changesSchedule(
        createRequest({ ...draft, contractNo: 'X', premiumPerCycleAmount: '1', contractStatus: 'UNPAID' }),
        contract,
      ),
    ).toBe(false)
    expect(changesSchedule(createRequest({ ...draft, monthlyEquivalentFirstPremium: '1' }), contract)).toBe(true)
    expect(changesSchedule(createRequest({ ...draft, standardSurrenderDeductionAmount: '' }), contract)).toBe(true)
  })
})

describe('[FGC-FUN-018·030] 보험료 파생·입력 검증', () => {
  it('직접 입력한 원주기 보험료는 보존하고, 비운 경우만 자동 계산한다', () => {
    const loaded = initialDraft('2026-10-12', contract)
    expect(fillCyclePremium({ ...loaded, monthlyEquivalentFirstPremium: '200000' }).premiumPerCycleAmount).toBe(
      '299000',
    )
    const empty = fillCyclePremium({ ...loaded, premiumEntered: false, premiumPerCycleAmount: '' })
    expect(empty.premiumPerCycleAmount).toBe('300000')
    expect(fillCyclePremium({ ...empty, paymentCycleCode: 'SINGLE' }).premiumPerCycleAmount).toBe('')
  })
  it('[FGC-FUN-018] 누락·공백 계약번호·미래/존재하지 않는 날짜·음수·소수·정밀도 손실을 거부한다', () => {
    const draft = initialDraft('2026-10-12', contract)
    expect(validateDraft(draft, '2026-10-12')).toEqual({})
    for (const contractDate of ['2026-10-13', '2026-02-30', ''])
      expect(validateDraft({ ...draft, contractDate }, '2026-10-12').contractDate).toBeTruthy()
    expect(
      validateDraft(
        {
          ...draft,
          contractNo: '  ',
          firstPremiumAmount: '-1',
          premiumPerCycleAmount: '0.5',
          monthlyEquivalentFirstPremium: '9007199254740993',
          paymentTermMonths: '0',
          agentId: '',
        },
        '2026-10-12',
      ),
    ).toMatchObject({
      contractNo: expect.any(String),
      firstPremiumAmount: expect.any(String),
      premiumPerCycleAmount: expect.any(String),
      monthlyEquivalentFirstPremium: expect.any(String),
      paymentTermMonths: expect.any(String),
      agentId: expect.any(String),
    })
    expect(
      createRequest({ ...draft, standardSurrenderDeductionAmount: '' }).standardSurrenderDeductionAmount,
    ).toBeUndefined()
  })

  const moneyFields = [
    'premiumPerCycleAmount',
    'firstPremiumAmount',
    'monthlyEquivalentFirstPremium',
    'standardSurrenderDeductionAmount',
  ] as const

  it.each(['0', '9007199254740991'])('[FGC-FUN-018] 금액 경계값 %s는 손실 없이 저장 요청에 담는다', (value) => {
    const draft = initialDraft('2026-10-12', contract)
    for (const name of moneyFields) draft[name] = value
    expect(validateDraft(draft, '2026-10-12')).toEqual({})
    const body = createRequest(draft)
    for (const name of moneyFields) expect(body[name]).toBe(value === '0' ? 0 : Number.MAX_SAFE_INTEGER)
  })

  it.each(moneyFields)('[FGC-FUN-018] %s가 안전한 정수 상한을 1원 초과하면 해당 필드에서 거부한다', (name) => {
    const draft = { ...initialDraft('2026-10-12', contract), [name]: '9007199254740992' }
    expect(validateDraft(draft, '2026-10-12')).toEqual({ [name]: expect.any(String) })
  })

  it('[FGC-FUN-018] 납입기간은 서버 Integer 상한까지 허용하고 다음 정수는 거부한다', () => {
    const draft = { ...initialDraft('2026-10-12', contract), paymentTermMonths: '2147483647' }
    expect(validateDraft(draft, '2026-10-12')).toEqual({})
    expect(createRequest(draft).paymentTermMonths).toBe(2147483647)
    expect(validateDraft({ ...draft, paymentTermMonths: '2147483648' }, '2026-10-12')).toEqual({
      paymentTermMonths: expect.any(String),
    })
  })

  it('[FGC-FUN-030] 한도 미리보기는 12배 결과의 안전한 정수 경계 직전까지 표시하고 초과 시 숨긴다', async () => {
    renderAt()
    await ready()
    const preview = within(screen.getByRole('complementary', { name: '1,200% 한도 참고 미리보기' }))
    fireEvent.change(field('월납환산 초회보험료(원)'), { target: { value: '750599937895082' } })
    expect(preview.getByText('9,007,199,254,740,984')).toBeInTheDocument()
    fireEvent.change(field('월납환산 초회보험료(원)'), { target: { value: '750599937895083' } })
    expect(preview.getByText('—')).toBeInTheDocument()
    expect(preview.queryByText('9,007,199,254,740,984')).not.toBeInTheDocument()
  })
})
